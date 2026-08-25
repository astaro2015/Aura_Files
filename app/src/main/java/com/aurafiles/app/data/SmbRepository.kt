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
import java.util.Properties
import jcifs.CIFSContext
import jcifs.SmbConstants
import jcifs.context.BaseContext
import jcifs.config.PropertyConfiguration
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
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
    private var preferredAuth: AuthenticationContext? = null
    private var currentPath = ""

    suspend fun discoverShares(profile: SmbProfile): List<String> = mutex.withLock {
        validateHost(profile)
        disconnectInternal()
        preferredAuth = null
        activeProfile = sanitize(profile).copy(share = "")
        val shares = connectAndEnumerateShares(requireNotNull(activeProfile))
        currentPath = ""
        shares
    }

    suspend fun connect(profile: SmbProfile): Pair<String, List<SmbEntry>> = mutex.withLock {
        validateDirect(profile)
        disconnectInternal()
        preferredAuth = null
        activeProfile = sanitize(profile)
        currentPath = ""
        displayPath(currentPath) to connectShareRootInternal()
    }

    suspend fun connectShare(shareName: String): Pair<String, List<SmbEntry>> = mutex.withLock {
        val profile = activeProfile ?: throw IOException("Сначала подключитесь к SMB-компьютеру")
        val normalizedShare = shareName.trim().trim('/', '\\')
        require(normalizedShare.isNotEmpty()) { "Выберите общую папку" }
        disconnectInternal()
        activeProfile = profile.copy(share = normalizedShare)
        currentPath = ""
        displayPath(currentPath) to connectShareRootInternal()
    }

    suspend fun returnToShareList() = mutex.withLock {
        runCatching { share?.close() }
        share = null
        activeProfile = activeProfile?.copy(share = "")
        currentPath = ""
    }

    suspend fun disconnect() = mutex.withLock {
        disconnectInternal()
        preferredAuth = null
    }

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
        require(profile.share.isNotBlank()) { "Выберите общую папку" }
        var lastError: Throwable? = null
        authenticationCandidates(profile).forEach { auth ->
            disconnectInternal()
            val newClient = SMBClient(smbConfig())
            try {
                val newConnection = newClient.connect(profile.host)
                val newSession = newConnection.authenticate(auth)
                val newShare = newSession.connectShare(profile.share) as? DiskShare
                    ?: throw IOException("${profile.share} не является файловой SMB-папкой")
                client = newClient
                connection = newConnection
                session = newSession
                share = newShare
                preferredAuth = auth
                return
            } catch (error: Throwable) {
                lastError = error
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
            var newShare: DiskShare? = null
            try {
                val newConnection = newClient.connect(profile.host)
                val newSession = newConnection.authenticate(auth)
                newShare = newSession.connectShare(profile.share) as? DiskShare
                    ?: throw IOException("${profile.share} не является файловой SMB-папкой")
                val items = newShare.list("").toEntries("")
                client = newClient
                connection = newConnection
                session = newSession
                share = newShare
                preferredAuth = auth
                return items
            } catch (error: Throwable) {
                lastError = error
                runCatching { newShare?.close() }
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
                val shares = SmbFile("smb://${profile.host}/", authenticatedContext).use { root ->
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
                val suppliedCredentialsRemain = profile.username.isNotBlank() &&
                    auth.username != profile.username && shares.isEmpty()
                if (suppliedCredentialsRemain) {
                    return@forEach
                }
                preferredAuth = auth
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
        val candidates = buildList {
            preferredAuth?.let(::add)
            add(AuthenticationContext.guest())
            add(AuthenticationContext.anonymous())
            if (profile.username.isNotBlank()) {
                add(AuthenticationContext(profile.username, profile.password.toCharArray(), profile.domain))
            }
        }
        return candidates.distinctBy { auth ->
            "${auth.isGuest}|${auth.isAnonymous}|${auth.username}|${auth.domain}"
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

    private fun validateHost(profile: SmbProfile) {
        require(profile.host.trim().isNotEmpty()) { "Укажите адрес SMB-устройства" }
    }

    private fun validateDirect(profile: SmbProfile) {
        validateHost(profile)
        require(profile.share.trim().trim('/', '\\').isNotEmpty()) { "Укажите имя общей папки" }
    }

    private fun sanitize(profile: SmbProfile): SmbProfile {
        val rawHost = profile.host.trim().removePrefix("\\\\").removePrefix("smb://")
        val host = rawHost.substringBefore('\\').substringBefore('/').trim()
        return profile.copy(
            name = profile.name.trim().ifBlank { host },
            host = host,
            share = profile.share.trim().trim('/', '\\'),
            username = profile.username.trim(),
            domain = profile.domain.trim(),
        )
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
            raw.contains("STATUS_LOGON_FAILURE", true) || raw.contains("logon failure", true) ->
                "SMB: неверный логин или пароль"
            raw.contains("STATUS_BAD_NETWORK_NAME", true) || raw.contains("network name cannot be found", true) ->
                "SMB: общая папка не найдена"
            raw.contains("STATUS_ACCESS_DENIED", true) || raw.contains("access is denied", true) ->
                "SMB: нет доступа. Укажите учётную запись Windows"
            raw.contains("timed out", true) -> "SMB: устройство не ответило вовремя"
            raw.contains("connection refused", true) -> "SMB: устройство отклонило подключение"
            raw.isNotBlank() -> "SMB: $raw"
            else -> "Не удалось подключиться к SMB"
        }
    }

}
