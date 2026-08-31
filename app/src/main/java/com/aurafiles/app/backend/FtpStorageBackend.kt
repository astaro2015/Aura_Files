package com.aurafiles.app.backend

import com.aurafiles.app.model.FtpProfile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient

class FtpStorageBackend(
    private val profile: FtpProfile,
    override val descriptor: StorageBackendDescriptor = StorageBackendDescriptor(
        id = "ftp:${profile.host}:${profile.port}:${profile.username}",
        title = profile.name.ifBlank { "FTP ${profile.host}" },
        kind = StorageBackendKind.FTP,
    ),
) : StorageBackend {
    private val lock = Any()
    @Volatile private var client: FTPClient? = null

    override suspend fun list(path: String): List<StorageItem> = synchronized(lock) {
        val ftp = requireClient()
        val normalized = remotePath(path)
        ftp.listFiles(normalized).orEmpty()
            .filterNot { it.name == "." || it.name == ".." }
            .map { it.toItem(normalized) }
            .sortedWith(compareByDescending<StorageItem> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    override suspend fun stat(path: String): StorageItem? = synchronized(lock) {
        val normalized = normalize(path)
        if (normalized == "/") {
            return@synchronized StorageItem(descriptor.id, "/", descriptor.title, true)
        }
        val ftp = requireClient()
        val parent = remotePath(parent(normalized))
        ftp.listFiles(remotePath(normalized)).firstOrNull()?.takeIf { it.name != "." && it.name != ".." }?.toItem(parent)
            ?: ftp.listFiles(parent).firstOrNull { it.name == BackendPath.name(normalized) }?.toItem(parent)
    }

    override suspend fun openRead(path: String): StorageReadHandle {
        val ftp = newClient()
        val stream = ftp.retrieveFileStream(remotePath(path)) ?: run {
            val message = replyMessage(ftp, "Не удалось открыть файл для чтения")
            closeClient(ftp)
            throw IOException(message)
        }
        return object : StorageReadHandle {
            private var closed = false
            override val input: InputStream = stream
            override fun close() {
                if (closed) return
                closed = true
                var failure: Throwable? = null
                runCatching { input.close() }.onFailure { failure = it }
                runCatching {
                    if (!ftp.completePendingCommand()) throw IOException(replyMessage(ftp, "FTP не подтвердил чтение файла"))
                }.onFailure { if (failure == null) failure = it }
                closeClient(ftp)
                failure?.let { throw it }
            }
        }
    }

    override suspend fun openWrite(path: String, replace: Boolean): StorageWriteHandle {
        val ftp = newClient()
        val normalized = remotePath(path)
        try {
            if (!replace && ftp.listFiles(normalized).isNotEmpty()) throw IOException("${BackendPath.name(path)} уже существует")
            val stream = ftp.storeFileStream(normalized)
                ?: throw IOException(replyMessage(ftp, "Не удалось открыть файл для записи"))
            return object : StorageWriteHandle {
                private var finished = false
                override val output: OutputStream = stream
                override fun commit() {
                    if (finished) return
                    try {
                        output.flush()
                        output.close()
                        if (!ftp.completePendingCommand()) throw IOException(replyMessage(ftp, "FTP не подтвердил запись файла"))
                        finished = true
                    } catch (error: Throwable) {
                        // The transfer core writes to a temporary .aura-part-* path. If the
                        // server rejects finalisation, remove that temporary object while the
                        // control connection is still alive instead of leaving an orphan.
                        runCatching { output.close() }
                        runCatching { ftp.completePendingCommand() }
                        runCatching { ftp.deleteFile(normalized) }
                        finished = true
                        throw error
                    } finally {
                        closeClient(ftp)
                    }
                }
                override fun abort() {
                    if (finished) return
                    runCatching { output.close() }
                    runCatching { ftp.completePendingCommand() }
                    runCatching { ftp.deleteFile(normalized) }
                    finished = true
                    closeClient(ftp)
                }
                override fun close() {
                    if (!finished) abort()
                }
            }
        } catch (error: Throwable) {
            closeClient(ftp)
            throw error
        }
    }

    override suspend fun mkdir(path: String): StorageItem = synchronized(lock) {
        val ftp = requireClient()
        val normalized = remotePath(path)
        if (ftp.listFiles(normalized).isEmpty() && !ftp.makeDirectory(normalized)) {
            throw IOException(replyMessage(ftp, "Не удалось создать папку"))
        }
        StorageItem(descriptor.id, normalize(path), BackendPath.name(path), true)
    }

    override suspend fun rename(path: String, newName: String): StorageItem = synchronized(lock) {
        val ftp = requireClient()
        val source = remotePath(path)
        val targetPath = child(parent(path), newName)
        val target = remotePath(targetPath)
        if (!ftp.rename(source, target)) throw IOException(replyMessage(ftp, "Не удалось переименовать"))
        statBlocking(ftp, targetPath) ?: StorageItem(descriptor.id, targetPath, newName, false)
    }

    override suspend fun move(path: String, destinationDirectory: String): StorageItem = synchronized(lock) {
        val ftp = requireClient()
        val targetPath = child(destinationDirectory, BackendPath.name(path))
        if (!ftp.rename(remotePath(path), remotePath(targetPath))) {
            throw IOException(replyMessage(ftp, "FTP-сервер не выполнил перемещение"))
        }
        statBlocking(ftp, targetPath) ?: StorageItem(descriptor.id, targetPath, BackendPath.name(path), false)
    }

    override suspend fun delete(path: String, recursive: Boolean) = synchronized(lock) {
        val ftp = requireClient()
        val normalized = remotePath(path)
        val item = statBlocking(ftp, path) ?: return@synchronized
        if (item.isDirectory) {
            if (recursive) {
                ftp.listFiles(normalized).orEmpty().filterNot { it.name == "." || it.name == ".." }.forEach { child ->
                    deleteBlocking(ftp, childPath(path, child.name), true)
                }
            }
            if (!ftp.removeDirectory(normalized)) throw IOException(replyMessage(ftp, "Не удалось удалить папку"))
        } else if (!ftp.deleteFile(normalized)) {
            throw IOException(replyMessage(ftp, "Не удалось удалить файл"))
        }
    }

    override suspend fun ping(): Boolean = synchronized(lock) {
        runCatching { requireClient().sendNoOp() }.getOrDefault(false)
    }

    override fun close() = synchronized(lock) {
        client?.let { ftp ->
            runCatching { if (ftp.isConnected) ftp.logout() }
            runCatching { if (ftp.isConnected) ftp.disconnect() }
        }
        client = null
    }

    private fun requireClient(): FTPClient {
        val active = client
        if (active != null && active.isConnected && runCatching { active.sendNoOp() }.getOrDefault(false)) return active
        close()
        return newClient().also { client = it }
    }

    private fun newClient(): FTPClient {
        val ftp: FTPClient = if (profile.useTls) FTPSClient("TLS", false).apply {
            isEndpointCheckingEnabled = true
        } else FTPClient()
        ftp.connectTimeout = 15_000
        ftp.defaultTimeout = 15_000
        ftp.dataTimeout = Duration.ofSeconds(90)
        ftp.connect(profile.host.trim(), profile.port)
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
            runCatching { ftp.disconnect() }
            throw IOException(replyMessage(ftp, "FTP отклонил соединение"))
        }
        if (!ftp.login(profile.username, profile.password)) {
            runCatching { ftp.disconnect() }
            throw IOException(replyMessage(ftp, "Неверный логин или пароль FTP"))
        }
        if (ftp is FTPSClient) {
            ftp.execPBSZ(0L)
            ftp.execPROT("P")
        }
        ftp.enterLocalPassiveMode()
        require(ftp.setFileType(FTP.BINARY_FILE_TYPE)) { "Сервер не включил двоичный режим" }
        ftp.setFileTransferMode(FTP.STREAM_TRANSFER_MODE)
        ftp.setControlKeepAliveTimeout(Duration.ofSeconds(25))
        return ftp
    }

    private fun closeClient(ftp: FTPClient) {
        runCatching { if (ftp.isConnected) ftp.logout() }
        runCatching { if (ftp.isConnected) ftp.disconnect() }
    }

    private fun FTPFile.toItem(parentPath: String): StorageItem {
        val normalizedParent = normalize(parentPath)
        return StorageItem(
            backendId = descriptor.id,
            path = child(normalizedParent, name),
            name = name,
            isDirectory = isDirectory,
            size = if (isFile) size.coerceAtLeast(0L) else 0L,
            modifiedAt = timestampInstant?.toEpochMilli() ?: 0L,
            mimeType = if (isDirectory) null else BackendPath.guessMime(name),
        )
    }

    private fun statBlocking(ftp: FTPClient, path: String): StorageItem? {
        val normalized = normalize(path)
        if (normalized == "/") return StorageItem(descriptor.id, "/", descriptor.title, true)
        val p = remotePath(parent(normalized))
        return ftp.listFiles(p).firstOrNull { it.name == BackendPath.name(normalized) }?.toItem(parent(normalized))
    }

    private fun deleteBlocking(ftp: FTPClient, path: String, recursive: Boolean) {
        val item = statBlocking(ftp, path) ?: return
        val remote = remotePath(path)
        if (item.isDirectory) {
            if (recursive) {
                ftp.listFiles(remote).orEmpty().filterNot { it.name == "." || it.name == ".." }.forEach {
                    deleteBlocking(ftp, child(path, it.name), true)
                }
            }
            if (!ftp.removeDirectory(remote)) throw IOException(replyMessage(ftp, "Не удалось удалить папку ${item.name}"))
        } else if (!ftp.deleteFile(remote)) {
            throw IOException(replyMessage(ftp, "Не удалось удалить ${item.name}"))
        }
    }

    private fun remotePath(path: String): String = normalize(path)
    private fun childPath(parent: String, name: String): String = child(parent, name)

    private fun replyMessage(ftp: FTPClient, prefix: String): String =
        "$prefix: ${ftp.replyString?.trim().orEmpty()}".trimEnd(':', ' ')
}
