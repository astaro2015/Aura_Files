package com.aurafiles.app.index

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
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
        val result = mutableListOf<List<IndexedFileEntity>>()
        dao.duplicateSizes(rootId).forEach { size ->
            coroutineContext.ensureActive()
            val sameSize = dao.bySize(rootId, size)
            val quickGroups = sameSize.groupBy { entity ->
                coroutineContext.ensureActive()
                onFile(entity.name)
                val quick = entity.quickHash ?: quickHash(entity)
                if (entity.quickHash == null) dao.updateHashes(entity.uri, quick, entity.sha256)
                quick
            }.values.filter { it.size > 1 }

            quickGroups.forEach { candidates ->
                val exact = candidates.groupBy { entity ->
                    coroutineContext.ensureActive()
                    onFile(entity.name)
                    val full = entity.sha256 ?: fullHash(entity)
                    if (entity.sha256 == null) dao.updateHashes(entity.uri, entity.quickHash ?: quickHash(entity), full)
                    full
                }.values.filter { it.size > 1 }
                result += exact
            }
        }
        return result.sortedByDescending { group -> group.first().size * (group.size - 1L) }
    }

    private fun quickHash(entity: IndexedFileEntity): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(entity.size.toString().toByteArray())
        val input = resolver.openInputStream(Uri.parse(entity.uri))
            ?: throw IOException("Не удалось прочитать ${entity.name}")
        input.buffered().use { stream ->
            val buffer = ByteArray(QUICK_HASH_BYTES)
            val read = stream.read(buffer)
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun fullHash(entity: IndexedFileEntity): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = resolver.openInputStream(Uri.parse(entity.uri))
            ?: throw IOException("Не удалось прочитать ${entity.name}")
        input.buffered(HASH_BUFFER_BYTES).use { stream ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val QUICK_HASH_BYTES = 128 * 1024
        private const val HASH_BUFFER_BYTES = 1024 * 1024
    }
}

