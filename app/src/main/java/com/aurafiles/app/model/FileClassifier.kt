package com.aurafiles.app.model

import android.net.Uri

/** Centralised, allocation-conscious file classification used by both UI and indexer. */
object FileClassifier {
    private val primaryBookExtensions = setOf("epub", "fb2", "mobi", "azw", "azw3", "prc", "cbz", "cbr")
    private val readerExtensions = primaryBookExtensions + setOf(
        "docx", "rtf", "md", "markdown", "txt", "html", "htm", "pdf", "djvu", "djv"
    )
    private val documentExtensions = setOf(
        "pdf", "djvu", "djv", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "rtf", "txt", "md", "markdown", "csv", "html", "htm"
    )
    private val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "tbz2", "txz")
    private val temporaryExtensions = setOf("tmp", "temp", "log", "bak")
    private val imageExtensions = setOf(
        "jpg", "jpeg", "jpe", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff", "dng"
    )
    private val videoExtensions = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "3gp", "3g2", "ts", "m2ts", "mts", "mpg", "mpeg", "vob", "flv", "wmv", "asf", "ogv"
    )
    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "amr", "wma", "mid", "midi", "mka", "aiff", "aif", "ape", "alac"
    )

    data class Result(
        val extension: String,
        val category: FileCategory,
        val readerSupported: Boolean,
        val sourceFolder: String,
        val temporaryCandidate: Boolean,
    )

    fun classify(name: String, mimeType: String?, uri: Uri, parentUri: Uri?): Result {
        val extension = extension(name)
        val path = searchablePath(name, uri, parentUri)
        val source = sourceLabel(path)
        return Result(
            extension = extension,
            category = category(name, extension, mimeType),
            readerSupported = readerSupported(name, extension),
            sourceFolder = source,
            temporaryCandidate = temporaryCandidate(path, extension, source),
        )
    }

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun readerSupported(name: String, extension: String = extension(name)): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".fb2.zip") || extension in readerExtensions
    }

    fun primaryBook(name: String, extension: String = extension(name)): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".fb2.zip") || extension in primaryBookExtensions
    }

    fun category(name: String, extension: String = extension(name), mimeType: String?): FileCategory = when {
        mimeType?.startsWith("image/") == true -> FileCategory.Images
        mimeType?.startsWith("video/") == true -> FileCategory.Video
        mimeType?.startsWith("audio/") == true -> FileCategory.Audio
        isGenericMimeType(mimeType) && extension in imageExtensions -> FileCategory.Images
        isGenericMimeType(mimeType) && extension in videoExtensions -> FileCategory.Video
        isGenericMimeType(mimeType) && extension in audioExtensions -> FileCategory.Audio
        extension == "apk" || mimeType == "application/vnd.android.package-archive" -> FileCategory.Apk
        primaryBook(name, extension) -> FileCategory.Books
        mimeType == "application/pdf" || mimeType?.startsWith("text/") == true || extension in documentExtensions -> FileCategory.Documents
        extension in archiveExtensions || mimeType?.contains("zip", ignoreCase = true) == true ||
            mimeType?.contains("archive", ignoreCase = true) == true -> FileCategory.Archives
        else -> FileCategory.Other
    }

    private fun isGenericMimeType(mimeType: String?): Boolean = when (mimeType?.trim()?.lowercase()) {
        null, "", "*/*", "application/octet-stream", "binary/octet-stream",
        "application/unknown", "application/x-empty" -> true
        else -> false
    }

    fun sourceLabel(path: String): String = when {
        isThumbnailCache(path) -> "Миниатюры и кэш"
        path.contains("whatsapp") || path.contains("com.whatsapp") -> "WhatsApp"
        path.contains("telegram") || path.contains("org.telegram") -> "Telegram"
        path.contains("dcim/camera") -> "Камера"
        containsPathPart(path, "screenshots") || containsPathPart(path, "screenshot") -> "Снимки экрана"
        containsPathPart(path, "download") || containsPathPart(path, "downloads") -> "Загрузки"
        containsPathPart(path, "bluetooth") -> "Bluetooth"
        containsPathPart(path, "documents") -> "Документы"
        else -> "Другие папки"
    }

    fun temporaryCandidate(path: String, extension: String, sourceFolder: String = sourceLabel(path)): Boolean =
        sourceFolder == "Миниатюры и кэш" ||
            containsPathPart(path, "cache") ||
            containsPathPart(path, "temp") ||
            containsPathPart(path, "tmp") ||
            extension in temporaryExtensions

    fun searchablePath(name: String, uri: Uri, parentUri: Uri?): String =
        Uri.decode("${parentUri?.toString().orEmpty()}|$uri|$name").replace('\\', '/').lowercase()

    fun isThumbnailCache(path: String): Boolean =
        path.contains(".thumbnails") || containsPathPart(path, "thumbnails") || containsPathPart(path, "thumbnail")

    fun containsPathPart(normalizedPath: String, part: String): Boolean =
        normalizedPath.contains("/$part/") || normalizedPath.contains(":$part/") ||
            normalizedPath.endsWith("/$part") || normalizedPath.contains("/$part|")
}
