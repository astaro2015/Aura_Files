package com.aurafiles.app.cloud.yandex

fun interface YandexAccessTokenProvider {
    /** forceRefresh=true is used only after one authoritative HTTP 401. */
    fun accessToken(forceRefresh: Boolean): String
}

class YandexAuthTokenProvider(
    private val authService: YandexAuthService,
    private val profileId: String,
    private val config: YandexOAuthConfig,
) : YandexAccessTokenProvider {
    override fun accessToken(forceRefresh: Boolean): String =
        authService.accessToken(profileId, config, forceRefresh)
}
