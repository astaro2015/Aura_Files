package com.aurafiles.app.ui

import java.io.Reader
import kotlinx.coroutines.CancellationException

internal suspend fun <T> loadOptionalNeighbors(fallback: List<T>, load: suspend () -> List<T>): List<T> =
    try {
        load()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fallback
    }

internal val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
internal val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts", "m2ts")
internal val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "3gp")

internal fun isPreviewImage(name: String, mime: String?): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension == "djvu" || extension == "djv" || mime?.contains("djvu", ignoreCase = true) == true) {
        return false
    }
    return mime?.startsWith("image/") == true || extension in IMAGE_EXTENSIONS
}

internal fun isPreviewVideo(name: String, mime: String?): Boolean =
    mime?.startsWith("video/") == true || name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

internal fun isPreviewAudio(name: String, mime: String?): Boolean =
    mime?.startsWith("audio/") == true || name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

internal fun readTextPreview(reader: Reader): String {
    val buffer = CharArray(32_768)
    val count = reader.read(buffer)
    return if (count <= 0) "" else String(buffer, 0, count) + if (count == buffer.size) "\n\n…предпросмотр ограничен…" else ""
}
