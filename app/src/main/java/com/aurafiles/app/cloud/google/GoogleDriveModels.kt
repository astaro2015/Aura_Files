package com.aurafiles.app.cloud.google

const val GOOGLE_DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
const val GOOGLE_DRIVE_FOLDER_MIME = "application/vnd.google-apps.folder"
const val GOOGLE_DRIVE_SHORTCUT_MIME = "application/vnd.google-apps.shortcut"
const val GOOGLE_DRIVE_NATIVE_PREFIX = "application/vnd.google-apps."

data class GoogleDriveUser(
    val permissionId: String = "",
    val displayName: String = "",
    val emailAddress: String = "",
)

data class GoogleDriveAbout(
    val user: GoogleDriveUser = GoogleDriveUser(),
    val limit: Long? = null,
    val usage: Long? = null,
    val usageInDrive: Long? = null,
    val usageInDriveTrash: Long? = null,
)

data class GoogleDriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long = 0L,
    val modifiedAtEpochMs: Long = 0L,
    val parents: List<String> = emptyList(),
    val canDownload: Boolean = true,
    val shortcutTargetId: String = "",
    val trashed: Boolean = false,
) {
    val isDirectory: Boolean get() = mimeType == GOOGLE_DRIVE_FOLDER_MIME
    val isShortcut: Boolean get() = mimeType == GOOGLE_DRIVE_SHORTCUT_MIME
    val isGoogleNative: Boolean get() = mimeType.startsWith(GOOGLE_DRIVE_NATIVE_PREFIX) && !isDirectory && !isShortcut
}

data class GoogleExportSpec(
    val extension: String,
    val mimeType: String,
)

fun googleExportSpec(mimeType: String): GoogleExportSpec? = when (mimeType) {
    "application/vnd.google-apps.document" -> GoogleExportSpec(
        extension = ".docx",
        mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    )
    "application/vnd.google-apps.spreadsheet" -> GoogleExportSpec(
        extension = ".xlsx",
        mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    )
    "application/vnd.google-apps.presentation" -> GoogleExportSpec(
        extension = ".pptx",
        mimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )
    "application/vnd.google-apps.drawing" -> GoogleExportSpec(
        extension = ".pdf",
        mimeType = "application/pdf",
    )
    else -> null
}

fun GoogleDriveFile.presentedName(): String {
    val spec = googleExportSpec(mimeType) ?: return name
    return if (name.endsWith(spec.extension, ignoreCase = true)) name else name + spec.extension
}
