package com.aurafiles.app.backend

import android.content.Context
import android.net.Uri
import com.aurafiles.app.cloud.CloudProfile
import com.aurafiles.app.cloud.CloudProfileRepository
import com.aurafiles.app.cloud.CloudProvider
import com.aurafiles.app.cloud.google.GoogleIdentityAccessTokenProvider
import com.aurafiles.app.cloud.google.UrlConnectionGoogleBinaryTransfer
import com.aurafiles.app.cloud.yandex.YandexAuthService
import com.aurafiles.app.cloud.yandex.YandexAuthTokenProvider
import com.aurafiles.app.cloud.yandex.YandexOAuthSettingsStore
import com.aurafiles.app.network.NetworkProfile
import com.aurafiles.app.network.NetworkProfileRepository
import com.aurafiles.app.network.NetworkProtocol

class BackendFactory(
    private val context: Context,
    private val profiles: NetworkProfileRepository,
    private val cloudProfiles: CloudProfileRepository = CloudProfileRepository(context),
    private val yandexSettings: YandexOAuthSettingsStore = YandexOAuthSettingsStore(context),
) {
    fun local(rootUri: Uri, title: String = "Локальная память"): StorageBackend = LocalStorageBackend(
        context = context,
        rootUri = rootUri,
        descriptor = StorageBackendDescriptor(
            id = "local:${rootUri}",
            title = title,
            kind = StorageBackendKind.LOCAL,
        ),
    )

    fun network(profile: NetworkProfile): StorageBackend = when (profile.protocol) {
        NetworkProtocol.SMB -> SmbStorageBackend(profiles.smb(profile))
        NetworkProtocol.FTP, NetworkProtocol.FTPS -> FtpStorageBackend(profiles.ftp(profile))
        NetworkProtocol.SFTP -> SftpStorageBackend(profiles.sftp(profile))
    }

    fun cloud(profile: CloudProfile): StorageBackend = when (profile.provider) {
        CloudProvider.YANDEX_DISK -> {
            // A Yandex refresh token is bound to the OAuth application that issued it.
            // Keep using the per-profile client ID even if the app's default Client ID later changes.
            val config = yandexSettings.config(profile.oauthClientId.takeIf(String::isNotBlank))
            val auth = YandexAuthService(cloudProfiles)
            YandexDiskStorageBackend(
                profile = profile,
                tokenProvider = YandexAuthTokenProvider(auth, profile.id, config),
            )
        }
        CloudProvider.GOOGLE_DRIVE -> GoogleDriveStorageBackend(
            profile = profile,
            tokenProvider = GoogleIdentityAccessTokenProvider(context, profile.accountLabel),
            binaryTransfer = UrlConnectionGoogleBinaryTransfer(context.cacheDir),
        )
    }
}
