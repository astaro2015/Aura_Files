package com.aurafiles.app.backend

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap

data class StorageItem(
    val backendId: String,
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val modifiedAt: Long = 0L,
    val mimeType: String? = null,
)

data class StorageBackendDescriptor(
    val id: String,
    val title: String,
    val kind: StorageBackendKind,
    val rootPath: String = "/",
)

enum class StorageBackendKind {
    LOCAL,
    SMB,
    FTP,
    SFTP,
}

interface StorageReadHandle : Closeable {
    val input: InputStream
}

/**
 * A write handle may point at a temporary object. TransferEngine calls commit()
 * only after the complete stream has been written successfully, otherwise abort().
 */
interface StorageWriteHandle : Closeable {
    val output: OutputStream
    fun commit()
    fun abort()
}

interface StorageBackend : Closeable {
    val descriptor: StorageBackendDescriptor

    suspend fun list(path: String): List<StorageItem>
    suspend fun stat(path: String): StorageItem?
    suspend fun openRead(path: String): StorageReadHandle
    suspend fun openWrite(path: String, replace: Boolean = false): StorageWriteHandle
    suspend fun mkdir(path: String): StorageItem
    suspend fun rename(path: String, newName: String): StorageItem
    suspend fun move(path: String, destinationDirectory: String): StorageItem
    suspend fun delete(path: String, recursive: Boolean = false)

    /** Optional cheap keep-alive/reconnect hook. */
    suspend fun ping(): Boolean = true

    fun normalize(path: String): String = BackendPath.normalize(path)
    fun child(parent: String, name: String): String = BackendPath.child(parent, name)
    fun parent(path: String): String = BackendPath.parent(path)
}

class StorageBackendRegistry : Closeable {
    private val backends = ConcurrentHashMap<String, StorageBackend>()

    fun register(backend: StorageBackend): StorageBackend {
        backends.put(backend.descriptor.id, backend)?.let { previous ->
            if (previous !== backend) runCatching { previous.close() }
        }
        return backend
    }

    fun remove(id: String) {
        backends.remove(id)?.let { runCatching { it.close() } }
    }

    fun require(id: String): StorageBackend = backends[id]
        ?: throw IllegalArgumentException("StorageBackend '$id' не зарегистрирован")

    fun get(id: String): StorageBackend? = backends[id]

    fun descriptors(): List<StorageBackendDescriptor> = backends.values
        .map(StorageBackend::descriptor)
        .sortedBy { it.title.lowercase() }

    override fun close() {
        backends.values.forEach { runCatching { it.close() } }
        backends.clear()
    }
}

object BackendPath {
    fun normalize(raw: String): String {
        if (raw.isBlank()) return "/"
        val parts = raw.replace('\\', '/').split('/')
            .filter(String::isNotBlank)
            .filter { it != "." }
        require(parts.none { it == ".." }) { "Выход за корень backend запрещён" }
        return if (parts.isEmpty()) "/" else "/" + parts.joinToString("/")
    }

    fun child(parent: String, name: String): String {
        require(name.isNotBlank()) { "Пустое имя" }
        require('/' !in name && '\\' !in name && name != "." && name != "..") { "Некорректное имя: $name" }
        val base = normalize(parent)
        return normalize(if (base == "/") "/$name" else "$base/$name")
    }

    fun parent(path: String): String {
        val normalized = normalize(path)
        if (normalized == "/") return "/"
        return normalized.substringBeforeLast('/').ifBlank { "/" }
    }

    fun name(path: String): String {
        val normalized = normalize(path)
        return if (normalized == "/") "/" else normalized.substringAfterLast('/')
    }

    fun relativeSegments(path: String): List<String> = normalize(path)
        .removePrefix("/")
        .split('/')
        .filter(String::isNotBlank)

    fun guessMime(name: String): String =
        URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
}
