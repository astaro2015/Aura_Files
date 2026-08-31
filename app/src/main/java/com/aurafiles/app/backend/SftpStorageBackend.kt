package com.aurafiles.app.backend

import com.aurafiles.app.model.SftpHostKeyException
import com.aurafiles.app.model.SftpProfile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.Collections
import java.util.EnumSet
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier

class SftpStorageBackend(
    private val profile: SftpProfile,
    override val descriptor: StorageBackendDescriptor = StorageBackendDescriptor(
        id = "sftp:${profile.id.ifBlank { "${profile.host}:${profile.port}:${profile.username}" }}",
        title = profile.name.ifBlank { "SFTP ${profile.host}" },
        kind = StorageBackendKind.SFTP,
        rootPath = "/",
    ),
) : StorageBackend {
    private val lock = Any()
    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null
    private val initialRoot: String = BackendPath.normalize(profile.initialPath.ifBlank { "/" })

    init {
        require(profile.host.isNotBlank()) { "Введите адрес SFTP" }
        require(profile.username.isNotBlank()) { "Введите логин SFTP" }
        require(profile.port in 1..65535) { "Некорректный порт SFTP" }
        require(profile.password.isNotBlank() || profile.privateKey.isNotBlank()) {
            "Для SFTP нужен пароль или приватный ключ"
        }
    }

    override suspend fun list(path: String): List<StorageItem> = synchronized(lock) {
        val client = requireSftp()
        val normalized = remotePath(path)
        client.ls(normalized)
            .asSequence()
            .filterNot { it.name == "." || it.name == ".." }
            .map { info ->
                val attrs = info.attributes
                StorageItem(
                    backendId = descriptor.id,
                    path = child(path, info.name),
                    name = info.name,
                    isDirectory = attrs.type == FileMode.Type.DIRECTORY,
                    size = if (attrs.type == FileMode.Type.REGULAR) attrs.size.coerceAtLeast(0L) else 0L,
                    modifiedAt = attrs.mtime.toLong().coerceAtLeast(0L) * 1000L,
                    mimeType = if (attrs.type == FileMode.Type.DIRECTORY) null else BackendPath.guessMime(info.name),
                )
            }
            .sortedWith(compareByDescending<StorageItem> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }

    override suspend fun stat(path: String): StorageItem? = synchronized(lock) {
        statBlocking(requireSftp(), path)
    }

    override suspend fun openRead(path: String): StorageReadHandle = synchronized(lock) {
        val remote = requireSftp().open(remotePath(path), EnumSet.of(OpenMode.READ))
        val stream = remote.ReadAheadRemoteFileInputStream(16)
        object : StorageReadHandle {
            override val input: InputStream = stream
            override fun close() {
                runCatching { input.close() }
                runCatching { remote.close() }
            }
        }
    }

    override suspend fun openWrite(path: String, replace: Boolean): StorageWriteHandle = synchronized(lock) {
        val client = requireSftp()
        val target = remotePath(path)
        if (!replace && client.statExistence(target) != null) throw IOException("${BackendPath.name(path)} уже существует")
        val modes = EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
        val remote = client.open(target, modes)
        val stream = remote.RemoteFileOutputStream(0L, 16)
        object : StorageWriteHandle {
            private var finished = false
            override val output: OutputStream = stream
            override fun commit() {
                if (finished) return
                output.flush()
                output.close()
                remote.close()
                finished = true
            }
            override fun abort() {
                if (finished) return
                runCatching { output.close() }
                runCatching { remote.close() }
                runCatching { client.rm(target) }
                finished = true
            }
            override fun close() {
                if (!finished) abort()
            }
        }
    }

    override suspend fun mkdir(path: String): StorageItem = synchronized(lock) {
        val client = requireSftp()
        val target = remotePath(path)
        if (client.statExistence(target) == null) client.mkdir(target)
        statBlocking(client, path) ?: StorageItem(descriptor.id, normalize(path), BackendPath.name(path), true)
    }

    override suspend fun rename(path: String, newName: String): StorageItem = synchronized(lock) {
        val client = requireSftp()
        val target = child(parent(path), newName)
        client.rename(remotePath(path), remotePath(target))
        statBlocking(client, target) ?: StorageItem(descriptor.id, target, newName, false)
    }

    override suspend fun move(path: String, destinationDirectory: String): StorageItem = synchronized(lock) {
        val client = requireSftp()
        val target = child(destinationDirectory, BackendPath.name(path))
        client.rename(remotePath(path), remotePath(target))
        statBlocking(client, target) ?: StorageItem(descriptor.id, target, BackendPath.name(path), false)
    }

    override suspend fun delete(path: String, recursive: Boolean) = synchronized(lock) {
        deleteBlocking(requireSftp(), path, recursive)
    }

    override suspend fun ping(): Boolean = synchronized(lock) {
        runCatching { requireSftp().stat(remotePath("/")); true }.getOrDefault(false)
    }

    override fun close() = synchronized(lock) {
        runCatching { sftp?.close() }
        runCatching { ssh?.disconnect() }
        runCatching { ssh?.close() }
        sftp = null
        ssh = null
    }

    private fun requireSftp(): SFTPClient {
        sftp?.let { existing ->
            val alive = runCatching { existing.statExistence(remotePath("/")) != null }.getOrDefault(false)
            if (alive) return existing
        }
        // A sleeping phone / dropped Wi-Fi can leave a stale SFTP object behind.
        // Recreate the SSH+SFTP session before the next command.
        close()
        val verifier = FingerprintVerifier(profile.trustedFingerprint)
        val client = SSHClient().apply {
            connectTimeout = 15_000
            timeout = 90_000
        }
        client.addHostKeyVerifier(verifier)
        try {
            client.connect(profile.host.trim(), profile.port)
        } catch (error: Throwable) {
            val observed = verifier.observedFingerprint
            if (!observed.isNullOrBlank()) {
                throw SftpHostKeyException(
                    profile.host,
                    profile.port,
                    observed,
                    profile.trustedFingerprint.takeIf(String::isNotBlank),
                )
            }
            throw IOException("Не удалось подключиться к SFTP ${profile.host}:${profile.port}: ${error.message}", error)
        }

        try {
            if (profile.privateKey.isNotBlank()) {
                val keyFile = java.io.File.createTempFile("aura-sftp-key-", ".key")
                try {
                    keyFile.writeText(profile.privateKey)
                    val provider = if (profile.privateKeyPassphrase.isBlank()) {
                        client.loadKeys(keyFile.absolutePath)
                    } else {
                        client.loadKeys(keyFile.absolutePath, profile.privateKeyPassphrase)
                    }
                    client.authPublickey(profile.username, provider)
                } finally {
                    keyFile.writeText("")
                    keyFile.delete()
                }
            } else {
                client.authPassword(profile.username, profile.password)
            }
            val connected = client.newSFTPClient()
            ssh = client
            sftp = connected
            return connected
        } catch (error: Throwable) {
            runCatching { client.disconnect() }
            runCatching { client.close() }
            throw IOException("SFTP-аутентификация не удалась: ${error.message}", error)
        }
    }

    private fun statBlocking(client: SFTPClient, path: String): StorageItem? {
        val normalized = normalize(path)
        if (normalized == "/") return StorageItem(descriptor.id, "/", descriptor.title, true)
        val attrs = client.statExistence(remotePath(normalized)) ?: return null
        val directory = attrs.type == FileMode.Type.DIRECTORY
        return StorageItem(
            backendId = descriptor.id,
            path = normalized,
            name = BackendPath.name(normalized),
            isDirectory = directory,
            size = if (directory) 0L else attrs.size.coerceAtLeast(0L),
            modifiedAt = attrs.mtime.toLong().coerceAtLeast(0L) * 1000L,
            mimeType = if (directory) null else BackendPath.guessMime(BackendPath.name(normalized)),
        )
    }

    private fun deleteBlocking(client: SFTPClient, path: String, recursive: Boolean) {
        val item = statBlocking(client, path) ?: return
        val remote = remotePath(path)
        if (item.isDirectory) {
            val children = client.ls(remote).filterNot { it.name == "." || it.name == ".." }
            if (children.isNotEmpty() && !recursive) throw IOException("Папка ${item.name} не пуста")
            children.forEach { deleteBlocking(client, child(path, it.name), true) }
            client.rmdir(remote)
        } else {
            client.rm(remote)
        }
    }

    private fun remotePath(path: String): String {
        val normalized = normalize(path)
        return when {
            initialRoot == "/" -> normalized
            normalized == "/" -> initialRoot
            else -> "$initialRoot$normalized"
        }
    }

    private class FingerprintVerifier(
        private val trusted: String,
    ) : HostKeyVerifier {
        @Volatile var observedFingerprint: String? = null
            private set

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val fingerprint = fingerprint(key)
            observedFingerprint = fingerprint
            return trusted.isNotBlank() && constantTimeEquals(trusted, fingerprint)
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = Collections.emptyList()

        private fun fingerprint(key: PublicKey): String {
            // OpenSSH fingerprints hash the SSH wire-format public-key blob, not X.509 key.encoded.
            val blob = Buffer.PlainBuffer().putPublicKey(key).getCompactData()
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            val encoded = Base64.getEncoder().withoutPadding().encodeToString(digest)
            return "SHA256:$encoded"
        }

        private fun constantTimeEquals(a: String, b: String): Boolean {
            val left = a.toByteArray(Charsets.UTF_8)
            val right = b.toByteArray(Charsets.UTF_8)
            return MessageDigest.isEqual(left, right)
        }
    }
}
