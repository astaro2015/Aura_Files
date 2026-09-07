package com.aurafiles.app.backend

import com.aurafiles.app.cloud.CloudProfile
import com.aurafiles.app.cloud.CloudProvider
import com.aurafiles.app.cloud.google.GoogleAccessTokenProvider
import com.aurafiles.app.cloud.google.GoogleBinaryTransfer
import com.aurafiles.app.cloud.google.GoogleDriveApi
import com.aurafiles.app.cloud.google.GoogleDriveApiClient
import com.aurafiles.app.cloud.google.GoogleDriveApiException
import com.aurafiles.app.cloud.google.GoogleDriveFailureKind
import com.aurafiles.app.cloud.google.GoogleDriveFile
import com.aurafiles.app.cloud.google.UrlConnectionGoogleBinaryTransfer
import com.aurafiles.app.cloud.google.googleExportSpec
import com.aurafiles.app.cloud.google.presentedName
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64

class GoogleDriveStorageBackend(
    private val profile: CloudProfile,
    private val tokenProvider: GoogleAccessTokenProvider,
    private val apiFactory: (String) -> GoogleDriveApi = { token -> GoogleDriveApiClient(token) },
    private val binaryTransfer: GoogleBinaryTransfer = UrlConnectionGoogleBinaryTransfer(),
    override val descriptor: StorageBackendDescriptor = StorageBackendDescriptor(
        id = "google:${profile.id}",
        title = profile.name.ifBlank { "Google Drive" },
        kind = StorageBackendKind.GOOGLE_DRIVE,
    ),
) : StorageBackend {
    init {
        require(profile.provider == CloudProvider.GOOGLE_DRIVE) { "Cloud-профиль относится не к Google Drive" }
    }

    override suspend fun list(path: String): List<StorageItem> {
        val normalized = normalize(path)
        val parentId = resolveDirectoryId(normalized)
        return authorizedApi { listChildren(parentId) }
            .map { toStorageItem(it, normalized) }
    }

    override suspend fun stat(path: String): StorageItem? {
        val normalized = normalize(path)
        if (normalized == "/") {
            return StorageItem(
                backendId = descriptor.id,
                path = "/",
                name = descriptor.title,
                isDirectory = true,
            )
        }
        val segment = BackendPath.name(normalized)
        val parentPath = parent(normalized)
        return when {
            segment.startsWith(ID_PREFIX) -> {
                val id = decodeSegment(segment.removePrefix(ID_PREFIX)) ?: return null
                authorizedApi { file(id) }?.let { toStorageItem(it, parentPath) }
            }
            segment.startsWith(NAME_PREFIX) -> {
                val displayName = decodeSegment(segment.removePrefix(NAME_PREFIX)) ?: return null
                val parentId = resolveDirectoryId(parentPath)
                findByPresentedName(parentId, displayName)?.let { toStorageItem(it, parentPath) }
            }
            else -> {
                // Compatibility with callers that may still pass a plain path segment.
                val parentId = resolveDirectoryId(parentPath)
                findByPresentedName(parentId, segment)?.let { toStorageItem(it, parentPath) }
            }
        }
    }

    override suspend fun openRead(path: String): StorageReadHandle {
        val normalized = normalize(path)
        val file = resolveFile(normalized) ?: throw IOException("Файл в Google Drive не найден")
        if (file.isDirectory) throw IOException("Папку нельзя открыть как файл")
        if (file.isShortcut) throw IOException("Ярлык Google Drive нельзя скачать как обычный файл")
        if (!file.canDownload) throw IOException("Google Drive не разрешает скачивание этого файла")
        val export = if (file.isGoogleNative) googleExportSpec(file.mimeType) else null
        if (file.isGoogleNative && export == null) {
            throw IOException("Этот тип Google Workspace пока нельзя экспортировать: ${file.mimeType}")
        }
        val handle = authorizedBinaryRead(file, export)
        return object : StorageReadHandle {
            override val input = handle.input
            override fun close() = handle.close()
        }
    }

    override suspend fun openWrite(path: String, replace: Boolean): StorageWriteHandle =
        openWrite(path, replace, expectedSize = null)

    override suspend fun openWrite(path: String, replace: Boolean, expectedSize: Long?): StorageWriteHandle {
        val normalized = normalize(path)
        require(normalized != "/") { "Нельзя записать файл в корень без имени" }
        val parentPath = parent(normalized)
        val parentId = resolveDirectoryId(parentPath)
        val requestedName = displayNameFromPath(normalized)
        val existing = stat(normalized)
        if (existing != null && !replace) throw IOException("${existing.name} уже существует")
        val existingFile = existing?.let { resolveFile(it.path) }
        if (existingFile?.isDirectory == true) throw IOException("${existing.name} уже существует и является папкой")
        if (existingFile?.isGoogleNative == true) {
            throw IOException("Нельзя заменить Google Workspace документ обычным бинарным файлом")
        }
        val mimeType = BackendPath.guessMime(requestedName)
        val session = authorizedApi {
            startResumableUpload(
                fileId = existingFile?.id,
                parentId = parentId,
                name = requestedName,
                mimeType = mimeType,
                expectedSize = expectedSize,
            )
        }
        val handle = binaryTransfer.openWrite(session, expectedSize, mimeType)
        return object : StorageWriteHandle {
            private var finished = false
            override val output = handle.output

            override fun commit() {
                if (finished) return
                try {
                    handle.commit()
                    finished = true
                } catch (error: Throwable) {
                    runCatching { handle.abort() }
                    finished = true
                    throw error
                }
            }

            override fun abort() {
                if (finished) return
                handle.abort()
                finished = true
            }

            override fun close() {
                if (!finished) abort()
            }
        }
    }

    override suspend fun mkdir(path: String): StorageItem {
        val normalized = normalize(path)
        require(normalized != "/") { "Корень Google Drive уже существует" }
        stat(normalized)?.let { existing ->
            if (existing.isDirectory) return existing
            throw IOException("${existing.name} уже существует и не является папкой")
        }
        val parentPath = parent(normalized)
        val parentId = resolveDirectoryId(parentPath)
        val name = displayNameFromPath(normalized)
        val created = authorizedApi { createFolder(parentId, name) }
        return toStorageItem(created, parentPath)
    }

    override suspend fun rename(path: String, newName: String): StorageItem {
        val normalized = normalize(path)
        require(normalized != "/") { "Нельзя переименовать корень Google Drive" }
        val file = resolveFile(normalized) ?: throw IOException("Объект Google Drive не найден")
        val parentPath = parent(normalized)
        val requestedDisplayName = decodeNameArgument(newName)
        val providerName = providerNameForRename(file, requestedDisplayName)
        val collisionPath = child(parentPath, requestedDisplayName)
        val collision = stat(collisionPath)
        if (collision != null && resolveFile(collision.path)?.id != file.id) {
            throw IOException("$requestedDisplayName уже существует")
        }
        val renamed = authorizedApi { rename(file.id, providerName) }
        return toStorageItem(renamed, parentPath)
    }

    override suspend fun move(path: String, destinationDirectory: String): StorageItem {
        val normalized = normalize(path)
        require(normalized != "/") { "Нельзя перемещать корень Google Drive" }
        val file = resolveFile(normalized) ?: throw IOException("Объект Google Drive не найден")
        val destinationPath = normalize(destinationDirectory)
        val destinationParentId = resolveDirectoryId(destinationPath)
        val displayedName = file.presentedName()
        val collision = stat(child(destinationPath, displayedName))
        if (collision != null && resolveFile(collision.path)?.id != file.id) {
            throw IOException("$displayedName уже существует в папке назначения")
        }
        val moved = authorizedApi { move(file.id, destinationParentId, file.parents) }
        return toStorageItem(moved, destinationPath)
    }

    override suspend fun delete(path: String, recursive: Boolean) {
        val normalized = normalize(path)
        require(normalized != "/") { "Нельзя удалить корень Google Drive" }
        val file = resolveFile(normalized) ?: return
        if (file.isDirectory && !recursive && list(normalized).isNotEmpty()) {
            throw IOException("Папка не пуста")
        }
        // The file manager does not expose a Google trash view; normal delete remains recoverable in Google Drive itself.
        authorizedApi { trash(file.id) }
    }

    override suspend fun ping(): Boolean = runCatching {
        authorizedApi { about() }
        true
    }.getOrDefault(false)

    override fun child(parent: String, name: String): String {
        require(name.isNotBlank()) { "Пустое имя" }
        require('/' !in name && '\\' !in name && name != "." && name != "..") { "Некорректное имя: $name" }
        val base = normalize(parent)
        val segment = NAME_PREFIX + encodeSegment(name)
        return BackendPath.normalize(if (base == "/") "/$segment" else "$base/$segment")
    }

    override fun close() = Unit

    private suspend fun resolveDirectoryId(path: String): String {
        val normalized = normalize(path)
        if (normalized == "/") return ROOT_ID
        val file = resolveFile(normalized) ?: throw IOException("Папка Google Drive не найдена")
        if (!file.isDirectory) throw IOException("${file.presentedName()} не является папкой")
        return file.id
    }

    private suspend fun resolveFile(path: String): GoogleDriveFile? {
        val normalized = normalize(path)
        if (normalized == "/") return GoogleDriveFile(ROOT_ID, descriptor.title, GOOGLE_FOLDER_MIME)
        val segment = BackendPath.name(normalized)
        if (segment.startsWith(ID_PREFIX)) {
            val id = decodeSegment(segment.removePrefix(ID_PREFIX)) ?: return null
            return authorizedApi { file(id) }
        }
        val parentId = resolveDirectoryId(parent(normalized))
        return findByPresentedName(parentId, displayNameFromPath(normalized))
    }

    private suspend fun findByPresentedName(parentId: String, displayName: String): GoogleDriveFile? {
        // Drive allows duplicate provider names, and a native Google document can also export to
        // the same displayed name as a binary file (for example "report" -> "report.docx").
        // Name-based filesystem operations must never silently select one of those objects.
        val matches = linkedMapOf<String, GoogleDriveFile>()
        authorizedApi { findChildren(parentId, displayName) }
            .filter { it.presentedName() == displayName }
            .forEach { matches[it.id] = it }
        for ((baseName, mimeType) in exportBaseCandidates(displayName)) {
            authorizedApi { findChildren(parentId, baseName) }
                .filter { it.mimeType == mimeType && it.presentedName() == displayName }
                .forEach { matches[it.id] = it }
        }
        if (matches.size > 1) {
            throw IOException(
                "В Google Drive несколько объектов отображаются как «$displayName». " +
                    "Выбери нужный объект в списке; операция по одному имени неоднозначна."
            )
        }
        return matches.values.singleOrNull()
    }

    private fun exportBaseCandidates(displayName: String): List<Pair<String, String>> = buildList {
        EXPORT_MIME_BY_EXTENSION.forEach { (extension, mimeType) ->
            if (displayName.endsWith(extension, ignoreCase = true) && displayName.length > extension.length) {
                add(displayName.dropLast(extension.length) to mimeType)
            }
        }
    }

    private fun providerNameForRename(file: GoogleDriveFile, requestedDisplayName: String): String {
        val spec = googleExportSpec(file.mimeType) ?: return requestedDisplayName
        return if (requestedDisplayName.endsWith(spec.extension, ignoreCase = true) && requestedDisplayName.length > spec.extension.length) {
            requestedDisplayName.dropLast(spec.extension.length)
        } else requestedDisplayName
    }

    private fun toStorageItem(file: GoogleDriveFile, parentPath: String): StorageItem {
        val displayName = file.presentedName()
        val export = if (file.isGoogleNative) googleExportSpec(file.mimeType) else null
        val unsupported = file.isShortcut || (!file.isDirectory && !file.canDownload) || (file.isGoogleNative && export == null)
        val itemPath = idChildPath(parentPath, file.id)
        return StorageItem(
            backendId = descriptor.id,
            path = itemPath,
            name = displayName,
            isDirectory = file.isDirectory,
            size = if (file.isDirectory || file.isGoogleNative) 0L else file.size.coerceAtLeast(0L),
            modifiedAt = file.modifiedAtEpochMs.coerceAtLeast(0L),
            mimeType = when {
                file.isDirectory -> null
                export != null -> export.mimeType
                else -> file.mimeType.ifBlank { BackendPath.guessMime(displayName) }
            },
            isLink = unsupported,
        )
    }

    private fun idChildPath(parentPath: String, fileId: String): String {
        val base = normalize(parentPath)
        val segment = ID_PREFIX + encodeSegment(fileId)
        return BackendPath.normalize(if (base == "/") "/$segment" else "$base/$segment")
    }

    private fun displayNameFromPath(path: String): String {
        val segment = BackendPath.name(path)
        return when {
            segment.startsWith(NAME_PREFIX) -> decodeSegment(segment.removePrefix(NAME_PREFIX))
                ?: throw IOException("Повреждён путь Google Drive")
            segment.startsWith(ID_PREFIX) -> throw IOException("Для существующего Google Drive ID нужно сначала получить метаданные")
            else -> segment
        }
    }

    private fun decodeNameArgument(value: String): String {
        if (value.startsWith(NAME_PREFIX)) {
            return decodeSegment(value.removePrefix(NAME_PREFIX)) ?: throw IOException("Повреждено имя Google Drive")
        }
        return value
    }

    private suspend fun authorizedBinaryRead(
        file: GoogleDriveFile,
        export: com.aurafiles.app.cloud.google.GoogleExportSpec?,
    ): com.aurafiles.app.cloud.google.GoogleBinaryReadHandle {
        val firstToken = tokenProvider.accessToken(false)
        return try {
            binaryTransfer.openRead(firstToken, file, export)
        } catch (error: GoogleDriveApiException) {
            if (error.kind != GoogleDriveFailureKind.UNAUTHORIZED) throw error
            tokenProvider.invalidate(firstToken)
            val fresh = tokenProvider.accessToken(true)
            binaryTransfer.openRead(fresh, file, export)
        }
    }

    private suspend fun <T> authorizedApi(block: GoogleDriveApi.() -> T): T {
        val firstToken = tokenProvider.accessToken(false)
        return try {
            apiFactory(firstToken).block()
        } catch (error: GoogleDriveApiException) {
            if (error.kind != GoogleDriveFailureKind.UNAUTHORIZED) throw error
            tokenProvider.invalidate(firstToken)
            val fresh = tokenProvider.accessToken(true)
            apiFactory(fresh).block()
        }
    }

    private fun encodeSegment(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeSegment(value: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }.getOrNull()?.takeIf(String::isNotBlank)

    companion object {
        private const val ROOT_ID = "root"
        private const val ID_PREFIX = "~gid~"
        private const val NAME_PREFIX = "~gname~"
        private const val GOOGLE_FOLDER_MIME = "application/vnd.google-apps.folder"
        private val EXPORT_MIME_BY_EXTENSION = linkedMapOf(
            ".docx" to "application/vnd.google-apps.document",
            ".xlsx" to "application/vnd.google-apps.spreadsheet",
            ".pptx" to "application/vnd.google-apps.presentation",
            ".pdf" to "application/vnd.google-apps.drawing",
        )
    }
}
