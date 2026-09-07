package com.aurafiles.app.cloud.yandex

import android.content.Context
import android.os.Build
import com.aurafiles.app.BuildConfig
import java.util.UUID

/**
 * Device-local settings required to start Yandex device-code OAuth.
 *
 * Client ID is public application metadata. Client Secret is stored separately with AndroidKeyStore AES-GCM.
 * The device ID must stay
 * stable on one Android installation and must not be restored onto a different device.
 */
class YandexOAuthSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val secretStore = YandexOAuthSecretStore(context)

    fun configuredClientId(): String = preferences.getString(KEY_CLIENT_ID, "").orEmpty().trim().ifBlank {
        BuildConfig.YANDEX_OAUTH_CLIENT_ID.trim()
    }

    fun rememberClientSecret(clientId: String, clientSecret: String) {
        secretStore.put(clientId, clientSecret)
    }

    fun configuredClientSecret(clientId: String): String = secretStore.get(clientId).orEmpty()

    fun rememberClientId(clientId: String) {
        val normalized = clientId.trim()
        check(preferences.edit().putString(KEY_CLIENT_ID, normalized).commit()) {
            "Не удалось сохранить Yandex OAuth Client ID"
        }
    }

    fun deviceId(): String {
        preferences.getString(KEY_DEVICE_ID, null)?.trim()?.takeIf { it.length in 6..50 }?.let { return it }
        val generated = UUID.randomUUID().toString()
        check(preferences.edit().putString(KEY_DEVICE_ID, generated).commit()) {
            "Не удалось сохранить Yandex device ID"
        }
        return generated
    }

    fun config(
        clientIdOverride: String? = null,
        clientSecretOverride: String? = null,
    ): YandexOAuthConfig {
        val clientId = clientIdOverride?.trim().orEmpty().ifBlank(::configuredClientId)
        val clientSecret = clientSecretOverride?.trim().orEmpty().ifBlank { configuredClientSecret(clientId) }
        return YandexOAuthConfig(
            clientId = clientId,
            clientSecret = clientSecret,
            deviceId = deviceId(),
            deviceName = deviceName(),
        )
    }

    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return listOf(manufacturer, model)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .ifBlank { "Aura Files Android" }
            .take(100)
    }

    companion object {
        const val PREFERENCES = "aura_yandex_oauth"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
