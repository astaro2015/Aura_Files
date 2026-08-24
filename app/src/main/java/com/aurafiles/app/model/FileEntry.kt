package com.aurafiles.app.model

import android.net.Uri
import android.os.storage.StorageVolume
import androidx.documentfile.provider.DocumentFile

data class FileEntry(
    val document: DocumentFile,
    val name: String,
    val uri: Uri,
    val isDirectory: Boolean,
    val mimeType: String?,
    val size: Long,
    val modifiedAt: Long,
    val parentUri: Uri? = null,
)

data class TrashRecord(
    val entry: FileEntry,
    val originalParentUri: Uri,
    val originalName: String,
    val deletedAt: Long,
)

data class FolderCrumb(
    val document: DocumentFile,
    val label: String,
)

enum class ClipboardMode {
    Copy,
    Move,
}

data class FileClipboard(
    val entries: List<FileEntry>,
    val sourceParent: DocumentFile,
    val mode: ClipboardMode,
)

enum class FileSortMode {
    Name,
    Modified,
    Size,
    Type,
}

enum class FileViewMode {
    List,
    Grid,
}

enum class SystemSoundType {
    Ringtone,
    Alarm,
}

enum class FileCategory {
    Images,
    Video,
    Audio,
    Documents,
    Archives,
    Books,
    Apk,
    Downloads,
    Camera,
    Other,
}

data class FileCollectionGroup(
    val title: String,
    val entries: List<FileEntry>,
)

data class StorageVolumeInfo(
    val id: String,
    val label: String,
    val volume: StorageVolume?,
    val removable: Boolean,
    val state: String,
    val hardwareDetected: Boolean = false,
)

data class CategorySummary(
    val category: FileCategory,
    val count: Int,
    val bytes: Long,
)

data class StorageAnalysis(
    val files: List<FileEntry>,
    val totalBytes: Long,
    val categories: List<CategorySummary>,
    val largeFiles: List<FileEntry>,
    val duplicateGroups: List<List<FileEntry>>,
    val limitReached: Boolean,
    val scannedAt: Long,
) {
    val duplicateFileCount: Int get() = duplicateGroups.sumOf { (it.size - 1).coerceAtLeast(0) }
    val reclaimableDuplicateBytes: Long
        get() = duplicateGroups.sumOf { group ->
            (group.firstOrNull()?.size ?: 0L) * (group.size - 1).coerceAtLeast(0)
        }
}

fun FileEntry.category(): FileCategory {
    val extension = name.substringAfterLast('.', "").lowercase()
    return when {
        mimeType?.startsWith("image/") == true -> FileCategory.Images
        mimeType?.startsWith("video/") == true -> FileCategory.Video
        mimeType?.startsWith("audio/") == true -> FileCategory.Audio
        isBookFormat() -> FileCategory.Books
        mimeType == "application/pdf" || mimeType?.startsWith("text/") == true ||
            extension in setOf("pdf", "djvu", "djv", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "rtf", "txt", "md", "csv") -> FileCategory.Documents
        extension in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") ||
            mimeType?.contains("zip") == true || mimeType?.contains("archive") == true -> FileCategory.Archives
        extension == "apk" || mimeType == "application/vnd.android.package-archive" -> FileCategory.Apk
        else -> FileCategory.Other
    }
}

fun FileEntry.isBookFormat(): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".fb2.zip") || name.substringAfterLast('.', "").lowercase() in setOf(
        "epub", "fb2", "mobi", "azw", "azw3", "prc", "cbz", "cbr"
    )
}

fun FileEntry.isReaderSupported(): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".fb2.zip") || name.substringAfterLast('.', "").lowercase() in setOf(
        "epub", "fb2", "mobi", "azw", "azw3", "prc", "docx", "rtf", "md", "markdown",
        "txt", "html", "htm", "pdf", "djvu", "djv", "cbz", "cbr"
    )
}

fun FileEntry.isAudioFile(): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    return mimeType?.startsWith("audio/") == true || extension in setOf(
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "3gp", "mid", "midi"
    )
}

fun FileEntry.matchesCategory(category: FileCategory): Boolean = when (category) {
    FileCategory.Books -> isReaderSupported()
    FileCategory.Downloads -> searchablePath().containsPathPart("download") || searchablePath().containsPathPart("downloads")
    FileCategory.Camera -> searchablePath().contains("dcim/camera") || searchablePath().contains("dcim\\camera")
    else -> category() == category
}

fun FileEntry.isThumbnailCache(): Boolean {
    val path = searchablePath()
    return path.contains(".thumbnails") || path.containsPathPart("thumbnails") || path.containsPathPart("thumbnail")
}

fun FileEntry.isTemporaryCandidate(): Boolean {
    val path = searchablePath()
    val extension = name.substringAfterLast('.', "").lowercase()
    return isThumbnailCache() ||
        path.containsPathPart("cache") ||
        path.containsPathPart("temp") ||
        path.containsPathPart("tmp") ||
        extension in setOf("tmp", "temp", "log", "bak")
}

fun FileEntry.sourceLabel(): String {
    val path = searchablePath()
    return when {
        isThumbnailCache() -> "Миниатюры и кэш"
        path.contains("whatsapp") || path.contains("com.whatsapp") -> "WhatsApp"
        path.contains("telegram") || path.contains("org.telegram") -> "Telegram"
        path.contains("dcim/camera") || path.contains("dcim\\camera") -> "Камера"
        path.containsPathPart("screenshots") || path.containsPathPart("screenshot") -> "Снимки экрана"
        path.containsPathPart("download") || path.containsPathPart("downloads") -> "Загрузки"
        path.containsPathPart("bluetooth") -> "Bluetooth"
        path.containsPathPart("documents") -> "Документы"
        else -> "Другие папки"
    }
}

fun FileEntry.displayLocation(): String {
    val decoded = Uri.decode((parentUri ?: uri).toString())
    val documentPath = decoded.substringAfter("primary:", decoded)
        .substringAfter("document/")
        .substringBefore('?')
        .trimEnd('/')
    val parent = documentPath.substringBeforeLast('/', documentPath)
    return parent.takeLast(80).ifBlank { sourceLabel() }
}

private fun FileEntry.searchablePath(): String = Uri.decode("${parentUri?.toString().orEmpty()}|$uri|$name")
    .replace('\\', '/')
    .lowercase()

private fun String.containsPathPart(part: String): Boolean {
    val normalized = replace('\\', '/')
    return normalized.contains("/$part/") || normalized.contains(":$part/") ||
        normalized.endsWith("/$part") || normalized.contains("/$part|")
}

enum class MainSection {
    Browse,
    Recent,
    Network,
    Cleanup,
}

data class FtpProfile(
    val name: String = "Мой FTP",
    val host: String,
    val port: Int = 21,
    val username: String,
    val password: String,
    val useTls: Boolean = false,
)

data class FtpEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
)

data class FtpServerConfig(
    val port: Int = 2121,
    val username: String = "aura",
    val password: String,
    val readOnly: Boolean = true,
)

data class FtpServerStatus(
    val running: Boolean = false,
    val starting: Boolean = false,
    val endpoints: List<String> = emptyList(),
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    val rootLabel: String = "",
    val readOnly: Boolean = true,
    val clients: Int = 0,
    val error: String? = null,
)

enum class LanService {
    Smb,
    Ftp,
    Web,
    Ssh,
    Media,
}

data class LanDevice(
    val address: String,
    val name: String = address,
    val services: Set<LanService>,
)

data class SmbProfile(
    val name: String = "Сетевой диск",
    val host: String,
    val share: String,
    val username: String,
    val password: String,
    val domain: String = "",
)

data class SmbEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
)

enum class StorageAccessMode {
    Folder,
    Full,
}

data class StorageSnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0L)
    val usedFraction: Float
        get() = if (totalBytes == 0L) 0f else usedBytes.toFloat() / totalBytes.toFloat()
}
