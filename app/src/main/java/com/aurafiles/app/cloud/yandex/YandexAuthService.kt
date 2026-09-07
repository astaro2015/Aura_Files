package com.aurafiles.app.cloud.yandex

import com.aurafiles.app.cloud.CloudProfile
import com.aurafiles.app.cloud.CloudProfileRepository
import com.aurafiles.app.cloud.CloudProvider

/**
 * Orchestrates Yandex OAuth and secure token persistence.
 *
 * It handles only the official Yandex.Disk REST account and OAuth lifecycle.
 */
class YandexAuthService(
    private val profiles: CloudProfileRepository,
    private val oauth: YandexOAuthClient = YandexOAuthClient(),
    private val diskClient: (String) -> YandexDiskApiClient = { YandexDiskApiClient(it) },
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    fun startDeviceAuthorization(config: YandexOAuthConfig): YandexDeviceCode = oauth.requestDeviceCode(config)

    /** One polling attempt. The UI/coroutine layer owns the delay and cancellation policy. */
    fun pollDeviceAuthorization(
        config: YandexOAuthConfig,
        code: YandexDeviceCode,
        profileId: String? = null,
        profileName: String = "",
    ): Pair<YandexDeviceTokenPoll, AuthenticatedYandexAccount?> {
        return when (val poll = oauth.pollDeviceToken(config, code)) {
            YandexDeviceTokenPoll.Pending -> poll to null
            is YandexDeviceTokenPoll.SlowDown -> poll to null
            is YandexDeviceTokenPoll.Granted -> poll to finishAuthorization(
                tokenResponse = poll.token,
                profileId = profileId,
                profileName = profileName,
                oauthClientId = config.clientId,
            )
        }
    }

    fun finishAuthorization(
        tokenResponse: YandexTokenResponse,
        profileId: String? = null,
        profileName: String = "",
        oauthClientId: String = "",
    ): AuthenticatedYandexAccount {
        val now = nowEpochMs()
        val tokens = tokenResponse.toCloudAuthTokens(now)
        val info = diskClient(tokens.accessToken).diskInfo()
        val user = info.user
        val existing = profileId?.let(profiles::profile)
            ?: user.uid.takeIf(String::isNotBlank)?.let { uid ->
                profiles.profiles().firstOrNull { profile ->
                    profile.provider == CloudProvider.YANDEX_DISK && profile.accountId == uid
                }
            }
        if (existing != null && existing.provider != CloudProvider.YANDEX_DISK) {
            throw IllegalArgumentException("Cloud-профиль относится не к Яндекс.Диску")
        }
        val saved = profiles.saveAuthenticated(
            CloudProfile(
                id = profileId ?: existing?.id.orEmpty(),
                name = profileName.ifBlank { existing?.name.orEmpty() },
                provider = CloudProvider.YANDEX_DISK,
                accountId = user.uid.ifBlank { existing?.accountId.orEmpty() },
                accountLabel = user.bestLabel.ifBlank { existing?.accountLabel.orEmpty() },
                oauthClientId = oauthClientId.trim().ifBlank { existing?.oauthClientId.orEmpty() },
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                updatedAtEpochMs = now,
            ),
            tokens,
        )
        return AuthenticatedYandexAccount(
            profileId = saved.id,
            accountId = saved.accountId,
            accountLabel = saved.accountLabel,
            diskInfo = info,
        )
    }

    /**
     * Returns a usable access token, transparently rotating access+refresh tokens when required.
     * The newly returned refresh token is persisted too; Yandex can rotate it on every refresh.
     */
    @Synchronized
    fun accessToken(
        profileId: String,
        config: YandexOAuthConfig,
        forceRefresh: Boolean = false,
    ): String {
        val profile = requireNotNull(profiles.profile(profileId)) { "Cloud-профиль не найден" }
        require(profile.provider == CloudProvider.YANDEX_DISK) { "Cloud-профиль относится не к Яндекс.Диску" }
        val current = requireNotNull(profiles.tokens(profileId)) { "Для Яндекс.Диска нет сохранённых OAuth-токенов" }
        val now = nowEpochMs()
        if (!forceRefresh && current.isAccessTokenUsable(now)) return current.accessToken

        val refresh = current.refreshToken
            ?: throw YandexApiException(
                kind = YandexFailureKind.AUTH_EXPIRED,
                message = "Access token истёк, а refresh token отсутствует. Нужно войти через Яндекс заново.",
            )
        val refreshed = oauth.refreshToken(config, refresh).toCloudAuthTokens(now)
        val merged = mergeYandexRotatedTokens(current, refreshed)
        profiles.updateTokens(profileId, merged)
        return merged.accessToken
    }

    /** Validate the currently stored account against the official Disk API. */
    fun validateStoredAccount(profileId: String, config: YandexOAuthConfig): YandexDiskInfo {
        val token = accessToken(profileId, config)
        return try {
            diskClient(token).diskInfo()
        } catch (error: YandexApiException) {
            if (error.kind != YandexFailureKind.UNAUTHORIZED) throw error
            val refreshed = accessToken(profileId, config, forceRefresh = true)
            diskClient(refreshed).diskInfo()
        }
    }
}
