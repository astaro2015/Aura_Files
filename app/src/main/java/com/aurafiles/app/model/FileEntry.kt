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
    val originalUri: Uri? = null,
    val size: Long = entry.size,
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
    val largeFileCount: Int = largeFiles.size,
    val duplicateGroups: List<List<FileEntry>>,
    val temporaryFileCount: Int = 0,
    val temporaryBytes: Long = 0L,
    val limitReached: Boolean,
    val scannedAt: Long,
) {
    val duplicateFileCount: Int by lazy(LazyThreadSafetyMode.NONE) {
        duplicateGroups.sumOf { (it.size - 1).coerceAtLeast(0) }
    }
    val reclaimableDuplicateBytes: Long by lazy(LazyThreadSafetyMode.NONE) {
        duplicateGroups.sumOf { group ->
            (group.firstOrNull()?.size ?: 0L) * (group.size - 1).coerceAtLeast(0)
        }
    }
}

fun FileEntry.category(): FileCategory = FileClassifier.category(name, FileClassifier.extension(name), mimeType)

fun FileEntry.isBookFormat(): Boolean = FileClassifier.primaryBook(name)

fun FileEntry.isReaderSupported(): Boolean = FileClassifier.readerSupported(name)

fun FileEntry.isAudioFile(): Boolean {
    val extension = FileClassifier.extension(name)
    return mimeType?.startsWith("audio/") == true || extension in setOf(
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "3gp", "mid", "midi"
    )
}

fun FileEntry.matchesCategory(category: FileCategory): Boolean = when (category) {
    FileCategory.Books -> isReaderSupported()
    FileCategory.Downloads -> sourceLabel() == "Загрузки"
    FileCategory.Camera -> sourceLabel() == "Камера"
    else -> category() == category
}

fun FileEntry.isThumbnailCache(): Boolean = FileClassifier.isThumbnailCache(searchablePath())

fun FileEntry.isTemporaryCandidate(): Boolean {
    val path = searchablePath()
    return FileClassifier.temporaryCandidate(path, FileClassifier.extension(name), FileClassifier.sourceLabel(path))
}

fun FileEntry.sourceLabel(): String = FileClassifier.sourceLabel(searchablePath())

fun FileEntry.displayLocation(): String {
    val decoded = Uri.decode((parentUri ?: uri).toString())
    val documentPath = decoded.substringAfter("primary:", decoded)
        .substringAfter("document/")
        .substringBefore('?')
        .trimEnd('/')
    val parent = documentPath.substringBeforeLast('/', documentPath)
    return parent.takeLast(80).ifBlank { sourceLabel() }
}

private fun FileEntry.searchablePath(): String = FileClassifier.searchablePath(name, uri, parentUri)

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

data class SftpServerConfig(
    val port: Int = 2222,
    val username: String = "aura",
    val password: String,
    val readOnly: Boolean = true,
)

data class SftpServerStatus(
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
