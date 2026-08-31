package com.aurafiles.app.test

import com.aurafiles.app.backend.BackendPath
import com.aurafiles.app.backend.StorageBackend
import com.aurafiles.app.backend.StorageBackendDescriptor
import com.aurafiles.app.backend.StorageBackendKind
import com.aurafiles.app.backend.StorageItem
import com.aurafiles.app.backend.StorageReadHandle
import com.aurafiles.app.backend.StorageWriteHandle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class FakeStorageBackend(
    id: String,
    kind: StorageBackendKind = StorageBackendKind.LOCAL,
) : StorageBackend {
    override val descriptor = StorageBackendDescriptor(id, id, kind)
    private data class Node(var directory: Boolean, var bytes: ByteArray = byteArrayOf(), var modifiedAt: Long = 1L)
    private val nodes = linkedMapOf("/" to Node(true))
    var failReadPath: String? = null
    var failAfterBytes: Int = Int.MAX_VALUE
    var backupDeleteFailuresRemaining: Int = 0
    var backupDeleteAttempts: Int = 0

    fun putDirectory(path: String) {
        val normalized = BackendPath.normalize(path)
        ensureParents(normalized)
        nodes[normalized] = Node(true)
    }

    fun putFile(path: String, text: String, modifiedAt: Long = 1L) = putFile(path, text.toByteArray(), modifiedAt)
    fun putFile(path: String, bytes: ByteArray, modifiedAt: Long = 1L) {
        val normalized = BackendPath.normalize(path)
        ensureParents(normalized)
        nodes[normalized] = Node(false, bytes.copyOf(), modifiedAt)
    }

    fun readBytes(path: String): ByteArray = nodes[BackendPath.normalize(path)]?.bytes?.copyOf()
        ?: error("No file $path")
    fun exists(path: String): Boolean = nodes.containsKey(BackendPath.normalize(path))
    fun hiddenNames(): List<String> = nodes.keys
        .map(BackendPath::name)
        .filter { it.startsWith(".aura-") }

    override suspend fun list(path: String): List<StorageItem> {
        val parent = BackendPath.normalize(path)
        require(nodes[parent]?.directory == true) { "Not a directory: $path" }
        return nodes.keys.asSequence()
            .filter { it != parent && BackendPath.parent(it) == parent }
            .mapNotNull { statInternal(it) }
            .sortedWith(compareByDescending<StorageItem> { it.isDirectory }.thenBy { it.name.lowercase() })
            .toList()
    }

    override suspend fun stat(path: String): StorageItem? = statInternal(BackendPath.normalize(path))

    override suspend fun openRead(path: String): StorageReadHandle {
        val normalized = BackendPath.normalize(path)
        val node = nodes[normalized] ?: throw IOException("Missing $normalized")
        require(!node.directory)
        val base = ByteArrayInputStream(node.bytes)
        val stream: InputStream = if (normalized == failReadPath) object : InputStream() {
            private var count = 0
            override fun read(): Int {
                if (count >= failAfterBytes) throw IOException("simulated disconnect")
                val v = base.read()
                if (v >= 0) count++
                return v
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (count >= failAfterBytes) throw IOException("simulated disconnect")
                val allowed = minOf(len, (failAfterBytes - count).coerceAtLeast(1))
                val n = base.read(b, off, allowed)
                if (n > 0) count += n
                return n
            }
        } else base
        return object : StorageReadHandle {
            override val input: InputStream = stream
            override fun close() = input.close()
        }
    }

    override suspend fun openWrite(path: String, replace: Boolean): StorageWriteHandle {
        val normalized = BackendPath.normalize(path)
        if (!replace && nodes.containsKey(normalized)) throw IOException("Already exists: $normalized")
        ensureParents(normalized)
        val buffer = ByteArrayOutputStream()
        return object : StorageWriteHandle {
            private var done = false
            override val output = buffer
            override fun commit() {
                if (done) return
                nodes[normalized] = Node(false, buffer.toByteArray(), System.currentTimeMillis())
                done = true
            }
            override fun abort() { done = true }
            override fun close() { if (!done) abort() }
        }
    }

    override suspend fun mkdir(path: String): StorageItem {
        putDirectory(path)
        return requireNotNull(stat(path))
    }

    override suspend fun rename(path: String, newName: String): StorageItem {
        val source = BackendPath.normalize(path)
        require(newName.isNotBlank() && '/' !in newName && '\\' !in newName)
        val target = BackendPath.child(BackendPath.parent(source), newName)
        require(!nodes.containsKey(target)) { "Target exists: $target" }
        relocate(source, target)
        return requireNotNull(stat(target))
    }

    override suspend fun move(path: String, destinationDirectory: String): StorageItem {
        val source = BackendPath.normalize(path)
        val target = BackendPath.child(destinationDirectory, BackendPath.name(source))
        require(!nodes.containsKey(target)) { "Target exists: $target" }
        relocate(source, target)
        return requireNotNull(stat(target))
    }

    override suspend fun delete(path: String, recursive: Boolean) {
        val normalized = BackendPath.normalize(path)
        if (BackendPath.name(normalized).startsWith(".aura-backup-")) {
            backupDeleteAttempts += 1
            if (backupDeleteFailuresRemaining > 0) {
                backupDeleteFailuresRemaining -= 1
                throw IOException("simulated backup cleanup failure")
            }
        }
        if (normalized == "/") throw IOException("Root delete forbidden")
        val children = nodes.keys.filter { it.startsWith("$normalized/") }
        if (children.isNotEmpty() && !recursive) throw IOException("Directory not empty")
        children.sortedByDescending(String::length).forEach(nodes::remove)
        nodes.remove(normalized)
    }

    override fun close() = Unit

    private fun statInternal(path: String): StorageItem? {
        val node = nodes[path] ?: return null
        return StorageItem(
            backendId = descriptor.id,
            path = path,
            name = if (path == "/") descriptor.title else BackendPath.name(path),
            isDirectory = node.directory,
            size = if (node.directory) 0L else node.bytes.size.toLong(),
            modifiedAt = node.modifiedAt,
            mimeType = if (node.directory) null else BackendPath.guessMime(path),
        )
    }

    private fun ensureParents(path: String) {
        var current = BackendPath.parent(path)
        val pending = mutableListOf<String>()
        while (!nodes.containsKey(current)) {
            pending += current
            if (current == "/") break
            current = BackendPath.parent(current)
        }
        pending.asReversed().forEach { nodes[it] = Node(true) }
    }

    private fun relocate(source: String, target: String) {
        val entries = nodes.entries.filter { it.key == source || it.key.startsWith("$source/") }
            .sortedBy { it.key.length }
        require(entries.isNotEmpty()) { "Missing $source" }
        ensureParents(target)
        val copies = entries.map { (key, value) ->
            val suffix = key.removePrefix(source)
            (target + suffix) to value.copy(bytes = value.bytes.copyOf())
        }
        entries.sortedByDescending { it.key.length }.forEach { nodes.remove(it.key) }
        copies.forEach { (key, node) -> nodes[key] = node }
    }
}
