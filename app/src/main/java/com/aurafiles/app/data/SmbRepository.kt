package com.aurafiles.app.data

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.model.SmbEntry
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
import java.net.URLConnection
import java.util.EnumSet
import java.util.concurrent.TimeUnit
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
    private var currentPath = ""

    suspend fun connect(profile: SmbProfile): Pair<String, List<SmbEntry>> = mutex.withLock {
        validate(profile)
        disconnectInternal()
        activeProfile = profile.copy(host = profile.host.trim(), share = profile.share.trim().trim('/', '\\'))
        connectInternal()
        currentPath = ""
        displayPath(currentPath) to listInternal(currentPath)
    }

    suspend fun disconnect() = mutex.withLock { disconnectInternal() }

    suspend fun list(path: String): Pair<String, List<SmbEntry>> = mutex.withLock {
        val normalized = normalizePath(path)
        val items = withReconnect { it.list(normalized).toEntries(normalized) }
        currentPath = normalized
        displayPath(normalized) to items
    }

    suspend fun download(entry: SmbEntry, destination: DocumentFile): DocumentFile = mutex.withLock {
        require(!entry.isDirectory) { "Для скачивания выберите файл" }
        require(destination.isDirectory && destination.canWrite()) { "Локальная папка недоступна для записи" }
        val fileName = uniqueLocalName(destination, entry.name)
        val mime = URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
        val target = destination.createFile(mime, fileName)
            ?: throw IOException("Не удалось создать $fileName")
        try {
            withReconnect { disk ->
                disk.openFile(
                    normalizePath(entry.path),
                    EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_SEQUENTIAL_ONLY),
                ).use { remote ->
                    context.contentResolver.openOutputStream(target.uri, "w")?.use(remote::read)
                        ?: throw IOException("Не удалось записать $fileName")
                }
            }
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun connectInternal() {
        val profile = activeProfile ?: throw IOException("SMB-подключение ещё не настроено")
        val config = SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(20, TimeUnit.SECONDS)
            .withReadTimeout(90, TimeUnit.SECONDS)
            .withWriteTimeout(90, TimeUnit.SECONDS)
            .build()
        val newClient = SMBClient(config)
        try {
            val newConnection = newClient.connect(profile.host)
            val auth = if (profile.username.isBlank()) {
                AuthenticationContext.guest()
            } else {
                AuthenticationContext(profile.username, profile.password.toCharArray(), profile.domain)
            }
            val newSession = newConnection.authenticate(auth)
            val newShare = newSession.connectShare(profile.share) as? DiskShare
                ?: throw IOException("${profile.share} не является файловой SMB-папкой")
            client = newClient
            connection = newConnection
            session = newSession
            share = newShare
        } catch (error: Throwable) {
            runCatching { newClient.close() }
            throw IOException(smbMessage(error), error)
        }
    }

    private inline fun <T> withReconnect(action: (DiskShare) -> T): T {
        var disk = share?.takeIf { it.isConnected } ?: run {
            connectInternal()
            requireNotNull(share)
        }
        return try {
            action(disk)
        } catch (first: Throwable) {
            disconnectInternal()
            connectInternal()
            disk = requireNotNull(share)
            runCatching { action(disk) }.getOrElse { throw IOException(smbMessage(it), it) }
        }
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

    private fun validate(profile: SmbProfile) {
        require(profile.host.trim().isNotEmpty()) { "Укажите адрес SMB-устройства" }
        require(profile.share.trim().trim('/', '\\').isNotEmpty()) { "Укажите имя общей папки" }
    }

    private fun normalizePath(path: String): String = path.trim().trim('/', '\\').replace('/', '\\')

    private fun childPath(parent: String, name: String): String =
        if (parent.isBlank()) name else "${normalizePath(parent)}\\$name"

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

    private fun smbMessage(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("STATUS_LOGON_FAILURE", true) -> "SMB: неверный логин или пароль"
            raw.contains("STATUS_BAD_NETWORK_NAME", true) -> "SMB: общая папка не найдена"
            raw.contains("STATUS_ACCESS_DENIED", true) -> "SMB: нет доступа к общей папке"
            raw.isNotBlank() -> "SMB: $raw"
            else -> "Не удалось подключиться к SMB"
        }
    }
}
