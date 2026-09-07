package com.aurafiles.app.ui

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
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.aurafiles.app.tools.AuraApksBundle
import com.aurafiles.app.ui.theme.AuraFilesTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            val result = runCatching {
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
                val uri = FileProvider.getUriForFile(
                    this@InstalledAppsActivity,
                    "$packageName.fileprovider",
                    bundle,
                )
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
        if (app.isSplit || exportingPackage != null) return
        exportingPackage = app.packageName
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val source = File(app.sourceDir)
                    require(source.isFile) { "APK приложения недоступен" }
                    val shareDir = File(cacheDir, "shares").apply { mkdirs() }
                    val expiry = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
                    shareDir.listFiles().orEmpty()
                        .filter { it.name.startsWith("installed-") && it.lastModified() < expiry }
                        .forEach(File::delete)
                    val safeLabel = app.label
                        .replace(Regex("[^\\p{L}\\p{N}._ -]+"), "_")
                        .trim()
                        .take(48)
                        .ifBlank { app.packageName.substringAfterLast('.') }
                    val safeVersion = app.versionName
                        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                        .take(24)
                    val target = File(
                        shareDir,
                        "installed-${safeLabel}${safeVersion.takeIf(String::isNotBlank)?.let { "-$it" }.orEmpty()}.apk",
                    )
                    source.copyTo(target, overwrite = true)
                    target
                }
            }
            exportingPackage = null
            result.onSuccess { apk ->
                val uri = FileProvider.getUriForFile(
                    this@InstalledAppsActivity,
                    "$packageName.fileprovider",
                    apk,
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(contentResolver, apk.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    startActivity(Intent.createChooser(send, "Поделиться ${app.label}"))
                }.onFailure {
                    Toast.makeText(
                        this@InstalledAppsActivity,
                        "Не найдено приложение для отправки APK",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }.onFailure { error ->
                Toast.makeText(
                    this@InstalledAppsActivity,
                    error.message ?: "Не удалось извлечь APK",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
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
        runCatching {
            withContext(Dispatchers.IO) { loadInstalledApps(context.packageManager) }
        }.onSuccess { apps = it }
            .onFailure { loadError = it.message ?: "Не удалось получить список приложений" }
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
                    OutlinedButton(
                        onClick = if (app.isSplit) onShareSplit else onShare,
                        enabled = !exporting,
                    ) {
                        if (exporting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                exporting -> "Готовим…"
                                app.isSplit -> "Поделиться комплектом…"
                                else -> "Поделиться…"
                            }
                        )
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
