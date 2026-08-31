package com.aurafiles.app.data

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import com.aurafiles.app.MainActivity
import com.aurafiles.app.model.SftpServerConfig
import com.aurafiles.app.model.SftpServerStatus
import org.apache.sshd.common.util.OsUtils
import org.apache.sshd.common.util.io.PathUtils
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Foreground wrapper around Aura's embedded SFTP-only SSH server. */
class SftpServerService : Service() {
    private var server: SftpServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastError: String? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> beginStartServer(intent)
            else -> if (server == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping = true
        startJob?.cancel()
        startJob = null
        serviceScope.cancel()
        server?.stop()
        server = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        _status.value = SftpServerStatus(error = lastError)
        super.onDestroy()
    }

    /**
     * Server startup includes RSA host-key generation on first launch. Keep all of that off the
     * Android main thread so opening SFTP can never stall the UI or trigger an ANR.
     */
    private fun beginStartServer(intent: Intent) {
        if (server != null || startJob?.isActive == true) {
            updateNotification()
            return
        }
        stopping = false
        val starting = SftpServerStatus(starting = true)
        _status.value = starting
        startInForeground(notification(starting))
        startJob = serviceScope.launch { startServer(intent) }
    }

    private fun startServer(intent: Intent) {
        try {
            val rootUri = Uri.parse(requireNotNull(intent.getStringExtra(EXTRA_ROOT_URI)))
            val rootLabel = intent.getStringExtra(EXTRA_ROOT_LABEL).orEmpty().ifBlank { "Хранилище" }
            val config = SftpServerConfig(
                port = intent.getIntExtra(EXTRA_PORT, 2222),
                username = intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
                password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                readOnly = intent.getBooleanExtra(EXTRA_READ_ONLY, true),
            )
            validate(config)
            val rootPath = resolveLocalRoot(rootUri)
            require(Files.exists(rootPath) && Files.isDirectory(rootPath) && Files.isReadable(rootPath)) {
                "Нет прямого доступа к подключённой папке"
            }
            require(config.readOnly || Files.isWritable(rootPath)) {
                "Подключённая папка не разрешает запись"
            }

            prepareSshdForAndroid()
            val keyDir = filesDir.resolve("sftp")
            require(keyDir.exists() || keyDir.mkdirs()) { "Не удалось подготовить ключ SFTP-сервера" }
            val hostKey = keyDir.resolve("aura_hostkey.ser").toPath()
            val sftpServer = SftpServer(rootPath, hostKey, config) { count ->
                _status.update { current -> if (current.running) current.copy(clients = count) else current }
                updateNotification()
            }
            val actualPort = sftpServer.start()
            if (stopping) {
                sftpServer.stop()
                return
            }
            server = sftpServer
            acquireLocks()
            lastError = null
            _status.value = SftpServerStatus(
                running = true,
                endpoints = localIpv4Addresses().map { "sftp://$it:$actualPort" },
                port = actualPort,
                username = config.username,
                password = config.password,
                rootLabel = rootLabel,
                readOnly = config.readOnly,
            )
            updateNotification()
        } catch (error: Throwable) {
            if (!stopping) {
                lastError = error.message ?: "Не удалось запустить SFTP-сервер"
                _status.value = SftpServerStatus(error = lastError)
                stopSelf()
            }
        } finally {
            startJob = null
        }
    }

    /**
     * Apache MINA's SFTP server works with java.nio Path. Aura therefore maps only real
     * local-storage roots here; arbitrary cloud/provider SAF trees intentionally stay unsupported.
     */
    private fun resolveLocalRoot(uri: Uri): Path {
        if (uri.scheme == "file") {
            return Paths.get(requireNotNull(uri.path) { "Некорректный путь локальной папки" }).toAbsolutePath().normalize()
        }
        require(uri.scheme == "content") {
            "SFTP-сервер поддерживает только локальную память и SD-карту"
        }
        require(uri.authority == "com.android.externalstorage.documents") {
            "SFTP-сервер пока не может публиковать облачные/сетевые SAF-папки — выберите локальную память или SD-карту"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            require(Environment.isExternalStorageManager()) {
                "Для SFTP-сервера включите в Aura «Весь накопитель», затем запустите сервер снова"
            }
        }

        val documentId = runCatching {
            if (DocumentsContract.isTreeUri(uri)) DocumentsContract.getTreeDocumentId(uri)
            else DocumentsContract.getDocumentId(uri)
        }.getOrElse { throw IllegalArgumentException("Не удалось определить локальный путь выбранной папки") }
        val parts = documentId.split(':', limit = 2)
        val volume = parts.firstOrNull().orEmpty()
        val relative = parts.getOrNull(1).orEmpty().trimStart('/')
        val base = when {
            volume.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory().toPath()
            volume.equals("home", ignoreCase = true) -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).toPath()
            volume.isNotBlank() -> Paths.get("/storage", volume)
            else -> throw IllegalArgumentException("Не удалось определить накопитель выбранной папки")
        }
        return if (relative.isBlank()) base.toAbsolutePath().normalize()
        else base.resolve(relative).toAbsolutePath().normalize()
    }

    private fun prepareSshdForAndroid() {
        val appPath = filesDir.toPath().toAbsolutePath().normalize()
        // MINA SSHD has Android hooks for the JVM properties that Android normally omits.
        OsUtils.setAndroid(true)
        System.setProperty("user.name", "aura")
        OsUtils.setCurrentUser("aura")
        System.setProperty("user.home", appPath.toString())
        PathUtils.setUserHomeFolderResolver { appPath }
        System.setProperty("user.dir", appPath.toString())
        OsUtils.setCurrentWorkingDirectoryResolver { appPath }
    }

    private fun validate(config: SftpServerConfig) {
        require(config.port in 1024..65535) { "Порт должен быть от 1024 до 65535" }
        require(config.username.length in 3..64 && config.username.none(Char::isWhitespace)) {
            "Логин должен содержать 3–64 символа без пробелов"
        }
        require(config.password.length >= 8) { "Пароль должен содержать не менее 8 символов" }
        require(config.password.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Некорректный пароль" }
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(_status.value))
    }

    private fun notification(status: SftpServerStatus): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SftpServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            status.starting -> "Запуск…"
            status.running -> "Порт ${status.port} · подключений: ${status.clients} · ${if (status.readOnly) "только чтение" else "чтение и запись"}"
            status.error != null -> status.error
            else -> "Остановлен"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Aura Files — SFTP-сервер")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(status.running || status.starting)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Остановить", stopIntent).build())
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SFTP-сервер",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Работа SFTP-сервера Aura Files"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AuraFiles:SftpServer").apply {
            setReferenceCounted(false)
            acquire()
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuraFiles:SftpServer").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
        wakeLock = null
    }

    private fun localIpv4Addresses(): List<String> {
        val addresses = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
        }.getOrDefault(emptyList())
        return addresses.ifEmpty { listOf("IP телефона") }
    }

    companion object {
        private const val ACTION_START = "com.aurafiles.app.action.START_SFTP_SERVER"
        private const val ACTION_STOP = "com.aurafiles.app.action.STOP_SFTP_SERVER"
        private const val EXTRA_ROOT_URI = "root_uri"
        private const val EXTRA_ROOT_LABEL = "root_label"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_USERNAME = "username"
        private const val EXTRA_PASSWORD = "password"
        private const val EXTRA_READ_ONLY = "read_only"
        private const val CHANNEL_ID = "aura_sftp_server"
        private const val NOTIFICATION_ID = 7042

        private val _status = MutableStateFlow(SftpServerStatus())
        val status: StateFlow<SftpServerStatus> = _status.asStateFlow()

        fun start(context: Context, rootUri: Uri, rootLabel: String, config: SftpServerConfig) {
            val intent = Intent(context, SftpServerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ROOT_URI, rootUri.toString())
                .putExtra(EXTRA_ROOT_LABEL, rootLabel)
                .putExtra(EXTRA_PORT, config.port)
                .putExtra(EXTRA_USERNAME, config.username)
                .putExtra(EXTRA_PASSWORD, config.password)
                .putExtra(EXTRA_READ_ONLY, config.readOnly)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SftpServerService::class.java))
        }
    }
}
