package com.aurafiles.app.backend

import android.content.Context
import android.net.Uri
import com.aurafiles.app.network.NetworkProfile
import com.aurafiles.app.network.NetworkProfileRepository
import com.aurafiles.app.network.NetworkProtocol

class BackendFactory(
    private val context: Context,
    private val profiles: NetworkProfileRepository,
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
}
