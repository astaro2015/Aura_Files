package com.aurafiles.app.cloud.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleAccessTokenMemoryCacheTest {
    @Test
    fun cacheIsCaseInsensitiveAndTokenSpecificOnRemoval() {
        val email = "User.Example@gmail.com"
        GoogleAccessTokenMemoryCache.remove(email)
        GoogleAccessTokenMemoryCache.put(email, "token-a")

        assertEquals("token-a", GoogleAccessTokenMemoryCache.get("user.example@GMAIL.COM"))
        GoogleAccessTokenMemoryCache.remove(email, "other-token")
        assertEquals("token-a", GoogleAccessTokenMemoryCache.get(email))
        GoogleAccessTokenMemoryCache.remove(email, "token-a")
        assertNull(GoogleAccessTokenMemoryCache.get(email))
    }
}
