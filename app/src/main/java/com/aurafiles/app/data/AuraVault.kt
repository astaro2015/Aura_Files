package com.aurafiles.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.model.FileEntry
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Device-bound encrypted storage used by the UI labelled "Избранное".
 *
 * There is intentionally no persisted key file. The AES-256 key is deterministically derived
 * from ANDROID_ID with HKDF-SHA256 and a fixed application context string. On Android 8+ the
 * value is scoped to device + Android user + app signing key, so a normal reinstall signed with
 * the same key on the same device derives the same vault key while copying .AuraVault to another
 * device does not.
 *
 * AVF2 stores payload data as independently authenticated AES-GCM chunks. Some Android crypto
 * providers buffer an entire single-message GCM operation until doFinal(); using one GCM message
 * for a 256 MiB video can therefore request roughly 256 MiB + the 16-byte tag at once and OOM.
 * AVF2 caps each authenticated message at DATA_CHUNK_BYTES, keeping memory bounded regardless of
 * source file size. AVF1 remains readable for backwards compatibility with 1.3.1 vault files.
 *
 * This is a convenience/privacy vault, not a substitute for a user secret against an attacker who
 * can extract the source device's identifiers and reverse-engineer the app.
 */
class AuraVault(private val context: Context) {
    private val resolver = context.contentResolver
    private val random = SecureRandom()

    data class Item(
        val id: String,
        val name: String,
        val mimeType: String?,
        val size: Long,
        val modifiedAt: Long,
        val originalParentUri: String?,
        val encryptedFile: File,
        val hasThumbnail: Boolean = false,
    )

    data class Listing(
        val items: List<Item>,
        val unreadableCount: Int,
    )

    fun available(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            Environment.getExternalStorageDirectory().canWrite()
        }
    }

    fun list(): Listing {
        // Plaintext is only ever staged under Aura's private cache. Sweep expired staging files
        // whenever the vault is opened/refreshed as well as before creating a new one.
        cleanupOld(File(context.cacheDir, DEFAULT_PLAIN_CACHE_FOLDER))
        val data = dataDirectory(create = false) ?: return Listing(emptyList(), 0)
        cleanupVaultTemps(data)
        var unreadable = 0
        val items = data.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals(VAULT_EXTENSION, ignoreCase = true) }
            .mapNotNull { file ->
                runCatching { readMetadata(file) }
                    .onFailure { unreadable += 1 }
                    .getOrNull()
            }
            .sortedBy { it.name.lowercase() }
            .toList()
        return Listing(items, unreadable)
    }

    fun moveInto(entry: FileEntry): Item {
        require(!entry.isDirectory) { "Папки пока нельзя помещать в защищённое Избранное" }
        require(entry.vaultItemId == null && !isVaultUri(entry.uri)) { "Файл уже находится в защищённом Избранном" }
        ensureAvailable()
        val data = requireNotNull(dataDirectory(create = true)) { "Не удалось создать хранилище Избранного" }
        val id = UUID.randomUUID().toString()
        val target = File(data, "$id.$VAULT_EXTENSION")
        val temporary = File(data, ".$id.$VAULT_EXTENSION.tmp")
        var preserveTargetOnFailure = false
        try {
            encryptEntry(entry, temporary, id)
            if (!temporary.renameTo(target)) {
                copyFileAndSync(temporary, target)
                temporary.delete()
            }
            require(target.isFile && target.length() > MIN_CONTAINER_BYTES) {
                "Не удалось сохранить зашифрованную копию"
            }
            val item = readMetadata(target)

            val deleteConfirmed = runCatching { entry.document.delete() }.getOrDefault(false)
            if (deleteConfirmed) return item

            // Some SAF providers return false or throw after the file has already disappeared.
            // If the source is definitely still present we can safely roll the vault copy back and
            // preserve strict move semantics. If the state is false/unknown, keep the encrypted
            // copy: an occasional duplicate is safer than ever deleting the only surviving copy.
            val sourceStillExists = runCatching { entry.document.exists() }.getOrNull()
            if (sourceStillExists == true) {
                target.delete()
                throw IOException("Не удалось удалить исходный файл; перемещение в Избранное отменено")
            }

            preserveTargetOnFailure = true
            throw IOException(
                "Зашифрованная копия сохранена, но Android не подтвердил удаление исходника. " +
                    "Проверьте исходную папку; при необходимости удалите исходник вручную"
            )
        } catch (error: Throwable) {
            temporary.delete()
            if (!preserveTargetOnFailure) target.delete()
            throw error
        }
    }

    fun restore(item: Item, destination: DocumentFile): DocumentFile {
        require(destination.isDirectory && destination.canWrite()) { "Выбранная папка недоступна для записи" }
        val name = uniqueName(destination, item.name)
        val created = destination.createFile(item.mimeType ?: "application/octet-stream", name)
            ?: throw IOException("Не удалось создать $name")

        // Roll back the plaintext destination only while decrypt/write is still in progress.
        // After the vault copy has been asked to delete, never blindly delete the restored file:
        // preserving an extra copy is safer than losing the only remaining copy.
        try {
            val output = resolver.openOutputStream(created.uri, "w")
                ?: throw IOException("Не удалось открыть $name для записи")
            output.use { decryptData(item, it) }
        } catch (error: Throwable) {
            runCatching { created.delete() }
            throw error
        }

        val deleted = runCatching { item.encryptedFile.delete() }.getOrDefault(false)
        if (!deleted && item.encryptedFile.exists()) {
            throw IOException(
                "Файл возвращён в выбранную папку, но зашифрованная копия осталась в Избранном. " +
                    "Удалите её вручную после проверки возвращённого файла"
            )
        }
        return created
    }

    fun preparePlainFile(item: Item, purpose: String = DEFAULT_PLAIN_CACHE_FOLDER): File {
        val directory = File(context.cacheDir, purpose).apply { mkdirs() }
        cleanupOld(directory)
        val safe = safeName(item.name)
        val target = File(directory, "${UUID.randomUUID().toString().take(8)}-$safe")
        try {
            FileOutputStream(target).use { output -> decryptData(item, output) }
            if (item.modifiedAt > 0L) runCatching { target.setLastModified(item.modifiedAt) }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun delete(item: Item) {
        require(item.encryptedFile.delete()) { "Не удалось удалить ${item.name} из Избранного" }
    }

    fun toFileEntry(item: Item): FileEntry {
        val document = DocumentFile.fromFile(item.encryptedFile)
        return FileEntry(
            document = document,
            name = item.name,
            uri = Uri.fromFile(item.encryptedFile),
            isDirectory = false,
            mimeType = item.mimeType,
            size = item.size,
            modifiedAt = item.modifiedAt,
            parentUri = item.encryptedFile.parentFile?.let(Uri::fromFile),
            vaultItemId = item.id,
        )
    }

    /** Write only the bounded-memory AVF2 format. */
    private fun encryptEntry(entry: FileEntry, target: File, id: String) {
        val key = deriveKey()
        val magic = MAGIC_V2_BYTES
        val metaIv = randomBytes(GCM_IV_BYTES)
        val metadata = JSONObject()
            .put("id", id)
            .put("name", entry.name)
            .put("mimeType", entry.mimeType ?: JSONObject.NULL)
            .put("size", entry.size)
            .put("modifiedAt", entry.modifiedAt)
            .put("originalParentUri", entry.parentUri?.toString() ?: JSONObject.NULL)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val thumbnail = createThumbnail(entry)

        val metaCipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, metaIv))
            updateAAD(magic)
        }.doFinal(metadata)

        FileOutputStream(target).use { raw ->
            val input = resolver.openInputStream(entry.uri)
                ?: throw IOException("Не удалось прочитать ${entry.name}")
            input.use { source ->
                val buffered = BufferedOutputStream(raw)
                val header = DataOutputStream(buffered)
                writeMetadataHeader(header, magic, metaIv, metaCipher)
                writeThumbnailRecord(header, key, metaCipher, thumbnail)

                val plainBuffer = ByteArray(DATA_CHUNK_BYTES)
                var chunkIndex = 0L
                while (true) {
                    val plainLength = readChunk(source, plainBuffer)
                    if (plainLength <= 0) break
                    writeV2Record(
                        output = header,
                        key = key,
                        metaCipher = metaCipher,
                        chunkIndex = chunkIndex,
                        plain = plainBuffer,
                        plainLength = plainLength,
                    )
                    chunkIndex += 1L
                }

                // An authenticated zero-length record is an end marker. Its AAD contains the
                // final chunk index, so truncating/reordering records cannot produce a valid end.
                writeV2Record(
                    output = header,
                    key = key,
                    metaCipher = metaCipher,
                    chunkIndex = chunkIndex,
                    plain = EMPTY_BYTES,
                    plainLength = 0,
                )
                header.flush()
                raw.fd.sync()
            }
        }
    }

    private fun writeMetadataHeader(
        output: DataOutputStream,
        magic: ByteArray,
        metaIv: ByteArray,
        metaCipher: ByteArray,
    ) {
        output.write(magic)
        output.writeByte(metaIv.size)
        output.write(metaIv)
        output.writeInt(metaCipher.size)
        output.write(metaCipher)
    }

    private fun writeThumbnailRecord(
        output: DataOutputStream,
        key: SecretKeySpec,
        metaCipher: ByteArray,
        thumbnail: ByteArray?,
    ) {
        if (thumbnail == null || thumbnail.isEmpty()) {
            output.writeInt(0)
            return
        }
        require(thumbnail.size <= MAX_THUMBNAIL_BYTES) { "Слишком большая миниатюра сейфа" }
        val iv = randomBytes(GCM_IV_BYTES)
        val encrypted = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(MAGIC_V2_BYTES)
            updateAAD(metaCipher)
            updateAAD(THUMBNAIL_AAD)
        }.doFinal(thumbnail)
        output.writeInt(thumbnail.size)
        output.write(iv)
        output.write(encrypted)
    }

    private fun writeV2Record(
        output: DataOutputStream,
        key: SecretKeySpec,
        metaCipher: ByteArray,
        chunkIndex: Long,
        plain: ByteArray,
        plainLength: Int,
    ) {
        require(plainLength in 0..DATA_CHUNK_BYTES) { "Некорректный размер блока сейфа" }
        val iv = randomBytes(GCM_IV_BYTES)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(MAGIC_V2_BYTES)
            updateAAD(metaCipher)
            updateAAD(chunkAad(chunkIndex, plainLength))
        }
        // Android providers that buffer a whole GCM message can allocate at most one bounded
        // chunk (+ tag) here, never the size of the original file.
        val encrypted = cipher.doFinal(plain, 0, plainLength)
        require(encrypted.size == plainLength + GCM_TAG_BYTES) { "Некорректный размер зашифрованного блока" }
        output.writeInt(plainLength)
        output.write(iv)
        output.write(encrypted)
    }

    private fun readMetadata(file: File): Item {
        val key = deriveKey()
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val magic = readMagic(input)
            val metaIv = readSizedBytes(input, GCM_IV_BYTES, "IV метаданных")
            val metaLength = input.readInt()
            require(metaLength in 17..MAX_METADATA_CIPHER_BYTES) { "Повреждены метаданные сейфа" }
            val metaCipher = ByteArray(metaLength).also(input::readFully)

            val hasThumbnail = if (magic.contentEquals(MAGIC_V1_BYTES)) {
                readSizedBytes(input, GCM_IV_BYTES, "IV данных")
                false
            } else {
                val thumbnailLength = readThumbnailHeader(input, skipPayload = true)
                val firstLength = input.readInt()
                require(firstLength in 0..DATA_CHUNK_BYTES) { "Повреждён блок данных сейфа" }
                ByteArray(GCM_IV_BYTES).also(input::readFully)
                thumbnailLength > 0
            }

            val plain = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, metaIv))
                updateAAD(magic)
            }.doFinal(metaCipher)
            val json = JSONObject(String(plain, StandardCharsets.UTF_8))
            return Item(
                id = json.optString("id").ifBlank { file.nameWithoutExtension },
                name = json.getString("name"),
                mimeType = json.optString("mimeType").takeIf { it.isNotBlank() && it != "null" },
                size = json.optLong("size", -1L).coerceAtLeast(0L),
                modifiedAt = json.optLong("modifiedAt", 0L),
                originalParentUri = json.optString("originalParentUri").takeIf { it.isNotBlank() && it != "null" },
                encryptedFile = file,
                hasThumbnail = hasThumbnail,
            )
        }
    }

    fun loadThumbnail(item: Item): Bitmap? {
        if (!item.hasThumbnail) return null
        val key = deriveKey()
        DataInputStream(BufferedInputStream(FileInputStream(item.encryptedFile))).use { input ->
            val magic = readMagic(input)
            if (!magic.contentEquals(MAGIC_V2_BYTES)) return null
            readSizedBytes(input, GCM_IV_BYTES, "IV метаданных")
            val metaLength = input.readInt()
            require(metaLength in 17..MAX_METADATA_CIPHER_BYTES) { "Повреждены метаданные сейфа" }
            val metaCipher = ByteArray(metaLength).also(input::readFully)
            val plainLength = input.readInt()
            require(plainLength in 0..MAX_THUMBNAIL_BYTES) { "Повреждена миниатюра сейфа" }
            if (plainLength == 0) return null
            val iv = ByteArray(GCM_IV_BYTES).also(input::readFully)
            val encrypted = ByteArray(plainLength + GCM_TAG_BYTES).also(input::readFully)
            val plain = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                updateAAD(MAGIC_V2_BYTES)
                updateAAD(metaCipher)
                updateAAD(THUMBNAIL_AAD)
            }.doFinal(encrypted)
            return BitmapFactory.decodeByteArray(plain, 0, plain.size)
        }
    }

    private fun decryptData(item: Item, output: java.io.OutputStream) {
        val key = deriveKey()
        DataInputStream(BufferedInputStream(FileInputStream(item.encryptedFile))).use { input ->
            val magic = readMagic(input)
            readSizedBytes(input, GCM_IV_BYTES, "IV метаданных")
            val metaLength = input.readInt()
            require(metaLength in 17..MAX_METADATA_CIPHER_BYTES) { "Повреждены метаданные сейфа" }
            val metaCipher = ByteArray(metaLength).also(input::readFully)
            if (magic.contentEquals(MAGIC_V2_BYTES)) {
                readThumbnailHeader(input, skipPayload = true)
                decryptV2(input, output, key, metaCipher, item.size)
            } else {
                decryptV1(input, output, key, metaCipher)
            }
            output.flush()
        }
    }

    /** Backwards-compatible reader for AVF1 files created by 1.3.1. */
    private fun decryptV1(
        input: DataInputStream,
        output: java.io.OutputStream,
        key: SecretKeySpec,
        metaCipher: ByteArray,
    ) {
        val dataIv = readSizedBytes(input, GCM_IV_BYTES, "IV данных")
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, dataIv))
            updateAAD(MAGIC_V1_BYTES)
            updateAAD(metaCipher)
        }
        // AVF1 used a single GCM message. Keep the reader so existing small vault files survive
        // the upgrade, but all new writes are AVF2 to avoid provider-dependent whole-file memory.
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            val plain = cipher.update(buffer, 0, read)
            if (plain != null && plain.isNotEmpty()) output.write(plain)
        }
        val finalBytes = cipher.doFinal()
        if (finalBytes.isNotEmpty()) output.write(finalBytes)
    }

    private fun decryptV2(
        input: DataInputStream,
        output: java.io.OutputStream,
        key: SecretKeySpec,
        metaCipher: ByteArray,
        declaredSize: Long,
    ) {
        var chunkIndex = 0L
        var totalPlain = 0L
        while (true) {
            val plainLength = try {
                input.readInt()
            } catch (error: EOFException) {
                throw IOException("Зашифрованный файл обрезан: отсутствует завершающий блок", error)
            }
            require(plainLength in 0..DATA_CHUNK_BYTES) { "Повреждён размер блока данных сейфа" }
            val iv = ByteArray(GCM_IV_BYTES).also(input::readFully)
            val cipherLength = plainLength + GCM_TAG_BYTES
            val encrypted = ByteArray(cipherLength).also(input::readFully)
            val plain = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                updateAAD(MAGIC_V2_BYTES)
                updateAAD(metaCipher)
                updateAAD(chunkAad(chunkIndex, plainLength))
            }.doFinal(encrypted)
            require(plain.size == plainLength) { "Повреждён блок данных сейфа" }

            if (plainLength == 0) {
                require(input.read() == -1) { "После завершающего блока сейфа обнаружены лишние данные" }
                if (declaredSize > 0L) {
                    require(totalPlain == declaredSize) {
                        "Размер содержимого сейфа не совпадает с метаданными"
                    }
                }
                return
            }

            output.write(plain)
            totalPlain = Math.addExact(totalPlain, plainLength.toLong())
            chunkIndex = Math.addExact(chunkIndex, 1L)
        }
    }

    private fun readThumbnailHeader(input: DataInputStream, skipPayload: Boolean): Int {
        val plainLength = input.readInt()
        require(plainLength in 0..MAX_THUMBNAIL_BYTES) { "Повреждена миниатюра сейфа" }
        if (plainLength == 0) return 0
        ByteArray(GCM_IV_BYTES).also(input::readFully)
        if (skipPayload) skipFully(input, plainLength + GCM_TAG_BYTES)
        return plainLength
    }

    private fun skipFully(input: DataInputStream, bytes: Int) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skipBytes(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() < 0) throw EOFException("Зашифрованный файл обрезан")
                remaining -= 1
            }
        }
    }

    private fun createThumbnail(entry: FileEntry): ByteArray? {
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        val image = entry.mimeType?.startsWith("image/") == true || extension in IMAGE_EXTENSIONS
        val video = entry.mimeType?.startsWith("video/") == true || extension in VIDEO_EXTENSIONS
        if (!image && !video) return null
        val bitmap = try {
            if (image) decodeSampledThumbnail(entry) else decodeVideoThumbnail(entry)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Throwable) {
            null
        } ?: return null

        val normalized = try {
            scaleBitmap(bitmap, THUMBNAIL_EDGE)
        } catch (_: OutOfMemoryError) {
            bitmap.recycle()
            return null
        } catch (_: Throwable) {
            bitmap.recycle()
            return null
        }
        return try {
            ByteArrayOutputStream().use { bytes ->
                if (!normalized.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, bytes)) return null
                bytes.toByteArray().takeIf { it.size <= MAX_THUMBNAIL_BYTES }
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Throwable) {
            null
        } finally {
            if (normalized !== bitmap && !normalized.isRecycled) normalized.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun decodeSampledThumbnail(entry: FileEntry): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(entry.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width > MAX_THUMBNAIL_SOURCE_DIMENSION || height > MAX_THUMBNAIL_SOURCE_DIMENSION) return null
        if (width.toLong() * height.toLong() > MAX_THUMBNAIL_SOURCE_PIXELS) return null
        var sample = 1
        while (width / sample > THUMBNAIL_DECODE_EDGE || height / sample > THUMBNAIL_DECODE_EDGE) {
            if (sample >= MAX_THUMBNAIL_SAMPLE) break
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return resolver.openInputStream(entry.uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun decodeVideoThumbnail(entry: FileEntry): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, entry.uri)
            retriever.getScaledFrameAtTime(
                -1L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                THUMBNAIL_EDGE,
                THUMBNAIL_EDGE,
            )
        } finally {
            retriever.release()
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, edge: Int): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= edge || largest <= 0) return bitmap
        val scale = edge.toFloat() / largest.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun readMagic(input: DataInputStream): ByteArray {
        val magic = ByteArray(MAGIC_V2_BYTES.size)
        input.readFully(magic)
        require(
            magic.contentEquals(MAGIC_V1_BYTES) || magic.contentEquals(MAGIC_V2_BYTES)
        ) { "Неизвестный формат сейфа" }
        return magic
    }

    private fun chunkAad(chunkIndex: Long, plainLength: Int): ByteArray =
        ByteBuffer.allocate(CHUNK_AAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(chunkIndex)
            .putInt(plainLength)
            .array()

    private fun readChunk(input: java.io.InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            if (read == 0) {
                // InputStream is not supposed to return 0 for a non-empty request, but a few
                // ContentProvider wrappers do. Fall back to one-byte progress instead of spinning.
                val one = input.read()
                if (one < 0) break
                buffer[offset++] = one.toByte()
            } else {
                offset += read
            }
        }
        return offset
    }

    private fun deriveKey(): SecretKeySpec {
        val androidId = Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: throw GeneralSecurityException("Android не предоставил идентификатор устройства")
        val ikm = androidId.toByteArray(StandardCharsets.UTF_8)
        val zeroSalt = ByteArray(HKDF_HASH_BYTES)
        val extract = Mac.getInstance(HKDF_ALGORITHM).apply {
            init(SecretKeySpec(zeroSalt, HKDF_ALGORITHM))
        }.doFinal(ikm)
        val info = HKDF_INFO.toByteArray(StandardCharsets.UTF_8)
        val expand = Mac.getInstance(HKDF_ALGORITHM).apply {
            init(SecretKeySpec(extract, HKDF_ALGORITHM))
        }
        expand.update(info)
        expand.update(1.toByte())
        return SecretKeySpec(expand.doFinal().copyOf(AES_KEY_BYTES), "AES")
    }

    private fun isVaultUri(uri: Uri): Boolean {
        val path = uri.path?.replace('\\', '/') ?: return false
        return path.split('/').any { it.equals(VAULT_FOLDER, ignoreCase = false) }
    }

    private fun dataDirectory(create: Boolean): File? {
        val root = File(Environment.getExternalStorageDirectory(), VAULT_FOLDER)
        val data = File(root, DATA_FOLDER)
        if (create) {
            if (!data.exists() && !data.mkdirs()) return null
            File(root, NO_MEDIA_FILE).runCatchingCreate()
        }
        return data.takeIf { it.exists() && it.isDirectory }
    }

    private fun ensureAvailable() {
        require(available()) {
            "Для защищённого Избранного включите для Aura Files доступ «Весь накопитель»"
        }
    }

    private fun uniqueName(parent: DocumentFile, requested: String): String {
        if (parent.findFile(requested) == null) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val ext = if (dot > 0) requested.substring(dot) else ""
        for (index in 1..9999) {
            val candidate = "$base ($index)$ext"
            if (parent.findFile(candidate) == null) return candidate
        }
        return "$base-${System.currentTimeMillis()}$ext"
    }

    private fun readSizedBytes(input: DataInputStream, expected: Int, label: String): ByteArray {
        val size = input.readUnsignedByte()
        require(size == expected) { "Некорректный $label" }
        return ByteArray(size).also(input::readFully)
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun cleanupVaultTemps(directory: File) {
        val expiration = System.currentTimeMillis() - VAULT_TEMP_MAX_AGE_MS
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".tmp") && it.lastModified() < expiration }
            .forEach(File::delete)
    }

    private fun cleanupOld(directory: File) {
        val expiration = System.currentTimeMillis() - TEMP_PLAIN_MAX_AGE_MS
        directory.listFiles().orEmpty().filter { it.lastModified() < expiration }.forEach(File::delete)
    }

    private fun safeName(name: String): String = name
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim()
        .take(120)
        .ifBlank { "file" }

    private fun copyFileAndSync(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun File.runCatchingCreate() {
        if (!exists()) runCatching { createNewFile() }
    }

    companion object {
        const val VAULT_FOLDER = ".AuraVault"
        private const val DATA_FOLDER = "data"
        private const val NO_MEDIA_FILE = ".nomedia"
        private const val VAULT_EXTENSION = "avf"
        private const val DEFAULT_PLAIN_CACHE_FOLDER = "vault-open"
        private const val HKDF_ALGORITHM = "HmacSHA256"
        private const val HKDF_HASH_BYTES = 32
        private const val HKDF_INFO = "AuraVault-v1"
        private const val AES_KEY_BYTES = 32
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val DATA_CHUNK_BYTES = 1024 * 1024
        private const val CHUNK_AAD_BYTES = 8 + 4
        private const val MAX_METADATA_CIPHER_BYTES = 64 * 1024
        private const val THUMBNAIL_EDGE = 320
        private const val THUMBNAIL_DECODE_EDGE = 640
        private const val THUMBNAIL_JPEG_QUALITY = 78
        private const val MAX_THUMBNAIL_BYTES = 384 * 1024
        private const val MAX_THUMBNAIL_SOURCE_DIMENSION = 65_535
        private const val MAX_THUMBNAIL_SOURCE_PIXELS = 400_000_000L
        private const val MAX_THUMBNAIL_SAMPLE = 1 shl 15
        private const val MIN_CONTAINER_BYTES = 4 + 1 + GCM_IV_BYTES + 4 + 17 + 4 + 4 + GCM_IV_BYTES + GCM_TAG_BYTES
        private const val TEMP_PLAIN_MAX_AGE_MS = 2L * 60L * 60L * 1000L
        private const val VAULT_TEMP_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private val EMPTY_BYTES = ByteArray(0)
        private val THUMBNAIL_AAD = "AuraVault-thumbnail-v2".toByteArray(StandardCharsets.UTF_8)
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts", "m2ts")
        private val MAGIC_V1_BYTES = byteArrayOf('A'.code.toByte(), 'V'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
        private val MAGIC_V2_BYTES = byteArrayOf('A'.code.toByte(), 'V'.code.toByte(), 'F'.code.toByte(), '2'.code.toByte())
    }
}
