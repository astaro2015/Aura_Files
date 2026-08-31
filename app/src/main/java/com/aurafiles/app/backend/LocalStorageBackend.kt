package com.aurafiles.app.backend

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.data.FastDocumentListing
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class LocalStorageBackend(
    context: Context,
    private val rootUri: Uri,
    override val descriptor: StorageBackendDescriptor = StorageBackendDescriptor(
        id = "local:${rootUri}",
        title = "Локальная память",
        kind = StorageBackendKind.LOCAL,
    ),
) : StorageBackend {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val root: DocumentFile = documentFromUri(rootUri)
        ?: throw IOException("Локальный корень недоступен")

    override suspend fun list(path: String): List<StorageItem> {
        val directory = resolve(path) ?: throw IOException("Папка недоступна: $path")
        require(directory.isDirectory) { "Это не папка: $path" }
        return FastDocumentListing.list(appContext, directory)
            .map { info ->
                StorageItem(
                    backendId = descriptor.id,
                    path = child(path, info.name),
                    name = info.name,
                    isDirectory = info.isDirectory,
                    size = info.size,
                    modifiedAt = info.modifiedAt,
                    mimeType = info.mimeType,
                )
            }
            .sortedWith(compareByDescending<StorageItem> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    override suspend fun stat(path: String): StorageItem? {
        val normalized = normalize(path)
        val document = resolve(normalized) ?: return null
        return if (normalized == "/") {
            StorageItem(descriptor.id, "/", descriptor.title, true, modifiedAt = document.lastModified())
        } else document.toStorageItem(parent(normalized))
    }

    override suspend fun openRead(path: String): StorageReadHandle {
        val document = resolve(path) ?: throw IOException("Файл недоступен: $path")
        require(document.isFile) { "Читать потоком можно только файл" }
        val stream = openInput(document.uri)
        return object : StorageReadHandle {
            override val input: InputStream = stream
            override fun close() = input.close()
        }
    }

    override suspend fun openWrite(path: String, replace: Boolean): StorageWriteHandle {
        val normalized = normalize(path)
        require(normalized != "/") { "Нельзя записывать корень" }
        val parent = resolve(parent(normalized)) ?: throw IOException("Папка назначения недоступна")
        require(parent.isDirectory && parent.canWrite()) { "Папка назначения недоступна для записи" }
        val name = BackendPath.name(normalized)
        val existing = parent.findFile(name)
        if (existing != null) {
            if (!replace) throw IOException("$name уже существует")
            require(existing.delete()) { "Не удалось заменить $name" }
        }
        val created = parent.createFile(BackendPath.guessMime(name), name)
            ?: throw IOException("Не удалось создать $name")
        val stream = try {
            openOutput(created.uri)
        } catch (error: Throwable) {
            created.delete()
            throw error
        }
        return object : StorageWriteHandle {
            private var finished = false
            override val output: OutputStream = stream
            override fun commit() {
                if (!finished) {
                    output.flush()
                    finished = true
                }
            }
            override fun abort() {
                runCatching { output.close() }
                created.delete()
                finished = true
            }
            override fun close() {
                runCatching { output.close() }
                if (!finished) created.delete()
            }
        }
    }

    override suspend fun mkdir(path: String): StorageItem {
        val normalized = normalize(path)
        require(normalized != "/") { "Корень уже существует" }
        val parent = resolve(parent(normalized)) ?: throw IOException("Родительская папка недоступна")
        val name = BackendPath.name(normalized)
        val existing = parent.findFile(name)
        val directory = existing?.takeIf(DocumentFile::isDirectory)
            ?: parent.createDirectory(name)
            ?: throw IOException("Не удалось создать папку $name")
        return directory.toStorageItem(parent(normalized))
    }

    override suspend fun rename(path: String, newName: String): StorageItem {
        val normalized = normalize(path)
        require(normalized != "/") { "Нельзя переименовать корень" }
        val document = resolve(normalized) ?: throw IOException("Объект недоступен")
        require(document.renameTo(newName)) { "Не удалось переименовать ${document.name}" }
        return document.toStorageItem(parent(normalized))
    }

    override suspend fun move(path: String, destinationDirectory: String): StorageItem {
        val normalized = normalize(path)
        val source = resolve(normalized) ?: throw IOException("Объект недоступен")
        val destination = resolve(destinationDirectory) ?: throw IOException("Папка назначения недоступна")
        require(destination.isDirectory && destination.canWrite()) { "Папка назначения недоступна" }
        val sourceParent = resolve(parent(normalized)) ?: throw IOException("Исходная папка недоступна")
        if (source.uri.scheme == ContentResolver.SCHEME_CONTENT &&
            destination.uri.scheme == ContentResolver.SCHEME_CONTENT &&
            source.uri.authority == destination.uri.authority
        ) {
            val moved = runCatching {
                DocumentsContract.moveDocument(resolver, source.uri, sourceParent.uri, destination.uri)
            }.getOrNull()
            if (moved != null) {
                val doc = documentFromUri(moved) ?: source
                return doc.toStorageItem(normalize(destinationDirectory))
            }
        }
        throw UnsupportedOperationException("Это локальное хранилище не поддерживает быстрый move; TransferEngine выполнит copy+delete")
    }

    override suspend fun delete(path: String, recursive: Boolean) {
        val normalized = normalize(path)
        require(normalized != "/") { "Нельзя удалить корень" }
        val document = resolve(normalized) ?: return
        if (document.isDirectory && !recursive && document.listFiles().isNotEmpty()) {
            throw IOException("Папка не пуста")
        }
        require(document.delete()) { "Не удалось удалить ${document.name}" }
    }

    override fun close() = Unit

    private fun resolve(path: String): DocumentFile? {
        val normalized = normalize(path)
        var current = root
        for (segment in BackendPath.relativeSegments(normalized)) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun DocumentFile.toStorageItem(parentPath: String): StorageItem {
        val name = name ?: "Без имени"
        return StorageItem(
            backendId = descriptor.id,
            path = child(parentPath, name),
            name = name,
            isDirectory = isDirectory,
            size = if (isFile) length().coerceAtLeast(0L) else 0L,
            modifiedAt = lastModified().coerceAtLeast(0L),
            mimeType = type,
        )
    }

    private fun documentFromUri(uri: Uri): DocumentFile? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: return null
            return DocumentFile.fromFile(File(path))
        }
        return runCatching { DocumentFile.fromTreeUri(appContext, uri) }.getOrNull()
            ?: runCatching { DocumentFile.fromSingleUri(appContext, uri) }.getOrNull()
    }

    private fun openInput(uri: Uri): InputStream {
        return if (uri.scheme == ContentResolver.SCHEME_FILE) {
            File(requireNotNull(uri.path)).inputStream()
        } else resolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")
    }

    private fun openOutput(uri: Uri): OutputStream {
        return if (uri.scheme == ContentResolver.SCHEME_FILE) {
            File(requireNotNull(uri.path)).outputStream()
        } else resolver.openOutputStream(uri, "w") ?: throw IOException("Не удалось открыть файл для записи")
    }
}
