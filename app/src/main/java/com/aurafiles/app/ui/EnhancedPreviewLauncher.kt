package com.aurafiles.app.ui

import android.content.Context
import android.content.Intent
import com.aurafiles.app.model.FileEntry

internal fun openEnhancedPreview(context: Context, entry: FileEntry, siblings: List<FileEntry> = emptyList()): Boolean {
    val extension = entry.name.substringAfterLast('.', "").lowercase()
    return when {
        extension == "apk" || entry.mimeType == "application/vnd.android.package-archive" -> {
            ApkInspectorActivity.start(context, entry)
            true
        }
        entry.mimeType?.startsWith("image/") == true || extension in IMAGE_EXTENSIONS -> {
            ImageViewerActivity.start(context, entry, siblings)
            true
        }
        entry.mimeType?.startsWith("video/") == true || extension in VIDEO_EXTENSIONS -> {
            MediaPlayerActivity.start(context, entry, audioOnly = false)
            true
        }
        entry.mimeType?.startsWith("audio/") == true || extension in AUDIO_EXTENSIONS -> {
            MediaPlayerActivity.start(context, entry, audioOnly = true)
            true
        }
        else -> false
    }
}

internal val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
internal val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts", "m2ts")
internal val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "3gp")
