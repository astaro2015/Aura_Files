package com.aurafiles.app.backend

import com.aurafiles.app.model.SmbProfile
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbStorageBackend(
    private val profile: SmbProfile,
    override val descriptor: StorageBackendDescriptor = StorageBackendDescriptor(
        id = "smb:${profile.host}:${profile.share}:${profile.username}",
        title = profile.name.ifBlank { "SMB ${profile.host}" },
        kind = StorageBackendKind.SMB,
    ),
) : StorageBackend {
    private val lock = Any()
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    init {
        require(profile.share.isNotBlank()) { "Для StorageBackend SMB нужно выбрать общую папку" }
    }

    override suspend fun list(path: String): List<StorageItem> = synchronized(lock) {
        val disk = requireShare()
        val smbPath = smbPath(path)
        disk.list(smbPath).asSequence()
            .filterNot { it.fileName == "." || it.fileName == ".." }
            .map { info ->
                val isDirectory = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                StorageItem(
                    backendId = descriptor.id,
                    path = child(path, info.fileName),
                    name = info.fileName,
                    isDirectory = isDirectory,
                    size = if (isDirectory) 0L else info.endOfFile.coerceAtLeast(0L),
                    modifiedAt = info.lastWriteTime.toEpochMillis(),
                    mimeType = if (isDirectory) null else BackendPath.guessMime(info.fileName),
                )
            }
            .sortedWith(compareByDescending<StorageItem> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }

    override suspend fun stat(path: String): StorageItem? = synchronized(lock) {
        val normalized = normalize(path)
        if (normalized == "/") return@synchronized StorageItem(descriptor.id, "/", descriptor.title, true)
        val disk = requireShare()
        val parentPath = parent(normalized)
        disk.list(smbPath(parentPath)).firstOrNull { it.fileName == BackendPath.name(normalized) }?.let { info ->
            val isDirectory = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            StorageItem(
                backendId = descriptor.id,
                path = normalized,
                name = info.fileName,
                isDirectory = isDirectory,
                size = if (isDirectory) 0L else info.endOfFile.coerceAtLeast(0L),
                modifiedAt = info.lastWriteTime.toEpochMillis(),
                mimeType = if (isDirectory) null else BackendPath.guessMime(info.fileName),
            )
        }
    }

    override suspend fun openRead(path: String): StorageReadHandle = synchronized(lock) {
        val remote = requireShare().openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_SEQUENTIAL_ONLY),
        )
        val stream = remote.inputStream
        object : StorageReadHandle {
            override val input: InputStream = stream
            override fun close() {
                runCatching { input.close() }
                runCatching { remote.close() }
            }
        }
    }

    override suspend fun openWrite(path: String, replace: Boolean): StorageWriteHandle = synchronized(lock) {
        val disposition = if (replace) SMB2CreateDisposition.FILE_OVERWRITE_IF else SMB2CreateDisposition.FILE_CREATE
        val remotePath = smbPath(path)
        val disk = requireShare()
        val remote = disk.openFile(
            remotePath,
            EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ, AccessMask.DELETE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            disposition,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_SEQUENTIAL_ONLY),
        )
        val stream = remote.outputStream
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
                runCatching { if (disk.fileExists(remotePath)) disk.rm(remotePath) }
                finished = true
            }
            override fun close() {
                if (!finished) abort()
            }
        }
    }

    override suspend fun mkdir(path: String): StorageItem = synchronized(lock) {
        val disk = requireShare()
        val remote = smbPath(path)
        if (!disk.folderExists(remote)) disk.mkdir(remote)
        StorageItem(descriptor.id, normalize(path), BackendPath.name(path), true)
    }

    override suspend fun rename(path: String, newName: String): StorageItem = synchronized(lock) {
        val disk = requireShare()
        val target = child(parent(path), newName)
        moveRemote(disk, path, target)
        statBlocking(disk, target) ?: StorageItem(descriptor.id, target, newName, false)
    }

    override suspend fun move(path: String, destinationDirectory: String): StorageItem = synchronized(lock) {
        val disk = requireShare()
        val target = child(destinationDirectory, BackendPath.name(path))
        moveRemote(disk, path, target)
        statBlocking(disk, target) ?: StorageItem(descriptor.id, target, BackendPath.name(path), false)
    }

    override suspend fun delete(path: String, recursive: Boolean) = synchronized(lock) {
        deleteBlocking(requireShare(), path, recursive)
    }

    override suspend fun ping(): Boolean = synchronized(lock) {
        runCatching { requireShare().list(smbPath("/")); true }.getOrDefault(false)
    }

    override fun close() = synchronized(lock) {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        share = null
        session = null
        connection = null
        client = null
    }

    private fun requireShare(): DiskShare {
        share?.takeIf { it.isConnected }?.let { return it }
        close()
        val candidates = buildList {
            // Explicit credentials first; only then guest/anonymous fallback.
            if (profile.username.isNotBlank()) {
                add(AuthenticationContext(profile.username, profile.password.toCharArray(), profile.domain))
            }
            add(AuthenticationContext.guest())
            add(AuthenticationContext.anonymous())
        }
        var last: Throwable? = null
        candidates.forEach { auth ->
            val smbClient = SMBClient(smbConfig())
            var conn: Connection? = null
            var sess: Session? = null
            var connected: DiskShare? = null
            try {
                conn = smbClient.connect(profile.host.trim())
                sess = conn.authenticate(auth)
                connected = sess.connectShare(profile.share.trim().trim('/', '\\')) as? DiskShare
                    ?: throw IOException("${profile.share} не является файловой SMB-папкой")
                client = smbClient
                connection = conn
                session = sess
                share = connected
                return connected
            } catch (error: Throwable) {
                last = error
                runCatching { connected?.close() }
                runCatching { sess?.close() }
                runCatching { conn?.close() }
                runCatching { smbClient.close() }
            }
        }
        throw IOException("Не удалось подключиться к SMB ${profile.host}/${profile.share}: ${last?.message.orEmpty()}", last)
    }

    private fun smbConfig(): SmbConfig = SmbConfig.builder()
        .withTimeout(20, TimeUnit.SECONDS)
        .withSoTimeout(20, TimeUnit.SECONDS)
        .withReadTimeout(90, TimeUnit.SECONDS)
        .withWriteTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun statBlocking(disk: DiskShare, path: String): StorageItem? {
        val normalized = normalize(path)
        if (normalized == "/") return StorageItem(descriptor.id, "/", descriptor.title, true)
        return disk.list(smbPath(parent(normalized))).firstOrNull { it.fileName == BackendPath.name(normalized) }?.let { info ->
            val directory = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            StorageItem(
                descriptor.id,
                normalized,
                info.fileName,
                directory,
                if (directory) 0L else info.endOfFile.coerceAtLeast(0L),
                info.lastWriteTime.toEpochMillis(),
                if (directory) null else BackendPath.guessMime(info.fileName),
            )
        }
    }

    private fun moveRemote(disk: DiskShare, sourcePath: String, targetPath: String) {
        val source = statBlocking(disk, sourcePath) ?: throw IOException("SMB-объект не найден")
        val options = if (source.isDirectory) {
            EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE)
        } else {
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        }
        disk.open(
            smbPath(sourcePath),
            EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            options,
        ).use { handle ->
            handle.rename(smbPath(targetPath), false)
        }
    }

    private fun deleteBlocking(disk: DiskShare, path: String, recursive: Boolean) {
        val item = statBlocking(disk, path) ?: return
        val remote = smbPath(path)
        if (item.isDirectory) {
            val children = disk.list(remote).filterNot { it.fileName == "." || it.fileName == ".." }
            if (children.isNotEmpty() && !recursive) throw IOException("Папка ${item.name} не пуста")
            children.forEach { deleteBlocking(disk, child(path, it.fileName), true) }
            disk.rmdir(remote, false)
        } else {
            disk.rm(remote)
        }
    }

    private fun smbPath(path: String): String = normalize(path).removePrefix("/").replace('/', '\\')
}
