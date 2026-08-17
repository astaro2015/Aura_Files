package com.aurafiles.app.data

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.model.FtpServerConfig
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A deliberately small LAN FTP server rooted inside one user-approved DocumentFile tree. */
class FtpServer(
    private val context: Context,
    private val root: DocumentFile,
    private val config: FtpServerConfig,
    private val onClientCountChanged: (Int) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val clientCount = AtomicInteger(0)
    private val slots = Semaphore(MAX_CLIENTS)
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "AuraFtp").apply { isDaemon = true }
    }
    private var listener: ServerSocket? = null

    fun start(): Int {
        check(running.compareAndSet(false, true)) { "FTP-сервер уже запущен" }
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(config.port), ACCEPT_BACKLOG)
        }
        listener = server
        executor.execute { acceptLoop(server) }
        return server.localPort
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { listener?.close() }
        listener = null
        clients.toList().forEach { runCatching { it.close() } }
        clients.clear()
        executor.shutdownNow()
        clientCount.set(0)
        onClientCountChanged(0)
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                if (!running.get()) break else continue
            }
            if (!isLocalPeer(socket)) {
                runCatching {
                    socket.getOutputStream().write("421 Разрешены только подключения из локальной сети\r\n".toByteArray(StandardCharsets.UTF_8))
                }
                runCatching { socket.close() }
                continue
            }
            if (!slots.tryAcquire()) {
                runCatching {
                    socket.getOutputStream().write("421 Слишком много подключений\r\n".toByteArray(StandardCharsets.UTF_8))
                }
                runCatching { socket.close() }
                continue
            }
            clients += socket
            onClientCountChanged(clientCount.incrementAndGet())
            executor.execute {
                try {
                    socket.keepAlive = true
                    socket.tcpNoDelay = true
                    Session(socket).run()
                } finally {
                    clients -= socket
                    runCatching { socket.close() }
                    slots.release()
                    onClientCountChanged(clientCount.decrementAndGet().coerceAtLeast(0))
                }
            }
        }
    }

    private inner class Session(private val control: Socket) {
        private val reader = BufferedReader(control.getInputStream().reader(StandardCharsets.UTF_8))
        private val writer = BufferedWriter(OutputStreamWriter(control.getOutputStream(), StandardCharsets.UTF_8))
        private var loggedIn = false
        private var suppliedUser = ""
        private var failedLogins = 0
        private var cwd = "/"
        private var passiveListener: ServerSocket? = null
        private var renameFrom: Pair<String, DocumentFile>? = null
        private var restartOffset = 0L

        fun run() {
            reply(220, "Aura Files FTP готов")
            while (running.get() && !control.isClosed) {
                val line = try {
                    reader.readLine()
                } catch (_: IOException) {
                    null
                } ?: break
                if (line.length > MAX_COMMAND_LENGTH) {
                    reply(500, "Слишком длинная команда")
                    continue
                }
                if ('\u0000' in line) {
                    reply(500, "Некорректная команда")
                    continue
                }
                val command = line.substringBefore(' ').uppercase(Locale.US)
                val argument = line.substringAfter(' ', "")
                if (!handle(command, argument)) break
            }
            closePassive()
        }

        private fun handle(command: String, argument: String): Boolean {
            when (command) {
                "USER" -> {
                    suppliedUser = argument
                    loggedIn = false
                    reply(331, "Нужен пароль")
                }
                "PASS" -> authenticate(argument)
                "QUIT" -> {
                    reply(221, "До свидания")
                    return false
                }
                "NOOP" -> reply(200, "OK")
                "SYST" -> reply(215, "UNIX Type: L8")
                "FEAT" -> features()
                "OPTS" -> if (argument.equals("UTF8 ON", true)) reply(200, "UTF8 включён") else reply(501, "Неизвестная опция")
                else -> {
                    if (!loggedIn) {
                        reply(530, "Сначала войдите")
                        return true
                    }
                    handleAuthenticated(command, argument)
                }
            }
            return failedLogins < MAX_FAILED_LOGINS
        }

        private fun authenticate(password: String) {
            val validUser = secureEquals(suppliedUser, config.username)
            val validPassword = secureEquals(password, config.password)
            if (validUser && validPassword) {
                loggedIn = true
                failedLogins = 0
                reply(230, "Вход выполнен")
            } else {
                failedLogins += 1
                reply(if (failedLogins >= MAX_FAILED_LOGINS) 421 else 530, "Неверный логин или пароль")
            }
        }

        private fun handleAuthenticated(command: String, argument: String) {
            when (command) {
                "PWD", "XPWD" -> reply(257, "\"${cwd.replace("\"", "\"\"")}\"")
                "CWD", "XCWD" -> changeDirectory(argument)
                "CDUP", "XCUP" -> changeDirectory("..")
                "TYPE" -> if (argument.equals("I", true) || argument.equals("A", true)) reply(200, "Тип установлен") else reply(504, "Поддерживаются TYPE I и TYPE A")
                "MODE" -> if (argument.equals("S", true)) reply(200, "Режим потока") else reply(504, "Поддерживается MODE S")
                "STRU" -> if (argument.equals("F", true)) reply(200, "Файловая структура") else reply(504, "Поддерживается STRU F")
                "PASV" -> enterPassive(extended = false)
                "EPSV" -> enterPassive(extended = true)
                "PORT", "EPRT" -> reply(502, "Активный режим отключён; используйте PASV/EPSV")
                "LIST" -> list(argument, machine = false, namesOnly = false)
                "NLST" -> list(argument, machine = false, namesOnly = true)
                "MLSD" -> list(argument, machine = true, namesOnly = false)
                "MLST" -> machineStat(argument)
                "RETR" -> retrieve(argument)
                "REST" -> setRestartOffset(argument)
                "STOR" -> store(argument)
                "APPE", "STOU" -> reply(502, "Команда не поддерживается")
                "SIZE" -> size(argument)
                "MDTM" -> modifiedTime(argument)
                "MKD", "XMKD" -> makeDirectory(argument)
                "RMD", "XRMD" -> removeDirectory(argument)
                "DELE" -> deleteFile(argument)
                "RNFR" -> renameFrom(argument)
                "RNTO" -> renameTo(argument)
                "STAT" -> reply(211, "Aura Files FTP; клиентов: ${clientCount.get()}; корень: /")
                "SITE" -> reply(502, "SITE не поддерживается")
                "CLNT" -> reply(200, "Клиент принят")
                "AUTH" -> reply(502, "TLS-сервер пока не поддерживается")
                else -> reply(502, "Команда $command не поддерживается")
            }
        }

        private fun features() {
            raw("211-Возможности")
            raw(" UTF8")
            raw(" EPSV")
            raw(" PASV")
            raw(" SIZE")
            raw(" MDTM")
            raw(" REST STREAM")
            raw(" MLST type*;size*;modify*;")
            raw("211 Конец")
        }

        private fun changeDirectory(rawPath: String) {
            val path = virtualPath(rawPath)
            val document = resolve(path)
            if (document == null || !document.isDirectory) reply(550, "Папка не найдена")
            else {
                cwd = path
                reply(250, "Папка открыта")
            }
        }

        private fun enterPassive(extended: Boolean) {
            closePassive()
            val localAddress = control.localAddress
            if (!extended && localAddress !is Inet4Address) {
                reply(522, "Для IPv6 используйте EPSV")
                return
            }
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(localAddress, 0), 1)
                soTimeout = DATA_ACCEPT_TIMEOUT_MILLIS
            }
            passiveListener = server
            val port = server.localPort
            if (extended) {
                reply(229, "Entering Extended Passive Mode (|||$port|)")
            } else {
                val bytes = (localAddress as Inet4Address).address.map { it.toInt() and 0xff }
                reply(227, "Entering Passive Mode (${bytes.joinToString(",")},${port / 256},${port % 256})")
            }
        }

        private fun list(rawArgument: String, machine: Boolean, namesOnly: Boolean) {
            val requested = listingArgument(rawArgument)
            val path = virtualPath(requested)
            val target = resolve(path)
            if (target == null) {
                reply(550, "Объект не найден")
                return
            }
            val documents = if (target.isDirectory) target.listFiles().filterNot { it.name == TRASH_FOLDER } else listOf(target)
            withDataConnection("Открываем список") { data ->
                val output = BufferedWriter(OutputStreamWriter(data.getOutputStream(), StandardCharsets.UTF_8))
                documents.sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name?.lowercase() }).forEach { document ->
                    val name = cleanDisplayName(document.name ?: "Без названия")
                    val line = when {
                        namesOnly -> name
                        machine -> machineLine(document, name)
                        else -> unixListLine(document, name)
                    }
                    output.write(line)
                    output.write("\r\n")
                }
                output.flush()
            }
        }

        private fun machineStat(rawPath: String) {
            val document = resolve(virtualPath(rawPath))
            if (document == null) reply(550, "Объект не найден")
            else {
                raw("250-Информация")
                raw(" ${machineLine(document, cleanDisplayName(document.name ?: "/"))}")
                raw("250 Конец")
            }
        }

        private fun retrieve(rawPath: String) {
            val document = resolve(virtualPath(rawPath))
            if (document == null || !document.isFile) {
                reply(550, "Файл не найден")
                restartOffset = 0L
                return
            }
            val offset = restartOffset
            restartOffset = 0L
            withDataConnection("Передача ${cleanDisplayName(document.name ?: "файла")}") { data ->
                val input = context.contentResolver.openInputStream(document.uri)
                    ?: throw IOException("Не удалось открыть файл")
                input.use {
                    skipFully(it, offset)
                    data.getOutputStream().use { output -> it.copyTo(output, TRANSFER_BUFFER_SIZE) }
                }
            }
        }

        private fun setRestartOffset(argument: String) {
            val offset = argument.toLongOrNull()
            if (offset == null || offset < 0L) reply(501, "Некорректное смещение")
            else {
                restartOffset = offset
                reply(350, "Продолжение с позиции $offset")
            }
        }

        private fun store(rawPath: String) {
            if (!writesAllowed()) return
            val path = virtualPath(rawPath)
            val (parentPath, name) = splitParent(path) ?: run {
                reply(550, "Некорректное имя")
                return
            }
            val parent = resolve(parentPath)
            if (parent == null || !parent.isDirectory) {
                reply(550, "Папка назначения не найдена")
                return
            }
            val existing = parent.findFile(name)
            if (existing?.isDirectory == true) {
                reply(550, "Это имя занято папкой")
                return
            }
            var created = false
            val target = existing ?: parent.createFile(
                URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream",
                name,
            )?.also { created = true }
            if (target == null) {
                reply(550, "Не удалось создать файл")
                return
            }
            val completed = withDataConnection("Приём ${cleanDisplayName(name)}") { data ->
                val output = context.contentResolver.openOutputStream(target.uri, "w")
                    ?: throw IOException("Не удалось записать файл")
                output.use { targetStream -> data.getInputStream().copyTo(targetStream, TRANSFER_BUFFER_SIZE) }
            }
            if (!completed && created) target.delete()
        }

        private fun size(rawPath: String) {
            val document = resolve(virtualPath(rawPath))
            if (document == null || !document.isFile) reply(550, "Файл не найден")
            else reply(213, document.length().toString())
        }

        private fun modifiedTime(rawPath: String) {
            val document = resolve(virtualPath(rawPath))
            if (document == null) reply(550, "Объект не найден")
            else reply(213, ftpTimestamp(document.lastModified()))
        }

        private fun makeDirectory(rawPath: String) {
            if (!writesAllowed()) return
            val path = virtualPath(rawPath)
            val (parentPath, name) = splitParent(path) ?: run { reply(550, "Некорректное имя"); return }
            val parent = resolve(parentPath)
            if (parent == null || !parent.isDirectory || parent.findFile(name) != null) {
                reply(550, "Папку создать нельзя")
                return
            }
            if (parent.createDirectory(name) == null) reply(550, "Не удалось создать папку")
            else reply(257, "\"$path\" создана")
        }

        private fun removeDirectory(rawPath: String) {
            if (!writesAllowed()) return
            val path = virtualPath(rawPath)
            val document = resolve(path)
            if (path == "/" || document == null || !document.isDirectory) {
                reply(550, "Папка не найдена")
            } else if (document.listFiles().isNotEmpty()) {
                reply(550, "Удалить можно только пустую папку")
            } else if (!document.delete()) reply(550, "Не удалось удалить папку")
            else reply(250, "Папка удалена")
        }

        private fun deleteFile(rawPath: String) {
            if (!writesAllowed()) return
            val document = resolve(virtualPath(rawPath))
            if (document == null || !document.isFile) reply(550, "Файл не найден")
            else if (!document.delete()) reply(550, "Не удалось удалить файл")
            else reply(250, "Файл удалён")
        }

        private fun renameFrom(rawPath: String) {
            if (!writesAllowed()) return
            val path = virtualPath(rawPath)
            val document = resolve(path)
            if (path == "/" || document == null) reply(550, "Объект не найден")
            else {
                renameFrom = path to document
                reply(350, "Укажите новое имя")
            }
        }

        private fun renameTo(rawPath: String) {
            if (!writesAllowed()) return
            val source = renameFrom
            renameFrom = null
            if (source == null) {
                reply(503, "Сначала используйте RNFR")
                return
            }
            val targetPath = virtualPath(rawPath)
            val (sourceParent, _) = splitParent(source.first) ?: run { reply(550, "Некорректный путь"); return }
            val (targetParent, targetName) = splitParent(targetPath) ?: run { reply(550, "Некорректный путь"); return }
            if (sourceParent != targetParent) {
                reply(553, "Перемещение между папками через RNTO не поддерживается")
            } else if (resolve(targetPath) != null) {
                reply(553, "Имя уже занято")
            } else if (!source.second.renameTo(targetName)) reply(550, "Не удалось переименовать")
            else reply(250, "Название изменено")
        }

        private fun writesAllowed(): Boolean {
            if (!config.readOnly) return true
            reply(550, "Сервер работает только для чтения")
            return false
        }

        private fun withDataConnection(label: String, block: (Socket) -> Unit): Boolean {
            val server = passiveListener
            passiveListener = null
            if (server == null) {
                reply(425, "Сначала включите PASV или EPSV")
                return false
            }
            reply(150, label)
            return try {
                server.use {
                    val data = it.accept()
                    data.use { socket ->
                        if (socket.inetAddress != control.inetAddress) {
                            throw IOException("Адрес data-соединения не совпадает с control-соединением")
                        }
                        socket.soTimeout = DATA_IO_TIMEOUT_MILLIS
                        block(socket)
                    }
                }
                reply(226, "Передача завершена")
                true
            } catch (error: Throwable) {
                reply(426, "Передача прервана: ${cleanReply(error.message ?: "ошибка")}")
                false
            }
        }

        private fun resolve(path: String): DocumentFile? {
            if (path == "/") return root.takeIf { it.exists() }
            var current = root
            path.trim('/').split('/').forEach { segment ->
                if (segment == TRASH_FOLDER) return null
                current = current.findFile(segment) ?: return null
            }
            return current.takeIf { it.exists() }
        }

        private fun virtualPath(rawPath: String): String {
            val raw = rawPath.trim().ifEmpty { cwd }
            val source = if (raw.startsWith('/')) raw else if (cwd == "/") "/$raw" else "$cwd/$raw"
            val segments = ArrayDeque<String>()
            source.replace('\\', '/').split('/').filter(String::isNotBlank).forEach { segment ->
                when (segment) {
                    "." -> Unit
                    ".." -> if (segments.isNotEmpty()) segments.removeLast()
                    else -> if ('\u0000' !in segment) segments.addLast(segment)
                }
            }
            return "/" + segments.joinToString("/")
        }

        private fun splitParent(path: String): Pair<String, String>? {
            if (path == "/") return null
            val name = path.substringAfterLast('/')
            if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name || '\u0000' in name) return null
            val parent = path.substringBeforeLast('/', "").ifBlank { "/" }
            return parent to name
        }

        private fun listingArgument(raw: String): String {
            val trimmed = raw.trim()
            return if (trimmed.startsWith('-')) trimmed.substringAfter(' ', "") else trimmed
        }

        private fun machineLine(document: DocumentFile, name: String): String {
            val type = if (document.isDirectory) "dir" else "file"
            return "type=$type;size=${if (document.isFile) document.length() else 0};modify=${ftpTimestamp(document.lastModified())}; $name"
        }

        private fun unixListLine(document: DocumentFile, name: String): String {
            val permissions = if (document.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
            val date = requireNotNull(LIST_DATE_FORMAT.get()).format(Date(document.lastModified().takeIf { it > 0L } ?: 0L))
            return "$permissions 1 aura aura ${document.length()} $date $name"
        }

        private fun closePassive() {
            runCatching { passiveListener?.close() }
            passiveListener = null
        }

        private fun reply(code: Int, message: String) = raw("$code ${cleanReply(message)}")

        private fun raw(line: String) {
            writer.write(line)
            writer.write("\r\n")
            writer.flush()
        }
    }

    private fun secureEquals(first: String, second: String): Boolean = MessageDigest.isEqual(
        first.toByteArray(StandardCharsets.UTF_8),
        second.toByteArray(StandardCharsets.UTF_8),
    )

    private fun isLocalPeer(socket: Socket): Boolean {
        val address = socket.inetAddress
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        if (address is Inet6Address) {
            val first = address.address.firstOrNull()?.toInt()?.and(0xff) ?: return false
            return first and 0xfe == 0xfc // fc00::/7 — IPv6 unique local address
        }
        return false
    }

    private fun cleanReply(value: String): String = value.replace('\r', ' ').replace('\n', ' ').take(300)
    private fun cleanDisplayName(value: String): String = value.replace('\r', '_').replace('\n', '_')

    private fun ftpTimestamp(timestamp: Long): String =
        requireNotNull(FTP_TIME_FORMAT.get()).format(Date(timestamp.takeIf { it > 0L } ?: 0L))

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) remaining -= skipped
            else if (input.read() < 0) throw IOException("Смещение больше размера файла")
            else remaining -= 1L
        }
    }

    private companion object {
        const val MAX_CLIENTS = 4
        const val MAX_FAILED_LOGINS = 5
        const val MAX_COMMAND_LENGTH = 4_096
        const val ACCEPT_BACKLOG = 8
        const val DATA_ACCEPT_TIMEOUT_MILLIS = 20_000
        const val DATA_IO_TIMEOUT_MILLIS = 120_000
        const val TRANSFER_BUFFER_SIZE = 64 * 1024
        const val TRASH_FOLDER = ".AuraTrash"

        val FTP_TIME_FORMAT = ThreadLocal.withInitial {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }
        val LIST_DATE_FORMAT = ThreadLocal.withInitial {
            SimpleDateFormat("MMM dd HH:mm", Locale.US).apply { timeZone = TimeZone.getDefault() }
        }
    }
}
