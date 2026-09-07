package com.aurafiles.app.cloud

data class CloudAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochMs: Long? = null,
    val tokenType: String = "Bearer",
) {
    init {
        require(accessToken.isNotBlank()) { "Пустой access token" }
        require(refreshToken == null || refreshToken.isNotBlank()) { "Пустой refresh token" }
    }

    override fun toString(): String =
        "CloudAuthTokens(accessToken=<redacted>, refreshToken=${if (refreshToken == null) "null" else "<redacted>"}, expiresAtEpochMs=$expiresAtEpochMs, tokenType=$tokenType)"

    fun isAccessTokenUsable(
        nowEpochMs: Long = System.currentTimeMillis(),
        safetyWindowMs: Long = DEFAULT_SAFETY_WINDOW_MS,
    ): Boolean {
        if (accessToken.isBlank()) return false
        val expiry = expiresAtEpochMs ?: return true
        return expiry - safetyWindowMs > nowEpochMs
    }

    companion object {
        const val DEFAULT_SAFETY_WINDOW_MS = 60_000L
    }
}
