package com.aurafiles.app.tools

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.aurafiles.app.AuraFileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Publishes an APK through a system-owned content provider before it is shared.
 *
 * On Android 10+ the copy lives in Downloads/Aura Files and the share URI belongs to MediaStore.
 * This deliberately keeps messenger interoperability independent from Aura's own ContentProvider.
 */
object ApkSharePublisher {
    private const val BUFFER_SIZE = 1024 * 1024
    private const val DOWNLOAD_SUBDIRECTORY = "Aura Files"

    data class PublishedApk(
        val uri: Uri,
        val displayName: String,
        val size: Long,
        val savedToDownloads: Boolean,
    )

    suspend fun publish(context: Context, source: File): PublishedApk {
        require(source.isFile) { "APK отсутствует" }
        val sourceSize = source.length()
        require(sourceSize > 0L) { "APK пуст" }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            !ApkShareDeliveryPolicy.useMediaStore(Build.VERSION.SDK_INT)
        ) {
            return publishWithAuraProvider(context, source, sourceSize)
        }

        val mediaStoreError: Throwable = try {
            return publishToDownloads(context, source, sourceSize)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            error
        }

        try {
            return publishWithAuraProvider(context, source, sourceSize)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fallbackError: Throwable) {
            throw IOException(
                buildString {
                    append("MediaStore publish failed")
                    mediaStoreError.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
                    append("; Aura provider fallback failed")
                    fallbackError.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
                },
                fallbackError,
            ).also { combined -> combined.addSuppressed(mediaStoreError) }
        }
    }

    private fun publishWithAuraProvider(
        context: Context,
        source: File,
        sourceSize: Long,
    ): PublishedApk {
        val published = PublishedApk(
            uri = AuraFileProvider.uriForFile(context, source),
            displayName = source.name,
            size = sourceSize,
            savedToDownloads = false,
        )
        verifyPublished(context, published)
        return published
    }

    private suspend fun publishToDownloads(
        context: Context,
        source: File,
        sourceSize: Long,
    ): PublishedApk {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("MediaStore Downloads доступен только с Android 10")
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            // A generic binary MIME is intentional. Some messengers special-case the APK MIME
            // as an installable package instead of treating the stream as a normal document.
            put(MediaStore.MediaColumns.MIME_TYPE, ApkShareDeliveryPolicy.SHARE_MIME)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIRECTORY",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Android не создал файл в Загрузках")

        var published = false
        try {
            val output = resolver.openOutputStream(uri, "w")
                ?: throw IOException("Android не открыл файл в Загрузках для записи")
            output.buffered(BUFFER_SIZE).use { out ->
                source.inputStream().buffered(BUFFER_SIZE).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        copied = Math.addExact(copied, read.toLong())
                        require(copied <= sourceSize) { "APK изменился во время публикации" }
                    }
                    require(copied == sourceSize) {
                        "В Загрузки записано $copied байт вместо $sourceSize"
                    }
                }
                out.flush()
            }

            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            val updated = resolver.update(uri, ready, null, null)
            require(updated == 1) { "Android не завершил публикацию APK" }

            val metadata = resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        cursor.getString(nameIndex)
                    } else source.name
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex)
                    } else -1L
                    name to size
                }
            } ?: throw IOException("Android не вернул данные опубликованного APK")

            require(metadata.second == sourceSize) {
                "MediaStore сообщает размер ${metadata.second} вместо $sourceSize"
            }
            val candidate = PublishedApk(
                uri = uri,
                displayName = metadata.first,
                size = sourceSize,
                savedToDownloads = true,
            )
            verifyPublished(context, candidate)
            published = true
            return candidate
        } finally {
            if (!published) {
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    private fun verifyPublished(context: Context, published: PublishedApk) {
        val resolver = context.contentResolver
        val metadata = resolver.query(
            published.uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                name to size
            }
        } ?: throw IOException("Android не вернул метаданные APK для отправки")

        require(metadata.first == null || metadata.first == published.displayName) {
            "Android сообщает имя ${metadata.first} вместо ${published.displayName}"
        }
        require(metadata.second == null || metadata.second == published.size) {
            "Android сообщает размер ${metadata.second} вместо ${published.size}"
        }
        val visibleMime = resolver.getType(published.uri)
        require(visibleMime == null || visibleMime == ApkShareDeliveryPolicy.SHARE_MIME) {
            "Android публикует APK как $visibleMime вместо ${ApkShareDeliveryPolicy.SHARE_MIME}"
        }
        verifyReadable(resolver, published.uri, published.size)
    }

    private fun verifyReadable(
        resolver: android.content.ContentResolver,
        uri: Uri,
        expectedSize: Long,
    ) {
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Android не открыл опубликованный APK")
        descriptor.use { pfd ->
            if (pfd.statSize >= 0L) {
                require(pfd.statSize == expectedSize) {
                    "Android открыл ${pfd.statSize} байт вместо $expectedSize"
                }
            }
        }
        resolver.openInputStream(uri)?.buffered()?.use { input ->
            val header = ByteArray(4)
            val read = input.read(header)
            require(read == 4) { "Опубликованный APK пуст или обрезан" }
            require(header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
                "Опубликованный файл не похож на APK/ZIP"
            }
        } ?: throw IOException("Android не открыл поток опубликованного APK")
    }
}
