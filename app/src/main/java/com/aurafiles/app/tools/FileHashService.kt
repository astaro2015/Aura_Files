package com.aurafiles.app.tools

import com.aurafiles.app.util.toHexString

import android.content.ContentResolver
import android.content.Context
import com.aurafiles.app.model.FileEntry
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class FileHashes(
    val md5: String,
    val sha1: String,
    val sha256: String,
)

class FileHashService(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun calculate(
        entry: FileEntry,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): FileHashes {
        require(!entry.isDirectory) { "Хэши рассчитываются только для файлов" }
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        val stream = if (entry.uri.scheme == ContentResolver.SCHEME_FILE) {
            File(requireNotNull(entry.uri.path)).inputStream()
        } else resolver.openInputStream(entry.uri) ?: throw IOException("Не удалось прочитать ${entry.name}")
        var processed = 0L
        stream.buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                md5.update(buffer, 0, read)
                sha1.update(buffer, 0, read)
                sha256.update(buffer, 0, read)
                processed += read
                onProgress(processed, entry.size.coerceAtLeast(0L))
            }
        }
        return FileHashes(md5.digest().toHexString(), sha1.digest().toHexString(), sha256.digest().toHexString())
    }


    companion object { private const val BUFFER_SIZE = 1024 * 1024 }
}
