package com.aurafiles.app.cloud.yandex

import java.util.Base64
import org.json.JSONObject

class YandexOAuthClient internal constructor(
    private val transport: YandexHttpTransport = UrlConnectionYandexHttpTransport(),
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    fun requestDeviceCode(config: YandexOAuthConfig): YandexDeviceCode {
        val normalized = config.normalized()
        val response = postForm(
            DEVICE_CODE_URL,
            buildMap {
                put("client_id", normalized.clientId)
                put("device_id", normalized.deviceId)
                put("device_name", normalized.deviceName)
                put("scope", normalized.scope)
            },
        )
        if (response.statusCode !in 200..299) throwYandexError(response)
        val json = parseJsonObject(response)
        val expiresInSeconds = json.optLong("expires_in", DEFAULT_DEVICE_CODE_LIFETIME_SECONDS)
            .coerceAtLeast(1L)
        return YandexDeviceCode(
            deviceCode = json.optString("device_code"),
            userCode = json.optString("user_code"),
            verificationUrl = json.optString("verification_url").ifBlank { DEFAULT_VERIFICATION_URL },
            intervalSeconds = json.optInt("interval", DEFAULT_POLL_INTERVAL_SECONDS).coerceAtLeast(1),
            expiresAtEpochMs = safeExpiry(nowEpochMs(), expiresInSeconds),
        )
    }

    fun pollDeviceToken(config: YandexOAuthConfig, deviceCode: YandexDeviceCode): YandexDeviceTokenPoll {
        if (deviceCode.isExpired(nowEpochMs())) {
            throw YandexApiException(YandexFailureKind.AUTH_EXPIRED)
        }
        val normalized = config.normalized()
        val response = postForm(
            TOKEN_URL,
            mapOf(
                "grant_type" to "device_code",
                "code" to deviceCode.deviceCode,
            ),
            basicClient = normalized.clientId to normalized.clientSecret,
        )
        if (response.statusCode in 200..299) return YandexDeviceTokenPoll.Granted(parseToken(response))

        val json = runCatching { JSONObject(response.body) }.getOrNull()
        val providerCode = json?.optString("error").orEmpty()
        return when (classifyYandexFailure(response.statusCode, providerCode, json?.optString("error_description").orEmpty())) {
            YandexFailureKind.AUTH_PENDING -> YandexDeviceTokenPoll.Pending
            YandexFailureKind.RATE_LIMITED -> YandexDeviceTokenPoll.SlowDown()
            else -> throwYandexError(response)
        }
    }

    fun refreshToken(config: YandexOAuthConfig, refreshToken: String): YandexTokenResponse {
        require(refreshToken.isNotBlank()) { "Пустой Yandex refresh token" }
        val normalized = config.normalized()
        val response = postForm(
            TOKEN_URL,
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken.trim(),
            ),
            basicClient = normalized.clientId to normalized.clientSecret,
        )
        if (response.statusCode !in 200..299) throwYandexError(response)
        return parseToken(response)
    }

    private fun parseToken(response: YandexHttpResponse): YandexTokenResponse {
        val json = parseJsonObject(response)
        val accessToken = json.optString("access_token")
        if (accessToken.isBlank()) {
            throw YandexApiException(
                kind = YandexFailureKind.BAD_RESPONSE,
                statusCode = response.statusCode,
                message = "Яндекс не вернул access token.",
            )
        }
        return YandexTokenResponse(
            accessToken = accessToken,
            refreshToken = json.nullableString("refresh_token"),
            expiresInSeconds = json.nullableLong("expires_in"),
            tokenType = json.optString("token_type", "bearer"),
            scope = json.optString("scope"),
        )
    }

    private fun postForm(
        url: String,
        values: Map<String, String>,
        basicClient: Pair<String, String>? = null,
    ): YandexHttpResponse {
        val headers = linkedMapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/x-www-form-urlencoded",
            "User-Agent" to USER_AGENT,
        )
        basicClient?.let { (clientId, clientSecret) ->
            val credentials = "$clientId:$clientSecret".toByteArray(Charsets.UTF_8)
            headers["Authorization"] = "Basic " + Base64.getEncoder().encodeToString(credentials)
        }
        return transport.execute(
            YandexHttpRequest(
                method = "POST",
                url = url,
                headers = headers,
                body = formBody(values),
            )
        )
    }

    private fun safeExpiry(now: Long, seconds: Long): Long {
        val maxSeconds = (Long.MAX_VALUE - now).coerceAtLeast(0L) / 1000L
        return now + seconds.coerceAtMost(maxSeconds) * 1000L
    }

    companion object {
        const val DEVICE_CODE_URL = "https://oauth.yandex.com/device/code"
        const val TOKEN_URL = "https://oauth.yandex.com/token"
        const val DEFAULT_VERIFICATION_URL = "https://oauth.yandex.com/device"
        const val DEFAULT_POLL_INTERVAL_SECONDS = 5
        const val DEFAULT_DEVICE_CODE_LIFETIME_SECONDS = 600L
        internal const val USER_AGENT = "AuraFiles/1.2.4"
    }
}
