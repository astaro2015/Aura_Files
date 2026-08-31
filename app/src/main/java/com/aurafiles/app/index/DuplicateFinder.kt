package com.aurafiles.app.index

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class DuplicateFinder(
    private val resolver: ContentResolver,
    private val dao: IndexedFileDao,
) {
    suspend fun findExactDuplicates(
        rootId: String,
        onFile: (String) -> Unit = {},
    ): List<List<IndexedFileEntity>> {
        val changed = LinkedHashMap<Long, IndexedFileEntity>(HASH_WRITE_BATCH)
        val result = mutableListOf<List<IndexedFileEntity>>()

        fun markChanged(entity: IndexedFileEntity) {
            changed[entity.id] = entity
            if (changed.size >= HASH_WRITE_BATCH) {
                dao.upsertAll(changed.values.toList())
                changed.clear()
            }
        }

        dao.duplicateCandidates(rootId)
            .groupBy { it.size }
            .values
            .forEach { sameSize ->
                coroutineContext.ensureActive()
                val quickGroups = sameSize.groupBy { entity ->
                    coroutineContext.ensureActive()
                    onFile(entity.name)
                    entity.quickHash ?: quickHash(entity).also { quick ->
                        entity.quickHash = quick
                        markChanged(entity)
                    }
                }.values.filter { it.size > 1 }

                quickGroups.forEach { candidates ->
                    val exact = candidates.groupBy { entity ->
                        coroutineContext.ensureActive()
                        onFile(entity.name)
                        entity.sha256 ?: fullHash(entity).also { full ->
                            entity.sha256 = full
                            // quickHash is already known for every candidate in this branch.
                            markChanged(entity)
                        }
                    }.values.filter { it.size > 1 }
                    result += exact
                }
            }

        if (changed.isNotEmpty()) dao.upsertAll(changed.values.toList())
        return result.sortedByDescending { group -> group.first().size * (group.size - 1L) }
    }

    private fun quickHash(entity: IndexedFileEntity): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(entity.size.toString().toByteArray(Charsets.US_ASCII))
        openInput(entity).buffered(QUICK_HASH_BYTES).use { stream ->
            val buffer = ByteArray(QUICK_HASH_BYTES)
            val read = stream.read(buffer)
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun fullHash(entity: IndexedFileEntity): String {
        val digest = MessageDigest.getInstance("SHA-256")
        openInput(entity).buffered(HASH_BUFFER_BYTES).use { stream ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun openInput(entity: IndexedFileEntity): InputStream {
        val uri = Uri.parse(entity.uri)
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: throw IOException("Не удалось определить путь ${entity.name}")
            return File(path).inputStream()
        }
        return resolver.openInputStream(uri) ?: throw IOException("Не удалось прочитать ${entity.name}")
    }

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        var out = 0
        for (byte in this) {
            val value = byte.toInt() and 0xff
            chars[out++] = HEX[value ushr 4]
            chars[out++] = HEX[value and 0x0f]
        }
        return String(chars)
    }

    companion object {
        private const val QUICK_HASH_BYTES = 128 * 1024
        private const val HASH_BUFFER_BYTES = 1024 * 1024
        private const val HASH_WRITE_BATCH = 128
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
