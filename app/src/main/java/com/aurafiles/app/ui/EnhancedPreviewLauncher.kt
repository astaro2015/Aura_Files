package com.aurafiles.app.ui

import android.content.Context
import android.content.Intent
import com.aurafiles.app.model.FileEntry

internal fun openEnhancedPreview(context: Context, entry: FileEntry, siblings: List<FileEntry> = emptyList()): Boolean {
    val extension = entry.name.substringAfterLast('.', "").lowercase()
    return when {
        extension == "apks" || entry.mimeType == "application/vnd.aurafiles.apks+zip" -> {
            SplitPackageInstallerActivity.start(context, entry)
            true
        }
        extension == "apk" || entry.mimeType == "application/vnd.android.package-archive" -> {
            ApkInspectorActivity.start(context, entry)
            true
        }
        isPreviewImage(entry.name, entry.mimeType) -> {
            ImageViewerActivity.start(context, entry, siblings)
            true
        }
        isPreviewVideo(entry.name, entry.mimeType) -> {
            MediaPlayerActivity.start(context, entry, audioOnly = false, siblings = siblings)
            true
        }
        isPreviewAudio(entry.name, entry.mimeType) -> {
            MediaPlayerActivity.start(context, entry, audioOnly = true, siblings = siblings)
            true
        }
        else -> false
    }
}
