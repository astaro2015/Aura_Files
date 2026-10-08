package com.aurafiles.app.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
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
 * Device-bound encrypted storage used by the UI labelled "Сейф".
 *
 * There is intentionally no persisted key file. The AES-256 key is deterministically derived
 * from ANDROID_ID with HKDF-SHA256 and a fixed application context string. On Android 8+ the
 * value is scoped to device + Android user + app signing key, so a normal reinstall signed with
 * the same key on the same device derives the same vault key while copying .AuraSafe to another
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
    private val restoreJournalStore = VaultRestoreJournalStore(context.noBackupFilesDir)

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
        recoverPendingRestores()
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
        require(!entry.isDirectory) { "Папки пока нельзя помещать в Сейф" }
        require(entry.vaultItemId == null && !isVaultUri(entry.uri)) { "Файл уже находится в Сейфе" }
        ensureAvailable()
        val data = requireNotNull(dataDirectory(create = true)) { "Не удалось создать Сейф" }
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
                throw IOException("Не удалось удалить исходный файл; перемещение в Сейф отменено")
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
        DocumentTreeSafety.requireNotFilesystemSymlink(destination, "Восстановление из Сейфа")
        val finalName = uniqueName(destination, item.name)
        val permissionOwned = acquireRestorePermissionIfNeeded(destination.uri)
        val serviceName = allocateRestoreTemporaryName(destination)
        val created = destination.createFile(item.mimeType ?: "application/octet-stream", serviceName)
            ?: run {
                releaseRestorePermissionIfOwned(destination.uri, permissionOwned)
                throw IOException("Не удалось создать временный файл для $finalName")
            }
        val temporaryName = created.name ?: serviceName
        if (!temporaryName.startsWith(VAULT_RESTORE_TEMP_PREFIX)) {
            runCatching { created.delete() }
            releaseRestorePermissionIfOwned(destination.uri, permissionOwned)
            throw IOException("Провайдер изменил служебное имя восстановления; безопасное восстановление невозможно")
        }

        var journal: VaultRestoreJournal? = null
        try {
            journal = restoreJournalStore.create(
                destinationUri = destination.uri.toString(),
                temporaryName = temporaryName,
                finalName = finalName,
                vaultFileName = item.encryptedFile.name,
                releasePersistedPermission = permissionOwned,
            )
            val output = resolver.openOutputStream(created.uri, "w")
                ?: throw IOException("Не удалось открыть временный файл для $finalName")
            output.use { decryptData(item, it) }

            journal = restoreJournalStore.update(journal, VaultRestorePhase.FINALIZE_PENDING)
            val final = when (renameVaultRestoreAndProbe(destination, created, temporaryName, finalName)) {
                VaultRenameOutcome.COMMITTED -> findUniqueChildStrict(destination, finalName)
                    ?: throw IOException("Восстановленный файл $finalName больше не виден в папке")
                VaultRenameOutcome.NOT_COMMITTED -> {
                    cleanupVaultRestoreTemporary(destination, created, temporaryName)
                    restoreJournalStore.remove(journal)
                    releaseRestorePermissionIfOwned(destination.uri, permissionOwned)
                    throw IOException("Не удалось завершить восстановление $finalName")
                }
                VaultRenameOutcome.AMBIGUOUS -> throw IOException(
                    "Результат финализации $finalName неоднозначен; Aura сохраняет журнал и ничего не удаляет"
                )
            }
            journal = restoreJournalStore.update(journal, VaultRestorePhase.FINALIZED)

            val deleted = runCatching { item.encryptedFile.delete() }.getOrDefault(false)
            if (!deleted && item.encryptedFile.exists()) {
                restoreJournalStore.remove(journal)
                releaseRestorePermissionIfOwned(destination.uri, permissionOwned)
                throw IOException(
                    "Файл возвращён в выбранную папку, но зашифрованная копия осталась в Сейфе. " +
                        "Удалите её вручную после проверки возвращённого файла"
                )
            }
            restoreJournalStore.remove(journal)
            releaseRestorePermissionIfOwned(destination.uri, permissionOwned)
            return final
        } catch (error: Throwable) {
            val record = journal
            if (record == null) {
                runCatching { created.delete() }
                releaseRestorePermissionIfOwned(destination.uri, permissionOwned)
            } else if (record.phase == VaultRestorePhase.WRITING && item.encryptedFile.exists()) {
                // Decryption did not reach finalization and the encrypted source is still proven.
                // It is safe to remove our partial plaintext staging file immediately.
                val cleaned = runCatching {
                    cleanupVaultRestoreTemporary(destination, created, temporaryName)
                    true
                }.getOrDefault(false)
                if (cleaned) {
                    runCatching { restoreJournalStore.remove(record) }
                    releaseRestorePermissionIfOwned(destination.uri, permissionOwned)
                }
            }
            throw error
        }
    }

    fun preparePlainFile(item: Item, purpose: String = DEFAULT_PLAIN_CACHE_FOLDER): File {
        val directory = File(context.cacheDir, purpose).apply { mkdirs() }
        cleanupOld(directory)
        requireCacheSpace(directory, item.size)
        val safe = safeName(item.name)
        val target = File(directory, "${UUID.randomUUID().toString().take(8)}-$safe")
        try {
            FileOutputStream(target).use { output -> decryptData(item, output) }
            // Keep the cache file timestamp as its staging time. The original modified time lives
            // in encrypted metadata/FileEntry; reusing it here would make an old photo look like
            // an expired plaintext cache file immediately and could delete it while a viewer reads it.
            runCatching { target.setLastModified(System.currentTimeMillis()) }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun delete(item: Item) {
        require(item.encryptedFile.delete()) { "Не удалось удалить ${item.name} из Сейфа" }
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
                var totalPlainBytes = 0L
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
                    totalPlainBytes = Math.addExact(totalPlainBytes, plainLength.toLong())
                    chunkIndex += 1L
                }
                if (entry.size > 0L && totalPlainBytes != entry.size) {
                    throw IOException(
                        "Источник ${entry.name} изменился во время чтения: " +
                            "прочитано $totalPlainBytes из ${entry.size} байт"
                    )
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

        val oriented = if (image) applyThumbnailOrientation(entry, bitmap) else bitmap
        val normalized = try {
            scaleBitmap(oriented, THUMBNAIL_EDGE)
        } catch (_: OutOfMemoryError) {
            if (!oriented.isRecycled) oriented.recycle()
            return null
        } catch (_: Throwable) {
            if (!oriented.isRecycled) oriented.recycle()
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
            if (normalized !== oriented && !normalized.isRecycled) normalized.recycle()
            if (!oriented.isRecycled) oriented.recycle()
            if (oriented !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun applyThumbnailOrientation(entry: FileEntry, bitmap: Bitmap): Bitmap {
        val exif = runCatching {
            if (entry.uri.scheme == "file") {
                ExifInterface(requireNotNull(entry.uri.path))
            } else {
                val descriptor = resolver.openFileDescriptor(entry.uri, "r")
                    ?: throw IOException("Не удалось открыть EXIF")
                descriptor.use { ExifInterface(it.fileDescriptor) }
            }
        }.getOrNull() ?: return bitmap
        if (exif.rotationDegrees == 0 && !exif.isFlipped) return bitmap
        val matrix = Matrix().apply {
            if (exif.isFlipped) postScale(-1f, 1f)
            if (exif.rotationDegrees != 0) postRotate(exif.rotationDegrees.toFloat())
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: OutOfMemoryError) {
            bitmap
        } catch (_: RuntimeException) {
            bitmap
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
        val path = uri.path ?: return false
        return VaultStoragePaths.isVaultPath(path)
    }

    private fun dataDirectory(create: Boolean): File? {
        val storageRoot = Environment.getExternalStorageDirectory()
        val data = VaultStoragePaths.dataDirectory(storageRoot, create) ?: return null
        if (create) {
            File(data.parentFile, NO_MEDIA_FILE).runCatchingCreate()
        }
        return data
    }

    private fun ensureAvailable() {
        require(available()) {
            "Для Сейфа включите для Aura Files доступ «Весь накопитель»"
        }
    }

    private fun recoverPendingRestores() {
        restoreJournalStore.list().forEach { record ->
            val destinationUri = Uri.parse(record.destinationUri)
            val destination = when (destinationUri.scheme) {
                "file" -> destinationUri.path?.let { DocumentFile.fromFile(File(it)) }
                else -> DocumentFile.fromTreeUri(context, destinationUri)
            } ?: throw IOException("Папка незавершённого восстановления Vault недоступна")
            DocumentTreeSafety.requireNotFilesystemSymlink(destination, "Восстановление Vault после сбоя")
            val temporary = findUniqueChildStrict(destination, record.temporaryName)
            val final = findUniqueChildStrict(destination, record.finalName)
            val vaultSource = dataDirectory(create = false)?.let { File(it, record.vaultFileName) }
            val encryptedSourceExists = vaultSource?.isFile == true

            when (record.phase) {
                VaultRestorePhase.WRITING -> when {
                    final != null -> throw IOException(
                        "Журнал восстановления ${record.finalName} противоречив: финальный файл появился до фазы финализации; " +
                            "Aura ничего не удаляет"
                    )
                    temporary != null && !encryptedSourceExists -> throw IOException(
                        "Найден частичный plaintext ${record.temporaryName}, но зашифрованный оригинал не подтверждён; " +
                            "Aura ничего не удаляет"
                    )
                    temporary != null -> cleanupVaultRestoreTemporary(destination, temporary, record.temporaryName)
                    !encryptedSourceExists -> throw IOException(
                        "Не удалось подтвердить ни зашифрованный оригинал, ни временную, ни финальную копию ${record.finalName}; " +
                            "журнал сохранён для ручной проверки"
                    )
                }
                VaultRestorePhase.FINALIZE_PENDING -> when {
                    final != null && temporary != null -> throw IOException(
                        "Одновременно видны финальная и временная копии ${record.finalName}; Aura сохраняет обе и журнал"
                    )
                    final != null -> Unit // rename committed before ACK/phase fsync
                    temporary != null && encryptedSourceExists ->
                        cleanupVaultRestoreTemporary(destination, temporary, record.temporaryName)
                    temporary != null -> throw IOException(
                        "Состояние восстановления ${record.finalName} неоднозначно; временный plaintext сохранён"
                    )
                    !encryptedSourceExists -> throw IOException(
                        "Не удалось подтвердить ни зашифрованный оригинал, ни временную, ни финальную копию ${record.finalName}; " +
                            "журнал сохранён для ручной проверки"
                    )
                }
                VaultRestorePhase.FINALIZED -> when {
                    final != null && temporary != null -> throw IOException(
                        "После подтверждённой финализации одновременно видны две plaintext-копии ${record.finalName}; " +
                            "Aura ничего не удаляет"
                    )
                    final != null -> Unit
                    temporary != null -> throw IOException(
                        "Финализация ${record.finalName} была подтверждена, но вместо финального файла виден только staging; " +
                            "Aura ничего не удаляет"
                    )
                    encryptedSourceExists -> Unit // restored file disappeared externally, but encrypted source remains safe
                    else -> throw IOException(
                        "После подтверждённой финализации не найдена ни одна копия ${record.finalName}; " +
                            "журнал сохранён для ручной проверки"
                    )
                }
            }
            restoreJournalStore.remove(record)
            releaseRestorePermissionIfOwned(destinationUri, record.releasePersistedPermission)
        }
    }

    private fun acquireRestorePermissionIfNeeded(destinationUri: Uri): Boolean {
        if (destinationUri.scheme != "content") return false
        val permissionUri = restorePermissionUri(destinationUri)
        val alreadyPersisted = resolver.persistedUriPermissions.any { permission ->
            permission.uri == permissionUri && permission.isWritePermission
        }
        if (alreadyPersisted) return false
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(permissionUri, flags)
        } catch (error: Exception) {
            throw IOException(
                "Провайдер не разрешил сохранить доступ к папке до завершения восстановления; " +
                    "Aura не создаёт plaintext без возможности очистить его после сбоя",
                error,
            )
        }
        return true
    }

    private fun releaseRestorePermissionIfOwned(destinationUri: Uri, owned: Boolean) {
        if (!owned || destinationUri.scheme != "content") return
        val permissionUri = restorePermissionUri(destinationUri)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.releasePersistableUriPermission(permissionUri, flags) }
    }

    private fun restorePermissionUri(destinationUri: Uri): Uri {
        // OpenDocumentTree grants /tree/<id>, whereas DocumentFile exposes
        // /tree/<id>/document/<id>. Android persists only the exact granted URI.
        // Keep the destination document in the journal, but acquire/release its
        // tree grant so the same permission remains usable after process death.
        if (!DocumentsContract.isTreeUri(destinationUri)) return destinationUri
        val authority = destinationUri.authority
            ?: throw IOException("Не удалось определить провайдер выбранной папки")
        return DocumentsContract.buildTreeDocumentUri(
            authority,
            DocumentsContract.getTreeDocumentId(destinationUri),
        )
    }

    private enum class VaultRenameOutcome { COMMITTED, NOT_COMMITTED, AMBIGUOUS }

    private fun renameVaultRestoreAndProbe(
        parent: DocumentFile,
        temporary: DocumentFile,
        temporaryName: String,
        finalName: String,
    ): VaultRenameOutcome {
        try {
            temporary.renameTo(finalName)
        } catch (_: Exception) {
            // Reconcile provider lost-ACK below.
        }
        val oldProbe = runCatching { findUniqueChildStrict(parent, temporaryName) }
        val finalProbe = runCatching { findUniqueChildStrict(parent, finalName) }
        if (oldProbe.isFailure || finalProbe.isFailure) return VaultRenameOutcome.AMBIGUOUS
        val oldExists = oldProbe.getOrNull() != null
        val finalExists = finalProbe.getOrNull() != null
        return when {
            !oldExists && finalExists -> VaultRenameOutcome.COMMITTED
            oldExists && !finalExists -> VaultRenameOutcome.NOT_COMMITTED
            else -> VaultRenameOutcome.AMBIGUOUS
        }
    }

    private fun cleanupVaultRestoreTemporary(parent: DocumentFile, temporary: DocumentFile, temporaryName: String) {
        val existing = findUniqueChildStrict(parent, temporaryName) ?: return
        if (!DocumentTreeSafety.sameIdentity(existing.uri, temporary.uri)) {
            throw IOException("Служебное имя $temporaryName уже занято другим объектом; Aura ничего не удаляет")
        }
        try {
            existing.delete()
        } catch (_: Exception) {
            // Probe below.
        }
        if (findUniqueChildStrict(parent, temporaryName) != null) {
            throw IOException("Не удалось безопасно удалить временный plaintext $temporaryName")
        }
    }

    private fun findUniqueChildStrict(parent: DocumentFile, name: String): DocumentFile? {
        val matches = FastDocumentListing.listStrict(context, parent).filter { it.name == name }
        if (matches.size > 1) throw IOException("Имя $name неоднозначно: найдено объектов ${matches.size}")
        return matches.singleOrNull()?.document
    }

    private fun allocateRestoreTemporaryName(parent: DocumentFile): String {
        repeat(VAULT_RESTORE_TEMP_ATTEMPTS) {
            val candidate = "$VAULT_RESTORE_TEMP_PREFIX${UUID.randomUUID()}"
            if (findUniqueChildStrict(parent, candidate) == null) return candidate
        }
        throw IOException("Не удалось подобрать безопасное служебное имя восстановления")
    }

    private fun uniqueName(parent: DocumentFile, requested: String): String {
        if (findUniqueChildStrict(parent, requested) == null) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val ext = if (dot > 0) requested.substring(dot) else ""
        for (index in 1..9999) {
            val candidate = "$base ($index)$ext"
            if (findUniqueChildStrict(parent, candidate) == null) return candidate
        }
        throw IOException("Не удалось подобрать уникальное имя для $requested")
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
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.lastModified() > 0L && it.lastModified() < expiration }
            .forEach(File::delete)
    }

    private fun requireCacheSpace(directory: File, expectedBytes: Long) {
        val usable = directory.usableSpace
        require(usable > PLAIN_CACHE_SPACE_RESERVE_BYTES) { "Недостаточно свободного места для временного просмотра" }
        if (expectedBytes > 0L) {
            require(expectedBytes <= usable - PLAIN_CACHE_SPACE_RESERVE_BYTES) {
                "Недостаточно свободного места для временного просмотра ${formatBytes(expectedBytes)}"
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mib = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mib >= 1024.0) "%.1f ГБ".format(mib / 1024.0) else "%.0f МБ".format(mib)
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
        const val VAULT_FOLDER = VaultStoragePaths.FOLDER
        const val LEGACY_VAULT_FOLDER = VaultStoragePaths.LEGACY_FOLDER
        fun isVaultFolder(name: String): Boolean = VaultStoragePaths.isVaultFolder(name)
        private const val NO_MEDIA_FILE = ".nomedia"
        private const val VAULT_EXTENSION = "avf"
        private const val DEFAULT_PLAIN_CACHE_FOLDER = "vault-open"
        private const val VAULT_RESTORE_TEMP_PREFIX = ".aura-vault-restore-"
        private const val VAULT_RESTORE_TEMP_ATTEMPTS = 64
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
        private const val PLAIN_CACHE_SPACE_RESERVE_BYTES = 256L * 1024L * 1024L
        private const val VAULT_TEMP_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private val EMPTY_BYTES = ByteArray(0)
        private val THUMBNAIL_AAD = "AuraVault-thumbnail-v2".toByteArray(StandardCharsets.UTF_8)
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts", "m2ts")
        private val MAGIC_V1_BYTES = byteArrayOf('A'.code.toByte(), 'V'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
        private val MAGIC_V2_BYTES = byteArrayOf('A'.code.toByte(), 'V'.code.toByte(), 'F'.code.toByte(), '2'.code.toByte())
    }
}
