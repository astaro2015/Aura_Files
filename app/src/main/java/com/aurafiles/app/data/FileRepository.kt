package com.aurafiles.app.data

import com.aurafiles.app.AuraFileProvider
import com.aurafiles.app.util.toHexString

import android.content.Context
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.storage.StorageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.model.FileEntry
import com.aurafiles.app.model.DeleteAnimationMode
import com.aurafiles.app.model.FileCategory
import com.aurafiles.app.model.CategorySummary
import com.aurafiles.app.model.StorageSnapshot
import com.aurafiles.app.model.StorageAnalysis
import com.aurafiles.app.model.StorageVolumeInfo
import com.aurafiles.app.model.StorageAccessMode
import com.aurafiles.app.model.TrashRecord
import com.aurafiles.app.model.matchesCategory
import com.aurafiles.app.model.isTemporaryCandidate
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.net.URLConnection
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.ArrayDeque
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal const val AURA_TRASH_FOLDER = ".AuraTrash"

class FileRepository(private val context: Context) {
    private val resolver = context.contentResolver
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val vault = AuraVault(context)

    fun restoreRoot(): DocumentFile? {
        if (currentAccessMode() == StorageAccessMode.Full) {
            return if (hasFullAccess()) fullAccessRoot() else null
        }
        val rawUri = preferences.getString(KEY_ROOT_URI, null) ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(rawUri))?.takeIf { it.exists() }
    }

    fun currentAccessMode(): StorageAccessMode {
        return if (preferences.getString(KEY_ACCESS_MODE, null) == StorageAccessMode.Full.name) {
            StorageAccessMode.Full
        } else {
            StorageAccessMode.Folder
        }
    }

    fun hasFullAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    @Suppress("DEPRECATION")
    fun attachFullRoot(): DocumentFile {
        require(hasFullAccess()) { "Полный доступ не разрешён в настройках Android" }
        val root = fullAccessRoot()
        preferences.edit().putString(KEY_ACCESS_MODE, StorageAccessMode.Full.name).apply()
        return root
    }

    @Suppress("DEPRECATION")
    private fun fullAccessRoot(): DocumentFile {
        val directory = Environment.getExternalStorageDirectory()
        require(directory.exists() && directory.canRead()) { "Общая память устройства недоступна" }
        return DocumentFile.fromFile(directory)
    }

    fun attachRoot(uri: Uri): DocumentFile {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val previousUri = preferences.getString(KEY_ROOT_URI, null)?.let(Uri::parse)
        resolver.takePersistableUriPermission(uri, flags)
        val root = requireNotNull(DocumentFile.fromTreeUri(context, uri)) {
            "Не удалось открыть выбранную папку"
        }
        preferences.edit()
            .putString(KEY_ROOT_URI, uri.toString())
            .putString(KEY_ACCESS_MODE, StorageAccessMode.Folder.name)
            .apply()
        if (previousUri != null && previousUri != uri) {
            runCatching { resolver.releasePersistableUriPermission(previousUri, flags) }
        }
        return root
    }

    fun listChildren(directory: DocumentFile): List<FileEntry> {
        DocumentTreeSafety.requireNotFilesystemSymlink(directory, "Открытие папки")
        return FastDocumentListing.list(context, directory)
            .asSequence()
            .filterNot { it.name == TRASH_FOLDER || AuraVault.isVaultFolder(it.name) }
            .map { info ->
                FileEntry(
                    document = info.document,
                    name = info.name,
                    uri = info.uri,
                    isDirectory = info.isDirectory,
                    mimeType = info.mimeType,
                    size = info.size,
                    modifiedAt = info.modifiedAt,
                    parentUri = directory.uri,
                )
            }
            .sortedWith(
                compareByDescending<FileEntry> { it.isDirectory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
            .toList()
    }

    fun createFolder(parent: DocumentFile, requestedName: String): DocumentFile {
        val name = requestedName.trim()
        require(name.isNotEmpty()) { "Введите название папки" }
        require(!AuraVault.isVaultFolder(name) && name != TRASH_FOLDER) { "Это имя зарезервировано Aura Files" }
        require(parent.findFile(name) == null) { "Папка с таким названием уже существует" }
        return requireNotNull(parent.createDirectory(name)) { "Не удалось создать папку" }
    }

    fun rename(entry: FileEntry, requestedName: String): Uri {
        val name = requestedName.trim()
        require(name.isNotEmpty()) { "Введите новое название" }
        require(!AuraVault.isVaultFolder(name) && name != TRASH_FOLDER) { "Это имя зарезервировано Aura Files" }
        val oldUri = entry.uri
        require(entry.document.renameTo(name)) { "Не удалось переименовать объект" }
        replaceFavoriteUri(oldUri, entry.document.uri)
        return entry.document.uri
    }

    fun delete(entry: FileEntry) {
        val parentUri = requireNotNull(entry.parentUri) { "Не удалось определить родительскую папку ${entry.name}" }
        when (deleteDocumentAndProbe(entry.document, parentUri)) {
            LocalDeleteOutcome.COMMITTED -> Unit
            LocalDeleteOutcome.NOT_COMMITTED -> throw IOException("Не удалось удалить ${entry.name}")
            LocalDeleteOutcome.AMBIGUOUS -> throw IOException(
                "Состояние ${entry.name} после удаления неизвестно; Aura не выполняет дополнительное удаление"
            )
        }
    }

    fun moveToTrash(root: DocumentFile, entry: FileEntry): TrashRecord {
        val originalParentUri = requireNotNull(entry.parentUri) {
            "Не удалось определить исходную папку"
        }
        val trash = trashDirectory(root, create = true)
            ?: throw IOException("Не удалось создать корзину")
        val moved = if (findUniqueChildStrict(trash, entry.name) == null) {
            tryFastMove(entry.document, originalParentUri, trash)
        } else null
        val trashed = moved ?: run {
            val copy = copyDocument(entry.document, trash)
            when (deleteDocumentAndProbe(entry.document, originalParentUri)) {
                LocalDeleteOutcome.COMMITTED -> copy
                LocalDeleteOutcome.NOT_COMMITTED -> {
                    rollbackSafetyCopy(trash, copy)
                    throw IOException("Не удалось переместить ${entry.name} в корзину; исходник сохранён")
                }
                LocalDeleteOutcome.AMBIGUOUS -> {
                    // Do not destroy the completed trash copy: the provider may have deleted the
                    // source and only lost the response. Persist the known original mapping before
                    // reporting ambiguity; if persistence itself is interrupted, listTrash() still
                    // recovers the physical orphan conservatively.
                    val safetyRecord = TrashRecord(
                        entry = copy.toEntry(trash.uri),
                        originalParentUri = originalParentUri,
                        originalName = entry.name,
                        deletedAt = System.currentTimeMillis(),
                        originalUri = entry.uri,
                        size = entry.size,
                    )
                    saveTrashMetadata(loadTrashMetadata() + safetyRecord.toStoredTrashRecord())
                    throw IOException(
                        "Состояние ${entry.name} после копирования в корзину неизвестно; " +
                            "страховочная копия и путь восстановления сохранены"
                    )
                }
            }
        }
        val record = TrashRecord(
            entry = trashed.toEntry(trash.uri),
            originalParentUri = originalParentUri,
            originalName = entry.name,
            deletedAt = System.currentTimeMillis(),
            originalUri = entry.uri,
            size = entry.size,
        )
        saveTrashMetadata(loadTrashMetadata() + record.toStoredTrashRecord())
        return record
    }

    fun listTrash(root: DocumentFile): List<TrashRecord> {
        val trash = trashDirectory(root, create = false) ?: return emptyList()
        val documents = FastDocumentListing.listStrict(context, trash)
        val byIdentity = documents.associateBy { DocumentTreeSafety.identityKey(it.uri) }
        val metadata = loadTrashMetadata()
        val matchedKeys = mutableSetOf<String>()
        val records = mutableListOf<TrashRecord>()
        val normalizedMetadata = mutableListOf<StoredTrashRecord>()

        metadata.forEach { stored ->
            val key = DocumentTreeSafety.identityKey(stored.uri)
            val info = byIdentity[key] ?: return@forEach
            matchedKeys += key
            normalizedMetadata += stored
            records += TrashRecord(
                entry = FileEntry(
                    document = info.document,
                    name = info.name,
                    uri = info.uri,
                    isDirectory = info.isDirectory,
                    mimeType = info.mimeType,
                    size = info.size,
                    modifiedAt = info.modifiedAt,
                    parentUri = trash.uri,
                ),
                originalParentUri = stored.originalParentUri,
                originalName = stored.originalName.ifBlank { info.name },
                deletedAt = stored.deletedAt.takeIf { it > 0L } ?: info.modifiedAt.takeIf { it > 0L } ?: 0L,
                originalUri = stored.originalUri,
                size = stored.size.takeIf { it > 0L } ?: info.size,
            )
        }

        // A process/power loss can happen after a physical move/copy and before metadata commit.
        // Surface such objects rather than leaving invisible data in .AuraTrash. The original
        // parent is unknowable, so restore falls back to the attached root.
        documents.forEach { info ->
            val key = DocumentTreeSafety.identityKey(info.uri)
            if (key in matchedKeys) return@forEach
            val recovered = StoredTrashRecord(
                uri = info.uri,
                originalParentUri = root.uri,
                originalName = info.name,
                deletedAt = info.modifiedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                originalUri = null,
                size = info.size,
            )
            normalizedMetadata += recovered
            records += TrashRecord(
                entry = FileEntry(
                    document = info.document,
                    name = info.name,
                    uri = info.uri,
                    isDirectory = info.isDirectory,
                    mimeType = info.mimeType,
                    size = info.size,
                    modifiedAt = info.modifiedAt,
                    parentUri = trash.uri,
                ),
                originalParentUri = root.uri,
                originalName = info.name,
                deletedAt = recovered.deletedAt,
                originalUri = null,
                size = info.size,
            )
        }

        if (normalizedMetadata != metadata) saveTrashMetadata(normalizedMetadata)
        return records.sortedByDescending(TrashRecord::deletedAt)
    }

    fun restoreFromTrash(root: DocumentFile, record: TrashRecord): FileEntry {
        val trash = trashDirectory(root, create = false)
            ?: throw IOException("Корзина недоступна")
        val trashIdentity = DocumentTreeSafety.identityKey(trash.uri)
        val actualParentIdentity = record.entry.parentUri?.let(DocumentTreeSafety::identityKey)
        if (actualParentIdentity != null && actualParentIdentity != trashIdentity) {
            throw IOException("Источник восстановления больше не находится в корзине")
        }
        val originalParent = documentFromUri(record.originalParentUri)
            ?.takeIf { it.exists() && it.isDirectory && it.canWrite() }
            ?.also { DocumentTreeSafety.requireNotFilesystemSymlink(it, "Восстановление из корзины") }
            ?: root.also { DocumentTreeSafety.requireNotFilesystemSymlink(it, "Восстановление из корзины") }
        val moved = if (findUniqueChildStrict(originalParent, record.originalName) == null) {
            tryFastMove(record.entry.document, trash.uri, originalParent)
        } else null
        val restored = moved ?: run {
            val copy = copyDocument(record.entry.document, originalParent, record.originalName)
            when (deleteDocumentAndProbe(record.entry.document, trash.uri)) {
                LocalDeleteOutcome.COMMITTED -> copy
                LocalDeleteOutcome.NOT_COMMITTED -> {
                    rollbackSafetyCopy(originalParent, copy)
                    throw IOException("Не удалось удалить исходник из корзины; восстановленная копия отменена")
                }
                LocalDeleteOutcome.AMBIGUOUS -> {
                    // Keep both copies and metadata. Removing the restored file here could delete
                    // the only surviving bytes if the trash delete actually committed.
                    throw IOException(
                        "Состояние файла в корзине после восстановления неизвестно; " +
                            "восстановленная копия сохранена"
                    )
                }
            }
        }
        removeTrashRecord(record.entry.uri)
        return restored.toEntry(originalParent.uri)
    }

    fun permanentlyDelete(record: TrashRecord) {
        val parentUri = requireNotNull(record.entry.parentUri) { "Не удалось определить корзину" }
        when (deleteDocumentAndProbe(record.entry.document, parentUri)) {
            LocalDeleteOutcome.COMMITTED -> removeTrashRecord(record.entry.uri)
            LocalDeleteOutcome.NOT_COMMITTED -> throw IOException("Не удалось удалить ${record.entry.name}")
            LocalDeleteOutcome.AMBIGUOUS -> throw IOException(
                "Состояние ${record.entry.name} после удаления неизвестно; запись корзины сохранена"
            )
        }
    }

    fun emptyTrash(root: DocumentFile) {
        val trash = trashDirectory(root, create = false) ?: return
        val children = FastDocumentListing.listStrict(context, trash)
        var metadata = loadTrashMetadata()
        children.forEach { child ->
            when (deleteDocumentAndProbe(child.document, trash.uri)) {
                LocalDeleteOutcome.COMMITTED -> {
                    val childIdentity = DocumentTreeSafety.identityKey(child.uri)
                    val updated = metadata.filterNot { DocumentTreeSafety.identityKey(it.uri) == childIdentity }
                    if (updated != metadata) {
                        saveTrashMetadata(updated)
                        metadata = updated
                    }
                }
                LocalDeleteOutcome.NOT_COMMITTED -> throw IOException("Не удалось удалить ${child.name}")
                LocalDeleteOutcome.AMBIGUOUS -> throw IOException(
                    "Состояние ${child.name} после удаления неизвестно; очистка корзины остановлена"
                )
            }
        }
    }

    /**
     * Legacy favourites used to be URI bookmarks. Since 1.3.1 the visible vault (now "Сейф") is a
     * device-bound encrypted vault, so normal storage entries are never considered already
     * favourited: moving a file there removes the original.
     */
    fun favoriteUris(): Set<Uri> = emptySet()

    fun moveToFavorite(entry: FileEntry): FileEntry {
        require(!entry.isDirectory) { "Папки пока нельзя помещать в Сейф" }
        val moved = vault.moveInto(entry)
        // Old URI bookmarks are intentionally discarded only after the new vault is actually
        // used; upgrading the app never moves a user's files without an explicit action.
        preferences.edit().remove(KEY_FAVORITES).apply()
        return vault.toFileEntry(moved)
    }

    fun moveToFavorites(entries: List<FileEntry>): List<FileEntry> {
        require(entries.isNotEmpty()) { "Нет файлов для перемещения" }
        require(entries.none(FileEntry::isDirectory)) {
            "Папки пока нельзя помещать в Сейф"
        }
        entries.forEach(::moveToFavorite)
        return favoriteEntries()
    }

    /** Compatibility entry point for older UI code; "toggle" now always means move into vault. */
    fun toggleFavorites(entries: List<FileEntry>): Set<Uri> {
        moveToFavorites(entries)
        return emptySet()
    }

    private fun replaceFavoriteUri(oldUri: Uri, newUri: Uri) = Unit

    fun favoriteEntries(): List<FileEntry> = vault.list().items.map(vault::toFileEntry)

    fun vaultUnreadableCount(): Int = vault.list().unreadableCount

    fun showHiddenFiles(): Boolean = preferences.getBoolean(KEY_SHOW_HIDDEN, false)

    fun setShowHiddenFiles(value: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_HIDDEN, value).apply()
    }

    fun showThumbnailFiles(): Boolean = preferences.getBoolean(KEY_SHOW_THUMBNAIL_FILES, false)

    fun setShowThumbnailFiles(value: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_THUMBNAIL_FILES, value).apply()
    }

    fun showGridThumbnails(): Boolean = preferences.getBoolean(KEY_GRID_THUMBNAILS, true)

    fun setShowGridThumbnails(value: Boolean) {
        preferences.edit().putBoolean(KEY_GRID_THUMBNAILS, value).apply()
    }

    fun showFavoritesOnHome(): Boolean = preferences.getBoolean(KEY_FAVORITES_HOME, true)

    fun setShowFavoritesOnHome(value: Boolean) {
        preferences.edit().putBoolean(KEY_FAVORITES_HOME, value).apply()
    }

    fun deleteAnimationMode(): DeleteAnimationMode {
        val raw = preferences.getString(KEY_DELETE_ANIMATION, DeleteAnimationMode.Dissolve.name)
        // 0.14.18 removed the old Fast mode. A legacy stored "Fast" value simply falls back to
        // Dissolve; write it back once so the preference is clean for future launches.
        val parsed = runCatching { DeleteAnimationMode.valueOf(raw.orEmpty()) }.getOrDefault(DeleteAnimationMode.Dissolve)
        if (raw != parsed.name) preferences.edit().putString(KEY_DELETE_ANIMATION, parsed.name).apply()
        return parsed
    }

    fun setDeleteAnimationMode(value: DeleteAnimationMode) {
        preferences.edit().putString(KEY_DELETE_ANIMATION, value.name).apply()
    }

    fun recoverPendingBatchRename(): String? = synchronized(BATCH_RENAME_LOCK) {
        val journal = loadBatchRenameJournal() ?: return@synchronized null
        val initialPhase = journal.phase
        val store = RepositoryBatchRenameNameStore()
        var latest = journal
        val persist: (BatchRenameJournal) -> Unit = { state ->
            persistBatchRenameJournal(state)
            latest = state
        }
        try {
            latest = when (latest.phase) {
                BatchRenamePhase.PREPARE,
                BatchRenamePhase.FINALIZE -> BatchRenameJournalEngine.continueForward(latest, store, persist)
                BatchRenamePhase.ROLLBACK_STAGE,
                BatchRenamePhase.ROLLBACK_RESTORE -> BatchRenameJournalEngine.continueRollback(latest, store, persist)
            }
            finishRecoveredBatchRename(latest)
            when (initialPhase) {
                BatchRenamePhase.PREPARE,
                BatchRenamePhase.FINALIZE -> "Незавершённое пакетное переименование завершено после перезапуска"
                BatchRenamePhase.ROLLBACK_STAGE,
                BatchRenamePhase.ROLLBACK_RESTORE -> "Откат незавершённого пакетного переименования завершён"
            }
        } catch (forwardError: Exception) {
            if (latest.phase == BatchRenamePhase.ROLLBACK_STAGE || latest.phase == BatchRenamePhase.ROLLBACK_RESTORE) {
                throw forwardError
            }
            // If continuing the requested operation is no longer safe, switch durably to rollback.
            // beginRollback records the exact forward phase/step so a crash during rollback can
            // still identify which visible name belongs to each selected object.
            val persisted = loadBatchRenameJournal() ?: latest
            latest = BatchRenameJournalEngine.beginRollback(persisted, persist)
            try {
                latest = BatchRenameJournalEngine.continueRollback(latest, store, persist)
                verifyBatchRenameOriginals(latest)
                require(preferences.edit().putStringSet(KEY_FAVORITES, latest.favoriteUris).commit()) {
                    "Не удалось восстановить состояние после отката пакетного переименования"
                }
                clearBatchRenameIndexReconcileMarker()
                clearBatchRenameJournal()
                "Незавершённое пакетное переименование безопасно отменено после перезапуска"
            } catch (rollbackError: Exception) {
                throw IOException(
                    "Не удалось ни завершить, ни безопасно откатить пакетное переименование; журнал сохранён",
                    rollbackError,
                ).also { it.addSuppressed(forwardError) }
            }
        }
    }

    fun batchRename(entries: List<FileEntry>, newNames: List<String>): List<Uri> = synchronized(BATCH_RENAME_LOCK) {
        // A journal left by a killed process always has priority over a new destructive action.
        recoverPendingBatchRename()
        batchRenameLocked(entries, newNames)
    }

    private fun batchRenameLocked(entries: List<FileEntry>, newNames: List<String>): List<Uri> {
        require(entries.isNotEmpty() && entries.size == newNames.size) { "Некорректный список имён" }
        require(entries.size <= BATCH_RENAME_MAX_ENTRIES) { "Слишком много объектов для одного пакетного переименования" }
        val cleaned = newNames.map(String::trim)
        require(cleaned.none(String::isBlank)) { "Новое имя не может быть пустым" }
        require(cleaned.none { AuraVault.isVaultFolder(it) || it == TRASH_FOLDER }) { "Это имя зарезервировано Aura Files" }
        require(cleaned.distinctBy(String::lowercase).size == cleaned.size) { "Новые имена повторяются" }

        val parentUris = entries.map { entry ->
            requireNotNull(entry.parentUri) { "Не удалось определить папку для ${entry.name}" }
        }
        val parents = parentUris.distinct().associateWith { parentUri ->
            requireNotNull(documentFromUri(parentUri)?.takeIf { it.exists() && it.isDirectory && it.canWrite() }) {
                "Не удалось открыть папку для пакетного переименования"
            }
        }

        entries.indices.groupBy { parentUris[it] }.forEach { (parentUri, indexes) ->
            val parent = requireNotNull(parents[parentUri])
            val selectedUris = indexes.map { entries[it].uri }.toSet()
            val originalNames = indexes.map { entries[it].name }
            require(originalNames.distinctBy(String::lowercase).size == originalNames.size) {
                "В папке есть выбранные объекты с одинаковыми именами; безопасное восстановление невозможно"
            }
            indexes.forEach { index ->
                val collision = parent.findFile(cleaned[index])
                require(collision == null || collision.uri in selectedUris) {
                    "Имя ${cleaned[index]} уже занято"
                }
            }
        }

        val reservedByParent = mutableMapOf<Uri, MutableSet<String>>()
        entries.indices.forEach { index ->
            val reserved = reservedByParent.getOrPut(parentUris[index]) { linkedSetOf() }
            reserved += entries[index].name.lowercase()
            reserved += cleaned[index].lowercase()
        }
        val items = entries.indices.map { index ->
            val parentUri = parentUris[index]
            val parent = requireNotNull(parents[parentUri])
            val reserved = requireNotNull(reservedByParent[parentUri])
            BatchRenameJournalItem(
                parentKey = parentUri.toString(),
                originalUri = entries[index].uri.toString(),
                originalName = entries[index].name,
                temporaryName = allocateBatchRenameServiceName(parent, ".aura-rename-", reserved),
                targetName = cleaned[index],
                rollbackName = allocateBatchRenameServiceName(parent, ".aura-rollback-", reserved),
            )
        }
        var latest = BatchRenameJournal(
            operationId = UUID.randomUUID().toString(),
            phase = BatchRenamePhase.PREPARE,
            step = 0,
            items = items,
            favoriteUris = favoriteUris().map(Uri::toString).toSet(),
        )
        // Complete plan, original URIs and all unique service names are fsync'd before mutation #1.
        persistBatchRenameJournal(latest)
        val store = RepositoryBatchRenameNameStore()
        val persist: (BatchRenameJournal) -> Unit = { state ->
            persistBatchRenameJournal(state)
            latest = state
        }

        try {
            latest = BatchRenameJournalEngine.continueForward(latest, store, persist)
            val finalUris = verifyBatchRenameFinal(latest)
            val replacements = latest.items.map(BatchRenameJournalItem::originalUri).zip(finalUris.map(Uri::toString)).toMap()
            val updatedFavorites = latest.favoriteUris.map { replacements[it] ?: it }.toSet()
            require(preferences.edit().putStringSet(KEY_FAVORITES, updatedFavorites).commit()) {
                "Не удалось синхронно сохранить состояние пакетного переименования"
            }
            persistBatchRenameIndexReconcileMarker(latest.operationId)
            clearBatchRenameJournal()
            return finalUris
        } catch (error: Exception) {
            val rollbackFailure = runCatching {
                val persisted = loadBatchRenameJournal() ?: latest
                latest = if (persisted.phase == BatchRenamePhase.PREPARE || persisted.phase == BatchRenamePhase.FINALIZE) {
                    BatchRenameJournalEngine.beginRollback(persisted, persist)
                } else {
                    persisted
                }
                latest = BatchRenameJournalEngine.continueRollback(latest, store, persist)
                verifyBatchRenameOriginals(latest)
                require(preferences.edit().putStringSet(KEY_FAVORITES, latest.favoriteUris).commit()) {
                    "Не удалось восстановить состояние после отката пакетного переименования"
                }
                clearBatchRenameIndexReconcileMarker()
                clearBatchRenameJournal()
            }.exceptionOrNull()
            if (rollbackFailure != null) {
                throw IOException(
                    buildString {
                        append(error.message ?: "Пакетное переименование не завершено")
                        append(". Автоматическое восстановление тоже не завершено; журнал сохранён: ")
                        append(rollbackFailure.message ?: rollbackFailure.javaClass.simpleName)
                    },
                    error,
                )
            }
            throw error
        }
    }

    private fun finishRecoveredBatchRename(journal: BatchRenameJournal) {
        when (journal.phase) {
            BatchRenamePhase.FINALIZE -> {
                val finalUris = verifyBatchRenameFinal(journal)
                val replacements = journal.items.map(BatchRenameJournalItem::originalUri)
                    .zip(finalUris.map(Uri::toString))
                    .toMap()
                val updatedFavorites = journal.favoriteUris.map { replacements[it] ?: it }.toSet()
                require(preferences.edit().putStringSet(KEY_FAVORITES, updatedFavorites).commit()) {
                    "Не удалось синхронно сохранить восстановленное пакетное переименование"
                }
                persistBatchRenameIndexReconcileMarker(journal.operationId)
            }
            BatchRenamePhase.ROLLBACK_RESTORE -> {
                verifyBatchRenameOriginals(journal)
                require(preferences.edit().putStringSet(KEY_FAVORITES, journal.favoriteUris).commit()) {
                    "Не удалось восстановить состояние после отката пакетного переименования"
                }
                clearBatchRenameIndexReconcileMarker()
            }
            BatchRenamePhase.PREPARE,
            BatchRenamePhase.ROLLBACK_STAGE -> throw IOException("Журнал остановился в незавершённой фазе ${journal.phase}")
        }
        clearBatchRenameJournal()
    }

    private fun verifyBatchRenameOriginals(journal: BatchRenameJournal) {
        require(journal.phase == BatchRenamePhase.ROLLBACK_RESTORE && journal.step == journal.items.size) {
            "Откат пакетного переименования не завершён"
        }
        journal.items.forEach { item ->
            val original = findUniqueJournalDocument(item.parentKey, item.originalName)
            require(original != null) { "Не удалось подтвердить исходный объект ${item.originalName}" }
            require(findUniqueJournalDocument(item.parentKey, item.temporaryName) == null) {
                "Остался временный объект ${item.temporaryName}"
            }
            require(findUniqueJournalDocument(item.parentKey, item.rollbackName) == null) {
                "Остался страховочный объект ${item.rollbackName}"
            }
        }
    }

    private fun verifyBatchRenameFinal(journal: BatchRenameJournal): List<Uri> {
        require(journal.phase == BatchRenamePhase.FINALIZE && journal.step == journal.items.size) {
            "Пакетное переименование не дошло до подтверждённого финала"
        }
        return journal.items.map { item ->
            val target = requireNotNull(findUniqueJournalDocument(item.parentKey, item.targetName)) {
                "Не удалось подтвердить итоговое имя ${item.targetName}"
            }
            require(findUniqueJournalDocument(item.parentKey, item.temporaryName) == null) {
                "Остался временный объект ${item.temporaryName}"
            }
            require(findUniqueJournalDocument(item.parentKey, item.rollbackName) == null) {
                "Остался страховочный объект ${item.rollbackName}"
            }
            target.uri
        }
    }

    private inner class RepositoryBatchRenameNameStore : BatchRenameNameStore {
        override fun exists(parentKey: String, name: String): Boolean =
            findUniqueJournalDocument(parentKey, name) != null

        override fun rename(parentKey: String, fromName: String, toName: String): Boolean {
            if (fromName == toName) return true
            val source = findUniqueJournalDocument(parentKey, fromName) ?: return false
            if (findUniqueJournalDocument(parentKey, toName) != null) return false
            return source.renameTo(toName)
        }
    }

    private fun findUniqueJournalDocument(parentKey: String, name: String): DocumentFile? {
        val parent = requireNotNull(documentFromUri(Uri.parse(parentKey))?.takeIf { it.exists() && it.isDirectory && it.canWrite() }) {
            "Папка незавершённого переименования недоступна: $parentKey"
        }
        val matches = FastDocumentListing.list(context, parent).filter { it.name == name }
        require(matches.size <= 1) { "Имя $name неоднозначно: найдено объектов ${matches.size}" }
        return matches.singleOrNull()?.document
    }

    private fun allocateBatchRenameServiceName(
        parent: DocumentFile,
        prefix: String,
        reservedLowercase: MutableSet<String>,
    ): String {
        repeat(BATCH_RENAME_SERVICE_NAME_ATTEMPTS) {
            val candidate = "$prefix${UUID.randomUUID()}"
            val key = candidate.lowercase()
            if (key !in reservedLowercase && parent.findFile(candidate) == null) {
                reservedLowercase += key
                return candidate
            }
        }
        throw IOException("Не удалось подобрать безопасное служебное имя для пакетного переименования")
    }

    private fun batchRenameJournalFile(): File = File(context.noBackupFilesDir, BATCH_RENAME_JOURNAL_FILE)

    private fun batchRenameIndexReconcileMarkerFile(): File =
        File(context.noBackupFilesDir, BATCH_RENAME_INDEX_RECONCILE_FILE)

    /**
     * True after file names are durably committed but before the index/cache layer has
     * acknowledged those URI changes. The marker deliberately survives process death.
     */
    fun hasPendingBatchRenameIndexReconciliation(): Boolean {
        val file = batchRenameIndexReconcileMarkerFile()
        return file.exists() || File(file.path + ".bak").exists()
    }

    /** Call only after the stale URI index has been updated or safely discarded. */
    fun confirmBatchRenameIndexReconciled() {
        AtomicFile(batchRenameIndexReconcileMarkerFile()).delete()
    }

    private fun persistBatchRenameIndexReconcileMarker(operationId: String) {
        val atomic = AtomicFile(batchRenameIndexReconcileMarkerFile())
        val output = atomic.startWrite()
        try {
            output.write(
                JSONObject()
                    .put("version", 1)
                    .put("operationId", operationId)
                    .put("committedAt", System.currentTimeMillis())
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            )
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun clearBatchRenameIndexReconcileMarker() {
        AtomicFile(batchRenameIndexReconcileMarkerFile()).delete()
    }

    private fun persistBatchRenameJournal(journal: BatchRenameJournal) {
        val atomic = AtomicFile(batchRenameJournalFile())
        val output = atomic.startWrite()
        try {
            output.write(journal.toJson().toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun loadBatchRenameJournal(): BatchRenameJournal? {
        val file = batchRenameJournalFile()
        val backup = File(file.path + ".bak")
        if (!file.exists() && !backup.exists()) return null
        val raw = try {
            AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (error: Throwable) {
            throw IOException("Не удалось прочитать журнал пакетного переименования", error)
        }
        return try {
            batchRenameJournalFromJson(JSONObject(raw))
        } catch (error: Throwable) {
            throw IOException("Журнал пакетного переименования повреждён; автоматические действия остановлены", error)
        }
    }

    private fun clearBatchRenameJournal() {
        AtomicFile(batchRenameJournalFile()).delete()
    }

    private fun BatchRenameJournal.toJson(): JSONObject = JSONObject()
        .put("version", BATCH_RENAME_JOURNAL_VERSION)
        .put("operationId", operationId)
        .put("phase", phase.name)
        .put("step", step)
        .put("rollbackSourcePhase", rollbackSourcePhase?.name.orEmpty())
        .put("rollbackSourceStep", rollbackSourceStep)
        .put("favoriteUris", JSONArray().apply { favoriteUris.forEach { put(it) } })
        .put("items", JSONArray().apply {
            items.forEach { item ->
                put(
                    JSONObject()
                        .put("parentKey", item.parentKey)
                        .put("originalUri", item.originalUri)
                        .put("originalName", item.originalName)
                        .put("temporaryName", item.temporaryName)
                        .put("targetName", item.targetName)
                        .put("rollbackName", item.rollbackName)
                )
            }
        })

    private fun batchRenameJournalFromJson(json: JSONObject): BatchRenameJournal {
        require(json.getInt("version") == BATCH_RENAME_JOURNAL_VERSION) { "Неподдерживаемая версия журнала" }
        val operationId = json.getString("operationId")
        require(runCatching { UUID.fromString(operationId) }.isSuccess) { "Некорректный ID журнала" }
        val array = json.getJSONArray("items")
        require(array.length() in 1..BATCH_RENAME_MAX_ENTRIES) { "Некорректное число записей журнала" }
        val items = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    BatchRenameJournalItem(
                        parentKey = item.getString("parentKey"),
                        originalUri = item.getString("originalUri"),
                        originalName = item.getString("originalName"),
                        temporaryName = item.getString("temporaryName"),
                        targetName = item.getString("targetName"),
                        rollbackName = item.getString("rollbackName"),
                    )
                )
            }
        }
        validateBatchRenameJournalItems(items)
        val phase = BatchRenamePhase.valueOf(json.getString("phase"))
        val step = json.getInt("step")
        require(step in 0..items.size) { "Некорректный шаг журнала" }
        val rollbackSourcePhase = json.optString("rollbackSourcePhase").takeIf(String::isNotBlank)?.let { BatchRenamePhase.valueOf(it) }
        val rollbackSourceStep = json.optInt("rollbackSourceStep", 0)
        if (phase == BatchRenamePhase.ROLLBACK_STAGE || phase == BatchRenamePhase.ROLLBACK_RESTORE) {
            require(rollbackSourcePhase == BatchRenamePhase.PREPARE || rollbackSourcePhase == BatchRenamePhase.FINALIZE) {
                "В журнале отката отсутствует исходная фаза"
            }
            require(rollbackSourceStep in 0..items.size) { "Некорректный исходный шаг отката" }
        }
        val favoriteUris = json.optJSONArray("favoriteUris")?.let { favorites ->
            buildSet {
                for (index in 0 until favorites.length()) add(favorites.getString(index))
            }
        }.orEmpty()
        return BatchRenameJournal(
            operationId = operationId,
            phase = phase,
            step = step,
            items = items,
            favoriteUris = favoriteUris,
            rollbackSourcePhase = rollbackSourcePhase,
            rollbackSourceStep = rollbackSourceStep,
        )
    }

    private fun validateBatchRenameJournalItems(items: List<BatchRenameJournalItem>) {
        require(items.map { it.originalUri }.distinct().size == items.size) { "URI записей журнала повторяются" }
        items.forEach { item ->
            require(item.parentKey.isNotBlank() && item.originalUri.isNotBlank())
            require(item.originalName.isNotBlank() && item.targetName.isNotBlank())
            require(item.temporaryName.startsWith(".aura-rename-") && item.rollbackName.startsWith(".aura-rollback-"))
        }
        items.groupBy(BatchRenameJournalItem::parentKey).values.forEach { siblings ->
            val originals = siblings.map { it.originalName.lowercase() }
            val targets = siblings.map { it.targetName.lowercase() }
            val temps = siblings.map { it.temporaryName.lowercase() }
            val rollbacks = siblings.map { it.rollbackName.lowercase() }
            require(originals.distinct().size == originals.size) { "Исходные имена журнала неоднозначны" }
            require(targets.distinct().size == targets.size) { "Целевые имена журнала неоднозначны" }
            require(temps.distinct().size == temps.size && rollbacks.distinct().size == rollbacks.size) {
                "Служебные имена журнала повторяются"
            }
            val visible = (originals + targets).toSet()
            require(temps.none { it in visible } && rollbacks.none { it in visible }) {
                "Служебное имя журнала пересекается с пользовательским"
            }
            require(temps.toSet().intersect(rollbacks.toSet()).isEmpty()) { "Служебные имена журнала пересекаются" }
        }
    }

    fun sha256(entry: FileEntry): String = contentHash(entry)

    fun setLastModified(entry: FileEntry, timestampMillis: Long) {
        require(!entry.isDirectory) { "Изменение даты папки пока не поддерживается" }
        require(timestampMillis > 0L) { "Выбрана некорректная дата" }

        val directFile = resolveDirectFile(entry.uri)
        if (directFile != null && directFile.exists() && setDirectLastModified(directFile, timestampMillis)) {
            return
        }

        if (tryProviderLastModified(entry.uri, timestampMillis)) return

        val descriptor = if (entry.uri.scheme == "file") {
            val path = requireNotNull(entry.uri.path) { "Не удалось определить путь файла" }
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_WRITE)
        } else {
            resolver.openFileDescriptor(entry.uri, "rw")
        } ?: throw IOException("Хранилище не предоставило доступ к файлу")
        descriptor.use {
            val errorNumber = NativeFileTime.setModified(it.fd, timestampMillis)
            if (errorNumber != 0) {
                val permissionHint = if (errorNumber == 13 || errorNumber == 1) {
                    if (hasFullAccess()) {
                        "Файл защищён самой системой или находится в закрытом каталоге"
                    } else {
                        "Провайдер папки запретил изменение даты. Включите «Весь накопитель» и повторите"
                    }
                } else {
                    "Это хранилище не поддерживает изменение даты"
                }
                throw IOException(
                    "$permissionHint (системный код $errorNumber)"
                )
            }
        }
    }

    fun copy(entry: FileEntry, destination: DocumentFile): DocumentFile {
        require(destination.isDirectory && destination.canWrite()) { "Папка недоступна для записи" }
        return copyDocument(entry.document, destination)
    }

    fun createZip(entries: List<FileEntry>, destination: DocumentFile, requestedName: String): DocumentFile {
        require(entries.isNotEmpty()) { "Выберите файлы для архива" }
        require(destination.isDirectory && destination.canWrite()) { "Папка недоступна для записи" }
        DocumentTreeSafety.requireNotFilesystemSymlink(destination, "Создание ZIP")
        // Preflight before creating the archive itself. Otherwise choosing a destination inside
        // a selected directory can make the newly-created ZIP become part of its own input tree.
        entries.forEach { preflightRecursiveSource(it.document, destination, "Создание ZIP") }
        val cleanName = requestedName.trim().ifEmpty { "Архив" }
        val archiveName = if (cleanName.endsWith(".zip", ignoreCase = true)) cleanName else "$cleanName.zip"
        val targetName = uniqueName(destination, archiveName)
        val archive = destination.createFile("application/zip", targetName)
            ?: throw IOException("Не удалось создать архив")

        try {
            val rawOutput = resolver.openOutputStream(archive.uri, "w")
                ?: throw IOException("Не удалось открыть архив для записи")
            ZipOutputStream(rawOutput.buffered()).use { zip ->
                entries.forEach { entry ->
                    addToZip(
                        zip,
                        entry.document,
                        ArchiveSafety.safeSegment(entry.name),
                        depth = 0,
                        visitedDirectories = mutableSetOf(),
                    )
                }
            }
            return archive
        } catch (error: Throwable) {
            archive.delete()
            throw error
        }
    }

    fun extractZip(entry: FileEntry, destination: DocumentFile): DocumentFile {
        require(!entry.isDirectory && entry.name.endsWith(".zip", ignoreCase = true)) {
            "Можно распаковать только ZIP-архив"
        }
        val baseName = entry.name.substringBeforeLast('.').ifBlank { "Архив" }
        val folderName = uniqueName(destination, baseName)
        val root = destination.createDirectory(folderName)
            ?: throw IOException("Не удалось создать папку для распаковки")

        try {
            val rawInput = resolver.openInputStream(entry.uri)
                ?: throw IOException("Не удалось прочитать архив")
            ZipInputStream(rawInput.buffered()).use { zip ->
                var itemCount = 0
                var totalBytes = 0L
                while (true) {
                    val zipEntry = zip.nextEntry ?: break
                    itemCount += 1
                    require(itemCount <= MAX_ZIP_ENTRIES) { "В архиве слишком много объектов" }
                    val segments = ArchiveSafety.safePath(zipEntry.name)
                    if (segments.isEmpty()) continue
                    var parent = root
                    segments.dropLast(1).forEach { segment ->
                        parent = parent.findFile(segment)?.takeIf(DocumentFile::isDirectory)
                            ?: parent.createDirectory(segment)
                            ?: throw IOException("Не удалось создать папку $segment")
                    }
                    if (zipEntry.isDirectory) {
                        parent.findFile(segments.last()) ?: parent.createDirectory(segments.last())
                    } else {
                        val fileName = uniqueName(parent, segments.last())
                        val mime = URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
                        val file = parent.createFile(mime, fileName)
                            ?: throw IOException("Не удалось создать $fileName")
                        val output = resolver.openOutputStream(file.uri, "w")
                            ?: throw IOException("Не удалось записать $fileName")
                        output.use { target ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                totalBytes += read
                                require(totalBytes <= MAX_EXTRACTED_BYTES) { "Архив слишком велик для безопасной распаковки" }
                                target.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            return root
        } catch (error: Throwable) {
            root.delete()
            throw error
        }
    }

    fun analyze(root: DocumentFile, maxFiles: Int = MAX_ANALYZED_FILES): StorageAnalysis {
        val files = mutableListOf<FileEntry>()
        var limitReached = false
        data class PendingDirectory(val directory: DocumentFile, val depth: Int)

        DocumentTreeSafety.requireNotFilesystemSymlink(root, "Анализ хранилища")
        val visitedDirectories = mutableSetOf<String>()
        DocumentTreeSafety.requireUniqueDirectoryVisit(root, 0, visitedDirectories, "Анализ хранилища")
        val pending = ArrayDeque<PendingDirectory>()
        pending.addLast(PendingDirectory(root, 0))

        while (pending.isNotEmpty() && !limitReached) {
            val current = pending.removeLast()
            val children = FastDocumentListing.listStrict(context, current.directory)
            for (child in children.asReversed()) {
                if (limitReached) break
                if (child.name == TRASH_FOLDER || AuraVault.isVaultFolder(child.name)) continue
                if (DocumentTreeSafety.isFilesystemSymlink(child.document)) continue
                if (child.isDirectory) {
                    val depth = current.depth + 1
                    DocumentTreeSafety.requireUniqueDirectoryVisit(
                        child.document,
                        depth,
                        visitedDirectories,
                        "Анализ хранилища",
                    )
                    pending.addLast(PendingDirectory(child.document, depth))
                } else {
                    files += FileEntry(
                        document = child.document,
                        name = child.name,
                        uri = child.uri,
                        isDirectory = false,
                        mimeType = child.mimeType,
                        size = child.size,
                        modifiedAt = child.modifiedAt,
                        parentUri = current.directory.uri,
                    )
                    if (files.size >= maxFiles) limitReached = true
                }
            }
        }

        val categories = FileCategory.entries.map { category ->
            val matching = files.filter { it.matchesCategory(category) }
            CategorySummary(category, matching.size, matching.sumOf(FileEntry::size))
        }
        val duplicateGroups = files
            .filter { it.size > 0L }
            .groupBy(FileEntry::size)
            .values
            .filter { it.size > 1 }
            .flatMap { sameSize ->
                sameSize.groupBy { contentHash(it) }.values.filter { it.size > 1 }
            }
            .sortedByDescending { it.first().size * (it.size - 1) }

        val temporary = files.filter(FileEntry::isTemporaryCandidate)
        return StorageAnalysis(
            files = files,
            totalBytes = files.sumOf(FileEntry::size),
            categories = categories,
            largeFiles = files.filter { it.size >= LARGE_FILE_BYTES }.sortedByDescending(FileEntry::size).take(50),
            largeFileCount = files.count { it.size >= LARGE_FILE_BYTES },
            duplicateGroups = duplicateGroups,
            temporaryFileCount = temporary.size,
            temporaryBytes = temporary.sumOf(FileEntry::size),
            limitReached = limitReached,
            scannedAt = System.currentTimeMillis(),
        )
    }

    fun saveAnalysis(root: DocumentFile, analysis: StorageAnalysis) {
        val fileArray = JSONArray()
        analysis.files.forEach { entry ->
            fileArray.put(
                JSONObject()
                    .put("uri", entry.uri.toString())
                    .put("name", entry.name)
                    .put("mime", entry.mimeType ?: JSONObject.NULL)
                    .put("size", entry.size)
                    .put("modified", entry.modifiedAt)
                    .put("parent", entry.parentUri?.toString() ?: JSONObject.NULL)
            )
        }
        val duplicateArray = JSONArray()
        analysis.duplicateGroups.forEach { group ->
            duplicateArray.put(JSONArray().apply { group.forEach { put(it.uri.toString()) } })
        }
        val payload = JSONObject()
            .put("version", ANALYSIS_CACHE_VERSION)
            .put("root", root.uri.toString())
            .put("scannedAt", analysis.scannedAt)
            .put("limitReached", analysis.limitReached)
            .put("files", fileArray)
            .put("duplicates", duplicateArray)
        val target = File(context.filesDir, ANALYSIS_CACHE_FILE)
        val temporary = File(context.filesDir, "$ANALYSIS_CACHE_FILE.tmp")
        temporary.writeText(payload.toString())
        if (target.exists()) target.delete()
        require(temporary.renameTo(target)) { "Не удалось сохранить индекс анализа" }
    }

    fun loadAnalysis(root: DocumentFile): StorageAnalysis? = runCatching {
        val target = File(context.filesDir, ANALYSIS_CACHE_FILE)
        if (!target.exists()) return null
        val payload = JSONObject(target.readText())
        if (payload.optInt("version") != ANALYSIS_CACHE_VERSION || payload.optString("root") != root.uri.toString()) {
            return null
        }
        val files = buildList {
            val array = payload.getJSONArray("files")
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val uri = Uri.parse(item.getString("uri"))
                val document = documentFromUri(uri) ?: continue
                add(
                    FileEntry(
                        document = document,
                        name = item.getString("name"),
                        uri = uri,
                        isDirectory = false,
                        mimeType = item.optString("mime").takeIf { it.isNotBlank() && it != "null" },
                        size = item.optLong("size"),
                        modifiedAt = item.optLong("modified"),
                        parentUri = item.optString("parent").takeIf { it.isNotBlank() && it != "null" }?.let(Uri::parse),
                    )
                )
            }
        }
        val byUri = files.associateBy { it.uri.toString() }
        val duplicates = buildList {
            val groups = payload.optJSONArray("duplicates") ?: JSONArray()
            for (groupIndex in 0 until groups.length()) {
                val stored = groups.getJSONArray(groupIndex)
                val restored = buildList {
                    for (entryIndex in 0 until stored.length()) {
                        byUri[stored.getString(entryIndex)]?.let(::add)
                    }
                }
                if (restored.size > 1) add(restored)
            }
        }
        val categories = FileCategory.entries.map { category ->
            val matching = files.filter { it.matchesCategory(category) }
            CategorySummary(category, matching.size, matching.sumOf(FileEntry::size))
        }
        val temporary = files.filter(FileEntry::isTemporaryCandidate)
        StorageAnalysis(
            files = files,
            totalBytes = files.sumOf(FileEntry::size),
            categories = categories,
            largeFiles = files.filter { it.size >= LARGE_FILE_BYTES }.sortedByDescending(FileEntry::size).take(50),
            largeFileCount = files.count { it.size >= LARGE_FILE_BYTES },
            duplicateGroups = duplicates,
            temporaryFileCount = temporary.size,
            temporaryBytes = temporary.sumOf(FileEntry::size),
            limitReached = payload.optBoolean("limitReached"),
            scannedAt = payload.optLong("scannedAt"),
        )
    }.getOrNull()

    fun clearAnalysisCache() {
        File(context.filesDir, ANALYSIS_CACHE_FILE).delete()
        File(context.filesDir, "$ANALYSIS_CACHE_FILE.tmp").delete()
    }

    fun openIntent(entry: FileEntry): Intent {
        val accessibleUri = externallyAccessibleUri(entry)
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        val resolvedMime = when (extension) {
            "djvu", "djv" -> "image/vnd.djvu"
            "apk" -> "application/vnd.android.package-archive"
            "apks" -> "application/vnd.aurafiles.apks+zip"
            else -> entry.mimeType ?: "*/*"
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(accessibleUri, resolvedMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun shareIntent(entries: List<FileEntry>): Intent {
        require(entries.isNotEmpty()) { "Нет объектов для отправки" }
        val prepared = entries.map { entry ->
            if (entry.isDirectory) {
                val archive = createTemporaryShareArchive(entry)
                SharedItem(
                    name = archive.name,
                    mimeType = "application/zip",
                    uri = AuraFileProvider.uriForFile(context, archive),
                )
            } else {
                SharedItem(entry.name, entry.mimeType ?: "application/octet-stream", externallyAccessibleUri(entry))
            }
        }
        val mimeTypes = prepared.map(SharedItem::mimeType).distinct()
        val commonType = mimeTypes.singleOrNull() ?: "*/*"
        val sharedUris = prepared.map(SharedItem::uri)
        val sharedClip = ClipData.newUri(resolver, prepared.first().name, sharedUris.first()).apply {
            sharedUris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        return if (prepared.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = commonType
                putExtra(Intent.EXTRA_STREAM, sharedUris.first())
                clipData = sharedClip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = commonType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(sharedUris))
                clipData = sharedClip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun createTemporaryShareArchive(entry: FileEntry): File {
        val shareDirectory = File(context.cacheDir, "shares").apply { mkdirs() }
        val expiration = System.currentTimeMillis() - TEMP_SHARE_MAX_AGE_MILLIS
        shareDirectory.listFiles().orEmpty().filter { it.lastModified() < expiration }.forEach(File::delete)
        val baseName = ArchiveSafety.safeSegment(entry.name).removeSuffix(".zip").ifBlank { "Папка" }
        val target = File(shareDirectory, "$baseName-${UUID.randomUUID().toString().take(8)}.zip")
        try {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                addToZip(
                    zip,
                    entry.document,
                    ArchiveSafety.safeSegment(entry.name),
                    depth = 0,
                    visitedDirectories = mutableSetOf(),
                )
            }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun storageSnapshot(): StorageSnapshot {
        val stats = StatFs(context.filesDir.absolutePath)
        return StorageSnapshot(
            totalBytes = stats.totalBytes,
            availableBytes = stats.availableBytes,
        )
    }

    fun storageVolumes(): List<StorageVolumeInfo> {
        val manager = context.getSystemService(StorageManager::class.java)
        val mounted = manager.storageVolumes
            .filterNot { it.isPrimary }
            .map { volume ->
                StorageVolumeInfo(
                    id = volume.uuid ?: volume.toString(),
                    label = volume.getDescription(context).ifBlank { if (volume.isRemovable) "Съёмный накопитель" else "Накопитель" },
                    volume = volume,
                    removable = volume.isRemovable,
                    state = volume.state,
                )
            }
        val usbManager = context.getSystemService(UsbManager::class.java)
        val hardware = usbManager.deviceList.values
            .filter { device ->
                device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE ||
                    (0 until device.interfaceCount).any { index ->
                        device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
                    }
            }
            .map { device ->
                StorageVolumeInfo(
                    id = "usb-${device.vendorId}-${device.productId}-${device.deviceId}",
                    label = device.productName?.takeIf(String::isNotBlank)
                        ?: "USB ${device.vendorId.toString(16).uppercase()}:${device.productId.toString(16).uppercase()}",
                    volume = null,
                    removable = true,
                    state = "USB обнаружено · ожидает монтирования Android",
                    hardwareDetected = true,
                )
            }
        // StorageManager may expose an SD card or adopted volume while an unmounted USB stick
        // is visible only through UsbManager. Keep both sources instead of returning early.
        return (mounted + hardware).distinctBy(StorageVolumeInfo::id)
    }

    private fun preflightRecursiveSource(
        source: DocumentFile,
        destination: DocumentFile,
        operation: String,
    ) {
        DocumentTreeSafety.requireNotFilesystemSymlink(destination, operation)
        DocumentTreeSafety.requireNotFilesystemSymlink(source, operation)
        if (!source.isDirectory) return
        if (DocumentTreeSafety.isSameOrDescendantFile(destination, source) == true) {
            throw IOException("$operation: папку нельзя поместить внутрь самой себя или её потомка")
        }

        val destinationKey = DocumentTreeSafety.identityKey(destination)
        val visited = mutableSetOf<String>()
        data class PendingDirectory(val directory: DocumentFile, val depth: Int)
        val rootKey = DocumentTreeSafety.requireUniqueDirectoryVisit(source, 0, visited, operation)
        if (rootKey == destinationKey) {
            throw IOException("$operation: исходная папка и папка назначения совпадают")
        }
        val pending = ArrayDeque<PendingDirectory>()
        pending.addLast(PendingDirectory(source, 0))
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            for (child in FastDocumentListing.listStrict(context, current.directory)) {
                DocumentTreeSafety.requireNotFilesystemSymlink(child.document, operation)
                if (!child.isDirectory) continue
                val depth = current.depth + 1
                val key = DocumentTreeSafety.requireUniqueDirectoryVisit(child.document, depth, visited, operation)
                if (key == destinationKey) {
                    throw IOException("$operation: папку нельзя поместить внутрь самой себя или её потомка")
                }
                pending.addLast(PendingDirectory(child.document, depth))
            }
        }
    }

    private fun copyDocument(
        source: DocumentFile,
        destination: DocumentFile,
        preferredName: String? = null,
    ): DocumentFile {
        preflightRecursiveSource(source, destination, "Копирование")
        return copyDocumentRecursive(
            source = source,
            destination = destination,
            preferredName = preferredName,
            depth = 0,
            visitedDirectories = mutableSetOf(),
        )
    }

    private fun copyDocumentRecursive(
        source: DocumentFile,
        destination: DocumentFile,
        preferredName: String?,
        depth: Int,
        visitedDirectories: MutableSet<String>,
    ): DocumentFile {
        DocumentTreeSafety.requireDepth(depth, "Копирование")
        DocumentTreeSafety.requireNotFilesystemSymlink(source, "Копирование")
        return if (source.isDirectory) {
            DocumentTreeSafety.requireUniqueDirectoryVisit(source, depth, visitedDirectories, "Копирование")
            val folderName = uniqueName(destination, preferredName ?: source.name ?: "Новая папка")
            val copiedFolder = destination.createDirectory(folderName)
                ?: throw IOException("Не удалось создать папку $folderName")
            try {
                FastDocumentListing.listStrict(context, source).forEach { child ->
                    copyDocumentRecursive(
                        source = child.document,
                        destination = copiedFolder,
                        preferredName = null,
                        depth = depth + 1,
                        visitedDirectories = visitedDirectories,
                    )
                }
                copiedFolder
            } catch (error: Throwable) {
                copiedFolder.delete()
                throw error
            }
        } else {
            val fileName = uniqueName(destination, preferredName ?: source.name ?: "Файл")
            val copiedFile = destination.createFile(source.type ?: "application/octet-stream", fileName)
                ?: throw IOException("Не удалось создать файл $fileName")
            try {
                val input = if (source.uri.scheme == "file") {
                    File(requireNotNull(source.uri.path) { "Не удалось определить путь ${source.name}" }).inputStream()
                } else {
                    resolver.openInputStream(source.uri)
                        ?: throw IOException("Не удалось прочитать ${source.name}")
                }
                input.use { sourceStream ->
                    val output = if (copiedFile.uri.scheme == "file") {
                        File(requireNotNull(copiedFile.uri.path) { "Не удалось определить путь $fileName" }).outputStream()
                    } else {
                        resolver.openOutputStream(copiedFile.uri, "w")
                            ?: throw IOException("Не удалось записать $fileName")
                    }
                    output.use { targetStream -> sourceStream.copyTo(targetStream) }
                }
                copiedFile
            } catch (error: Throwable) {
                copiedFile.delete()
                throw error
            }
        }
    }

    private fun addToZip(
        zip: ZipOutputStream,
        source: DocumentFile,
        path: String,
        depth: Int,
        visitedDirectories: MutableSet<String>,
    ) {
        DocumentTreeSafety.requireDepth(depth, "Создание ZIP")
        DocumentTreeSafety.requireNotFilesystemSymlink(source, "Создание ZIP")
        if (source.isDirectory) {
            DocumentTreeSafety.requireUniqueDirectoryVisit(source, depth, visitedDirectories, "Создание ZIP")
            val directoryPath = "$path/"
            zip.putNextEntry(ZipEntry(directoryPath))
            zip.closeEntry()
            FastDocumentListing.listStrict(context, source).forEach { child ->
                addToZip(
                    zip,
                    child.document,
                    "$path/${ArchiveSafety.safeSegment(child.name)}",
                    depth + 1,
                    visitedDirectories,
                )
            }
        } else {
            zip.putNextEntry(ZipEntry(path).apply {
                if (source.lastModified() > 0L) time = source.lastModified()
            })
            val input = resolver.openInputStream(source.uri)
                ?: throw IOException("Не удалось прочитать ${source.name}")
            input.use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun contentHash(entry: FileEntry): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = resolver.openInputStream(entry.uri)
            ?: throw IOException("Не удалось проверить ${entry.name}")
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexString()
    }

    @Suppress("DEPRECATION")
    private fun resolveDirectFile(uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)
        if (!hasFullAccess()) return null
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY || !DocumentsContract.isDocumentUri(context, uri)) {
            return null
        }
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        if (documentId.startsWith("raw:")) return File(documentId.removePrefix("raw:"))
        val separator = documentId.indexOf(':')
        if (separator < 0) return null
        val volume = documentId.substring(0, separator)
        val relativePath = documentId.substring(separator + 1)
        val volumeRoot = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage", volume)
        }
        val candidate = if (relativePath.isBlank()) volumeRoot else File(volumeRoot, relativePath)
        val canonicalRoot = runCatching { volumeRoot.canonicalFile }.getOrNull() ?: return null
        val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        return canonicalCandidate.takeIf {
            it.path == canonicalRoot.path || it.path.startsWith(canonicalRoot.path + File.separator)
        }
    }

    private fun setDirectLastModified(file: File, timestampMillis: Long): Boolean {
        val nioSuccess = runCatching {
            Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(timestampMillis))
            true
        }.getOrDefault(false)
        if (!nioSuccess && !runCatching { file.setLastModified(timestampMillis) }.getOrDefault(false)) return false
        return isTimestampClose(file.lastModified(), timestampMillis)
    }

    private fun tryProviderLastModified(uri: Uri, timestampMillis: Long): Boolean {
        if (uri.scheme != "content") return false
        return runCatching {
            val values = ContentValues(1).apply {
                put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, timestampMillis)
            }
            if (resolver.update(uri, values, null, null) <= 0) return@runCatching false
            resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                cursor.moveToFirst() && isTimestampClose(cursor.getLong(0), timestampMillis)
            } ?: false
        }.getOrDefault(false)
    }

    private fun isTimestampClose(actual: Long, expected: Long): Boolean {
        return actual > 0L && kotlin.math.abs(actual - expected) <= TIMESTAMP_TOLERANCE_MILLIS
    }

    private fun documentFromUri(uri: Uri): DocumentFile? = FastDocumentListing.resolve(context, uri)

    private fun externallyAccessibleUri(entry: FileEntry): Uri {
        if (entry.uri.scheme != "file") return entry.uri
        val path = requireNotNull(entry.uri.path) { "Не удалось определить путь файла" }
        return AuraFileProvider.uriForFile(context, File(path))
    }

    private data class StoredTrashRecord(
        val uri: Uri,
        val originalParentUri: Uri,
        val originalName: String,
        val deletedAt: Long,
        val originalUri: Uri?,
        val size: Long,
    )

    private fun TrashRecord.toStoredTrashRecord(): StoredTrashRecord = StoredTrashRecord(
        uri = entry.uri,
        originalParentUri = originalParentUri,
        originalName = originalName,
        deletedAt = deletedAt,
        originalUri = originalUri,
        size = size,
    )

    /**
     * Trash metadata is intentionally parsed without resolving every URI. Resolving each
     * DocumentFile here used to cause several provider IPC calls for every trash entry even
     * when we only needed to append/remove one metadata row.
     */
    private fun loadTrashMetadata(): List<StoredTrashRecord> {
        val raw = preferences.getString(KEY_TRASH_RECORDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val uri = item.optString("uri").takeIf(String::isNotBlank)?.let(Uri::parse) ?: continue
                    val parent = item.optString("parent").takeIf(String::isNotBlank)?.let(Uri::parse) ?: continue
                    add(
                        StoredTrashRecord(
                            uri = uri,
                            originalParentUri = parent,
                            originalName = item.optString("name"),
                            deletedAt = item.optLong("deletedAt"),
                            originalUri = item.optString("originalUri").takeIf(String::isNotBlank)?.let(Uri::parse),
                            size = item.optLong("size", 0L).coerceAtLeast(0L),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveTrashMetadata(records: List<StoredTrashRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("uri", record.uri.toString())
                    .put("parent", record.originalParentUri.toString())
                    .put("name", record.originalName)
                    .put("deletedAt", record.deletedAt)
                    .put("originalUri", record.originalUri?.toString().orEmpty())
                    .put("size", record.size)
            )
        }
        if (!preferences.edit().putString(KEY_TRASH_RECORDS, array.toString()).commit()) {
            throw IOException("Не удалось надёжно сохранить состояние корзины")
        }
    }

    private fun removeTrashRecord(uri: Uri) {
        saveTrashMetadata(loadTrashMetadata().filterNot { it.uri == uri })
    }

    private fun uniqueName(parent: DocumentFile, original: String): String {
        if (parent.findFile(original) == null) return original

        val dot = original.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < original.lastIndex
        val base = if (hasExtension) original.substring(0, dot) else original
        val extension = if (hasExtension) original.substring(dot) else ""

        var number = 2
        while (true) {
            val candidate = "$base ($number)$extension"
            if (parent.findFile(candidate) == null) return candidate
            number += 1
        }
    }

    private enum class LocalDeleteOutcome { COMMITTED, NOT_COMMITTED, AMBIGUOUS }

    private fun trashDirectory(root: DocumentFile, create: Boolean): DocumentFile? {
        DocumentTreeSafety.requireNotFilesystemSymlink(root, "Корзина")
        val existing = findUniqueChildStrict(root, TRASH_FOLDER)
        val trash = when {
            existing != null -> {
                if (!existing.isDirectory) throw IOException("$TRASH_FOLDER существует, но не является папкой")
                existing
            }
            create -> root.createDirectory(TRASH_FOLDER)
                ?: throw IOException("Не удалось создать корзину")
            else -> null
        }
        trash?.let { DocumentTreeSafety.requireNotFilesystemSymlink(it, "Корзина") }
        return trash
    }

    private fun findUniqueChildStrict(parent: DocumentFile, name: String): DocumentFile? {
        val matches = FastDocumentListing.listStrict(context, parent).filter { it.name == name }
        if (matches.size > 1) throw IOException("Найдено несколько объектов с именем $name")
        return matches.singleOrNull()?.document
    }

    private fun findIdentityStrict(parent: DocumentFile, uri: Uri): DocumentFile? {
        val key = DocumentTreeSafety.identityKey(uri)
        val matches = FastDocumentListing.listStrict(context, parent)
            .filter { DocumentTreeSafety.identityKey(it.uri) == key }
        if (matches.size > 1) throw IOException("Провайдер вернул неоднозначный идентификатор документа")
        return matches.singleOrNull()?.document
    }

    private fun deleteDocumentAndProbe(document: DocumentFile, parentUri: Uri): LocalDeleteOutcome {
        try {
            document.delete()
        } catch (_: Exception) {
            // A provider may commit deletion and lose/throw the response. Probe below.
        }
        val parent = documentFromUri(parentUri) ?: return LocalDeleteOutcome.AMBIGUOUS
        val remaining = try {
            findIdentityStrict(parent, document.uri)
        } catch (_: Exception) {
            return LocalDeleteOutcome.AMBIGUOUS
        }
        return if (remaining == null) LocalDeleteOutcome.COMMITTED else LocalDeleteOutcome.NOT_COMMITTED
    }

    private fun rollbackSafetyCopy(parent: DocumentFile, copy: DocumentFile) {
        val outcome = deleteDocumentAndProbe(copy, parent.uri)
        if (outcome != LocalDeleteOutcome.COMMITTED) {
            throw IOException("Исходник сохранён, но страховочную копию не удалось безопасно убрать; оставлены обе копии")
        }
    }

    private fun tryFastMove(source: DocumentFile, sourceParentUri: Uri, destination: DocumentFile): DocumentFile? {
        DocumentTreeSafety.requireNotFilesystemSymlink(source, "Перемещение")
        DocumentTreeSafety.requireNotFilesystemSymlink(destination, "Перемещение")
        val sourceName = source.name ?: return null
        if (findUniqueChildStrict(destination, sourceName) != null) return null

        val directSource = resolveDirectFile(source.uri)
        val directDestination = resolveDirectFile(destination.uri)
        if (directSource != null && directDestination?.isDirectory == true) {
            val target = File(directDestination, sourceName)
            if (target.exists()) return null
            try {
                directSource.renameTo(target)
            } catch (_: Exception) {
                // Reconcile actual filesystem state below.
            }
            val sourceExists = directSource.exists()
            val targetExists = target.exists()
            return when {
                !sourceExists && targetExists -> DocumentFile.fromFile(target)
                sourceExists && !targetExists -> null
                else -> throw IOException(
                    "Не удалось однозначно подтвердить быстрое перемещение $sourceName; fallback не выполняется"
                )
            }
        }
        if (source.uri.scheme != "content" || sourceParentUri.scheme != "content" || destination.uri.scheme != "content") {
            return null
        }
        if (source.uri.authority != destination.uri.authority) return null

        var movedUri: Uri? = null
        try {
            movedUri = DocumentsContract.moveDocument(resolver, source.uri, sourceParentUri, destination.uri)
        } catch (_: Exception) {
            // Lost ACK is reconciled below. Never blindly retry a destructive move.
        }
        val sourceParent = documentFromUri(sourceParentUri)
            ?: throw IOException("Не удалось проверить исходную папку после перемещения $sourceName")
        val sourceStillPresent = try {
            findIdentityStrict(sourceParent, source.uri) != null
        } catch (error: Exception) {
            throw IOException("Не удалось проверить исходник после перемещения $sourceName", error)
        }
        if (movedUri == null) {
            if (sourceStillPresent) return null
            // The move may have committed but there is no returned destination identity. A
            // same-name destination could have been created concurrently, so do not guess.
            throw IOException(
                "Перемещение $sourceName могло завершиться, но подтверждение потеряно; fallback не выполняется"
            )
        }
        val destinationDocument = try {
            findIdentityStrict(destination, movedUri)
        } catch (error: Exception) {
            throw IOException("Не удалось проверить результат перемещения $sourceName", error)
        }
        return when {
            !sourceStillPresent && destinationDocument != null -> destinationDocument
            sourceStillPresent && destinationDocument == null -> null
            else -> throw IOException(
                "Не удалось однозначно подтвердить перемещение $sourceName; fallback не выполняется"
            )
        }
    }

    private fun DocumentFile.toEntry(parentUri: Uri? = null): FileEntry {
        return FileEntry(
            document = this,
            name = name ?: "Без названия",
            uri = uri,
            isDirectory = isDirectory,
            mimeType = type,
            size = if (isDirectory) 0L else length(),
            modifiedAt = lastModified(),
            parentUri = parentUri,
        )
    }

    private data class SharedItem(val name: String, val mimeType: String, val uri: Uri)

    private companion object {
        const val PREFERENCES = "aura_files_preferences"
        const val KEY_ROOT_URI = "root_uri"
        const val KEY_ACCESS_MODE = "access_mode"
        const val KEY_TRASH_RECORDS = "trash_records"
        const val KEY_FAVORITES = "favorite_uris"
        const val KEY_SHOW_HIDDEN = "show_hidden"
        const val KEY_SHOW_THUMBNAIL_FILES = "show_thumbnail_files"
        const val KEY_GRID_THUMBNAILS = "grid_thumbnails"
        const val KEY_FAVORITES_HOME = "favorites_home"
        const val KEY_DELETE_ANIMATION = "delete_animation_mode"
        const val ANALYSIS_CACHE_FILE = "analysis-index.json"
        const val ANALYSIS_CACHE_VERSION = 1
        const val TRASH_FOLDER = AURA_TRASH_FOLDER
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val TIMESTAMP_TOLERANCE_MILLIS = 2_000L
        const val MAX_ANALYZED_FILES = 10_000
        const val LARGE_FILE_BYTES = 50L * 1024L * 1024L
        const val MAX_ZIP_ENTRIES = 20_000
        const val MAX_EXTRACTED_BYTES = 4L * 1024L * 1024L * 1024L
        const val TEMP_SHARE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
        const val BATCH_RENAME_JOURNAL_FILE = "batch-rename-journal-v1.json"
        const val BATCH_RENAME_INDEX_RECONCILE_FILE = "batch-rename-index-reconcile-v1.json"
        const val BATCH_RENAME_JOURNAL_VERSION = 1
        const val BATCH_RENAME_MAX_ENTRIES = 10_000
        const val BATCH_RENAME_SERVICE_NAME_ATTEMPTS = 64
        val BATCH_RENAME_LOCK = Any()
    }
}
