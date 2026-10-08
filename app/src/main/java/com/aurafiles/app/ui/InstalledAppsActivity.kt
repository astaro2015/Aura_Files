package com.aurafiles.app.ui

import com.aurafiles.app.AuraFileProvider
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.aurafiles.app.tools.ApkShareDeliveryPolicy
import com.aurafiles.app.tools.ApkSharePublisher
import com.aurafiles.app.tools.AuraApksBundle
import com.aurafiles.app.tools.UniversalApkExporter
import com.aurafiles.app.tools.UniversalApkExportPolicy
import com.aurafiles.app.ui.theme.AuraFilesTheme
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private suspend fun <T> installedAppsResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

class InstalledAppsActivity : ComponentActivity() {
    private var exportingPackage by mutableStateOf<String?>(null)

    private val openApksPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "package.apks"
        if (!displayName.endsWith(".apks", ignoreCase = true)) {
            Toast.makeText(this, "Выберите файл .apks, созданный Aura Files", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        SplitPackageInstallerActivity.start(this, uri, displayName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AuraFilesTheme {
                InstalledAppsScreen(
                    onBack = ::finish,
                    onShare = ::shareInstalledApk,
                    onShareSplit = ::shareInstalledSplit,
                    onOpenApks = ::pickApksForInstall,
                    exportingPackage = exportingPackage,
                )
            }
        }
    }


    private fun pickApksForInstall() {
        openApksPicker.launch(
            arrayOf(
                AuraApksBundle.MIME_TYPE,
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
                "*/*",
            )
        )
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()


    private fun shareInstalledSplit(app: InstalledAppEntry) {
        if (!app.isSplit || exportingPackage != null) return
        exportingPackage = app.packageName
        Toast.makeText(this, "Готовим SPLIT-комплект…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = installedAppsResult {
                withContext(Dispatchers.IO) {
                    AuraApksBundle.exportInstalledPackage(
                        this@InstalledAppsActivity,
                        AuraApksBundle.InstalledPackageSource(
                            packageName = app.packageName,
                            label = app.label,
                            versionName = app.versionName,
                            versionCode = app.versionCode,
                            baseApkPath = app.sourceDir,
                            splitApkPaths = app.splitSourceDirs,
                        ),
                    )
                }
            }
            exportingPackage = null
            result.onSuccess { bundle ->
                val uri = AuraFileProvider.uriForFile(this@InstalledAppsActivity, bundle)
                val send = Intent(Intent.ACTION_SEND).apply {
                    // Preserve Aura's custom MIME when the messenger supports it;
                    // this lets the receiving phone route the document straight back to Aura.
                    type = AuraApksBundle.MIME_TYPE
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(contentResolver, bundle.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    startActivity(Intent.createChooser(send, "Поделиться SPLIT-комплектом ${app.label}"))
                }.onFailure {
                    Toast.makeText(
                        this@InstalledAppsActivity,
                        "Не найдено приложение для отправки SPLIT-комплекта",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }.onFailure { error ->
                Toast.makeText(
                    this@InstalledAppsActivity,
                    error.message ?: "Не удалось собрать SPLIT-комплект",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun shareInstalledApk(app: InstalledAppEntry) {
        if (exportingPackage != null) return
        if (app.isSplit) {
            shareInstalledUniversalApk(app)
            return
        }
        exportingPackage = app.packageName
        lifecycleScope.launch {
            val result = installedAppsResult {
                withContext(Dispatchers.IO) {
                    val source = File(app.sourceDir)
                    require(source.isFile) { "APK приложения недоступен" }
                    val sourceSize = source.length()
                    require(sourceSize in 1..MAX_SHARED_APK_BYTES) { "APK слишком большой или пуст" }
                    val shareDir = File(cacheDir, "shares").apply { mkdirs() }
                    val expiry = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
                    shareDir.listFiles().orEmpty()
                        .filter { it.name.startsWith("installed-") && it.lastModified() < expiry }
                        .forEach(File::delete)
                    requireShareCacheSpace(shareDir, sourceSize)
                    val target = uniqueShareFile(
                        shareDir,
                        UniversalApkExportPolicy.outputFileName(
                            app.label,
                            app.packageName,
                            app.versionName,
                            app.versionCode,
                        ),
                    )
                    val temporary = File(shareDir, ".${target.name}.${UUID.randomUUID()}.tmp")
                    try {
                        FileOutputStream(temporary).use { output ->
                            source.inputStream().buffered(APK_COPY_BUFFER).use { input ->
                                val buffer = ByteArray(APK_COPY_BUFFER)
                                var copied = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    copied = Math.addExact(copied, read.toLong())
                                    require(copied <= sourceSize) { "APK изменился во время подготовки" }
                                    output.write(buffer, 0, read)
                                    requireShareCacheSpace(shareDir, 0L)
                                }
                                require(copied == sourceSize) { "APK обрезан во время подготовки" }
                            }
                            output.flush()
                            output.fd.sync()
                        }
                        require(source.length() == sourceSize) { "APK изменился во время подготовки" }
                        currentCoroutineContext().ensureActive()
                        require(temporary.renameTo(target)) { "Не удалось завершить подготовку APK" }
                        target
                    } catch (error: Throwable) {
                        temporary.delete()
                        throw error
                    }
                }
            }
            exportingPackage = null
            result.onSuccess { apk -> shareApkFile(apk, "Поделиться ${app.label}") }
                .onFailure { error ->
                    Toast.makeText(
                        this@InstalledAppsActivity,
                        error.message ?: "Не удалось извлечь APK",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun shareInstalledUniversalApk(app: InstalledAppEntry) {
        if (!app.isSplit || exportingPackage != null) return
        exportingPackage = app.packageName
        Toast.makeText(this, "Готовим единый APK…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = installedAppsResult {
                withContext(Dispatchers.IO) {
                    UniversalApkExporter.export(
                        this@InstalledAppsActivity,
                        AuraApksBundle.InstalledPackageSource(
                            packageName = app.packageName,
                            label = app.label,
                            versionName = app.versionName,
                            versionCode = app.versionCode,
                            baseApkPath = app.sourceDir,
                            splitApkPaths = app.splitSourceDirs,
                        ),
                    )
                }
            }
            exportingPackage = null
            result.onSuccess { exported ->
                if (isFinishing || isDestroyed) return@onSuccess
                AlertDialog.Builder(this@InstalledAppsActivity)
                    .setTitle("APK готов")
                    .setMessage(
                        buildString {
                            append(app.label)
                            append("\n\n")
                            append(exported.warning)
                            append("\n\nПодпись Aura APK Export:\n")
                            append(exported.signerSha256)
                        },
                    )
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Поделиться") { dialog, _ ->
                        dialog.dismiss()
                        // Some OEM resolver implementations (notably MIUI/HyperOS) can swallow
                        // a chooser launch while the source AlertDialog is still being dismissed.
                        // Schedule Aura's own target picker for the next UI-loop turn instead.
                        window.decorView.post {
                            if (!isFinishing && !isDestroyed) {
                                shareApkFile(exported.file, "Поделиться ${app.label}")
                            }
                        }
                    }
                    .show()
            }.onFailure { error ->
                Toast.makeText(
                    this@InstalledAppsActivity,
                    (error.message ?: "Не удалось собрать единый APK") + "\nМожно использовать APKS.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun shareApkFile(apk: File, chooserTitle: String) {
        if (!apk.isFile || apk.length() <= 0L) {
            Toast.makeText(this, "APK недоступен", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val result = installedAppsResult {
                withContext(Dispatchers.IO) {
                    ApkSharePublisher.publish(this@InstalledAppsActivity, apk)
                }
            }
            result.onSuccess { published ->
                if (isFinishing || isDestroyed) return@onSuccess
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = ApkShareDeliveryPolicy.SHARE_MIME
                    putExtra(Intent.EXTRA_STREAM, published.uri)
                    putExtra(Intent.EXTRA_TITLE, published.displayName)
                    clipData = ClipData.newRawUri(published.displayName, published.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    startActivity(Intent.createChooser(send, chooserTitle))
                    if (published.savedToDownloads) {
                        Toast.makeText(
                            this@InstalledAppsActivity,
                            "APK сохранён в Загрузки/Aura Files",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }.onFailure { error ->
                    Toast.makeText(
                        this@InstalledAppsActivity,
                        "Не удалось открыть системное меню отправки: ${error.message ?: error.javaClass.simpleName}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }.onFailure { error ->
                Toast.makeText(
                    this@InstalledAppsActivity,
                    "Не удалось подготовить APK для отправки: ${error.message ?: error.javaClass.simpleName}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun requireShareCacheSpace(directory: File, expectedBytes: Long) {
        val usable = directory.usableSpace
        if (usable <= SHARE_CACHE_RESERVE_BYTES) {
            throw IOException("Недостаточно свободного места: Aura сохраняет резерв 128 МиБ")
        }
        if (expectedBytes > 0L && expectedBytes > usable - SHARE_CACHE_RESERVE_BYTES) {
            throw IOException("Недостаточно свободного места для подготовки APK")
        }
    }

    private fun uniqueShareFile(directory: File, requestedName: String): File {
        val direct = File(directory, requestedName)
        if (!direct.exists()) return direct
        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        for (index in 2..9999) {
            val candidate = File(directory, "$base ($index)$extension")
            if (!candidate.exists()) return candidate
        }
        throw IOException("Не удалось подобрать имя временного APK")
    }

    private companion object {
        const val APK_COPY_BUFFER = 256 * 1024
        const val MAX_SHARED_APK_BYTES = 8L * 1024L * 1024L * 1024L
        const val SHARE_CACHE_RESERVE_BYTES = 128L * 1024L * 1024L
    }
}

private data class InstalledAppEntry(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val sourceDir: String,
    val splitSourceDirs: List<String>,
    val applicationInfo: ApplicationInfo,
) {
    val isSplit: Boolean get() = splitSourceDirs.isNotEmpty()
    val apkCount: Int get() = 1 + splitSourceDirs.size
}

@Composable
private fun InstalledAppsScreen(
    onBack: () -> Unit,
    onShare: (InstalledAppEntry) -> Unit,
    onShareSplit: (InstalledAppEntry) -> Unit,
    onOpenApks: () -> Unit,
    exportingPackage: String?,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledAppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            apps = withContext(Dispatchers.IO) { loadInstalledApps(context.packageManager) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            loadError = error.message ?: "Не удалось получить список приложений"
        }
    }

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
                Column(Modifier.weight(1f)) {
                    Text("Приложения", fontWeight = FontWeight.SemiBold)
                    Text(
                        apps?.let { "Установлено пользователем: ${it.size}" } ?: "Читаем список…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            OutlinedButton(
                onClick = onOpenApks,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Rounded.InstallMobile, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Установить .APKS", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Выбрать SPLIT-комплект, полученный через Telegram или другим способом",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text("Найти приложение") },
            )

            when {
                loadError != null -> {
                    Text(
                        loadError.orEmpty(),
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                apps == null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                else -> {
                    val normalized = query.trim()
                    val filtered = if (normalized.isBlank()) apps.orEmpty() else apps.orEmpty().filter {
                        it.label.contains(normalized, ignoreCase = true) ||
                            it.packageName.contains(normalized, ignoreCase = true)
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 6.dp, 12.dp, 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filtered, key = { it.packageName }) { app ->
                            InstalledAppRow(
                                app = app,
                                onShare = { onShare(app) },
                                onShareSplit = { onShareSplit(app) },
                                exporting = exportingPackage == app.packageName,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledAppRow(
    app: InstalledAppEntry,
    onShare: () -> Unit,
    onShareSplit: () -> Unit,
    exporting: Boolean,
) {
    val context = LocalContext.current
    val icon = remember(app.packageName) {
        runCatching { app.applicationInfo.loadIcon(context.packageManager) }.getOrNull()
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AndroidView(
                modifier = Modifier.size(48.dp),
                factory = { imageContext ->
                    ImageView(imageContext).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
                },
                update = { it.setImageDrawable(icon) },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.label,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(app.versionName.ifBlank { "версия ?" })
                        append(" · ")
                        append(app.packageName)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Column(modifier = Modifier.padding(top = 7.dp)) {
                    AppTypeBadge(app)
                    Spacer(Modifier.size(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = onShare,
                            enabled = !exporting,
                        ) {
                            if (exporting) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(UniversalApkExportPolicy.shareLabel(app.isSplit, exporting))
                        }
                        if (app.isSplit) {
                            OutlinedButton(
                                onClick = onShareSplit,
                                enabled = !exporting,
                            ) {
                                Text("APKS")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppTypeBadge(app: InstalledAppEntry) {
    val container = if (app.isSplit) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (app.isSplit) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = container,
        contentColor = content,
    ) {
        Text(
            if (app.isSplit) "SPLIT · ${app.apkCount} APK" else "APK",
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun loadInstalledApps(packageManager: PackageManager): List<InstalledAppEntry> {
    val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getInstalledApplications(0)
    }
    return installed.asSequence()
        .filter { info ->
            val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val updatedSystem = info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            !system || updatedSystem
        }
        .mapNotNull { info ->
            runCatching {
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(info.packageName, PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(info.packageName, 0)
                }
                InstalledAppEntry(
                    packageName = info.packageName,
                    label = info.loadLabel(packageManager).toString().ifBlank { info.packageName },
                    versionName = packageInfo.versionName.orEmpty(),
                    versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode.toLong()
                    },
                    sourceDir = info.sourceDir,
                    splitSourceDirs = info.splitSourceDirs?.toList().orEmpty(),
                    applicationInfo = info,
                )
            }.getOrNull()
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        .toList()
}
