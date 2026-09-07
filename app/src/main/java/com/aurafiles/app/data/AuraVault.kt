package com.aurafiles.app.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.model.FileEntry
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Device-bound encrypted storage used by the UI labelled "Избранное".
 *
 * There is intentionally no persisted key file. The AES-256 key is deterministically derived
 * from ANDROID_ID with HKDF-SHA256 and a fixed application context string. On Android 8+ the
 * value is scoped to device + Android user + app signing key, so a normal reinstall signed with
 * the same key on the same device derives the same vault key while copying .AuraVault to another
 * device does not.
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
        ensureAvailable()
        val data = requireNotNull(dataDirectory(create = true)) { "Не удалось создать хранилище Избранного" }
        val id = UUID.randomUUID().toString()
        val target = File(data, "$id.$VAULT_EXTENSION")
        val temporary = File(data, ".$id.$VAULT_EXTENSION.tmp")
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
            if (!entry.document.delete()) {
                target.delete()
                throw IOException("Не удалось удалить исходный файл; перемещение в Избранное отменено")
            }
            return item
        } catch (error: Throwable) {
            // Until the source has been deleted the whole operation must remain transactional:
            // never leave a second, encrypted copy behind after a failed move.
            temporary.delete()
            target.delete()
            throw error
        }
    }

    fun restore(item: Item, destination: DocumentFile): DocumentFile {
        require(destination.isDirectory && destination.canWrite()) { "Выбранная папка недоступна для записи" }
        val name = uniqueName(destination, item.name)
        val created = destination.createFile(item.mimeType ?: "application/octet-stream", name)
            ?: throw IOException("Не удалось создать $name")
        try {
            val output = resolver.openOutputStream(created.uri, "w")
                ?: throw IOException("Не удалось открыть $name для записи")
            output.use { decryptData(item, it) }
            if (!item.encryptedFile.delete()) {
                created.delete()
                throw IOException("Файл расшифрован, но не удалось удалить его из Избранного; операция отменена")
            }
            return created
        } catch (error: Throwable) {
            runCatching { created.delete() }
            throw error
        }
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

    private fun encryptEntry(entry: FileEntry, target: File, id: String) {
        val key = deriveKey()
        val metaIv = randomBytes(GCM_IV_BYTES)
        val dataIv = randomBytes(GCM_IV_BYTES)
        val metadata = JSONObject()
            .put("id", id)
            .put("name", entry.name)
            .put("mimeType", entry.mimeType ?: JSONObject.NULL)
            .put("size", entry.size)
            .put("modifiedAt", entry.modifiedAt)
            .put("originalParentUri", entry.parentUri?.toString() ?: JSONObject.NULL)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        val metaCipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, metaIv))
            updateAAD(MAGIC_BYTES)
        }.doFinal(metadata)

        val input = resolver.openInputStream(entry.uri)
            ?: throw IOException("Не удалось прочитать ${entry.name}")
        FileOutputStream(target).use { raw ->
            val buffered = BufferedOutputStream(raw)
            val header = DataOutputStream(buffered)
            header.write(MAGIC_BYTES)
            header.writeByte(metaIv.size)
            header.write(metaIv)
            header.writeInt(metaCipher.size)
            header.write(metaCipher)
            header.writeByte(dataIv.size)
            header.write(dataIv)
            header.flush()

            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, dataIv))
                updateAAD(MAGIC_BYTES)
                updateAAD(metaCipher)
            }
            input.use { source ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    val encrypted = cipher.update(buffer, 0, read)
                    if (encrypted != null && encrypted.isNotEmpty()) buffered.write(encrypted)
                }
                val finalBytes = cipher.doFinal()
                if (finalBytes.isNotEmpty()) buffered.write(finalBytes)
            }
            buffered.flush()
            raw.fd.sync()
        }
    }

    private fun readMetadata(file: File): Item {
        val key = deriveKey()
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val magic = ByteArray(MAGIC_BYTES.size)
            input.readFully(magic)
            require(magic.contentEquals(MAGIC_BYTES)) { "Неизвестный формат сейфа" }
            val metaIv = readSizedBytes(input, GCM_IV_BYTES, "IV метаданных")
            val metaLength = input.readInt()
            require(metaLength in 17..MAX_METADATA_CIPHER_BYTES) { "Повреждены метаданные сейфа" }
            val metaCipher = ByteArray(metaLength).also(input::readFully)
            // Data IV follows metadata. Reading it here validates the header structure without
            // decrypting the potentially large payload.
            readSizedBytes(input, GCM_IV_BYTES, "IV данных")

            val plain = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, metaIv))
                updateAAD(MAGIC_BYTES)
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
            )
        }
    }

    private fun decryptData(item: Item, output: java.io.OutputStream) {
        val key = deriveKey()
        DataInputStream(BufferedInputStream(FileInputStream(item.encryptedFile))).use { input ->
            val magic = ByteArray(MAGIC_BYTES.size)
            input.readFully(magic)
            require(magic.contentEquals(MAGIC_BYTES)) { "Неизвестный формат сейфа" }
            readSizedBytes(input, GCM_IV_BYTES, "IV метаданных")
            val metaLength = input.readInt()
            require(metaLength in 17..MAX_METADATA_CIPHER_BYTES) { "Повреждены метаданные сейфа" }
            val metaCipher = ByteArray(metaLength).also(input::readFully)
            val dataIv = readSizedBytes(input, GCM_IV_BYTES, "IV данных")
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, dataIv))
                updateAAD(MAGIC_BYTES)
                updateAAD(metaCipher)
            }
            // Do not use CipherInputStream for GCM here. We need doFinal() to run explicitly so
            // authentication/tag failures are propagated to the caller instead of ever being
            // mistaken for a successful restore/share. Callers delete any partially written
            // plaintext if doFinal() fails.
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val plain = cipher.update(buffer, 0, read)
                if (plain != null && plain.isNotEmpty()) output.write(plain)
            }
            val finalBytes = cipher.doFinal()
            if (finalBytes.isNotEmpty()) output.write(finalBytes)
            output.flush()
        }
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
        private const val MAX_METADATA_CIPHER_BYTES = 64 * 1024
        private const val MIN_CONTAINER_BYTES = 4 + 1 + GCM_IV_BYTES + 4 + 17 + 1 + GCM_IV_BYTES + 16
        private const val TEMP_PLAIN_MAX_AGE_MS = 2L * 60L * 60L * 1000L
        private const val VAULT_TEMP_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private val MAGIC_BYTES = byteArrayOf('A'.code.toByte(), 'V'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
    }
}
