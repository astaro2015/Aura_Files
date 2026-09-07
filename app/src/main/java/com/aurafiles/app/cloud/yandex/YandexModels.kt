package com.aurafiles.app.cloud.yandex

import com.aurafiles.app.cloud.CloudAuthTokens
import kotlin.math.max

data class YandexOAuthConfig(
    val clientId: String,
    val clientSecret: String = "",
    val scope: String = "",
    val deviceId: String = "",
    val deviceName: String = "Aura Files Android",
) {
    init {
        require(clientId.isNotBlank()) { "Пустой Yandex OAuth Client ID" }
    }

    fun normalized(): YandexOAuthConfig = copy(
        clientId = clientId.trim(),
        clientSecret = clientSecret.trim(),
        scope = scope.trim(),
        deviceId = deviceId.trim(),
        deviceName = deviceName.trim().ifBlank { "Aura Files Android" },
    )

    override fun toString(): String =
        "YandexOAuthConfig(clientId=$clientId, clientSecret=${if (clientSecret.isBlank()) "<empty>" else "<redacted>"}, scope=$scope, deviceId=$deviceId, deviceName=$deviceName)"
}

data class YandexDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSeconds: Int,
    val expiresAtEpochMs: Long,
) {
    init {
        require(deviceCode.isNotBlank()) { "Яндекс не вернул device_code" }
        require(userCode.isNotBlank()) { "Яндекс не вернул user_code" }
        require(verificationUrl.startsWith("https://")) { "Некорректный verification URL" }
        require(intervalSeconds > 0) { "Некорректный polling interval" }
    }

    override fun toString(): String =
        "YandexDeviceCode(deviceCode=<redacted>, userCode=$userCode, verificationUrl=$verificationUrl, intervalSeconds=$intervalSeconds, expiresAtEpochMs=$expiresAtEpochMs)"

    fun isExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean = nowEpochMs >= expiresAtEpochMs
}

data class YandexTokenResponse(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresInSeconds: Long? = null,
    val tokenType: String = "bearer",
    val scope: String = "",
) {
    init {
        require(accessToken.isNotBlank()) { "Яндекс не вернул access token" }
    }

    override fun toString(): String =
        "YandexTokenResponse(accessToken=<redacted>, refreshToken=${if (refreshToken == null) "null" else "<redacted>"}, expiresInSeconds=$expiresInSeconds, tokenType=$tokenType, scope=$scope)"

    fun toCloudAuthTokens(nowEpochMs: Long = System.currentTimeMillis()): CloudAuthTokens {
        val expiresAt = expiresInSeconds
            ?.takeIf { it > 0L }
            ?.let { seconds -> safeEpochAdd(nowEpochMs, seconds) }
        return CloudAuthTokens(
            accessToken = accessToken.trim(),
            refreshToken = refreshToken?.trim()?.takeIf(String::isNotBlank),
            expiresAtEpochMs = expiresAt,
            tokenType = tokenType.trim().ifBlank { "Bearer" },
        )
    }

    private fun safeEpochAdd(nowEpochMs: Long, seconds: Long): Long {
        val maxSeconds = max(0L, (Long.MAX_VALUE - nowEpochMs) / 1000L)
        return nowEpochMs + seconds.coerceAtMost(maxSeconds) * 1000L
    }
}

data class YandexDiskUser(
    val uid: String = "",
    val login: String = "",
    val displayName: String = "",
) {
    val bestLabel: String
        get() = login.ifBlank { displayName.ifBlank { uid } }
}

data class YandexDiskInfo(
    val totalSpace: Long? = null,
    val usedSpace: Long? = null,
    val trashSize: Long? = null,
    val maxFileSize: Long? = null,
    val paid: Boolean? = null,
    val revision: Long? = null,
    val user: YandexDiskUser = YandexDiskUser(),
)

sealed interface YandexDeviceTokenPoll {
    data object Pending : YandexDeviceTokenPoll
    data class SlowDown(val extraDelaySeconds: Int = 2) : YandexDeviceTokenPoll
    data class Granted(val token: YandexTokenResponse) : YandexDeviceTokenPoll
}

internal fun mergeYandexRotatedTokens(
    previous: CloudAuthTokens,
    refreshed: CloudAuthTokens,
): CloudAuthTokens = refreshed.copy(
    refreshToken = refreshed.refreshToken ?: previous.refreshToken,
)

data class AuthenticatedYandexAccount(
    val profileId: String,
    val accountId: String,
    val accountLabel: String,
    val diskInfo: YandexDiskInfo,
)

data class YandexDiskResource(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val modifiedAtEpochMs: Long = 0L,
    val mimeType: String? = null,
)

data class YandexTransferLink(
    val href: String,
    val method: String = "GET",
) {
    init {
        require(href.startsWith("https://")) { "Яндекс вернул небезопасную transfer-ссылку" }
    }
}

data class YandexOperationLink(
    val href: String? = null,
)

enum class YandexOperationState {
    IN_PROGRESS,
    SUCCESS,
    FAILED,
}
