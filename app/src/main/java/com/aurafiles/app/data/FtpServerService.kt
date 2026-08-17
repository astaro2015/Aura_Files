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
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.MainActivity
import com.aurafiles.app.model.FtpServerConfig
import com.aurafiles.app.model.FtpServerStatus
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FtpServerService : Service() {
    private var server: FtpServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastError: String? = null

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
            ACTION_START -> startServer(intent)
            else -> if (server == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        server?.stop()
        server = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        _status.value = FtpServerStatus(error = lastError)
        super.onDestroy()
    }

    private fun startServer(intent: Intent) {
        if (server != null) {
            updateNotification()
            return
        }
        val starting = FtpServerStatus(starting = true)
        _status.value = starting
        startInForeground(notification(starting))
        try {
            val rootUri = Uri.parse(requireNotNull(intent.getStringExtra(EXTRA_ROOT_URI)))
            val rootLabel = intent.getStringExtra(EXTRA_ROOT_LABEL).orEmpty().ifBlank { "Хранилище" }
            val config = FtpServerConfig(
                port = intent.getIntExtra(EXTRA_PORT, 2121),
                username = intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
                password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                readOnly = intent.getBooleanExtra(EXTRA_READ_ONLY, true),
            )
            validate(config)
            val root = documentFromUri(rootUri)
                ?: throw IllegalArgumentException("Подключённая папка больше недоступна")
            require(root.exists() && root.isDirectory && root.canRead()) { "Нет доступа к подключённой папке" }
            require(config.readOnly || root.canWrite()) { "Подключённая папка не разрешает запись" }

            val ftpServer = FtpServer(applicationContext, root, config) { count ->
                _status.update { current -> if (current.running) current.copy(clients = count) else current }
                updateNotification()
            }
            val actualPort = ftpServer.start()
            server = ftpServer
            acquireLocks()
            lastError = null
            _status.value = FtpServerStatus(
                running = true,
                endpoints = localIpv4Addresses().map { "ftp://$it:$actualPort" },
                port = actualPort,
                username = config.username,
                password = config.password,
                rootLabel = rootLabel,
                readOnly = config.readOnly,
            )
            updateNotification()
        } catch (error: Throwable) {
            lastError = error.message ?: "Не удалось запустить FTP-сервер"
            _status.value = FtpServerStatus(error = lastError)
            stopSelf()
        }
    }

    private fun documentFromUri(uri: Uri): DocumentFile? = when (uri.scheme) {
        "file" -> uri.path?.let(::File)?.let(DocumentFile::fromFile)
        "content" -> DocumentFile.fromTreeUri(this, uri) ?: DocumentFile.fromSingleUri(this, uri)
        else -> null
    }

    private fun validate(config: FtpServerConfig) {
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
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(_status.value))
    }

    private fun notification(status: FtpServerStatus): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FtpServerService::class.java).setAction(ACTION_STOP),
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
            .setContentTitle("Aura Files — FTP-сервер")
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
            "FTP-сервер",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Работа FTP-сервера Aura Files"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AuraFiles:FtpServer").apply {
            setReferenceCounted(false)
            acquire()
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuraFiles:FtpServer").apply {
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
        private const val ACTION_START = "com.aurafiles.app.action.START_FTP_SERVER"
        private const val ACTION_STOP = "com.aurafiles.app.action.STOP_FTP_SERVER"
        private const val EXTRA_ROOT_URI = "root_uri"
        private const val EXTRA_ROOT_LABEL = "root_label"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_USERNAME = "username"
        private const val EXTRA_PASSWORD = "password"
        private const val EXTRA_READ_ONLY = "read_only"
        private const val CHANNEL_ID = "aura_ftp_server"
        private const val NOTIFICATION_ID = 7041

        private val _status = MutableStateFlow(FtpServerStatus())
        val status: StateFlow<FtpServerStatus> = _status.asStateFlow()

        fun start(context: Context, rootUri: Uri, rootLabel: String, config: FtpServerConfig) {
            val intent = Intent(context, FtpServerService::class.java)
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
            context.stopService(Intent(context, FtpServerService::class.java))
        }
    }
}
