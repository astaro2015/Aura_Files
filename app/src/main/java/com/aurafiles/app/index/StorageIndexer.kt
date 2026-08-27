package com.aurafiles.app.index

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.model.CategorySummary
import com.aurafiles.app.model.FileCategory
import com.aurafiles.app.model.FileEntry
import com.aurafiles.app.model.StorageAnalysis
import com.aurafiles.app.model.category
import com.aurafiles.app.model.sourceLabel
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class IndexScanState {
    IDLE,
    SCANNING,
    HASHING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

data class IndexScanProgress(
    val state: IndexScanState = IndexScanState.IDLE,
    val filesCount: Long = 0L,
    val totalBytes: Long = 0L,
    val currentFolder: String = "",
    val currentFile: String = "",
)

class StorageIndexer(
    context: Context,
    database: AuraIndexDatabase = AuraIndexDatabase.get(context),
) {
    private val appContext = context.applicationContext
    private val fileDao = database.indexedFileDao()
    private val rootDao = database.rootDao()
    private val duplicateFinder = DuplicateFinder(appContext.contentResolver, fileDao)
    private val _progress = MutableStateFlow(IndexScanProgress())
    val progress: StateFlow<IndexScanProgress> = _progress.asStateFlow()

    suspend fun scan(root: DocumentFile): StorageAnalysis {
        require(root.isDirectory) { "Корень индекса должен быть папкой" }
        val rootId = root.uri.toString()
        val generation = System.currentTimeMillis()
        val previous = rootDao.get(rootId)
        rootDao.upsert(
            IndexedRootEntity(
                rootId,
                root.uri.toString(),
                root.name ?: "Хранилище",
                generation,
                previous?.lastScanCompleted ?: 0L,
                previous?.filesCount ?: 0L,
                previous?.totalBytes ?: 0L,
                generation,
            )
        )
        var count = 0L
        var bytes = 0L
        val batch = ArrayList<IndexedFileEntity>(BATCH_SIZE)
        _progress.value = IndexScanProgress(state = IndexScanState.SCANNING)

        suspend fun flush() {
            if (batch.isNotEmpty()) {
                fileDao.upsertAll(batch.toList())
                batch.clear()
            }
        }

        suspend fun walk(directory: DocumentFile, relativePath: String) {
            currentCoroutineContext().ensureActive()
            _progress.value = _progress.value.copy(currentFolder = relativePath.ifBlank { "/" })
            directory.listFiles().forEach { child ->
                currentCoroutineContext().ensureActive()
                if (child.name == TRASH_FOLDER) return@forEach
                val childPath = if (relativePath.isBlank()) child.name.orEmpty() else "$relativePath/${child.name.orEmpty()}"
                if (child.isDirectory) {
                    walk(child, childPath)
                } else {
                    val entry = child.toEntry(directory.uri)
                    val unchanged = fileDao.findUnchanged(rootId, entry.uri.toString(), entry.size, entry.modifiedAt)
                    batch += IndexedFileEntity(
                        rootId,
                        entry.uri.toString(),
                        directory.uri.toString(),
                        entry.name,
                        entry.name.substringAfterLast('.', "").lowercase(),
                        entry.mimeType,
                        entry.size,
                        entry.modifiedAt,
                        entry.category().name,
                        entry.sourceLabel(),
                        unchanged?.sha256,
                        unchanged?.quickHash,
                        generation,
                    )
                    count += 1
                    bytes += entry.size.coerceAtLeast(0L)
                    _progress.value = IndexScanProgress(IndexScanState.SCANNING, count, bytes, relativePath, entry.name)
                    if (batch.size >= BATCH_SIZE) flush()
                }
            }
        }

        return try {
            walk(root, "")
            flush()
            fileDao.deleteNotSeen(rootId, generation)
            _progress.value = IndexScanProgress(IndexScanState.HASHING, count, bytes)
            val duplicateEntities = duplicateFinder.findExactDuplicates(rootId) { name ->
                _progress.value = _progress.value.copy(currentFile = name)
            }
            val completedAt = System.currentTimeMillis()
            rootDao.upsert(
                IndexedRootEntity(
                    rootId,
                    root.uri.toString(),
                    root.name ?: "Хранилище",
                    generation,
                    completedAt,
                    count,
                    bytes,
                    generation,
                )
            )
            val analysis = buildAnalysis(rootId, duplicateEntities, completedAt)
            _progress.value = IndexScanProgress(IndexScanState.COMPLETED, count, bytes)
            analysis
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            _progress.value = _progress.value.copy(state = IndexScanState.CANCELLED)
            throw cancelled
        } catch (error: Throwable) {
            _progress.value = _progress.value.copy(state = IndexScanState.FAILED)
            throw error
        }
    }

    fun load(root: DocumentFile): StorageAnalysis? {
        val rootId = root.uri.toString()
        val indexedRoot = rootDao.get(rootId)?.takeIf { it.lastScanCompleted > 0L } ?: return null
        val duplicates = loadDuplicateGroups(rootId)
        return buildAnalysis(rootId, duplicates, indexedRoot.lastScanCompleted)
    }

    fun categoryEntries(root: DocumentFile, category: FileCategory, limit: Int = UI_QUERY_LIMIT): List<FileEntry> =
        when (category) {
            FileCategory.Downloads -> fileDao.bySourceFolder(root.uri.toString(), "Загрузки", limit)
            FileCategory.Camera -> fileDao.bySourceFolder(root.uri.toString(), "Камера", limit)
            else -> fileDao.byCategory(root.uri.toString(), category.name, limit)
        }.mapNotNull(::toEntry)

    fun temporaryEntries(root: DocumentFile, limit: Int = UI_QUERY_LIMIT): List<FileEntry> =
        fileDao.temporary(root.uri.toString(), limit).mapNotNull(::toEntry)

    fun recentEntries(root: DocumentFile, limit: Int = 500): List<FileEntry> =
        fileDao.recent(root.uri.toString(), limit).mapNotNull(::toEntry)

    fun clear(root: DocumentFile) {
        fileDao.deleteRoot(root.uri.toString())
    }

    private fun buildAnalysis(
        rootId: String,
        duplicateEntities: List<List<IndexedFileEntity>>,
        scannedAt: Long,
    ): StorageAnalysis {
        val categories = FileCategory.entries.map { category ->
            val count = when (category) {
                FileCategory.Downloads -> fileDao.sourceCount(rootId, "Загрузки")
                FileCategory.Camera -> fileDao.sourceCount(rootId, "Камера")
                else -> fileDao.categoryCount(rootId, category.name)
            }
            val bytes = when (category) {
                FileCategory.Downloads -> fileDao.sourceBytes(rootId, "Загрузки")
                FileCategory.Camera -> fileDao.sourceBytes(rootId, "Камера")
                else -> fileDao.categoryBytes(rootId, category.name)
            }
            CategorySummary(
                category,
                count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                bytes,
            )
        }
        return StorageAnalysis(
            files = fileDao.recent(rootId, UI_CACHE_LIMIT).mapNotNull(::toEntry),
            totalBytes = fileDao.totalBytes(rootId),
            categories = categories,
            largeFiles = fileDao.largest(rootId, 50).mapNotNull(::toEntry),
            duplicateGroups = duplicateEntities.map { group -> group.mapNotNull(::toEntry) }.filter { it.size > 1 },
            limitReached = false,
            scannedAt = scannedAt,
        )
    }

    private fun loadDuplicateGroups(rootId: String): List<List<IndexedFileEntity>> =
        fileDao.duplicateSizes(rootId).flatMap { size ->
            fileDao.bySize(rootId, size)
                .filter { !it.sha256.isNullOrBlank() }
                .groupBy { it.sha256 }
                .values
                .filter { it.size > 1 }
        }

    private fun toEntry(entity: IndexedFileEntity): FileEntry? {
        val uri = Uri.parse(entity.uri)
        val document = DocumentFile.fromSingleUri(appContext, uri) ?: return null
        if (!document.exists()) return null
        return FileEntry(
            document = document,
            name = entity.name,
            uri = uri,
            isDirectory = false,
            mimeType = entity.mimeType,
            size = entity.size,
            modifiedAt = entity.modifiedAt,
            parentUri = Uri.parse(entity.parentUri),
        )
    }

    private fun DocumentFile.toEntry(parentUri: Uri): FileEntry {
        val displayName = name ?: throw IOException("Файл без имени нельзя проиндексировать")
        return FileEntry(
            document = this,
            name = displayName,
            uri = uri,
            isDirectory = false,
            mimeType = type,
            size = length(),
            modifiedAt = lastModified(),
            parentUri = parentUri,
        )
    }

    companion object {
        private const val BATCH_SIZE = 500
        private const val UI_CACHE_LIMIT = 2_000
        private const val UI_QUERY_LIMIT = 5_000
        private const val TRASH_FOLDER = ".AuraTrash"
    }
}
