package com.aurafiles.app.cloud.google

interface GoogleAccessTokenProvider {
    suspend fun accessToken(forceRefresh: Boolean = false): String
    suspend fun invalidate(accessToken: String) = Unit
}
