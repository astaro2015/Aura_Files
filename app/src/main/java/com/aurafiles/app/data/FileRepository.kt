package com.aurafiles.app.data

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
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.model.FileEntry
import com.aurafiles.app.model.FileCategory
import com.aurafiles.app.model.CategorySummary
import com.aurafiles.app.model.StorageSnapshot
import com.aurafiles.app.model.StorageAnalysis
import com.aurafiles.app.model.StorageVolumeInfo
import com.aurafiles.app.model.StorageAccessMode
import com.aurafiles.app.model.TrashRecord
import com.aurafiles.app.model.matchesCategory
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.net.URLConnection
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FileRepository(private val context: Context) {
    private val resolver = context.contentResolver
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

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
        return directory.listFiles()
            .filterNot { it.name == TRASH_FOLDER }
            .map { it.toEntry(directory.uri) }
            .sortedWith(
                compareByDescending<FileEntry> { it.isDirectory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
    }

    fun createFolder(parent: DocumentFile, requestedName: String): DocumentFile {
        val name = requestedName.trim()
        require(name.isNotEmpty()) { "Введите название папки" }
        require(parent.findFile(name) == null) { "Папка с таким названием уже существует" }
        return requireNotNull(parent.createDirectory(name)) { "Не удалось создать папку" }
    }

    fun rename(entry: FileEntry, requestedName: String): Uri {
        val name = requestedName.trim()
        require(name.isNotEmpty()) { "Введите новое название" }
        val oldUri = entry.uri
        require(entry.document.renameTo(name)) { "Не удалось переименовать объект" }
        replaceFavoriteUri(oldUri, entry.document.uri)
        return entry.document.uri
    }

    fun delete(entry: FileEntry) {
        require(entry.document.delete()) { "Не удалось удалить ${entry.name}" }
    }

    fun moveToTrash(root: DocumentFile, entry: FileEntry): TrashRecord {
        val originalParentUri = requireNotNull(entry.parentUri) {
            "Не удалось определить исходную папку"
        }
        val trash = root.findFile(TRASH_FOLDER)?.takeIf { it.isDirectory }
            ?: root.createDirectory(TRASH_FOLDER)
            ?: throw IOException("Не удалось создать корзину")
        val copied = copyDocument(entry.document, trash)
        if (!entry.document.delete()) {
            copied.delete()
            throw IOException("Не удалось переместить ${entry.name} в корзину")
        }
        val record = TrashRecord(
            entry = copied.toEntry(trash.uri),
            originalParentUri = originalParentUri,
            originalName = entry.name,
            deletedAt = System.currentTimeMillis(),
        )
        saveTrashRecords(loadTrashMetadata() + record)
        return record
    }

    fun listTrash(root: DocumentFile): List<TrashRecord> {
        val trash = root.findFile(TRASH_FOLDER)?.takeIf { it.isDirectory } ?: return emptyList()
        val documents = trash.listFiles().associateBy { it.uri.toString() }
        val valid = loadTrashMetadata().mapNotNull { record ->
            val document = documents[record.entry.uri.toString()] ?: return@mapNotNull null
            record.copy(entry = document.toEntry(trash.uri))
        }.sortedByDescending(TrashRecord::deletedAt)
        return valid
    }

    fun restoreFromTrash(root: DocumentFile, record: TrashRecord): DocumentFile {
        val originalParent = documentFromUri(record.originalParentUri)
            ?.takeIf { it.exists() && it.isDirectory && it.canWrite() }
            ?: root
        val restored = copyDocument(record.entry.document, originalParent, record.originalName)
        if (!record.entry.document.delete()) {
            restored.delete()
            throw IOException("Не удалось удалить копию из корзины")
        }
        removeTrashRecord(record.entry.uri)
        return restored
    }

    fun permanentlyDelete(record: TrashRecord) {
        require(record.entry.document.delete()) { "Не удалось удалить ${record.entry.name}" }
        removeTrashRecord(record.entry.uri)
    }

    fun emptyTrash(root: DocumentFile) {
        val children = root.findFile(TRASH_FOLDER)?.takeIf { it.isDirectory }?.listFiles().orEmpty()
        val removedUris = children.map { it.uri }.toSet()
        children.forEach { child ->
            require(child.delete()) { "Не удалось удалить ${child.name}" }
        }
        saveTrashRecords(loadTrashMetadata().filterNot { it.entry.uri in removedUris })
    }

    fun favoriteUris(): Set<Uri> {
        return preferences.getStringSet(KEY_FAVORITES, emptySet()).orEmpty().map(Uri::parse).toSet()
    }

    fun toggleFavorites(entries: List<FileEntry>): Set<Uri> {
        val current = favoriteUris().toMutableSet()
        val shouldRemove = entries.all { it.uri in current }
        entries.forEach { entry -> if (shouldRemove) current.remove(entry.uri) else current.add(entry.uri) }
        preferences.edit().putStringSet(KEY_FAVORITES, current.map(Uri::toString).toSet()).apply()
        return current
    }

    private fun replaceFavoriteUri(oldUri: Uri, newUri: Uri) {
        if (oldUri == newUri) return
        val current = favoriteUris().toMutableSet()
        if (current.remove(oldUri)) {
            current.add(newUri)
            preferences.edit().putStringSet(KEY_FAVORITES, current.map(Uri::toString).toSet()).apply()
        }
    }

    fun favoriteEntries(): List<FileEntry> {
        val valid = favoriteUris().mapNotNull { uri ->
            runCatching { documentFromUri(uri)?.takeIf { it.exists() }?.toEntry() }.getOrNull()
        }
        val validUris = valid.map { it.uri.toString() }.toSet()
        preferences.edit().putStringSet(KEY_FAVORITES, validUris).apply()
        return valid.sortedBy { it.name.lowercase() }
    }

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

    fun batchRename(entries: List<FileEntry>, newNames: List<String>) {
        require(entries.isNotEmpty() && entries.size == newNames.size) { "Некорректный список имён" }
        val cleaned = newNames.map(String::trim)
        require(cleaned.none(String::isBlank)) { "Новое имя не может быть пустым" }
        require(cleaned.distinctBy(String::lowercase).size == cleaned.size) { "Новые имена повторяются" }

        entries.zip(cleaned).groupBy { it.first.parentUri }.forEach { (parentUri, changes) ->
            val parent = parentUri?.let(::documentFromUri)
            if (parent != null) {
                val selectedUris = changes.map { it.first.uri }.toSet()
                changes.forEach { (_, requestedName) ->
                    val collision = parent.findFile(requestedName)
                    require(collision == null || collision.uri in selectedUris) {
                        "Имя $requestedName уже занято"
                    }
                }
            }
        }

        val originals = entries.map(FileEntry::name)
        try {
            entries.forEach { entry ->
                require(entry.document.renameTo(".aura-${UUID.randomUUID()}")) {
                    "Не удалось подготовить ${entry.name} к переименованию"
                }
            }
            entries.zip(cleaned).forEach { (entry, requestedName) ->
                val oldUri = entry.uri
                require(entry.document.renameTo(requestedName)) { "Не удалось переименовать ${entry.name}" }
                replaceFavoriteUri(oldUri, entry.document.uri)
            }
        } catch (error: Throwable) {
            entries.zip(originals).forEach { (entry, original) -> runCatching { entry.document.renameTo(original) } }
            throw error
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
        val cleanName = requestedName.trim().ifEmpty { "Архив" }
        val archiveName = if (cleanName.endsWith(".zip", ignoreCase = true)) cleanName else "$cleanName.zip"
        val targetName = uniqueName(destination, archiveName)
        val archive = destination.createFile("application/zip", targetName)
            ?: throw IOException("Не удалось создать архив")

        try {
            val rawOutput = resolver.openOutputStream(archive.uri, "w")
                ?: throw IOException("Не удалось открыть архив для записи")
            ZipOutputStream(rawOutput.buffered()).use { zip ->
                entries.forEach { addToZip(zip, it.document, safeZipSegment(it.name)) }
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
                    val segments = safeZipPath(zipEntry.name)
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

        fun walk(directory: DocumentFile) {
            if (limitReached) return
            directory.listFiles().forEach { child ->
                if (limitReached) return@forEach
                if (child.name == TRASH_FOLDER) return@forEach
                if (child.isDirectory) {
                    walk(child)
                } else {
                    files += child.toEntry(directory.uri)
                    if (files.size >= maxFiles) limitReached = true
                }
            }
        }
        walk(root)

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

        return StorageAnalysis(
            files = files,
            totalBytes = files.sumOf(FileEntry::size),
            categories = categories,
            largeFiles = files.filter { it.size >= LARGE_FILE_BYTES }.sortedByDescending(FileEntry::size).take(50),
            duplicateGroups = duplicateGroups,
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
        StorageAnalysis(
            files = files,
            totalBytes = files.sumOf(FileEntry::size),
            categories = categories,
            largeFiles = files.filter { it.size >= LARGE_FILE_BYTES }.sortedByDescending(FileEntry::size).take(50),
            duplicateGroups = duplicates,
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
                    uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archive),
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
        val baseName = safeZipSegment(entry.name).removeSuffix(".zip").ifBlank { "Папка" }
        val target = File(shareDirectory, "$baseName-${UUID.randomUUID().toString().take(8)}.zip")
        try {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                addToZip(zip, entry.document, safeZipSegment(entry.name))
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
        if (mounted.isNotEmpty()) return mounted

        val usbManager = context.getSystemService(UsbManager::class.java)
        return usbManager.deviceList.values
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
    }

    private fun copyDocument(
        source: DocumentFile,
        destination: DocumentFile,
        preferredName: String? = null,
    ): DocumentFile {
        return if (source.isDirectory) {
            val folderName = uniqueName(destination, preferredName ?: source.name ?: "Новая папка")
            val copiedFolder = destination.createDirectory(folderName)
                ?: throw IOException("Не удалось создать папку $folderName")
            try {
                source.listFiles().forEach { child -> copyDocument(child, copiedFolder) }
                copiedFolder
            } catch (error: Throwable) {
                copiedFolder.delete()
                throw error
            }
        } else {
            val fileName = uniqueName(destination, preferredName ?: source.name ?: "Файл")
            val copiedFile = destination.createFile(source.type ?: "application/octet-stream", fileName)
                ?: throw IOException("Не удалось создать файл $fileName")
            val input = resolver.openInputStream(source.uri)
                ?: throw IOException("Не удалось прочитать ${source.name}")
            val output = resolver.openOutputStream(copiedFile.uri, "w")
                ?: throw IOException("Не удалось записать $fileName")
            try {
                input.use { sourceStream ->
                    output.use { targetStream -> sourceStream.copyTo(targetStream) }
                }
                copiedFile
            } catch (error: Throwable) {
                copiedFile.delete()
                throw error
            }
        }
    }

    private fun addToZip(zip: ZipOutputStream, source: DocumentFile, path: String) {
        if (source.isDirectory) {
            val directoryPath = "$path/"
            zip.putNextEntry(ZipEntry(directoryPath))
            zip.closeEntry()
            source.listFiles().forEach { child ->
                addToZip(zip, child, "$path/${safeZipSegment(child.name ?: "Без названия")}")
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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun safeZipSegment(name: String): String {
        return name.replace('/', '_').replace('\\', '_').ifBlank { "Без названия" }
    }

    private fun safeZipPath(rawPath: String): List<String> {
        require(!rawPath.startsWith('/') && !rawPath.startsWith('\\')) { "Небезопасный путь в архиве" }
        return rawPath.replace('\\', '/').split('/')
            .filter(String::isNotBlank)
            .onEach { require(it != "." && it != "..") { "Небезопасный путь в архиве" } }
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

    private fun documentFromUri(uri: Uri): DocumentFile? {
        return if (uri.scheme == "file") {
            uri.path?.let(::File)?.let(DocumentFile::fromFile)
        } else {
            DocumentFile.fromSingleUri(context, uri)
        }
    }

    private fun externallyAccessibleUri(entry: FileEntry): Uri {
        if (entry.uri.scheme != "file") return entry.uri
        val path = requireNotNull(entry.uri.path) { "Не удалось определить путь файла" }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(path),
        )
    }

    private fun loadTrashMetadata(): List<TrashRecord> {
        val raw = preferences.getString(KEY_TRASH_RECORDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val uri = Uri.parse(item.getString("uri"))
                    val record = runCatching {
                        val document = documentFromUri(uri) ?: return@runCatching null
                        TrashRecord(
                            entry = document.toEntry(),
                            originalParentUri = Uri.parse(item.getString("parent")),
                            originalName = item.getString("name"),
                            deletedAt = item.getLong("deletedAt"),
                        )
                    }.getOrNull()
                    if (record != null) add(record)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveTrashRecords(records: List<TrashRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("uri", record.entry.uri.toString())
                    .put("parent", record.originalParentUri.toString())
                    .put("name", record.originalName)
                    .put("deletedAt", record.deletedAt)
            )
        }
        preferences.edit().putString(KEY_TRASH_RECORDS, array.toString()).apply()
    }

    private fun removeTrashRecord(uri: Uri) {
        saveTrashRecords(loadTrashMetadata().filterNot { it.entry.uri == uri })
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
        const val ANALYSIS_CACHE_FILE = "analysis-index.json"
        const val ANALYSIS_CACHE_VERSION = 1
        const val TRASH_FOLDER = ".AuraTrash"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val TIMESTAMP_TOLERANCE_MILLIS = 2_000L
        const val MAX_ANALYZED_FILES = 10_000
        const val LARGE_FILE_BYTES = 50L * 1024L * 1024L
        const val MAX_ZIP_ENTRIES = 20_000
        const val MAX_EXTRACTED_BYTES = 4L * 1024L * 1024L * 1024L
        const val TEMP_SHARE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
