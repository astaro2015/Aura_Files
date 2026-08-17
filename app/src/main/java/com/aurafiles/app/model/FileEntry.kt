package com.aurafiles.app.model

import android.net.Uri
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

enum class FileCategory {
    Images,
    Video,
    Audio,
    Documents,
    Archives,
    Other,
}

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
        mimeType == "application/pdf" || mimeType?.startsWith("text/") == true ||
            extension in setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "rtf", "txt", "md", "csv") -> FileCategory.Documents
        extension in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") ||
            mimeType?.contains("zip") == true || mimeType?.contains("archive") == true -> FileCategory.Archives
        else -> FileCategory.Other
    }
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
