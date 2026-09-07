package com.aurafiles.app.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.aurafiles.app.model.FileEntry
import com.aurafiles.app.tools.AuraApksBundle
import com.aurafiles.app.ui.theme.AuraFilesTheme
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SplitPackageInstallerActivity : ComponentActivity() {
    private var prepared: AuraApksBundle.PreparedBundle? = null
    private var state by mutableStateOf(ScreenState(loading = true, title = "SPLIT-комплект"))
    private var waitingForUnknownSourcePermission = false
    private var installStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AuraFilesTheme {
                SplitInstallerScreen(
                    state = state,
                    onBack = ::finish,
                    onInstall = ::requestInstall,
                )
            }
        }
        if (intent.action == ACTION_INSTALL_RESULT) {
            handleInstallResult(intent)
        } else {
            loadIncoming(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_INSTALL_RESULT) {
            handleInstallResult(intent)
        } else {
            prepared?.cleanup()
            prepared = null
            loadIncoming(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForUnknownSourcePermission && canInstallUnknownApps()) {
            waitingForUnknownSourcePermission = false
            requestInstall()
        }
    }

    override fun onDestroy() {
        // Once commit() has succeeded, PackageInstaller owns its private session copy.
        // Until then, remove our extracted cache when the preview is closed.
        if (!installStarted) prepared?.cleanup()
        super.onDestroy()
    }

    private fun loadIncoming(sourceIntent: Intent) {
        val sharedUri = if (sourceIntent.action == Intent.ACTION_SEND) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sourceIntent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                sourceIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
        } else null
        val uri = sourceIntent.data
            ?: sharedUri
            ?: sourceIntent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            ?: sourceIntent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
            ?: run {
                state = ScreenState(error = "Aura не получила файл .apks", title = "SPLIT-комплект")
                return
            }
        val displayName = sourceIntent.getStringExtra(EXTRA_NAME)
            ?.takeIf(String::isNotBlank)
            ?: queryDisplayName(uri)
            ?: uri.lastPathSegment
            ?: "package.apks"
        state = ScreenState(loading = true, title = displayName)
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    openInput(uri).use { input ->
                        AuraApksBundle.prepareForInstall(this@SplitPackageInstallerActivity, input, displayName)
                    }
                }
            }
            result.onSuccess { bundle ->
                prepared = bundle
                val manifest = bundle.manifest
                state = ScreenState(
                    title = manifest.label,
                    subtitle = "${manifest.versionName.ifBlank { "версия ?" }} · ${manifest.packageName}",
                    details = buildList {
                        add("SPLIT · ${bundle.parts.size} APK · ${formatBytes(bundle.totalSize)}")
                        add("base.apk + ${bundle.parts.size - 1} частей")
                        if (bundle.nativeAbis.isNotEmpty()) add("ABI: ${bundle.nativeAbis.joinToString()}")
                    },
                    warnings = bundle.warnings,
                    installEnabled = true,
                )
            }.onFailure { error ->
                prepared?.cleanup()
                prepared = null
                state = ScreenState(
                    title = displayName,
                    error = error.message ?: "Не удалось прочитать SPLIT-комплект",
                )
            }
        }
    }

    private fun requestInstall() {
        val bundle = prepared ?: return
        if (state.installing) return
        if (!canInstallUnknownApps()) {
            waitingForUnknownSourcePermission = true
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            )
            runCatching { startActivity(settings) }
                .onSuccess {
                    state = state.copy(
                        status = "Разрешите Aura Files устанавливать неизвестные приложения. После возврата установка продолжится автоматически.",
                    )
                }
                .onFailure { error ->
                    waitingForUnknownSourcePermission = false
                    Toast.makeText(this, "Не удалось открыть разрешение: ${error.message}", Toast.LENGTH_LONG).show()
                }
            return
        }

        state = state.copy(installing = true, installEnabled = false, status = STATUS_PREPARING, error = null)
        installStarted = true
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { createAndCommitSession(bundle) }
            }
            result.onSuccess { sessionId ->
                bundle.cleanup()
                prepared = null
                // STATUS_PENDING_USER_ACTION may race with commit() returning. Do not
                // overwrite a newer callback state if Android already answered.
                if (state.status == STATUS_PREPARING) {
                    state = state.copy(
                        installing = true,
                        installEnabled = false,
                        status = "Комплект передан Android · session $sessionId. Ожидаем подтверждение/результат…",
                    )
                }
            }.onFailure { error ->
                installStarted = false
                state = state.copy(
                    installing = false,
                    installEnabled = true,
                    error = error.message ?: "Не удалось запустить установку",
                    status = null,
                )
            }
        }
    }

    private fun createAndCommitSession(bundle: AuraApksBundle.PreparedBundle): Int {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(bundle.manifest.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                bundle.parts.forEachIndexed { index, part ->
                    val source = requireNotNull(part.file) { "APK-часть не подготовлена: ${part.originalName}" }
                    require(source.isFile && source.length() == part.size) { "APK-часть повреждена: ${part.originalName}" }
                    val sessionName = if (part.isBase) "base.apk" else "split-%03d.apk".format(index)
                    source.inputStream().buffered(256 * 1024).use { input ->
                        session.openWrite(sessionName, 0L, source.length()).use { output ->
                            input.copyTo(output, 256 * 1024)
                            session.fsync(output)
                        }
                    }
                }

                val callbackIntent = Intent(this, SplitPackageInstallerActivity::class.java).apply {
                    action = ACTION_INSTALL_RESULT
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_LABEL, bundle.manifest.label)
                    putExtra(EXTRA_PACKAGE, bundle.manifest.packageName)
                    putExtra(EXTRA_VERSION, bundle.manifest.versionName)
                    putExtra(EXTRA_SESSION_ID, sessionId)
                }
                val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getActivity(
                    this,
                    sessionId,
                    callbackIntent,
                    pendingFlags,
                )
                session.commit(callback.intentSender)
            }
            return sessionId
        } catch (error: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    private fun handleInstallResult(resultIntent: Intent) {
        val label = resultIntent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "Приложение" }
        val packageName = resultIntent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val version = resultIntent.getStringExtra(EXTRA_VERSION).orEmpty()
        val status = resultIntent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val systemMessage = resultIntent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    resultIntent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    resultIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                state = ScreenState(
                    title = label,
                    subtitle = listOf(version, packageName).filter(String::isNotBlank).joinToString(" · "),
                    installing = true,
                    status = "Android ждёт подтверждение установки…",
                )
                if (confirmation != null) {
                    runCatching { startActivity(confirmation) }.onFailure { error ->
                        state = state.copy(
                            installing = false,
                            error = "Не удалось открыть системное подтверждение: ${error.message}",
                            status = null,
                        )
                    }
                } else {
                    state = state.copy(
                        installing = false,
                        error = "Android запросил подтверждение, но не передал окно установщика",
                        status = null,
                    )
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                installStarted = false
                state = ScreenState(
                    title = label,
                    subtitle = listOf(version, packageName).filter(String::isNotBlank).joinToString(" · "),
                    success = "Установлено успешно",
                )
            }
            else -> {
                installStarted = false
                val friendly = packageInstallerFailure(status)
                state = ScreenState(
                    title = label,
                    subtitle = listOf(version, packageName).filter(String::isNotBlank).joinToString(" · "),
                    error = buildString {
                        append(friendly)
                        if (systemMessage.isNotBlank()) append("\n$systemMessage")
                    },
                )
            }
        }
    }

    private fun packageInstallerFailure(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Установка заблокирована системой или политикой устройства"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Установка отменена"
        PackageInstaller.STATUS_FAILURE_INVALID -> "Android отклонил комплект: APK повреждены, несовместимы между собой или имеют разные package/подписи"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Конфликт с уже установленной версией или её подписью"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Недостаточно места для установки"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Приложение несовместимо с этим телефоном"
        else -> "Не удалось установить SPLIT-комплект (код $status)"
    }

    private fun canInstallUnknownApps(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun openInput(uri: Uri): InputStream {
        contentResolver.openInputStream(uri)?.let { return it }
        if (uri.scheme == "file") {
            return File(requireNotNull(uri.path)).inputStream()
        }
        throw IllegalArgumentException("Не удалось открыть $uri")
    }

    private fun queryDisplayName(uri: Uri): String? = if (uri.scheme == "content") {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    } else null

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit += 1
        }
        return if (unit == 0) "$bytes ${units[unit]}" else "%.2f %s".format(value, units[unit])
    }

    companion object {
        private const val ACTION_INSTALL_RESULT = "com.aurafiles.app.action.SPLIT_INSTALL_RESULT"
        private const val EXTRA_URI = "split_uri"
        private const val EXTRA_NAME = "split_name"
        private const val EXTRA_LABEL = "split_label"
        private const val EXTRA_PACKAGE = "split_package"
        private const val EXTRA_VERSION = "split_version"
        private const val EXTRA_SESSION_ID = "split_session_id"
        private const val STATUS_PREPARING = "Подготавливаем установку…"

        fun start(context: Context, entry: FileEntry) {
            context.startActivity(
                Intent(context, SplitPackageInstallerActivity::class.java)
                    .putExtra(EXTRA_URI, entry.uri.toString())
                    .putExtra(EXTRA_NAME, entry.name),
            )
        }

        fun start(context: Context, uri: Uri, displayName: String) {
            context.startActivity(
                Intent(context, SplitPackageInstallerActivity::class.java)
                    .putExtra(EXTRA_URI, uri.toString())
                    .putExtra(EXTRA_NAME, displayName),
            )
        }
    }
}

private data class ScreenState(
    val loading: Boolean = false,
    val title: String,
    val subtitle: String = "",
    val details: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val installEnabled: Boolean = false,
    val installing: Boolean = false,
    val status: String? = null,
    val success: String? = null,
    val error: String? = null,
)

@Composable
private fun SplitInstallerScreen(
    state: ScreenState,
    onBack: () -> Unit,
    onInstall: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                }
                Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.title, fontWeight = FontWeight.SemiBold)
                    if (state.subtitle.isNotBlank()) {
                        Text(
                            state.subtitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.loading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                    Text("Проверяем комплект, контрольные суммы и совместимость…")
                }

                state.details.forEach { detail ->
                    Text(detail, style = MaterialTheme.typography.bodyMedium)
                }

                if (state.warnings.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Важно", fontWeight = FontWeight.SemiBold)
                            }
                            state.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }

                state.status?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
                state.success?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(it, fontWeight = FontWeight.SemiBold)
                    }
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                if (state.installEnabled || state.installing) {
                    Button(
                        onClick = onInstall,
                        enabled = state.installEnabled && !state.installing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.installing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.InstallMobile, contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.installing) "Установка…" else "Установить весь комплект")
                    }
                }
            }
        }
    }
}
