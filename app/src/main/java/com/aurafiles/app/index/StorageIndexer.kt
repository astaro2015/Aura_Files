package com.aurafiles.app.index

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.data.DocumentTreeSafety
import com.aurafiles.app.data.FastDocumentListing
import com.aurafiles.app.data.AuraVault
import com.aurafiles.app.model.CategorySummary
import com.aurafiles.app.model.FileCategory
import com.aurafiles.app.model.FileClassifier
import com.aurafiles.app.model.FileEntry
import com.aurafiles.app.model.FileSortMode
import com.aurafiles.app.model.ImageSourceFilter
import com.aurafiles.app.model.StorageAnalysis
import java.io.IOException
import java.util.ArrayDeque
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

internal fun isProtectedAndroidSharedPath(path: String): Boolean {
    if (path.isBlank()) return false
    val normalized = path.replace('\\', '/').trimEnd('/').lowercase()
    return normalized.endsWith("/android/data") ||
        normalized.contains("/android/data/") ||
        normalized.endsWith("/android/obb") ||
        normalized.contains("/android/obb/")
}

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
    var skippedProtectedDirectoryCount: Int = 0
        private set

    suspend fun scan(
        root: DocumentFile,
        showHidden: Boolean = false,
        showThumbnails: Boolean = false,
    ): StorageAnalysis {
        require(root.isDirectory) { "Корень индекса должен быть папкой" }
        skippedProtectedDirectoryCount = 0
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

        data class PendingDirectory(val directory: DocumentFile, val relativePath: String, val depth: Int)

        suspend fun walkTree() {
            DocumentTreeSafety.requireNotFilesystemSymlink(root, "Индексация")
            val visitedDirectories = mutableSetOf<String>()
            DocumentTreeSafety.requireUniqueDirectoryVisit(root, 0, visitedDirectories, "Индексация")
            val pending = ArrayDeque<PendingDirectory>()
            pending.addLast(PendingDirectory(root, "", 0))

            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val current = pending.removeLast()
                publish(
                    IndexScanProgress(
                        IndexScanState.SCANNING,
                        count,
                        bytes,
                        current.relativePath.ifBlank { "/" },
                        "",
                    ),
                    force = false,
                )
                val children = FastDocumentListing.listStrict(appContext, current.directory)
                for (child in children.asReversed()) {
                    currentCoroutineContext().ensureActive()
                    if (child.name == TRASH_FOLDER || AuraVault.isVaultFolder(child.name)) continue
                    if (DocumentTreeSafety.isFilesystemSymlink(child.document)) continue
                    val childPath = if (current.relativePath.isBlank()) {
                        child.name
                    } else {
                        "${current.relativePath}/${child.name}"
                    }
                    if (child.isDirectory) {
                        // Android intentionally blocks third-party apps from enumerating other apps'
                        // private external-storage trees even when MANAGE_EXTERNAL_STORAGE is granted.
                        // Treat only those well-known protected trees as out of scope for analysis.
                        // Do not swallow generic I/O failures here: a disconnected SD/USB volume must
                        // still fail the scan instead of producing a falsely "complete" index.
                        if (isProtectedAndroidSharedPath(child.uri.path.orEmpty())) {
                            skippedProtectedDirectoryCount += 1
                            continue
                        }
                        val depth = current.depth + 1
                        DocumentTreeSafety.requireUniqueDirectoryVisit(
                            child.document,
                            depth,
                            visitedDirectories,
                            "Индексация",
                        )
                        pending.addLast(PendingDirectory(child.document, childPath, depth))
                        continue
                    }

                    val classification = FileClassifier.classify(
                        child.name,
                        child.mimeType,
                        child.uri,
                        current.directory.uri,
                    )
                    val previous = oldHashes[child.uri.toString()]?.takeIf {
                        it.size == child.size && it.modifiedAt == child.modifiedAt
                    }
                    batch += IndexedFileEntity(
                        rootId,
                        child.uri.toString(),
                        current.directory.uri.toString(),
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
                    count = try {
                        Math.addExact(count, 1L)
                    } catch (_: ArithmeticException) {
                        throw IOException("Индексация: переполнение счётчика файлов")
                    }
                    bytes = try {
                        Math.addExact(bytes, child.size.coerceAtLeast(0L))
                    } catch (_: ArithmeticException) {
                        throw IOException("Индексация: суммарный размер слишком велик")
                    }
                    publish(
                        IndexScanProgress(IndexScanState.SCANNING, count, bytes, current.relativePath, child.name),
                        force = false,
                    )
                    if (batch.size >= BATCH_SIZE) flush()
                }
            }
        }

        return try {
            walkTree()
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
            val analysis = buildAnalysis(rootId, duplicateEntities, completedAt, showHidden, showThumbnails)
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

    fun load(
        root: DocumentFile,
        showHidden: Boolean = false,
        showThumbnails: Boolean = false,
    ): StorageAnalysis? {
        val rootId = root.uri.toString()
        val indexedRoot = rootDao.get(rootId)?.takeIf { it.lastScanCompleted > 0L } ?: return null
        return buildAnalysis(rootId, loadDuplicateGroups(rootId), indexedRoot.lastScanCompleted, showHidden, showThumbnails)
    }

    fun imageEntriesPage(
        root: DocumentFile,
        sourceFilter: ImageSourceFilter,
        query: String,
        sortMode: FileSortMode,
        ascending: Boolean,
        offset: Int,
        limit: Int = IMAGE_PAGE_SIZE,
        showHidden: Boolean = false,
        showThumbnails: Boolean = false,
    ): List<FileEntry> {
        val rootId = root.uri.toString()
        return resolveAndPrune(
            rootId,
            fileDao.visibleImagesPage(
                rootId,
                sourceFilter.name,
                query.trim(),
                sortMode.name,
                ascending,
                limit,
                offset,
                showHidden,
                showThumbnails,
            ),
        )
    }

    fun imageEntryCount(
        root: DocumentFile,
        sourceFilter: ImageSourceFilter,
        query: String,
        showHidden: Boolean = false,
        showThumbnails: Boolean = false,
    ): Int = fileDao.visibleImageCount(
        root.uri.toString(),
        sourceFilter.name,
        query.trim(),
        showHidden,
        showThumbnails,
    ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun categoryEntries(
        root: DocumentFile,
        category: FileCategory,
        limit: Int = UI_QUERY_LIMIT,
        showHidden: Boolean = false,
        showThumbnails: Boolean = false,
    ): List<FileEntry> {
        val rootId = root.uri.toString()
        val entities = when (category) {
            FileCategory.Downloads -> fileDao.visibleBySourceFolder(rootId, "Загрузки", limit, showHidden, showThumbnails)
            FileCategory.Camera -> fileDao.visibleBySourceFolder(rootId, "Камера", limit, showHidden, showThumbnails)
            FileCategory.Books -> fileDao.visibleBooks(rootId, limit, showHidden, showThumbnails)
            else -> fileDao.visibleByCategory(rootId, category.name, limit, showHidden, showThumbnails)
        }
        return resolveAndPrune(rootId, entities)
    }

    fun temporaryEntries(
        root: DocumentFile,
        limit: Int = UI_QUERY_LIMIT,
        showHidden: Boolean = false,
        showThumbnails: Boolean = false,
    ): List<FileEntry> = resolveAndPrune(
        root.uri.toString(),
        fileDao.visibleTemporary(root.uri.toString(), limit, showHidden, showThumbnails),
    )

    fun largeEntries(
        root: DocumentFile,
        limit: Int = UI_QUERY_LIMIT,
        showHidden: Boolean = false,
        showThumbnails: Boolean = false,
    ): List<FileEntry> = resolveAndPrune(
        root.uri.toString(),
        fileDao.visibleLargestAtLeast(root.uri.toString(), LARGE_FILE_BYTES, limit, showHidden, showThumbnails),
    )

    fun recentEntries(
        root: DocumentFile,
        limit: Int = 500,
        showHidden: Boolean = false,
        showThumbnails: Boolean = false,
    ): List<FileEntry> = resolveAndPrune(
        root.uri.toString(),
        fileDao.visibleRecent(root.uri.toString(), limit, showHidden, showThumbnails),
    )

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
        showHidden: Boolean,
        showThumbnails: Boolean,
    ): StorageAnalysis {
        val categoryStats = fileDao.visibleCategoryAggregates(rootId, showHidden, showThumbnails).associateBy { it.category }
        val sourceStats = fileDao.visibleSourceAggregates(rootId, showHidden, showThumbnails).associateBy { it.sourceFolder }
        val bookCount = fileDao.visibleBookCount(rootId, showHidden, showThumbnails)
        val bookBytes = fileDao.visibleBookBytes(rootId, showHidden, showThumbnails)
        val storageCategories = FileCategory.entries.map { category ->
            val stat = categoryStats[category.name]?.let { it.count to it.bytes } ?: (0L to 0L)
            CategorySummary(category, stat.first.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), stat.second)
        }
        val categories = FileCategory.entries.map { category ->
            val stat = when (category) {
                FileCategory.Downloads -> sourceStats["Загрузки"]?.let { it.count to it.bytes }
                FileCategory.Camera -> sourceStats["Камера"]?.let { it.count to it.bytes }
                FileCategory.Books -> bookCount to bookBytes
                else -> categoryStats[category.name]?.let { it.count to it.bytes }
            } ?: (0L to 0L)
            CategorySummary(category, stat.first.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), stat.second)
        }
        val visibleDuplicates = duplicateEntities.map { group ->
            group.filter { entity ->
                (showHidden || !entity.name.startsWith('.')) &&
                    (showThumbnails || entity.sourceFolder != "Миниатюры и кэш")
            }.mapNotNull(::toEntryFast)
        }.filter { it.size > 1 }
        return StorageAnalysis(
            // `files` remains a bounded Recent cache; all counters below come from the full Room index.
            files = fileDao.visibleRecent(rootId, UI_CACHE_LIMIT, showHidden, showThumbnails).mapNotNull(::toEntryFast),
            totalFileCount = fileDao.count(rootId).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            totalBytes = fileDao.totalBytes(rootId),
            categories = categories,
            storageCategories = storageCategories,
            largeFiles = fileDao.visibleLargestAtLeast(rootId, LARGE_FILE_BYTES, 50, showHidden, showThumbnails).mapNotNull(::toEntryFast),
            largeFileCount = fileDao.visibleLargeCount(rootId, LARGE_FILE_BYTES, showHidden, showThumbnails)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            duplicateGroups = visibleDuplicates,
            temporaryFileCount = fileDao.visibleTemporaryCount(rootId, showHidden, showThumbnails)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            temporaryBytes = fileDao.visibleTemporaryBytes(rootId, showHidden, showThumbnails),
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
        const val IMAGE_PAGE_SIZE = 400
        private const val UI_QUERY_LIMIT = 5_000
        private const val PROGRESS_INTERVAL_MS = 200L
        private const val TRASH_FOLDER = ".AuraTrash"
        private const val LARGE_FILE_BYTES = 50L * 1024L * 1024L
    }
}
