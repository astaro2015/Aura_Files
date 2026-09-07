package com.aurafiles.app.cloud.google

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface GoogleAuthorizationStep {
    data class Granted(val accessToken: String) : GoogleAuthorizationStep {
        init { require(accessToken.isNotBlank()) { "Google вернул пустой access token" } }
        override fun toString(): String = "Granted(accessToken=<redacted>)"
    }

    data class NeedsResolution(val pendingIntent: PendingIntent) : GoogleAuthorizationStep
}

/**
 * Thin Android wrapper around Google Identity Services AuthorizationClient.
 * Aura never stores a Google refresh token: Google Play services owns authorization
 * state and returns a current access token when the Drive backend needs one.
 */
class GoogleIdentityAuthorization(context: Context) {
    private val appContext = context.applicationContext
    private val client get() = Identity.getAuthorizationClient(appContext)
    private val scopes = listOf(Scope(GOOGLE_DRIVE_SCOPE))

    suspend fun authorize(
        accountEmail: String? = null,
        selectAccount: Boolean = false,
    ): GoogleAuthorizationStep {
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
        accountEmail?.trim()?.takeIf(String::isNotBlank)?.let { email ->
            builder.setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
        }
        if (selectAccount) builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        val result = try {
            client.authorize(builder.build()).awaitTask()
        } catch (error: Throwable) {
            throw authorizationError(error)
        }
        return result.toStep()
    }

    fun authorizationResultFromIntent(data: Intent): GoogleAuthorizationStep.Granted {
        val result = try {
            client.getAuthorizationResultFromIntent(data)
        } catch (error: Throwable) {
            throw authorizationError(error)
        }
        return result.toGranted()
    }

    suspend fun clearToken(token: String) {
        if (token.isBlank()) return
        try {
            client.clearToken(ClearTokenRequest.builder().setToken(token).build()).awaitTask()
        } catch (error: Throwable) {
            throw authorizationError(error)
        }
    }

    suspend fun revoke(accountEmail: String) {
        val email = accountEmail.trim()
        if (email.isBlank()) return
        try {
            client.revokeAccess(
                RevokeAccessRequest.builder()
                    .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
                    .setScopes(scopes)
                    .build()
            ).awaitTask()
            GoogleAccessTokenMemoryCache.remove(email)
        } catch (error: Throwable) {
            throw authorizationError(error)
        }
    }

    private fun AuthorizationResult.toStep(): GoogleAuthorizationStep {
        if (hasResolution()) {
            val pending = pendingIntent ?: throw GoogleDriveApiException(
                kind = GoogleDriveFailureKind.BAD_RESPONSE,
                message = "Google запросил подтверждение, но не вернул окно авторизации.",
            )
            return GoogleAuthorizationStep.NeedsResolution(pending)
        }
        return toGranted()
    }

    private fun AuthorizationResult.toGranted(): GoogleAuthorizationStep.Granted {
        val token = accessToken?.trim().orEmpty()
        if (token.isBlank()) {
            throw GoogleDriveApiException(
                kind = GoogleDriveFailureKind.REAUTHORIZATION_REQUIRED,
                message = "Google не выдал access token. Подтверди доступ к Google Drive заново.",
            )
        }
        if (grantedScopes.none { it == GOOGLE_DRIVE_SCOPE }) {
            throw GoogleDriveApiException(
                kind = GoogleDriveFailureKind.FORBIDDEN,
                message = "Google не предоставил Aura Files доступ к Google Drive.",
            )
        }
        return GoogleAuthorizationStep.Granted(token)
    }

    private fun authorizationError(error: Throwable): GoogleDriveApiException {
        if (error is GoogleDriveApiException) return error
        val apiMessage = (error as? ApiException)?.message.orEmpty()
        val unregisteredOnApiConsole = apiMessage.contains("UNREGISTERED_ON_API_CONSOLE", ignoreCase = true)
        if (error is ApiException && (
                error.statusCode == CommonStatusCodes.DEVELOPER_ERROR ||
                    unregisteredOnApiConsole
                )
        ) {
            return GoogleDriveApiException(
                kind = GoogleDriveFailureKind.REAUTHORIZATION_REQUIRED,
                providerReason = if (unregisteredOnApiConsole) "UNREGISTERED_ON_API_CONSOLE" else "DEVELOPER_ERROR",
                providerMessage = apiMessage,
                message = buildString {
                    if (unregisteredOnApiConsole) {
                        append("Google отклонил эту APK: UNREGISTERED_ON_API_CONSOLE.\n\n")
                    } else {
                        append("Google OAuth не настроен для этой сборки Aura Files.\n\n")
                    }
                    append("Это не ошибка пароля или выбранного аккаунта. В Google Cloud нужно создать OAuth client типа Android именно для установленной APK:\n")
                    append("Package: ").append(appContext.packageName).append('\n')
                    append("SHA-1: ").append(installedSigningSha1()).append("\n\n")
                    append("Затем в ЭТОМ ЖЕ Google Cloud проекте:\n")
                    append("1. включить Google Drive API;\n")
                    append("2. в OAuth consent screen / Data Access добавить scope https://www.googleapis.com/auth/drive;\n")
                    append("3. если приложение в Testing — добавить используемый Google-аккаунт в Test users.\n\n")
                    append("После сохранения настроек Google иногда требуется несколько минут, затем нажми «Повторить».")
                },
                cause = error,
            )
        }
        if (error is ApiException) {
            val details = error.message?.trim().orEmpty()
            return GoogleDriveApiException(
                kind = GoogleDriveFailureKind.REAUTHORIZATION_REQUIRED,
                providerReason = "google_play_services_status_${error.statusCode}",
                providerMessage = details,
                message = buildString {
                    append("Google Play services не завершил авторизацию (код ")
                    append(error.statusCode)
                    append(')')
                    if (details.isNotBlank()) append(": ").append(details)
                    append(". Если доступ уже разрешён, Aura автоматически перепроверит его без второго окна.")
                },
                cause = error,
            )
        }
        return GoogleDriveApiException(
            kind = GoogleDriveFailureKind.REAUTHORIZATION_REQUIRED,
            providerMessage = error.message.orEmpty(),
            message = error.message?.takeIf(String::isNotBlank)
                ?: userMessageForGoogleDriveFailure(GoogleDriveFailureKind.REAUTHORIZATION_REQUIRED),
            cause = error,
        )
    }

    private fun installedSigningSha1(): String = runCatching {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNATURES)
        }
        val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.firstOrNull()?.toByteArray()
        } ?: return@runCatching "не удалось определить"
        MessageDigest.getInstance("SHA-1").digest(certificate)
            .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }.getOrDefault("не удалось определить")

    companion object {
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}

class GoogleIdentityAccessTokenProvider(
    context: Context,
    private val accountEmail: String,
) : GoogleAccessTokenProvider {
    private val identity = GoogleIdentityAuthorization(context)

    override suspend fun accessToken(forceRefresh: Boolean): String {
        if (accountEmail.isBlank()) {
            throw GoogleDriveApiException(
                kind = GoogleDriveFailureKind.REAUTHORIZATION_REQUIRED,
                message = "Для этого Google Drive не сохранён аккаунт. Подключи Google Drive заново.",
            )
        }
        if (!forceRefresh) {
            GoogleAccessTokenMemoryCache.get(accountEmail)?.let { return it }
        }
        return when (val step = identity.authorize(accountEmail = accountEmail, selectAccount = false)) {
            is GoogleAuthorizationStep.Granted -> step.accessToken.also { GoogleAccessTokenMemoryCache.put(accountEmail, it) }
            is GoogleAuthorizationStep.NeedsResolution -> throw GoogleDriveApiException(
                kind = GoogleDriveFailureKind.REAUTHORIZATION_REQUIRED,
                message = "Google требует подтверждения доступа. Открой раздел «Сеть» и подключи Google Drive заново.",
            )
        }
    }

    override suspend fun invalidate(accessToken: String) {
        GoogleAccessTokenMemoryCache.remove(accountEmail, accessToken)
        if (accessToken.isNotBlank()) identity.clearToken(accessToken)
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
    addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    addOnCanceledListener {
        if (continuation.isActive) continuation.cancel()
    }
}
