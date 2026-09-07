package com.aurafiles.app.backend

import com.aurafiles.app.model.SmbProfile
import com.aurafiles.app.network.isLikelySmbTransportError
import com.aurafiles.app.network.normalizedSmbProfile
import com.aurafiles.app.network.smbIOException
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
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbStorageBackend(
    rawProfile: SmbProfile,
    override val descriptor: StorageBackendDescriptor = StorageBackendDescriptor(
        // Saved profiles have stable ids. Endpoint-based ids collide when a profile is
        // duplicated or when the same user exists in two domains, so prefer the profile id.
        id = if (rawProfile.id.isNotBlank()) {
            "smb:${rawProfile.id}"
        } else {
            "smb:${rawProfile.host}:${rawProfile.share}:${rawProfile.domain}:${rawProfile.username}"
        },
        title = rawProfile.name.ifBlank { "SMB ${rawProfile.host}" },
        kind = StorageBackendKind.SMB,
    ),
) : StorageBackend {
    private val profile = rawProfile.normalizedSmbProfile()
    private val lock = Any()
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    init {
        require(profile.share.isNotBlank()) { "Для StorageBackend SMB нужно выбрать общую папку" }
    }

    override suspend fun list(path: String): List<StorageItem> = synchronized(lock) {
        withReadReconnect { disk ->
            val normalized = normalize(path)
            disk.list(smbPath(normalized)).asSequence()
                .filterNot { it.fileName == "." || it.fileName == ".." }
                .map { info ->
                    val isDirectory = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                    StorageItem(
                        backendId = descriptor.id,
                        path = child(normalized, info.fileName),
                        name = info.fileName,
                        isDirectory = isDirectory,
                        size = if (isDirectory) 0L else info.endOfFile.coerceAtLeast(0L),
                        modifiedAt = info.lastWriteTime.toEpochMillis(),
                        mimeType = if (isDirectory) null else BackendPath.guessMime(info.fileName),
                        isLink = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L,
                    )
                }
                .sortedWith(compareByDescending<StorageItem> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .toList()
        }
    }

    override suspend fun stat(path: String): StorageItem? = synchronized(lock) {
        withReadReconnect { disk -> statBlocking(disk, path) }
    }

    override suspend fun openRead(path: String): StorageReadHandle = synchronized(lock) {
        val normalized = normalize(path)
        val remote = openReadEntry(normalized)
        val stream = try {
            mappedInput(remote.inputStream)
        } catch (error: Throwable) {
            runCatching { remote.close() }
            throw smbError(error)
        }
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
        val remote = try {
            disk.openFile(
                remotePath,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                disposition,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_SEQUENTIAL_ONLY),
            )
        } catch (error: Throwable) {
            throw smbMutationError(error)
        }
        val stream = try {
            mappedOutput(remote.outputStream)
        } catch (error: Throwable) {
            runCatching { remote.close() }
            throw smbMutationError(error)
        }
        object : StorageWriteHandle {
            private var finished = false
            override val output: OutputStream = stream
            override fun commit() {
                if (finished) return
                try {
                    output.flush()
                    output.close()
                    remote.close()
                    finished = true
                } catch (error: Throwable) {
                    // Mark the handle finished only after cleanup. TransferEngine will still
                    // delete the temporary path through the backend after this exception.
                    runCatching { output.close() }
                    runCatching { remote.close() }
                    finished = true
                    throw smbMutationError(error)
                }
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
        try {
            val disk = requireShare()
            val normalized = normalize(path)
            val remote = smbPath(normalized)
            if (disk.fileExists(remote) || disk.folderExists(remote)) {
                throw java.io.IOException("SMB: файл или папка с таким именем уже существует")
            }
            disk.mkdir(remote)
            StorageItem(descriptor.id, normalized, BackendPath.name(normalized), true)
        } catch (error: Throwable) {
            throw smbMutationError(error)
        }
    }

    override suspend fun rename(path: String, newName: String): StorageItem = synchronized(lock) {
        try {
            val disk = requireShare()
            val target = child(parent(path), newName)
            moveRemote(disk, path, target)
            statBlocking(disk, target) ?: StorageItem(descriptor.id, target, newName, false)
        } catch (error: Throwable) {
            throw smbMutationError(error)
        }
    }

    override suspend fun move(path: String, destinationDirectory: String): StorageItem = synchronized(lock) {
        try {
            val disk = requireShare()
            val target = child(destinationDirectory, BackendPath.name(path))
            moveRemote(disk, path, target)
            statBlocking(disk, target) ?: StorageItem(descriptor.id, target, BackendPath.name(path), false)
        } catch (error: Throwable) {
            throw smbMutationError(error)
        }
    }

    override suspend fun delete(path: String, recursive: Boolean) = synchronized(lock) {
        try {
            deleteBlocking(requireShare(), path, recursive)
        } catch (error: Throwable) {
            throw smbMutationError(error)
        }
    }

    override suspend fun ping(): Boolean = synchronized(lock) {
        runCatching { withReadReconnect { it.list(""); true } }.getOrDefault(false)
    }

    override fun close() = synchronized(lock) { closeInternal() }

    private fun closeInternal() {
        val oldShare = share
        val oldSession = session
        val oldConnection = connection
        val oldClient = client
        share = null
        session = null
        connection = null
        client = null
        runCatching { oldShare?.close() }
        runCatching { oldSession?.close() }
        runCatching { oldConnection?.close() }
        runCatching { oldClient?.close() }
    }

    private fun requireShare(): DiskShare {
        share?.takeIf { it.isConnected }?.let { return it }
        closeInternal()
        val candidates = if (profile.username.isNotBlank()) {
            // Explicit credentials are exclusive: never turn a bad password into a guest session.
            listOf(AuthenticationContext(profile.username, profile.password.toCharArray(), profile.domain))
        } else {
            listOf(AuthenticationContext.guest(), AuthenticationContext.anonymous())
        }
        var last: Throwable? = null
        candidates.forEach { auth ->
            val smbClient = SMBClient(smbConfig())
            var conn: Connection? = null
            var sess: Session? = null
            var connected: DiskShare? = null
            try {
                conn = smbClient.connect(profile.host)
                sess = conn.authenticate(auth)
                connected = sess.connectShare(profile.share) as? DiskShare
                    ?: throw java.io.IOException("${profile.share} не является файловой SMB-папкой")
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
        throw smbError(last ?: java.io.IOException("Не удалось подключиться к SMB"))
    }

    private inline fun <T> withReadReconnect(action: (DiskShare) -> T): T {
        val firstShare = requireShare()
        try {
            return action(firstShare)
        } catch (first: Throwable) {
            if (!isLikelySmbTransportError(first)) throw smbError(first)
            closeInternal()
        }
        return try {
            action(requireShare())
        } catch (second: Throwable) {
            throw smbError(second)
        }
    }

    /** Opening a reader is idempotent, so a transport failure before the handle is returned may be retried once. */
    private fun openReadEntry(path: String): com.hierynomus.smbj.share.File {
        fun open(disk: DiskShare) = disk.openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_SEQUENTIAL_ONLY),
        )
        val firstShare = requireShare()
        try {
            return open(firstShare)
        } catch (first: Throwable) {
            if (!isLikelySmbTransportError(first)) throw smbError(first)
            closeInternal()
        }
        return try {
            open(requireShare())
        } catch (second: Throwable) {
            throw smbError(second)
        }
    }

    private fun smbError(error: Throwable) = smbIOException(
        error = error,
        host = profile.host,
        share = profile.share,
        guestMode = profile.username.isBlank(),
    )

    private fun mappedInput(source: InputStream): InputStream = object : FilterInputStream(source) {
        override fun read(): Int = mapStreamError { super.read() }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            mapStreamError { super.read(buffer, offset, length) }
        override fun skip(count: Long): Long = mapStreamError { super.skip(count) }
        override fun available(): Int = mapStreamError { super.available() }
    }

    private fun mappedOutput(target: OutputStream): OutputStream = object : FilterOutputStream(target) {
        override fun write(value: Int) = mapStreamError { out.write(value) }
        override fun write(buffer: ByteArray, offset: Int, length: Int) =
            mapStreamError { out.write(buffer, offset, length) }
        override fun flush() = mapStreamError { out.flush() }
        override fun close() = mapStreamError { out.close() }
    }

    private inline fun <T> mapStreamError(block: () -> T): T = try {
        block()
    } catch (error: Throwable) {
        if (isLikelySmbTransportError(error)) synchronized(lock) { closeInternal() }
        throw smbError(error)
    }

    private fun smbMutationError(error: Throwable): java.io.IOException {
        if (isLikelySmbTransportError(error)) synchronized(lock) { closeInternal() }
        return smbError(error)
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
        val remote = smbPath(normalized)
        // Ask the server whether the requested spelling exists first. This respects a
        // case-sensitive Samba share while still allowing case-insensitive Windows shares.
        val isFile = disk.fileExists(remote)
        val isDirectory = if (isFile) false else disk.folderExists(remote)
        if (!isFile && !isDirectory) return null

        val requestedName = BackendPath.name(normalized)
        val info = runCatching { disk.list(smbPath(parent(normalized))) }.getOrNull()?.let { entries ->
            entries.firstOrNull { it.fileName == requestedName }
                ?: entries.firstOrNull { it.fileName.equals(requestedName, ignoreCase = true) }
        }
        return if (info != null) {
            val directory = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            StorageItem(
                descriptor.id,
                normalized,
                info.fileName,
                directory,
                if (directory) 0L else info.endOfFile.coerceAtLeast(0L),
                info.lastWriteTime.toEpochMillis(),
                if (directory) null else BackendPath.guessMime(info.fileName),
                isLink = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L,
            )
        } else {
            // Extremely restrictive shares may permit direct lookup but deny a full parent listing.
            StorageItem(
                descriptor.id,
                normalized,
                requestedName,
                isDirectory,
                size = 0L,
                modifiedAt = 0L,
                mimeType = if (isDirectory) null else BackendPath.guessMime(requestedName),
                // If parent enumeration is denied we cannot prove a directory is not a
                // junction/reparse point. Treat it as link-like for recursive transfer safety.
                isLink = isDirectory,
            )
        }
    }

    private fun moveRemote(disk: DiskShare, sourcePath: String, targetPath: String) {
        val source = statBlocking(disk, sourcePath) ?: throw java.io.IOException("SMB-объект не найден")
        val options = if (source.isDirectory) {
            EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE)
        } else {
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        }
        disk.open(
            smbPath(sourcePath),
            // SMB rename requires DELETE; requesting GENERIC_READ too can fail on shares
            // which deliberately allow rename without read access.
            EnumSet.of(AccessMask.DELETE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            options,
        ).use { handle ->
            handle.rename(smbPath(targetPath), false)
        }
    }

    private fun deleteBlocking(
        disk: DiskShare,
        path: String,
        recursive: Boolean,
        knownInfo: com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation? = null,
    ) {
        val normalized = normalize(path)
        if (normalized == "/") throw java.io.IOException("SMB: нельзя удалить корень общей папки")
        val info = knownInfo ?: infoForPath(disk, normalized)
        val directory = info?.let {
            it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
        } ?: disk.folderExists(smbPath(normalized))
        val file = if (directory) false else disk.fileExists(smbPath(normalized))
        if (!directory && !file) return

        if (!directory) {
            disk.rm(smbPath(normalized))
            return
        }
        val reparsePoint = info?.let {
            it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L
        } ?: false
        if (reparsePoint) {
            // Junction/symlink: remove the link object itself. Never enumerate its target.
            disk.rmdir(smbPath(normalized), false)
            return
        }
        if (recursive && info == null) {
            // On a restrictive share we cannot prove that this directory is not a junction.
            // Refuse recursive deletion rather than risk following an unverified reparse target.
            throw java.io.IOException("SMB: не удалось безопасно проверить папку ${BackendPath.name(normalized)} перед рекурсивным удалением")
        }
        val children = disk.list(smbPath(normalized)).filterNot { it.fileName == "." || it.fileName == ".." }
        if (children.isNotEmpty() && !recursive) throw java.io.IOException("Папка ${BackendPath.name(normalized)} не пуста")
        if (recursive) {
            children.forEach { childInfo ->
                deleteBlocking(disk, child(normalized, childInfo.fileName), recursive = true, knownInfo = childInfo)
            }
        }
        disk.rmdir(smbPath(normalized), false)
    }

    private fun infoForPath(
        disk: DiskShare,
        path: String,
    ): com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation? {
        val normalized = normalize(path)
        if (normalized == "/") return null
        val requestedName = BackendPath.name(normalized)
        return runCatching { disk.list(smbPath(parent(normalized))) }.getOrNull()?.let { entries ->
            entries.firstOrNull { it.fileName == requestedName }
                ?: entries.firstOrNull { it.fileName.equals(requestedName, ignoreCase = true) }
        }
    }

    private fun smbPath(path: String): String = normalize(path).removePrefix("/").replace('/', '\\')
}
