package com.aurafiles.app.model

data class SftpProfile(
    val id: String = "",
    val name: String = "SFTP",
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String = "",
    val privateKey: String = "",
    val privateKeyPassphrase: String = "",
    val trustedFingerprint: String = "",
    val initialPath: String = "/",
) {
    val usesKey: Boolean get() = privateKey.isNotBlank()
}

class SftpHostKeyException(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val previousFingerprint: String?,
) : java.io.IOException(
    if (previousFingerprint.isNullOrBlank()) {
        "Неизвестный ключ SFTP-сервера $host:$port · $fingerprint"
    } else {
        "Ключ SFTP-сервера изменился: было $previousFingerprint, стало $fingerprint"
    }
)
