package com.aurafiles.app.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.model.FtpEntry
import com.aurafiles.app.model.FtpProfile
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.json.JSONObject
import java.io.IOException
import java.net.URLConnection
import java.security.KeyStore
import java.time.Duration
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A small, stateful FTP/FTPS client. Every public operation is serialized and
 * reconnects once when the control connection was dropped while Android slept.
 */
class FtpRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var client: FTPClient? = null
    private var activeProfile: FtpProfile? = null
    private var currentPath: String = "/"

    fun loadProfile(): FtpProfile? {
        val raw = preferences.getString(KEY_PROFILE, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            FtpProfile(
                name = json.optString("name", "Мой FTP"),
                host = json.getString("host"),
                port = json.optInt("port", 21),
                username = json.getString("username"),
                password = decrypt(json.optString("password")),
                useTls = json.optBoolean("tls", false),
            )
        }.getOrNull()
    }

    fun saveProfile(profile: FtpProfile) {
        validate(profile)
        val json = JSONObject()
            .put("name", profile.name.trim().ifBlank { profile.host.trim() })
            .put("host", profile.host.trim())
            .put("port", profile.port)
            .put("username", profile.username)
            .put("password", encrypt(profile.password))
            .put("tls", profile.useTls)
        preferences.edit().putString(KEY_PROFILE, json.toString()).apply()
    }

    suspend fun connect(profile: FtpProfile): Pair<String, List<FtpEntry>> = mutex.withLock {
        validate(profile)
        disconnectInternal()
        activeProfile = profile.copy(host = profile.host.trim())
        connectInternal()
        currentPath = normalizePath(client?.printWorkingDirectory() ?: "/")
        currentPath to listInternal(currentPath)
    }

    suspend fun disconnect() = mutex.withLock {
        disconnectInternal()
    }

    suspend fun keepAlive(): Boolean = mutex.withLock {
        val ftp = client
        if (ftp == null || !ftp.isConnected) {
            return@withLock runCatching { connectInternal(); true }.getOrDefault(false)
        }
        runCatching {
            if (!ftp.sendNoOp()) throw IOException(replyMessage(ftp, "Сервер не ответил на keep-alive"))
            true
        }.getOrElse {
            runCatching { disconnectInternal(); connectInternal(); true }.getOrDefault(false)
        }
    }

    suspend fun list(path: String): Pair<String, List<FtpEntry>> = mutex.withLock {
        val normalized = normalizePath(path)
        val entries = withReconnect { listInternal(normalized) }
        currentPath = normalized
        normalized to entries
    }

    suspend fun createDirectory(name: String): Pair<String, List<FtpEntry>> = mutex.withLock {
        val clean = safeName(name)
        withReconnect { ftp ->
            val target = childPath(currentPath, clean)
            if (!ftp.makeDirectory(target)) throw IOException(replyMessage(ftp, "Не удалось создать папку"))
        }
        currentPath to listInternal(currentPath)
    }

    suspend fun delete(entry: FtpEntry): Pair<String, List<FtpEntry>> = mutex.withLock {
        withReconnect { ftp ->
            val success = if (entry.isDirectory) ftp.removeDirectory(entry.path) else ftp.deleteFile(entry.path)
            if (!success) {
                val hint = if (entry.isDirectory) "Папка должна быть пустой. " else ""
                throw IOException(hint + replyMessage(ftp, "Не удалось удалить ${entry.name}"))
            }
        }
        currentPath to listInternal(currentPath)
    }

    suspend fun rename(entry: FtpEntry, requestedName: String): Pair<String, List<FtpEntry>> = mutex.withLock {
        val target = childPath(parentPath(entry.path), safeName(requestedName))
        withReconnect { ftp ->
            if (!ftp.rename(entry.path, target)) throw IOException(replyMessage(ftp, "Не удалось переименовать"))
        }
        currentPath to listInternal(currentPath)
    }

    suspend fun upload(uris: List<Uri>): Int = mutex.withLock {
        require(uris.isNotEmpty()) { "Выберите файлы для отправки" }
        var completed = 0
        uris.forEach { uri ->
            val document = DocumentFile.fromSingleUri(context, uri)
                ?: throw IOException("Не удалось открыть выбранный файл")
            require(document.isFile) { "Загрузка папок через системный выбор пока не поддерживается" }
            val name = safeName(document.name ?: "Файл")
            val remoteName = withReconnect { ftp -> uniqueRemoteName(ftp, currentPath, name) }
            val finalPath = childPath(currentPath, remoteName)
            val temporaryPath = childPath(currentPath, ".aura-part-${UUID.randomUUID()}")
            try {
                withReconnect { ftp ->
                    runCatching { ftp.deleteFile(temporaryPath) }
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Не удалось прочитать $name")
                    input.use {
                        if (!ftp.storeFile(temporaryPath, it)) {
                            throw IOException(replyMessage(ftp, "Не удалось загрузить $name"))
                        }
                    }
                    if (!ftp.rename(temporaryPath, finalPath)) {
                        throw IOException(replyMessage(ftp, "Не удалось завершить загрузку $name"))
                    }
                }
                completed += 1
            } catch (error: Throwable) {
                runCatching { requireConnected().deleteFile(temporaryPath) }
                throw error
            }
        }
        completed
    }

    suspend fun download(entry: FtpEntry, destination: DocumentFile): DocumentFile = mutex.withLock {
        require(!entry.isDirectory) { "Для скачивания выберите файл" }
        require(destination.isDirectory && destination.canWrite()) { "Локальная папка недоступна для записи" }
        val fileName = uniqueLocalName(destination, entry.name)
        val mime = URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
        val target = destination.createFile(mime, fileName)
            ?: throw IOException("Не удалось создать $fileName")
        try {
            withReconnect { ftp ->
                val output = context.contentResolver.openOutputStream(target.uri, "w")
                    ?: throw IOException("Не удалось записать $fileName")
                output.use {
                    if (!ftp.retrieveFile(entry.path, it)) {
                        throw IOException(replyMessage(ftp, "Не удалось скачать ${entry.name}"))
                    }
                }
            }
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun connectInternal() {
        val profile = activeProfile ?: loadProfile()?.also { activeProfile = it }
            ?: throw IOException("FTP-подключение ещё не настроено")
        val ftp = if (profile.useTls) {
            FTPSClient("TLS", false).apply { setEndpointCheckingEnabled(true) }
        } else {
            FTPClient()
        }
        configure(ftp)
        try {
            ftp.connect(profile.host, profile.port)
            if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
                throw IOException(replyMessage(ftp, "FTP-сервер отклонил подключение"))
            }
            if (!ftp.login(profile.username, profile.password)) {
                throw IOException(replyMessage(ftp, "Неверный логин или пароль"))
            }
            if (ftp is FTPSClient) {
                ftp.execPBSZ(0)
                ftp.execPROT("P")
            }
            ftp.enterLocalPassiveMode()
            if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw IOException(replyMessage(ftp, "Сервер не включил бинарный режим"))
            }
            client = ftp
        } catch (error: Throwable) {
            runCatching { if (ftp.isConnected) ftp.disconnect() }
            throw error
        }
    }

    private fun configure(ftp: FTPClient) {
        ftp.connectTimeout = 15_000
        ftp.defaultTimeout = 15_000
        ftp.setDataTimeout(Duration.ofSeconds(90))
        ftp.setControlKeepAliveTimeout(Duration.ofSeconds(25))
        ftp.setControlKeepAliveReplyTimeout(Duration.ofSeconds(10))
        ftp.setAutodetectUTF8(true)
        ftp.controlEncoding = Charsets.UTF_8.name()
        ftp.bufferSize = 64 * 1024
    }

    private fun listInternal(path: String): List<FtpEntry> {
        val ftp = requireConnected()
        val files = ftp.listFiles(path)
        if (files == null || (!FTPReply.isPositiveCompletion(ftp.replyCode) && files.isEmpty())) {
            throw IOException(replyMessage(ftp, "Не удалось открыть $path"))
        }
        return files
            .filterNot { it.name == "." || it.name == ".." }
            .map { it.toEntry(path) }
            .sortedWith(compareByDescending<FtpEntry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun FTPFile.toEntry(parent: String): FtpEntry = FtpEntry(
        name = name,
        path = childPath(parent, name),
        isDirectory = isDirectory,
        size = size,
        modifiedAt = timestamp?.timeInMillis ?: 0L,
    )

    private inline fun <T> withReconnect(action: (FTPClient) -> T): T {
        var ftp = runCatching { requireConnected() }.getOrElse {
            connectInternal()
            requireConnected()
        }
        return try {
            action(ftp)
        } catch (first: IOException) {
            disconnectInternal()
            connectInternal()
            ftp = requireConnected()
            action(ftp)
        }
    }

    private fun requireConnected(): FTPClient {
        val ftp = client
        if (ftp == null || !ftp.isConnected) throw IOException("FTP-соединение потеряно")
        return ftp
    }

    private fun disconnectInternal() {
        val ftp = client
        client = null
        if (ftp != null) {
            runCatching { if (ftp.isConnected) ftp.logout() }
            runCatching { if (ftp.isConnected) ftp.disconnect() }
        }
    }

    private fun validate(profile: FtpProfile) {
        require(profile.host.isNotBlank()) { "Введите адрес FTP-сервера" }
        require(profile.port in 1..65535) { "Порт должен быть от 1 до 65535" }
        require(profile.username.isNotBlank()) { "Введите имя пользователя" }
    }

    private fun safeName(raw: String): String {
        val clean = raw.trim()
        require(clean.isNotEmpty() && clean != "." && clean != "..") { "Введите корректное имя" }
        require('/' !in clean && '\\' !in clean && '\u0000' !in clean) { "Имя не должно содержать / или \\" }
        return clean
    }

    private fun normalizePath(raw: String): String {
        val segments = raw.replace('\\', '/').split('/').filter(String::isNotBlank)
        val normalized = ArrayDeque<String>()
        segments.forEach { segment ->
            when (segment) {
                "." -> Unit
                ".." -> if (normalized.isNotEmpty()) normalized.removeLast()
                else -> normalized.addLast(segment)
            }
        }
        return "/" + normalized.joinToString("/")
    }

    private fun childPath(parent: String, name: String): String =
        if (parent == "/") "/$name" else "${parent.trimEnd('/')}/$name"

    private fun parentPath(path: String): String = normalizePath(path.substringBeforeLast('/', ""))

    private fun uniqueLocalName(parent: DocumentFile, requested: String): String {
        if (parent.findFile(requested) == null) return requested
        val dot = requested.lastIndexOf('.')
        val stem = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        var index = 2
        while (parent.findFile("$stem ($index)$extension") != null) index += 1
        return "$stem ($index)$extension"
    }

    private fun uniqueRemoteName(ftp: FTPClient, parent: String, requested: String): String {
        val occupied = ftp.listFiles(parent).orEmpty()
            .map(FTPFile::getName)
            .toHashSet()
        if (requested !in occupied) return requested
        val dot = requested.lastIndexOf('.')
        val stem = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        var index = 2
        while ("$stem ($index)$extension" in occupied) index += 1
        return "$stem ($index)$extension"
    }

    private fun replyMessage(ftp: FTPClient, fallback: String): String {
        val reply = ftp.replyString?.trim().orEmpty()
        return if (reply.isBlank()) fallback else "$fallback: $reply"
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        if (value.isBlank()) return ""
        val packed = Base64.decode(value, Base64.NO_WRAP)
        require(packed.size > IV_SIZE) { "Повреждены сохранённые данные FTP" }
        val iv = packed.copyOfRange(0, IV_SIZE)
        val encrypted = packed.copyOfRange(IV_SIZE, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "aura_ftp"
        const val KEY_PROFILE = "profile"
        const val KEY_ALIAS = "aura_ftp_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
