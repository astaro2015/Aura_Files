package com.aurafiles.app.ui

import com.aurafiles.app.AuraFileProvider
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.data.AuraVault
import com.aurafiles.app.model.FileEntry
import com.aurafiles.app.model.isReaderSupported
import com.aurafiles.app.ui.theme.AuraFilesTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private suspend fun <T> vaultResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

class VaultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AuraFilesTheme {
                VaultScreen()
            }
        }
    }

    @Composable
    private fun VaultScreen() {
        val vault = remember { AuraVault(applicationContext) }
        val scope = rememberCoroutineScope()
        var listing by remember { mutableStateOf(AuraVault.Listing(emptyList(), 0)) }
        var refreshToken by remember { mutableIntStateOf(0) }
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf<String?>(null) }
        var restoreItem by remember { mutableStateOf<AuraVault.Item?>(null) }
        var deleteItem by remember { mutableStateOf<AuraVault.Item?>(null) }
        var previewEntry by remember { mutableStateOf<FileEntry?>(null) }
        var openingId by remember { mutableStateOf<String?>(null) }
        val thumbnailCache = remember {
            object : LruCache<String, Bitmap>(VAULT_THUMBNAIL_CACHE_BYTES) {
                override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
            }
        }

        fun openItem(item: AuraVault.Item) {
            if (openingId != null || busy) return
            openingId = item.encryptedFile.name
            status = "Готовим ${item.name}…"
            scope.launch {
                val prepared = vaultResult { withContext(Dispatchers.IO) { preparePlainEntry(vault, item) } }
                openingId = null
                prepared.onSuccess { entry ->
                    status = null
                    when {
                        entry.isReaderSupported() -> runCatching { openBookReader(this@VaultActivity, entry) }
                            .onFailure { previewEntry = entry }
                        ArchiveBrowserActivity.isBrowsableArchiveName(entry.name) ->
                            runCatching { ArchiveBrowserActivity.start(this@VaultActivity, entry) }
                                .onFailure { previewEntry = entry }
                        openEnhancedPreview(this@VaultActivity, entry) -> Unit
                        else -> previewEntry = entry
                    }
                }.onFailure { error ->
                    status = error.message ?: "Не удалось подготовить файл для просмотра"
                }
            }
        }

        val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val item = restoreItem
            restoreItem = null
            if (uri == null || item == null) return@rememberLauncherForActivityResult
            val destination = DocumentFile.fromTreeUri(this@VaultActivity, uri)
            if (destination == null) {
                status = "Не удалось открыть выбранную папку"
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                busy = true
                status = "Возвращаем ${item.name}…"
                vaultResult { withContext(Dispatchers.IO) { vault.restore(item, destination) } }
                    .onSuccess {
                        status = "${item.name} возвращён в обычное хранилище"
                        refreshToken += 1
                    }
                    .onFailure { status = it.message ?: "Не удалось вернуть файл" }
                busy = false
            }
        }

        LaunchedEffect(refreshToken) {
            vaultResult { withContext(Dispatchers.IO) { vault.list() } }
                .onSuccess { listing = it }
                .onFailure { error -> status = error.message ?: "Не удалось проверить состояние Сейфа" }
        }

        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { finish() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Сейф", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Зашифровано и привязано к этому устройству",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()

                status?.let { message ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(message, modifier = Modifier.padding(10.dp), fontSize = 12.sp)
                    }
                }

                when {
                    busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    !vault.available() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Для Сейфа нужен доступ Aura Files «Весь накопитель».",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    listing.items.isEmpty() && listing.unreadableCount == 0 -> Box(
                        Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Пока пусто. Добавляйте файлы через пункт «В Сейф» — исходник будет перемещён сюда и зашифрован.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 6.dp),
                    ) {
                        if (listing.unreadableCount > 0) {
                            item {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.errorContainer,
                                ) {
                                    Text(
                                        "Не удалось расшифровать объектов: ${listing.unreadableCount}. Возможно, сейф был перенесён с другого устройства или изменилась подпись Aura Files.",
                                        modifier = Modifier.padding(12.dp),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                        items(listing.items, key = { it.encryptedFile.name }) { item ->
                            VaultRow(
                                vault = vault,
                                item = item,
                                thumbnailCache = thumbnailCache,
                                enabled = !busy && openingId == null,
                                opening = openingId == item.encryptedFile.name,
                                onOpen = { openItem(item) },
                                onRestore = {
                                    restoreItem = item
                                    restoreLauncher.launch(null)
                                },
                                onShare = { shareVaultItem(vault, item) },
                                onDelete = { deleteItem = item },
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 58.dp))
                        }
                    }
                }
            }
        }

        previewEntry?.let { entry ->
            FilePreviewDialog(
                entry = entry,
                onOpenExternal = { openPlainEntryExternally(entry) },
                onDismiss = { previewEntry = null },
            )
        }

        deleteItem?.let { item ->
            AlertDialog(
                onDismissRequest = { deleteItem = null },
                title = { Text("Удалить из Сейфа?") },
                text = { Text("${item.name} будет удалён безвозвратно. Обычной копии файла уже нет.") },
                confirmButton = {
                    Button(onClick = {
                        deleteItem = null
                        scope.launch {
                            busy = true
                            vaultResult { withContext(Dispatchers.IO) { vault.delete(item) } }
                                .onSuccess {
                                    status = "${item.name} удалён"
                                    refreshToken += 1
                                }
                                .onFailure { status = it.message ?: "Не удалось удалить файл" }
                            busy = false
                        }
                    }) { Text("Удалить") }
                },
                dismissButton = { TextButton(onClick = { deleteItem = null }) { Text("Отмена") } },
            )
        }
    }

    @Composable
    private fun VaultRow(
        vault: AuraVault,
        item: AuraVault.Item,
        thumbnailCache: LruCache<String, Bitmap>,
        enabled: Boolean,
        opening: Boolean,
        onOpen: () -> Unit,
        onRestore: () -> Unit,
        onShare: () -> Unit,
        onDelete: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onOpen)
                .padding(start = 14.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VaultThumbnail(vault, item, thumbnailCache)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        append(formatVaultBytes(item.size))
                        if (item.modifiedAt > 0) {
                            append(" · ")
                            append(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.modifiedAt)))
                        }
                        if (opening) append(" · готовим просмотр…")
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRestore, enabled = enabled) {
                Icon(Icons.Rounded.RestoreFromTrash, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("Вернуть")
            }
            IconButton(onClick = onShare, enabled = enabled) {
                Icon(Icons.Rounded.Share, contentDescription = "Поделиться")
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Удалить")
            }
        }
    }

    @Composable
    private fun VaultThumbnail(
        vault: AuraVault,
        item: AuraVault.Item,
        cache: LruCache<String, Bitmap>,
    ) {
        val key = "${item.encryptedFile.absolutePath}|${item.encryptedFile.lastModified()}"
        val bitmap by produceState<Bitmap?>(initialValue = cache.get(key), key, item.hasThumbnail) {
            if (value == null && item.hasThumbnail) {
                value = withContext(Dispatchers.IO) {
                    runCatching { vault.loadThumbnail(item) }.getOrNull()?.also { cache.put(key, it) }
                }
            }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        vaultIcon(item),
                        contentDescription = null,
                        modifier = Modifier.size(27.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    private fun preparePlainEntry(vault: AuraVault, item: AuraVault.Item): FileEntry {
        val file = vault.preparePlainFile(item)
        val uri = AuraFileProvider.uriForFile(this, file)
        return FileEntry(
            document = DocumentFile.fromFile(file),
            name = item.name,
            uri = uri,
            isDirectory = false,
            mimeType = mimeFor(item),
            size = item.size,
            modifiedAt = item.modifiedAt,
            parentUri = null,
            vaultItemId = item.id,
        )
    }

    private fun openPlainEntryExternally(entry: FileEntry) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(entry.uri, entry.mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Нет приложения для открытия этого файла", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Нет доступа к временному файлу", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareVaultItem(vault: AuraVault, item: AuraVault.Item) {
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { vault.preparePlainFile(item) }
                val uri = AuraFileProvider.uriForFile(this@VaultActivity, file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeFor(item)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(Intent.createChooser(intent, "Поделиться файлом"))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this@VaultActivity, "Нет приложения для отправки файла", Toast.LENGTH_SHORT).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Toast.makeText(
                    this@VaultActivity,
                    error.message ?: "Не удалось подготовить файл",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun mimeFor(item: AuraVault.Item): String {
        val extension = item.name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "apk" -> "application/vnd.android.package-archive"
            "apks" -> "application/vnd.aurafiles.apks+zip"
            else -> item.mimeType ?: "application/octet-stream"
        }
    }

    private fun vaultIcon(item: AuraVault.Item): ImageVector = when {
        item.mimeType?.startsWith("image/") == true -> Icons.Rounded.Image
        item.mimeType?.startsWith("video/") == true -> Icons.Rounded.Movie
        item.mimeType?.startsWith("audio/") == true -> Icons.Rounded.MusicNote
        item.mimeType?.startsWith("text/") == true || item.mimeType == "application/pdf" -> Icons.Rounded.Description
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }

    private fun formatVaultBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes Б"
        val units = arrayOf("КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.toDouble() / 1024.0
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return if (value >= 100) "%.0f %s".format(value, units[index]) else "%.1f %s".format(value, units[index])
    }

    companion object {
        private const val VAULT_THUMBNAIL_CACHE_BYTES = 8 * 1024 * 1024
    }

}
