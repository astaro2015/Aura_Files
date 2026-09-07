package com.aurafiles.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.model.SmbEntry
import com.aurafiles.app.model.SmbProfile
import com.aurafiles.app.network.normalizedSmbProfile
import com.aurafiles.app.network.isLikelySmbTransportError
import com.aurafiles.app.network.normalizeSmbRelativePath
import com.aurafiles.app.network.smbUrlHost
import com.aurafiles.app.network.smbUserMessage
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
import java.util.Properties
import jcifs.CIFSContext
import jcifs.SmbConstants
import jcifs.context.BaseContext
import jcifs.config.PropertyConfiguration
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.File
import java.io.IOException
import java.net.URLConnection
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** SMB2/SMB3 browser. SMB1 is deliberately not enabled. */
class SmbRepository(private val context: Context) {
    private val mutex = Mutex()
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private var activeProfile: SmbProfile? = null
    private var preferredAuth: AuthenticationContext? = null
    private var currentPath = ""

    suspend fun discoverShares(profile: SmbProfile): List<String> = mutex.withLock {
        val normalized = profile.normalizedSmbProfile().copy(share = "")
        validateHost(normalized)
        disconnectInternal()
        preferredAuth = null
        activeProfile = normalized
        val shares = connectAndEnumerateShares(normalized)
        currentPath = ""
        shares
    }

    suspend fun connect(profile: SmbProfile): Pair<String, List<SmbEntry>> = mutex.withLock {
        val normalized = profile.normalizedSmbProfile()
        validateDirect(normalized)
        disconnectInternal()
        preferredAuth = null
        activeProfile = normalized
        currentPath = ""
        displayPath(currentPath) to connectShareRootInternal()
    }

    suspend fun connectShare(shareName: String): Pair<String, List<SmbEntry>> = mutex.withLock {
        val profile = activeProfile ?: throw IOException("Сначала подключитесь к SMB-компьютеру")
        val selected = profile.copy(share = shareName).normalizedSmbProfile()
        require(selected.share.isNotEmpty()) { "Выберите общую папку" }
        disconnectInternal()
        activeProfile = selected
        currentPath = ""
        displayPath(currentPath) to connectShareRootInternal()
    }

    suspend fun returnToShareList() = mutex.withLock {
        // A share handle, session and connection are all tied together in SMBJ. Closing only
        // DiskShare left an idle authenticated session alive until the next selection.
        disconnectInternal()
        activeProfile = activeProfile?.copy(share = "")
        currentPath = ""
    }

    suspend fun disconnect() = mutex.withLock {
        disconnectInternal()
        preferredAuth = null
        activeProfile = null
        currentPath = ""
    }

    suspend fun list(path: String): Pair<String, List<SmbEntry>> = mutex.withLock {
        val normalized = normalizePath(path)
        val items = withReconnect { it.list(normalized).toEntries(normalized) }
        currentPath = normalized
        displayPath(normalized) to items
    }

    suspend fun createDirectory(requestedName: String): Pair<String, List<SmbEntry>> = mutex.withLock {
        val name = safeName(requestedName)
        val path = childPath(currentPath, name)
        withMutation { disk ->
            require(!disk.folderExists(path) && !disk.fileExists(path)) { "$name уже существует" }
            disk.mkdir(path)
        }
        refreshAfterCommittedMutation("Папка создана")
    }

    suspend fun rename(entry: SmbEntry, requestedName: String): Pair<String, List<SmbEntry>> = mutex.withLock {
        val name = safeName(requestedName)
        val targetPath = childPath(parentPath(entry.path), name)
        withMutation { disk ->
            require(!disk.folderExists(targetPath) && !disk.fileExists(targetPath)) { "$name уже существует" }
            openEntry(disk, entry).use { remote -> remote.rename(targetPath, false) }
        }
        refreshAfterCommittedMutation("Переименование")
    }

    suspend fun delete(entry: SmbEntry, recursive: Boolean = false): Pair<String, List<SmbEntry>> = mutex.withLock {
        withMutation { disk -> deleteRemoteEntrySafely(disk, entry, recursive) }
        refreshAfterCommittedMutation(if (entry.isDirectory) "Удаление папки" else "Удаление файла")
    }

    suspend fun upload(
        uris: List<Uri>,
        destinationPath: String = currentPath,
        refreshListing: Boolean = true,
        onProgress: (name: String, written: Long, total: Long) -> Unit = { _, _, _ -> },
    ): Pair<String, List<SmbEntry>> = mutex.withLock {
        require(uris.isNotEmpty()) { "Выберите файлы для загрузки" }
        val destination = normalizePath(destinationPath)
        withMutation { disk ->
            uris.forEach { uri ->
                val source = documentFromUri(uri)
                    ?: throw IOException("Выбранный файл недоступен")
                uploadNode(disk, source, destination, onProgress)
            }
        }
        if (refreshListing) refreshAfterCommittedMutation("Загрузка")
        else displayPath(currentPath) to emptyList()
    }

    suspend fun download(
        entry: SmbEntry,
        destination: DocumentFile,
        onProgress: (name: String, written: Long, total: Long) -> Unit = { _, _, _ -> },
    ): DocumentFile = mutex.withLock {
        require(destination.isDirectory && destination.canWrite()) { "Локальная папка недоступна для записи" }
        withMutation { disk ->
            val current = currentEntryMetadata(disk, entry)
            require(!current.isReparsePoint) {
                "SMB: ссылка/junction ${current.name} не скачивается автоматически — откройте целевой объект явно"
            }
            if (current.isDirectory) downloadDirectory(disk, current, destination, onProgress)
            else downloadFile(disk, current, destination, onProgress)
        }
    }

    suspend fun move(entry: SmbEntry, destinationPath: String): Pair<String, List<SmbEntry>> = mutex.withLock {
        val normalizedDestination = normalizePath(destinationPath)
        val target = childPath(normalizedDestination, entry.name)
        withMutation { disk ->
            require(!disk.fileExists(target) && !disk.folderExists(target)) { "${entry.name} уже существует в папке назначения" }
            openEntry(disk, entry).use { it.rename(target, false) }
        }
        refreshAfterCommittedMutation("Перемещение")
    }

    suspend fun copy(entry: SmbEntry, destinationPath: String): Pair<String, List<SmbEntry>> = mutex.withLock {
        withMutation { disk -> copyRemoteNode(disk, entry, normalizePath(destinationPath)) }
        refreshAfterCommittedMutation("Копирование")
    }

    private fun uploadFile(
        disk: DiskShare,
        source: DocumentFile,
        destinationPath: String,
        onProgress: (String, Long, Long) -> Unit,
    ) {
        val requested = source.name ?: "Без имени"
        val finalName = uniqueRemoteName(disk, destinationPath, requested)
        val finalPath = childPath(destinationPath, finalName)
        val temporaryPath = childPath(destinationPath, ".aura-part-${UUID.randomUUID()}")
        try {
            disk.openFile(
                temporaryPath,
                EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ, AccessMask.DELETE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_CREATE,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_SEQUENTIAL_ONLY),
            ).use { remote ->
                val input = if (source.uri.scheme == "file") {
                    File(requireNotNull(source.uri.path) { "Не удалось определить путь $requested" }).inputStream()
                } else {
                    context.contentResolver.openInputStream(source.uri)
                        ?: throw IOException("Не удалось прочитать $requested")
                }
                var written = 0L
                onProgress(requested, 0L, source.length())
                input.buffered(TRANSFER_BUFFER_SIZE).use { sourceStream ->
                    remote.outputStream.buffered(TRANSFER_BUFFER_SIZE).use { targetStream ->
                        val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                        while (true) {
                            val read = sourceStream.read(buffer)
                            if (read < 0) break
                            targetStream.write(buffer, 0, read)
                            written += read
                            onProgress(requested, written, source.length())
                        }
                        targetStream.flush()
                    }
                }
                val expected = source.length()
                require(expected < 0L || remote.length == expected) {
                    "SMB записал ${remote.length} из $expected байт"
                }
                remote.rename(finalPath, false)
            }
        } catch (error: Throwable) {
            runCatching { if (disk.fileExists(temporaryPath)) disk.rm(temporaryPath) }
            throw error
        }
    }

    private fun uploadNode(
        disk: DiskShare,
        source: DocumentFile,
        destinationPath: String,
        onProgress: (String, Long, Long) -> Unit,
    ) {
        if (source.isFile) {
            uploadFile(disk, source, destinationPath, onProgress)
            return
        }
        require(source.isDirectory) { "Выбранный объект недоступен" }
        val requested = source.name ?: "Папка"
        val directoryName = uniqueRemoteName(disk, destinationPath, requested)
        val remoteDirectory = childPath(destinationPath, directoryName)
        disk.mkdir(remoteDirectory)
        try {
            source.listFiles().forEach { child -> uploadNode(disk, child, remoteDirectory, onProgress) }
        } catch (error: Throwable) {
            runCatching {
                deleteRemoteEntrySafely(
                    disk,
                    SmbEntry(requested, remoteDirectory, isDirectory = true, size = 0L, modifiedAt = 0L),
                    recursive = true,
                )
            }
            throw error
        }
    }

    private fun downloadFile(
        disk: DiskShare,
        entry: SmbEntry,
        destination: DocumentFile,
        onProgress: (String, Long, Long) -> Unit,
    ): DocumentFile {
        val finalName = uniqueLocalName(destination, entry.name)
        val temporaryName = ".aura-part-${UUID.randomUUID()}"
        val mimeType = URLConnection.guessContentTypeFromName(finalName) ?: "application/octet-stream"
        val temporary = destination.createFile(mimeType, temporaryName)
            ?: throw IOException("Не удалось создать временный файл для $finalName")
        try {
            disk.openFile(
                normalizePath(entry.path),
                EnumSet.of(AccessMask.GENERIC_READ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_SEQUENTIAL_ONLY),
            ).use { remote ->
                val output = if (temporary.uri.scheme == "file") {
                    File(requireNotNull(temporary.uri.path) { "Не удалось определить путь $finalName" }).outputStream()
                } else {
                    context.contentResolver.openOutputStream(temporary.uri, "w")
                        ?: throw IOException("Не удалось записать $finalName")
                }
                var written = 0L
                onProgress(entry.name, 0L, entry.size)
                remote.inputStream.buffered(TRANSFER_BUFFER_SIZE).use { sourceStream ->
                    output.buffered(TRANSFER_BUFFER_SIZE).use { targetStream ->
                        val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                        while (true) {
                            val read = sourceStream.read(buffer)
                            if (read < 0) break
                            targetStream.write(buffer, 0, read)
                            written += read
                            onProgress(entry.name, written, entry.size)
                        }
                        targetStream.flush()
                    }
                }
                require(entry.size < 0L || written == entry.size) {
                    "Получено $written из ${entry.size} байт"
                }
            }
            require(temporary.renameTo(finalName)) { "Не удалось завершить скачивание $finalName" }
            return temporary
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun downloadDirectory(
        disk: DiskShare,
        entry: SmbEntry,
        destination: DocumentFile,
        onProgress: (String, Long, Long) -> Unit,
    ): DocumentFile {
        val current = requireSafeRecursiveDirectory(disk, entry)
        val localName = uniqueLocalName(destination, current.name)
        val localRoot = destination.createDirectory(localName)
            ?: throw IOException("Не удалось создать папку $localName")
        try {
            val remote = normalizePath(current.path)
            disk.list(remote).toEntries(remote).forEach { child ->
                if (child.isDirectory) {
                    downloadDirectory(disk, child, localRoot, onProgress)
                } else {
                    val currentChild = currentEntryMetadata(disk, child)
                    require(!currentChild.isReparsePoint) {
                        "SMB: ссылка ${currentChild.name} внутри папки не скачивается автоматически"
                    }
                    downloadFile(disk, currentChild, localRoot, onProgress)
                }
            }
            return localRoot
        } catch (error: Throwable) {
            localRoot.delete()
            throw error
        }
    }

    private fun copyRemoteNode(disk: DiskShare, entry: SmbEntry, destinationPath: String) {
        val current = currentEntryMetadata(disk, entry)
        require(!current.isReparsePoint) { "SMB: ссылка/junction ${current.name} не копируется автоматически" }
        if (current.isDirectory) {
            val targetName = uniqueRemoteName(disk, destinationPath, current.name)
            val targetDirectory = childPath(destinationPath, targetName)
            disk.mkdir(targetDirectory)
            try {
                disk.list(normalizePath(current.path)).toEntries(normalizePath(current.path)).forEach { child ->
                    copyRemoteNode(disk, child, targetDirectory)
                }
            } catch (error: Throwable) {
                runCatching {
                    deleteRemoteEntrySafely(
                        disk,
                        SmbEntry(targetName, targetDirectory, isDirectory = true, size = 0L, modifiedAt = 0L),
                        recursive = true,
                    )
                }
                throw error
            }
            return
        }
        val targetName = uniqueRemoteName(disk, destinationPath, current.name)
        val finalPath = childPath(destinationPath, targetName)
        val temporaryPath = childPath(destinationPath, ".aura-part-${UUID.randomUUID()}")
        try {
            disk.openFile(
                normalizePath(current.path),
                EnumSet.of(AccessMask.GENERIC_READ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            ).use { source ->
                disk.openFile(
                    temporaryPath,
                    EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ, AccessMask.DELETE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_CREATE,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                ).use { target ->
                    source.remoteCopyTo(target)
                    require(target.length == current.size) { "SMB-копия имеет неверный размер" }
                    target.rename(finalPath, false)
                }
            }
        } catch (error: Throwable) {
            runCatching { if (disk.fileExists(temporaryPath)) disk.rm(temporaryPath) }
            throw error
        }
    }

    private fun openEntry(disk: DiskShare, entry: SmbEntry) = disk.open(
        normalizePath(entry.path),
        // Rename only requires DELETE on the opened object. Asking for GENERIC_WRITE/READ as
        // well causes avoidable ACCESS_DENIED failures on otherwise rename-capable shares.
        EnumSet.of(AccessMask.DELETE),
        EnumSet.of(
            if (entry.isDirectory) FileAttributes.FILE_ATTRIBUTE_DIRECTORY
            else FileAttributes.FILE_ATTRIBUTE_NORMAL
        ),
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        EnumSet.of(
            if (entry.isDirectory) SMB2CreateOptions.FILE_DIRECTORY_FILE
            else SMB2CreateOptions.FILE_NON_DIRECTORY_FILE
        ),
    )

    private fun connectedShare(): DiskShare = share?.takeIf { it.isConnected } ?: run {
        connectInternal()
        requireNotNull(share)
    }

    private fun connectInternal() {
        val profile = activeProfile ?: throw IOException("SMB-подключение ещё не настроено")
        require(profile.share.isNotBlank()) { "Выберите общую папку" }
        var lastError: Throwable? = null
        authenticationCandidates(profile).forEach { auth ->
            disconnectInternal()
            val newClient = SMBClient(smbConfig())
            var newConnection: Connection? = null
            var newSession: Session? = null
            var newShare: DiskShare? = null
            try {
                newConnection = newClient.connect(profile.host)
                newSession = newConnection.authenticate(auth)
                newShare = newSession.connectShare(profile.share) as? DiskShare
                    ?: throw IOException("${profile.share} не является файловой SMB-папкой")
                client = newClient
                connection = newConnection
                session = newSession
                share = newShare
                preferredAuth = auth.takeIf { it.isGuest || it.isAnonymous }
                return
            } catch (error: Throwable) {
                lastError = error
                runCatching { newShare?.close() }
                runCatching { newSession?.close() }
                runCatching { newConnection?.close() }
                runCatching { newClient.close() }
            }
        }
        val error = lastError ?: IOException("Не удалось подключиться к SMB")
        throw IOException(smbMessage(error), error)
    }

    private fun connectShareRootInternal(): List<SmbEntry> {
        val profile = activeProfile ?: throw IOException("SMB-подключение ещё не настроено")
        require(profile.share.isNotBlank()) { "Выберите общую папку" }
        var lastError: Throwable? = null
        authenticationCandidates(profile).forEach { auth ->
            disconnectInternal()
            val newClient = SMBClient(smbConfig())
            var newConnection: Connection? = null
            var newSession: Session? = null
            var newShare: DiskShare? = null
            try {
                newConnection = newClient.connect(profile.host)
                newSession = newConnection.authenticate(auth)
                newShare = newSession.connectShare(profile.share) as? DiskShare
                    ?: throw IOException("${profile.share} не является файловой SMB-папкой")
                val items = newShare.list("").toEntries("")
                client = newClient
                connection = newConnection
                session = newSession
                share = newShare
                preferredAuth = auth.takeIf { it.isGuest || it.isAnonymous }
                return items
            } catch (error: Throwable) {
                lastError = error
                runCatching { newShare?.close() }
                runCatching { newSession?.close() }
                runCatching { newConnection?.close() }
                runCatching { newClient.close() }
            }
        }
        val error = lastError ?: IOException("Не удалось открыть общую папку")
        throw IOException(smbMessage(error), error)
    }

    private fun connectAndEnumerateShares(profile: SmbProfile): List<String> {
        var lastError: Throwable? = null
        authenticationCandidates(profile).forEach { auth ->
            val baseContext = BaseContext(PropertyConfiguration(jcifsProperties()))
            val authenticatedContext: CIFSContext = when {
                auth.isGuest -> baseContext.withGuestCrendentials()
                auth.isAnonymous -> baseContext.withAnonymousCredentials()
                else -> baseContext.withCredentials(
                    NtlmPasswordAuthenticator(auth.domain, auth.username, String(auth.password))
                )
            }
            try {
                val shares = SmbFile("smb://${smbUrlHost(profile.host)}/", authenticatedContext).use { root ->
                    val children = root.listFiles()
                    try {
                        children.asSequence()
                            .filter { it.type == SmbConstants.TYPE_SHARE }
                            .map { it.name.trim().trimEnd('/') }
                            .filter { it.isNotEmpty() && !it.endsWith('$') }
                            .distinctBy { it.lowercase() }
                            .sortedWith(String.CASE_INSENSITIVE_ORDER)
                            .toList()
                    } finally {
                        children.forEach { runCatching { it.close() } }
                    }
                }
                preferredAuth = auth.takeIf { it.isGuest || it.isAnonymous }
                return shares
            } catch (error: Throwable) {
                lastError = error
            } finally {
                runCatching { authenticatedContext.close() }
                if (authenticatedContext !== baseContext) runCatching { baseContext.close() }
            }
        }
        val error = lastError ?: IOException("Не удалось получить список общих папок")
        throw IOException(smbMessage(error), error)
    }

    private fun jcifsProperties(): Properties = Properties().apply {
        setProperty("jcifs.smb.client.minVersion", "SMB202")
        setProperty("jcifs.smb.client.maxVersion", "SMB311")
        setProperty("jcifs.smb.client.connTimeout", "15000")
        setProperty("jcifs.smb.client.responseTimeout", "20000")
        setProperty("jcifs.smb.client.soTimeout", "20000")
    }

    private fun smbConfig(): SmbConfig = SmbConfig.builder()
        .withTimeout(20, TimeUnit.SECONDS)
        .withSoTimeout(20, TimeUnit.SECONDS)
        .withReadTimeout(90, TimeUnit.SECONDS)
        .withWriteTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun authenticationCandidates(profile: SmbProfile): List<AuthenticationContext> {
        // An explicitly supplied SMB identity must never silently fall back to guest/anonymous:
        // a wrong password should fail visibly instead of opening a different, partially accessible session.
        if (profile.username.isNotBlank()) {
            return listOf(AuthenticationContext(profile.username, profile.password.toCharArray(), profile.domain))
        }
        val candidates = buildList {
            preferredAuth?.takeIf { it.isGuest || it.isAnonymous }?.let(::add)
            add(AuthenticationContext.guest())
            add(AuthenticationContext.anonymous())
        }
        return candidates.distinctBy { auth -> "${auth.isGuest}|${auth.isAnonymous}" }
    }

    private inline fun <T> withReconnect(action: (DiskShare) -> T): T {
        var disk = share?.takeIf { it.isConnected } ?: run {
            connectInternal()
            requireNotNull(share)
        }
        return try {
            action(disk)
        } catch (first: Throwable) {
            // Repeating ACCESS_DENIED, NOT_FOUND or another semantic failure can only hide the
            // real error. A read/list operation is retried once exclusively for transport loss.
            if (!isLikelySmbTransportError(first)) throw IOException(smbMessage(first), first)
            disconnectInternal()
            connectInternal()
            disk = requireNotNull(share)
            runCatching { action(disk) }.getOrElse { throw IOException(smbMessage(it), it) }
        }
    }

    /** Mutations are never replayed automatically: a retry after a dropped response could duplicate work. */
    private inline fun <T> withMutation(action: (DiskShare) -> T): T {
        val disk = connectedShare()
        try {
            return action(disk)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // If transport died, discard the stale handles so the next explicit command starts
            // from a fresh session. Do not retry the mutation because its commit state is unknown.
            if (isLikelySmbTransportError(error)) disconnectInternal()
            throw IOException(smbMessage(error), error)
        }
    }

    private fun refreshAfterCommittedMutation(label: String): Pair<String, List<SmbEntry>> {
        return try {
            displayPath(currentPath) to listInternal(currentPath)
        } catch (error: Throwable) {
            val detail = smbMessage(error).removePrefix("SMB:").trim()
            throw IOException(
                "SMB: $label выполнено, но список не удалось обновить" +
                    detail.takeIf(String::isNotBlank)?.let { ". $it" }.orEmpty(),
                error,
            )
        }
    }

    private fun deleteRemoteEntrySafely(disk: DiskShare, entry: SmbEntry, recursive: Boolean) {
        // Re-read attributes immediately before acting. A path may be replaced between the
        // UI listing and this call; stale metadata must never make Aura recurse into a junction.
        val current = currentEntryMetadata(disk, entry)
        val remote = normalizePath(current.path)
        if (!current.isDirectory) {
            disk.rm(remote)
            return
        }
        if (current.isReparsePoint) {
            // Delete the link object itself, never enumerate/follow its target.
            disk.rmdir(remote, false)
            return
        }
        val children = disk.list(remote).toEntries(remote)
        if (children.isNotEmpty() && !recursive) throw IOException("Папка ${current.name} не пуста")
        if (recursive) children.forEach { child -> deleteRemoteEntrySafely(disk, child, recursive = true) }
        disk.rmdir(remote, false)
    }


    private fun requireSafeRecursiveDirectory(disk: DiskShare, entry: SmbEntry): SmbEntry {
        val current = currentEntryMetadata(disk, entry)
        require(current.isDirectory) { "SMB: ${current.name} больше не является папкой" }
        require(!current.isReparsePoint) {
            "SMB: ссылка/junction ${current.name} не обрабатывается рекурсивно"
        }
        return current
    }

    /**
     * Resolve fresh attributes from the parent immediately before a recursive operation.
     * Refuse to recurse when parent enumeration is unavailable: following an unverified
     * directory is less safe than asking the user to open/copy the target explicitly.
     */
    private fun currentEntryMetadata(disk: DiskShare, entry: SmbEntry): SmbEntry {
        val normalized = normalizePath(entry.path)
        require(normalized.isNotBlank()) { "SMB: операция с корнем общей папки запрещена" }
        val parent = parentPath(normalized)
        val requested = normalized.substringAfterLast('\\')
        val matches = try {
            disk.list(parent).toEntries(parent)
        } catch (error: Throwable) {
            throw IOException(
                "SMB: не удалось безопасно проверить ${entry.name} перед рекурсивной операцией",
                error,
            )
        }
        matches.firstOrNull { it.name == requested }?.let { return it }
        // Only use a case-insensitive fallback after the server confirms that the requested
        // spelling resolves. This keeps Windows shares convenient without mistaking `Foo` for
        // `foo` on a case-sensitive Samba share.
        val requestedExists = disk.fileExists(normalized) || disk.folderExists(normalized)
        if (requestedExists) {
            matches.firstOrNull { it.name.equals(requested, ignoreCase = true) }?.let { return it }
        }
        throw IOException("SMB: ${entry.name} больше не существует")
    }

    private fun List<com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation>.toEntries(
        parent: String,
    ): List<SmbEntry> = asSequence()
        .filterNot { it.fileName == "." || it.fileName == ".." }
        .map { info ->
            val isDirectory = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            SmbEntry(
                name = info.fileName,
                path = childPath(parent, info.fileName),
                isDirectory = isDirectory,
                size = if (isDirectory) 0L else info.endOfFile,
                modifiedAt = info.lastWriteTime.toEpochMillis(),
                isReparsePoint = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L,
            )
        }
        .sortedWith(compareByDescending<SmbEntry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        .toList()

    private fun listInternal(path: String): List<SmbEntry> = withReconnect { it.list(path).toEntries(path) }

    private fun disconnectInternal() {
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

    private fun validateHost(profile: SmbProfile) {
        require(profile.host.trim().isNotEmpty()) { "Укажите адрес SMB-устройства" }
    }

    private fun validateDirect(profile: SmbProfile) {
        validateHost(profile)
        require(profile.share.trim().trim('/', '\\').isNotEmpty()) { "Укажите имя общей папки" }
    }

    private fun documentFromUri(uri: Uri): DocumentFile? {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            return runCatching { DocumentFile.fromFile(File(path)) }.getOrNull()
        }
        return runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            ?: runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
    }

    private fun normalizePath(path: String): String = normalizeSmbRelativePath(path)

    private fun childPath(parent: String, name: String): String =
        if (parent.isBlank()) name else "${normalizePath(parent)}\\$name"

    private fun parentPath(path: String): String = normalizePath(path).substringBeforeLast('\\', "")

    private fun displayPath(path: String): String = if (path.isBlank()) "/" else "/${path.replace('\\', '/')}"

    private fun uniqueLocalName(parent: DocumentFile, requested: String): String {
        if (parent.findFile(requested) == null) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        var index = 2
        while (parent.findFile("$base ($index)$extension") != null) index++
        return "$base ($index)$extension"
    }

    private fun uniqueRemoteName(disk: DiskShare, parent: String, requested: String): String {
        fun exists(name: String): Boolean {
            val path = childPath(parent, name)
            return disk.fileExists(path) || disk.folderExists(path)
        }
        if (!exists(requested)) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        var index = 2
        while (exists("$base ($index)$extension")) index += 1
        return "$base ($index)$extension"
    }

    private fun safeName(raw: String): String {
        val value = raw.trim()
        require(value.isNotEmpty()) { "Введите название" }
        require(
            value != "." && value != ".." &&
                value.none { it in "\\/:*?\"<>|" || it.isISOControl() }
        ) {
            "Название содержит недопустимые символы"
        }
        return value
    }

    private fun smbMessage(error: Throwable): String = smbUserMessage(
        error = error,
        host = activeProfile?.host,
        share = activeProfile?.share,
        guestMode = activeProfile?.username?.isBlank(),
    )

    companion object {
        private const val TRANSFER_BUFFER_SIZE = 1024 * 1024
    }

}
