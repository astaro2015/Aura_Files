package com.aurafiles.app.index

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.data.FastDocumentListing
import com.aurafiles.app.model.CategorySummary
import com.aurafiles.app.model.FileCategory
import com.aurafiles.app.model.FileClassifier
import com.aurafiles.app.model.FileEntry
import com.aurafiles.app.model.StorageAnalysis
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
    private var lastProgressAt = 0L

    suspend fun scan(root: DocumentFile): StorageAnalysis {
        require(root.isDirectory) { "Корень индекса должен быть папкой" }
        val rootId = root.uri.toString()
        val generation = System.currentTimeMillis()
        val previousRoot = rootDao.get(rootId)
        // One query instead of findUnchanged() for every discovered file.
        val oldHashes = fileDao.hashSnapshots(rootId).associateBy { it.uri }
        rootDao.upsert(
            IndexedRootEntity(
                rootId,
                root.uri.toString(),
                root.name ?: "Хранилище",
                generation,
                previousRoot?.lastScanCompleted ?: 0L,
                previousRoot?.filesCount ?: 0L,
                previousRoot?.totalBytes ?: 0L,
                generation,
            )
        )
        var count = 0L
        var bytes = 0L
        val batch = ArrayList<IndexedFileEntity>(BATCH_SIZE)
        publish(IndexScanProgress(state = IndexScanState.SCANNING), force = true)

        fun flush() {
            if (batch.isNotEmpty()) {
                fileDao.upsertAll(batch.toList())
                batch.clear()
            }
        }

        suspend fun walk(directory: DocumentFile, relativePath: String) {
            currentCoroutineContext().ensureActive()
            publish(
                IndexScanProgress(IndexScanState.SCANNING, count, bytes, relativePath.ifBlank { "/" }, ""),
                force = false,
            )
            for (child in FastDocumentListing.list(appContext, directory)) {
                currentCoroutineContext().ensureActive()
                if (child.name == TRASH_FOLDER) continue
                val childPath = if (relativePath.isBlank()) child.name else "$relativePath/${child.name}"
                if (child.isDirectory) {
                    walk(child.document, childPath)
                    continue
                }

                val classification = FileClassifier.classify(child.name, child.mimeType, child.uri, directory.uri)
                val previous = oldHashes[child.uri.toString()]?.takeIf {
                    it.size == child.size && it.modifiedAt == child.modifiedAt
                }
                batch += IndexedFileEntity(
                    rootId,
                    child.uri.toString(),
                    directory.uri.toString(),
                    child.name,
                    classification.extension,
                    child.mimeType,
                    child.size,
                    child.modifiedAt,
                    classification.category.name,
                    classification.sourceFolder,
                    classification.readerSupported,
                    classification.temporaryCandidate,
                    previous?.sha256,
                    previous?.quickHash,
                    generation,
                )
                count += 1
                bytes += child.size.coerceAtLeast(0L)
                publish(
                    IndexScanProgress(IndexScanState.SCANNING, count, bytes, relativePath, child.name),
                    force = false,
                )
                if (batch.size >= BATCH_SIZE) flush()
            }
        }

        return try {
            walk(root, "")
            flush()
            fileDao.deleteNotSeen(rootId, generation)
            publish(IndexScanProgress(IndexScanState.HASHING, count, bytes), force = true)
            val duplicateEntities = duplicateFinder.findExactDuplicates(rootId) { name ->
                publish(IndexScanProgress(IndexScanState.HASHING, count, bytes, currentFile = name), force = false)
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
            publish(IndexScanProgress(IndexScanState.COMPLETED, count, bytes), force = true)
            analysis
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            publish(_progress.value.copy(state = IndexScanState.CANCELLED), force = true)
            throw cancelled
        } catch (error: Throwable) {
            publish(_progress.value.copy(state = IndexScanState.FAILED), force = true)
            throw error
        }
    }

    fun load(root: DocumentFile): StorageAnalysis? {
        val rootId = root.uri.toString()
        val indexedRoot = rootDao.get(rootId)?.takeIf { it.lastScanCompleted > 0L } ?: return null
        return buildAnalysis(rootId, loadDuplicateGroups(rootId), indexedRoot.lastScanCompleted)
    }

    fun categoryEntries(root: DocumentFile, category: FileCategory, limit: Int = UI_QUERY_LIMIT): List<FileEntry> {
        val rootId = root.uri.toString()
        val entities = when (category) {
            FileCategory.Downloads -> fileDao.bySourceFolder(root.uri.toString(), "Загрузки", limit)
            FileCategory.Camera -> fileDao.bySourceFolder(root.uri.toString(), "Камера", limit)
            FileCategory.Books -> fileDao.books(root.uri.toString(), limit)
            else -> fileDao.byCategory(root.uri.toString(), category.name, limit)
        }
        return resolveAndPrune(rootId, entities)
    }

    fun temporaryEntries(root: DocumentFile, limit: Int = UI_QUERY_LIMIT): List<FileEntry> =
        resolveAndPrune(root.uri.toString(), fileDao.temporary(root.uri.toString(), limit))

    fun largeEntries(root: DocumentFile, limit: Int = UI_QUERY_LIMIT): List<FileEntry> =
        resolveAndPrune(root.uri.toString(), fileDao.largestAtLeast(root.uri.toString(), LARGE_FILE_BYTES, limit))

    fun recentEntries(root: DocumentFile, limit: Int = 500): List<FileEntry> =
        resolveAndPrune(root.uri.toString(), fileDao.recent(root.uri.toString(), limit))

    fun clear(root: DocumentFile) {
        fileDao.deleteRoot(root.uri.toString())
    }

    /** Keep recommendation queries in sync immediately after files are moved to Aura trash. */
    fun removeUris(root: DocumentFile, uris: Collection<Uri>) {
        if (uris.isEmpty()) return
        fileDao.deleteUris(root.uri.toString(), uris.map(Uri::toString))
    }

    /**
     * Atomically from the UI point of view replaces indexed metadata after a local mutation.
     * The old URI is removed first because many SAF providers change a document URI on rename.
     * Content hashes are retained only when the byte size is unchanged.
     */
    fun replaceEntries(root: DocumentFile, replacements: Collection<Pair<Uri, FileEntry>>) {
        if (replacements.isEmpty()) return
        val rootId = root.uri.toString()
        val generation = rootDao.get(rootId)?.scanGeneration ?: System.currentTimeMillis()
        val prepared = replacements.mapNotNull { (oldUri, entry) ->
            if (entry.isDirectory) return@mapNotNull null
            val previous = fileDao.byUri(rootId, oldUri.toString())
                ?: fileDao.byUri(rootId, entry.uri.toString())
            val classification = FileClassifier.classify(entry.name, entry.mimeType, entry.uri, entry.parentUri)
            IndexedFileEntity(
                rootId,
                entry.uri.toString(),
                (entry.parentUri ?: root.uri).toString(),
                entry.name,
                classification.extension,
                entry.mimeType,
                entry.size,
                entry.modifiedAt,
                classification.category.name,
                classification.sourceFolder,
                classification.readerSupported,
                classification.temporaryCandidate,
                previous?.sha256?.takeIf { previous.size == entry.size },
                previous?.quickHash?.takeIf { previous.size == entry.size },
                generation,
            )
        }
        fileDao.deleteUris(rootId, replacements.map { it.first.toString() }.distinct())
        if (prepared.isNotEmpty()) fileDao.upsertAll(prepared)
    }

    fun replaceEntry(root: DocumentFile, oldUri: Uri, entry: FileEntry) {
        replaceEntries(root, listOf(oldUri to entry))
    }

    private fun buildAnalysis(
        rootId: String,
        duplicateEntities: List<List<IndexedFileEntity>>,
        scannedAt: Long,
    ): StorageAnalysis {
        val categoryStats = fileDao.categoryAggregates(rootId).associateBy { it.category }
        val sourceStats = fileDao.sourceAggregates(rootId).associateBy { it.sourceFolder }
        val bookCount = fileDao.bookCount(rootId)
        val bookBytes = fileDao.bookBytes(rootId)
        val categories = FileCategory.entries.map { category ->
            val stat = when (category) {
                FileCategory.Downloads -> sourceStats["Загрузки"]?.let { it.count to it.bytes }
                FileCategory.Camera -> sourceStats["Камера"]?.let { it.count to it.bytes }
                FileCategory.Books -> bookCount to bookBytes
                else -> categoryStats[category.name]?.let { it.count to it.bytes }
            } ?: (0L to 0L)
            CategorySummary(category, stat.first.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), stat.second)
        }
        return StorageAnalysis(
            // This is only a small UI cache for Recent. Recommendation counters below are
            // deliberately queried from the complete Room index, so they cannot show stale
            // values merely because a matching file is outside this 2,000-item window.
            files = fileDao.recent(rootId, UI_CACHE_LIMIT).mapNotNull(::toEntryFast),
            totalBytes = fileDao.totalBytes(rootId),
            categories = categories,
            largeFiles = fileDao.largestAtLeast(rootId, LARGE_FILE_BYTES, 50).mapNotNull(::toEntryFast),
            largeFileCount = fileDao.largeCount(rootId, LARGE_FILE_BYTES).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            duplicateGroups = duplicateEntities.map { group -> group.mapNotNull(::toEntryFast) }.filter { it.size > 1 },
            temporaryFileCount = fileDao.temporaryCount(rootId).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            temporaryBytes = fileDao.temporaryBytes(rootId),
            limitReached = false,
            scannedAt = scannedAt,
        )
    }

    private fun loadDuplicateGroups(rootId: String): List<List<IndexedFileEntity>> =
        fileDao.exactDuplicateHashedFiles(rootId)
            .groupBy { entity -> "${entity.size}:${entity.sha256}" }
            .values
            .filter { it.size > 1 }
            .sortedByDescending { group -> group.first().size * (group.size - 1L) }

    private fun toEntryFast(entity: IndexedFileEntity): FileEntry? {
        val uri = Uri.parse(entity.uri)
        val document = FastDocumentListing.resolve(appContext, uri) ?: return null
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

    /** Drop rows whose SAF documents disappeared while Aura was in the background. */
    private fun resolveAndPrune(rootId: String, entities: List<IndexedFileEntity>): List<FileEntry> {
        val missing = ArrayList<String>()
        val entries = entities.mapNotNull { entity ->
            toEntryFast(entity) ?: run {
                missing += entity.uri
                null
            }
        }
        if (missing.isNotEmpty()) fileDao.deleteUris(rootId, missing)
        return entries
    }

    private fun publish(value: IndexScanProgress, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastProgressAt < PROGRESS_INTERVAL_MS) return
        lastProgressAt = now
        _progress.value = value
    }

    companion object {
        private const val BATCH_SIZE = 750
        private const val UI_CACHE_LIMIT = 2_000
        private const val UI_QUERY_LIMIT = 5_000
        private const val PROGRESS_INTERVAL_MS = 200L
        private const val TRASH_FOLDER = ".AuraTrash"
        private const val LARGE_FILE_BYTES = 50L * 1024L * 1024L
    }
}
