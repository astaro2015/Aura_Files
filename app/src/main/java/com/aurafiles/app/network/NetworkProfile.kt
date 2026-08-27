package com.aurafiles.app.network

import java.util.UUID

enum class NetworkProtocol {
    FTP,
    FTPS,
    SMB,
}

data class NetworkProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: NetworkProtocol,
    val host: String,
    val port: Int,
    val username: String,
    val secretId: String?,
    val smbShare: String = "",
    val smbDomain: String = "",
    val tls: Boolean = false,
)

