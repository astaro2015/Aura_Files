package com.aurafiles.app.ui

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.aurafiles.app.data.FastDocumentListing
import com.aurafiles.app.archive.ArchiveVirtualEntry
import com.aurafiles.app.archive.ExtendedArchiveRepository
import com.aurafiles.app.model.FileEntry
import com.aurafiles.app.model.isReaderSupported
import com.aurafiles.app.ui.theme.AuraFilesTheme
import java.io.File
import java.net.URLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Read/browse view of an archive. Files stay inside the archive until the user opens
 * one (only that item is unpacked to cache) or explicitly extracts the whole archive.
 * This makes ZIP/TAR/TGZ/7Z/RAR behave like folders without a costly full extraction.
 */
class ArchiveBrowserActivity : ComponentActivity() {
    private lateinit var archiveEntry: FileEntry
    private lateinit var repository: ExtendedArchiveRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        archiveEntry = readEntry() ?: run { finish(); return }
        repository = ExtendedArchiveRepository(this)
        setContent {
            AuraFilesTheme {
                ArchiveBrowserScreen(
                    archive = archiveEntry,
                    repository = repository,
                    onClose = ::finish,
                    onOpenFile = ::openVirtualFile,
                    onExtractAll = ::extractAll,
                )
            }
        }
    }

    private fun readEntry(): FileEntry? {
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: return null
        val document = if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let { DocumentFile.fromFile(File(it)) }
        } else DocumentFile.fromSingleUri(this, uri)
        document ?: return null
        return FileEntry(
            document = document,
            name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { document.name ?: "Архив" },
            uri = uri,
            isDirectory = false,
            mimeType = intent.getStringExtra(EXTRA_MIME)?.takeIf(String::isNotBlank) ?: document.type,
            size = intent.getLongExtra(EXTRA_SIZE, document.length()),
            modifiedAt = intent.getLongExtra(EXTRA_MODIFIED, document.lastModified()),
            parentUri = intent.getStringExtra(EXTRA_PARENT)?.takeIf(String::isNotBlank)?.let(Uri::parse),
        )
    }

    private fun extractAll() {
        val parentUri = archiveEntry.parentUri
        val parent = parentUri?.let { FastDocumentListing.resolve(this, it) }
        if (parent == null || !parent.isDirectory) {
            Toast.makeText(this, "Не удалось определить папку рядом с архивом", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repository.extract(archiveEntry, parent) } }
            result.onSuccess { Toast.makeText(this@ArchiveBrowserActivity, "Архив распакован", Toast.LENGTH_SHORT).show() }
                .onFailure { error -> Toast.makeText(this@ArchiveBrowserActivity, error.message ?: "Ошибка распаковки", Toast.LENGTH_LONG).show() }
        }
    }

    private suspend fun openVirtualFile(item: ArchiveVirtualEntry) {
        val result = withContext(Dispatchers.IO) {
            runCatching { repository.extractEntryToCache(archiveEntry, item.path) }
        }
        result.onSuccess { file -> openCachedFile(file, item.name) }
            .onFailure { error -> Toast.makeText(this, error.message ?: "Не удалось открыть файл", Toast.LENGTH_LONG).show() }
    }

    private fun openCachedFile(file: File, displayName: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val document = DocumentFile.fromSingleUri(this, uri) ?: return
        val mime = URLConnection.guessContentTypeFromName(displayName) ?: "application/octet-stream"
        val entry = FileEntry(document, displayName, uri, false, mime, file.length(), file.lastModified(), null)
        when {
            entry.isReaderSupported() -> runCatching { openBookReader(this, entry) }.onFailure { openExternal(entry) }
            isBrowsableArchiveName(entry.name) -> start(this, entry)
            openEnhancedPreview(this, entry) -> Unit
            else -> openExternal(entry)
        }
    }

    private fun openExternal(entry: FileEntry) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(entry.uri, entry.mimeType ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(intent) }.onFailure {
            if (it is ActivityNotFoundException) Toast.makeText(this, "Нет приложения для открытия файла", Toast.LENGTH_SHORT).show()
            else Toast.makeText(this, it.message ?: "Не удалось открыть файл", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val EXTRA_URI = "archive_uri"
        private const val EXTRA_PARENT = "archive_parent"
        private const val EXTRA_NAME = "archive_name"
        private const val EXTRA_MIME = "archive_mime"
        private const val EXTRA_SIZE = "archive_size"
        private const val EXTRA_MODIFIED = "archive_modified"

        fun isBrowsableArchiveName(name: String): Boolean {
            val lower = name.lowercase()
            if (lower.endsWith(".fb2.zip")) return false
            return lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar") ||
                lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ||
                lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") ||
                lower.endsWith(".tar.xz") || lower.endsWith(".txz") ||
                lower.endsWith(".gz") || lower.endsWith(".bz2") || lower.endsWith(".xz")
        }

        fun start(context: Context, entry: FileEntry) {
            context.startActivity(
                Intent(context, ArchiveBrowserActivity::class.java)
                    .putExtra(EXTRA_URI, entry.uri.toString())
                    .putExtra(EXTRA_PARENT, entry.parentUri?.toString().orEmpty())
                    .putExtra(EXTRA_NAME, entry.name)
                    .putExtra(EXTRA_MIME, entry.mimeType.orEmpty())
                    .putExtra(EXTRA_SIZE, entry.size)
                    .putExtra(EXTRA_MODIFIED, entry.modifiedAt)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }
}

@Composable
private fun ArchiveBrowserScreen(
    archive: FileEntry,
    repository: ExtendedArchiveRepository,
    onClose: () -> Unit,
    onOpenFile: suspend (ArchiveVirtualEntry) -> Unit,
    onExtractAll: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf("") }
    var openingPath by remember { mutableStateOf<String?>(null) }
    val loadResult by produceState<Result<List<ArchiveVirtualEntry>>?>(null, archive.uri) {
        value = runCatching { withContext(Dispatchers.IO) { repository.listEntries(archive) } }
    }
    val allEntries = loadResult?.getOrNull().orEmpty()
    val childrenIndex = remember(allEntries) {
        allEntries.groupBy { it.path.substringBeforeLast('/', "") }.mapValues { (_, items) ->
            items.sortedWith(compareByDescending<ArchiveVirtualEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }
    val children = childrenIndex[currentPath].orEmpty()

    fun goBack() {
        if (currentPath.isBlank()) onClose() else currentPath = currentPath.substringBeforeLast('/', "")
    }
    BackHandler { goBack() }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = ::goBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад") }
            Column(modifier = Modifier.weight(1f)) {
                Text(archive.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    if (currentPath.isBlank()) "Внутри архива" else "/$currentPath",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            IconButton(onClick = onExtractAll) { Icon(Icons.Rounded.FolderOpen, contentDescription = "Распаковать всё") }
        }

        when {
            loadResult == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            loadResult?.isFailure == true -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(loadResult?.exceptionOrNull()?.message ?: "Не удалось прочитать архив")
            }
            children.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Папка пуста") }
            else -> LazyColumn(
                contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(children, key = { it.path }) { item ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = openingPath == null) {
                            if (item.isDirectory) currentPath = item.path
                            else {
                                openingPath = item.path
                                scope.launch {
                                    onOpenFile(item)
                                    openingPath = null
                                }
                            }
                        },
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (item.isDirectory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                                contentDescription = null,
                                tint = if (item.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (!item.isDirectory) Text(formatArchiveBytes(item.size), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                            if (openingPath == item.path) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else if (item.isDirectory) Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun formatArchiveBytes(value: Long): String {
    if (value < 0L) return "Размер после распаковки неизвестен"
    if (value < 1024) return "$value Б"
    val units = arrayOf("КБ", "МБ", "ГБ", "ТБ")
    var amount = value.toDouble()
    var unit = -1
    while (amount >= 1024.0 && unit < units.lastIndex) { amount /= 1024.0; unit++ }
    return if (amount >= 100) "%.0f %s".format(amount, units[unit]) else "%.1f %s".format(amount, units[unit])
}
