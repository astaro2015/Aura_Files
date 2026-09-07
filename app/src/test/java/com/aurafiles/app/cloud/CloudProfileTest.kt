package com.aurafiles.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProfileTest {
    @Test
    fun blankNameFallsBackToProviderAndAccount() {
        val profile = CloudProfile(
            id = "p1",
            name = "  ",
            provider = CloudProvider.YANDEX_DISK,
            accountLabel = "  user@yandex.ru  ",
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 100L,
        ).normalized(nowEpochMs = 200L)

        assertEquals("Яндекс.Диск — user@yandex.ru", profile.name)
        assertEquals("user@yandex.ru", profile.accountLabel)
        assertEquals(100L, profile.createdAtEpochMs)
        assertEquals(200L, profile.updatedAtEpochMs)
    }

    @Test
    fun providerStorageKeysAreStableAndBackwardCompatibleWithEnumName() {
        assertEquals(CloudProvider.GOOGLE_DRIVE, CloudProvider.fromStorageKey("google_drive"))
        assertEquals(CloudProvider.GOOGLE_DRIVE, CloudProvider.fromStorageKey("GOOGLE_DRIVE"))
    }

    @Test
    fun tokenFreshnessUsesSafetyWindow() {
        val now = 1_000_000L
        val fresh = CloudAuthTokens("access", expiresAtEpochMs = now + 120_000L)
        val expiring = CloudAuthTokens("access", expiresAtEpochMs = now + 30_000L)
        val unknownExpiry = CloudAuthTokens("access", expiresAtEpochMs = null)

        assertTrue(fresh.isAccessTokenUsable(now))
        assertFalse(expiring.isAccessTokenUsable(now))
        assertTrue(unknownExpiry.isAccessTokenUsable(now))
    }
}
