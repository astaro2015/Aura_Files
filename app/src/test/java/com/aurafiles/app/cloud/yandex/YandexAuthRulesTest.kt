package com.aurafiles.app.cloud.yandex

import com.aurafiles.app.cloud.CloudAuthTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YandexAuthRulesTest {
    @Test
    fun oauthErrorsAreNormalized() {
        assertEquals(YandexFailureKind.AUTH_PENDING, classifyYandexFailure(400, "authorization_pending"))
        assertEquals(YandexFailureKind.AUTH_DENIED, classifyYandexFailure(400, "access_denied"))
        assertEquals(YandexFailureKind.AUTH_EXPIRED, classifyYandexFailure(400, "invalid_grant"))
        assertEquals(YandexFailureKind.INVALID_CLIENT, classifyYandexFailure(400, "invalid_client"))
        assertEquals(YandexFailureKind.UNAUTHORIZED, classifyYandexFailure(401))
        assertEquals(YandexFailureKind.FORBIDDEN, classifyYandexFailure(403))
        assertEquals(YandexFailureKind.RATE_LIMITED, classifyYandexFailure(429))
        assertEquals(YandexFailureKind.SERVER, classifyYandexFailure(503))
    }

    @Test
    fun tokenResponseConvertsRelativeExpiryToAbsoluteTime() {
        val token = YandexTokenResponse(
            accessToken = "access",
            refreshToken = "refresh",
            expiresInSeconds = 120,
            tokenType = "bearer",
        ).toCloudAuthTokens(nowEpochMs = 1_000_000L)

        assertEquals("access", token.accessToken)
        assertEquals("refresh", token.refreshToken)
        assertEquals(1_120_000L, token.expiresAtEpochMs)
        assertEquals("bearer", token.tokenType)
    }

    @Test
    fun retryableClassificationDoesNotRetryCredentialFailures() {
        assertTrue(YandexApiException(YandexFailureKind.NETWORK).retryable)
        assertTrue(YandexApiException(YandexFailureKind.RATE_LIMITED).retryable)
        assertTrue(YandexApiException(YandexFailureKind.SERVER).retryable)
        assertFalse(YandexApiException(YandexFailureKind.INVALID_CLIENT).retryable)
        assertFalse(YandexApiException(YandexFailureKind.AUTH_EXPIRED).retryable)
    }

    @Test
    fun refreshedTokenMayKeepOldRefreshTokenWhenProviderOmitsRotation() {
        val previous = CloudAuthTokens("old-access", "old-refresh", 10L)
        val refreshedWithoutRefresh = CloudAuthTokens("new-access", null, 20L)
        val result = mergeYandexRotatedTokens(previous, refreshedWithoutRefresh)

        assertEquals("new-access", result.accessToken)
        assertEquals("old-refresh", result.refreshToken)
        assertEquals(20L, result.expiresAtEpochMs)
    }
    @Test
    fun secretBearingModelsRedactToString() {
        val oauth = YandexOAuthConfig("client", "super-secret")
        val token = YandexTokenResponse("access-secret", "refresh-secret", 120)
        val stored = CloudAuthTokens("stored-access", "stored-refresh", 123L)
        val device = YandexDeviceCode("device-secret", "ABCD-EFGH", "https://oauth.yandex.com/device", 5, 10_000L)

        assertFalse(oauth.toString().contains("super-secret"))
        assertFalse(token.toString().contains("access-secret"))
        assertFalse(token.toString().contains("refresh-secret"))
        assertFalse(stored.toString().contains("stored-access"))
        assertFalse(stored.toString().contains("stored-refresh"))
        assertFalse(device.toString().contains("device-secret"))
    }

    @Test
    fun deviceCodeRequestUsesGlobalOAuthHostAndDoesNotExposeSecret() {
        var captured: YandexHttpRequest? = null
        val transport = YandexHttpTransport { request ->
            captured = request
            // The response is not parsed in this assertion-focused test environment.
            throw YandexApiException(YandexFailureKind.OTHER)
        }
        val oauth = YandexOAuthClient(transport = transport, nowEpochMs = { 1_000L })
        runCatching {
            oauth.requestDeviceCode(
                YandexOAuthConfig(
                    clientId = "client-id",
                    clientSecret = "secret value",
                    scope = "cloud_api:disk.read cloud_api:disk.write",
                    deviceId = "device-id",
                    deviceName = "Aura Phone",
                )
            )
        }

        val request = requireNotNull(captured)
        assertEquals("https://oauth.yandex.com/device/code", request.url)
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        assertFalse(request.headers.containsKey("Authorization"))
        val body = String(request.body ?: error("body missing"), Charsets.UTF_8)
        assertTrue(body.contains("client_id=client-id"))
        assertFalse(body.contains("client_secret"))
        assertTrue(body.contains("device_id=device-id"))
        assertTrue(body.contains("device_name=Aura+Phone"))
        assertTrue(body.contains("scope=cloud_api%3Adisk.read+cloud_api%3Adisk.write"))
        assertFalse(request.toString().contains("secret value"))
    }

    @Test
    fun deviceTokenExchangeUsesBasicAuthorization() {
        var captured: YandexHttpRequest? = null
        val transport = YandexHttpTransport { request ->
            captured = request
            YandexHttpResponse(400, "{}")
        }
        val oauth = YandexOAuthClient(transport = transport, nowEpochMs = { 1_000L })
        runCatching {
            oauth.pollDeviceToken(
                YandexOAuthConfig(
                    clientId = "client-id",
                    clientSecret = "secret value",
                    deviceId = "device-id",
                    deviceName = "Aura Phone",
                ),
                YandexDeviceCode("device-code", "USER-CODE", "https://oauth.yandex.com/device", 5, 60_000L),
            )
        }

        val request = requireNotNull(captured)
        assertEquals("https://oauth.yandex.com/token", request.url)
        val body = String(request.body ?: error("body missing"), Charsets.UTF_8)
        assertTrue(body.contains("grant_type=device_code"))
        assertTrue(body.contains("code=device-code"))
        assertFalse(body.contains("client_secret"))
        assertFalse(body.contains("secret+value"))
        assertTrue(request.headers["Authorization"].orEmpty().startsWith("Basic "))
        assertFalse(request.toString().contains("secret value"))
    }


}
