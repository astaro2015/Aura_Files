package com.aurafiles.app.archive

import android.content.ContentResolver
import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.data.ArchiveSafety
import com.aurafiles.app.model.FileEntry
import com.github.junrar.Archive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream

enum class ArchiveFormat(val extension: String, val mimeType: String) {
    ZIP(".zip", "application/zip"),
    TAR(".tar", "application/x-tar"),
    TAR_GZ(".tar.gz", "application/gzip"),
    TAR_BZ2(".tar.bz2", "application/x-bzip2"),
    TAR_XZ(".tar.xz", "application/x-xz"),
    GZIP(".gz", "application/gzip"),
    BZIP2(".bz2", "application/x-bzip2"),
    XZ(".xz", "application/x-xz"),
}

data class ArchiveVirtualEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
)

class ExtendedArchiveRepository(private val context: Context) {
    private val resolver = context.contentResolver

    fun detectFormat(name: String): ArchiveFormat? {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> ArchiveFormat.TAR_GZ
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> ArchiveFormat.TAR_BZ2
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> ArchiveFormat.TAR_XZ
            lower.endsWith(".tar") -> ArchiveFormat.TAR
            lower.endsWith(".bz2") -> ArchiveFormat.BZIP2
            lower.endsWith(".xz") -> ArchiveFormat.XZ
            lower.endsWith(".gz") -> ArchiveFormat.GZIP
            lower.endsWith(".zip") -> ArchiveFormat.ZIP
            else -> null
        }
    }

    fun canBrowse(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".zip") || lower.endsWith(".tar") || lower.endsWith(".tar.gz") ||
            lower.endsWith(".tgz") || lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") ||
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") || lower.endsWith(".7z") || lower.endsWith(".rar") ||
            lower.endsWith(".gz") || lower.endsWith(".bz2") || lower.endsWith(".xz")
    }

    fun canExtract(name: String): Boolean = canBrowse(name)

    fun create(
        entries: List<FileEntry>,
        destination: DocumentFile,
        requestedName: String,
        explicitFormat: ArchiveFormat? = null,
    ): DocumentFile {
        require(entries.isNotEmpty()) { "Выберите файлы для архива" }
        require(destination.isDirectory && destination.canWrite()) { "Папка недоступна для записи" }
        val inferred = explicitFormat ?: detectFormat(requestedName) ?: ArchiveFormat.ZIP
        if (inferred in SINGLE_STREAM_FORMATS) {
            require(entries.size == 1 && !entries.first().isDirectory) {
                "GZ, BZ2 и XZ сжимают один файл. Для нескольких файлов используйте ZIP, TAR или TAR.GZ"
            }
        }
        val clean = requestedName.trim().ifBlank { "Архив" }
        val finalName = normalizeArchiveName(clean, inferred)
        val target = createUniqueFile(destination, inferred.mimeType, finalName)
        try {
            openOutput(target).buffered(1024 * 1024).use { raw ->
                when (inferred) {
                    ArchiveFormat.ZIP -> createZip(entries, raw)
                    ArchiveFormat.TAR -> createTar(entries, raw)
                    ArchiveFormat.TAR_GZ -> GzipCompressorOutputStream(raw).use { gzip -> createTar(entries, gzip) }
                    ArchiveFormat.TAR_BZ2 -> BZip2CompressorOutputStream(raw).use { bzip2 -> createTar(entries, bzip2) }
                    ArchiveFormat.TAR_XZ -> XZCompressorOutputStream(raw).use { xz -> createTar(entries, xz) }
                    ArchiveFormat.GZIP -> GzipCompressorOutputStream(raw).use { compressed ->
                        openInput(entries.single().document).use { input -> input.copyTo(compressed, IO_BUFFER_SIZE) }
                    }
                    ArchiveFormat.BZIP2 -> BZip2CompressorOutputStream(raw).use { compressed ->
                        openInput(entries.single().document).use { input -> input.copyTo(compressed, IO_BUFFER_SIZE) }
                    }
                    ArchiveFormat.XZ -> XZCompressorOutputStream(raw).use { compressed ->
                        openInput(entries.single().document).use { input -> input.copyTo(compressed, IO_BUFFER_SIZE) }
                    }
                }
            }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun extract(entry: FileEntry, destination: DocumentFile): DocumentFile {
        require(!entry.isDirectory && canExtract(entry.name)) { "Формат архива пока не поддерживается" }
        require(destination.isDirectory && destination.canWrite()) { "Папка недоступна для записи" }
        val singleStreamFormat = detectFormat(entry.name)?.takeIf { it in SINGLE_STREAM_FORMATS }
        if (singleStreamFormat != null) return extractSingleStream(entry, destination, singleStreamFormat)
        val folderBase = when {
            entry.name.endsWith(".tar.gz", true) -> entry.name.dropLast(7)
            entry.name.endsWith(".tgz", true) -> entry.name.dropLast(4)
            entry.name.endsWith(".tar.bz2", true) -> entry.name.dropLast(8)
            entry.name.endsWith(".tbz2", true) -> entry.name.dropLast(5)
            entry.name.endsWith(".tar.xz", true) -> entry.name.dropLast(7)
            entry.name.endsWith(".txz", true) -> entry.name.dropLast(4)
            else -> entry.name.substringBeforeLast('.', entry.name)
        }.ifBlank { "Архив" }
        val root = createUniqueDirectory(destination, folderBase)
        try {
            when {
                entry.name.endsWith(".rar", true) -> extractRar(entry, root)
                entry.name.endsWith(".7z", true) -> extract7z(entry, root)
                entry.name.endsWith(".tar.gz", true) || entry.name.endsWith(".tgz", true) -> {
                    openInput(entry).buffered(1024 * 1024).use { raw ->
                        openGzip(raw).use { gzip -> extractTar(gzip, root) }
                    }
                }
                entry.name.endsWith(".tar.bz2", true) || entry.name.endsWith(".tbz2", true) -> {
                    openInput(entry).buffered(IO_BUFFER_SIZE).use { raw ->
                        BZip2CompressorInputStream(raw, true).use { bzip2 -> extractTar(bzip2, root) }
                    }
                }
                entry.name.endsWith(".tar.xz", true) || entry.name.endsWith(".txz", true) -> {
                    openInput(entry).buffered(IO_BUFFER_SIZE).use { raw ->
                        openXz(raw).use { xz -> extractTar(xz, root) }
                    }
                }
                entry.name.endsWith(".tar", true) -> openInput(entry).buffered(1024 * 1024).use { extractTar(it, root) }
                else -> openInput(entry).buffered(1024 * 1024).use { extractZip(it, root) }
            }
            return root
        } catch (error: Throwable) {
            deleteTree(root)
            throw error
        }
    }

    fun listEntries(entry: FileEntry): List<ArchiveVirtualEntry> {
        require(!entry.isDirectory && canBrowse(entry.name)) { "Формат архива пока не поддерживается" }
        if (detectFormat(entry.name)?.let { it in SINGLE_STREAM_FORMATS } == true) {
            val name = singleStreamOutputName(entry.name)
            return listOf(ArchiveVirtualEntry(path = name, name = name, isDirectory = false, size = -1L))
        }
        val nodes = linkedMapOf<String, ArchiveVirtualEntry>()
        var rawEntryCount = 0
        fun add(rawName: String, directory: Boolean, size: Long) {
            rawEntryCount += 1
            require(rawEntryCount <= MAX_ARCHIVE_ENTRIES) { "В архиве слишком много объектов" }
            val parts = runCatching { ArchiveSafety.safePath(rawName) }.getOrNull() ?: return
            if (parts.isEmpty()) return
            var path = ""
            parts.forEachIndexed { index, segment ->
                path = if (path.isEmpty()) segment else "$path/$segment"
                if (index < parts.lastIndex) {
                    nodes.putIfAbsent(path, ArchiveVirtualEntry(path, segment, true, 0L))
                } else {
                    nodes[path] = ArchiveVirtualEntry(path, segment, directory, if (directory) 0L else size.coerceAtLeast(0L))
                }
            }
        }

        when {
            entry.name.endsWith(".rar", true) -> openInput(entry).buffered(1024 * 1024).use { raw ->
                Archive(raw).use { archive ->
                    archive.fileHeaders.forEach { header ->
                        add(header.fileName, header.isDirectory, header.fullUnpackSize)
                    }
                }
            }
            entry.name.endsWith(".7z", true) -> withTemporaryArchive(entry, ".7z") { temporary ->
                SevenZFile.builder().setFile(temporary).setMaxMemoryLimitKiB(MAX_7Z_MEMORY_KIB).get().use { archive ->
                    while (true) {
                        val item = archive.nextEntry ?: break
                        add(item.name, item.isDirectory, item.size)
                    }
                }
            }
            entry.name.endsWith(".tar.gz", true) || entry.name.endsWith(".tgz", true) ->
                openInput(entry).buffered(1024 * 1024).use { raw ->
                    openGzip(raw).use { gzip ->
                        TarArchiveInputStream(gzip).use { archive ->
                            while (true) {
                                val item = archive.nextEntry ?: break
                                if (item.isDirectory || (item.isFile && !item.isSymbolicLink && !item.isLink)) {
                                    add(item.name, item.isDirectory, item.size)
                                }
                            }
                        }
                    }
                }
            entry.name.endsWith(".tar.bz2", true) || entry.name.endsWith(".tbz2", true) ->
                openInput(entry).buffered(IO_BUFFER_SIZE).use { raw ->
                    BZip2CompressorInputStream(raw, true).use { bzip2 ->
                        TarArchiveInputStream(bzip2).use { archive ->
                            while (true) {
                                val item = archive.nextEntry ?: break
                                if (item.isDirectory || (item.isFile && !item.isSymbolicLink && !item.isLink)) {
                                    add(item.name, item.isDirectory, item.size)
                                }
                            }
                        }
                    }
                }
            entry.name.endsWith(".tar.xz", true) || entry.name.endsWith(".txz", true) ->
                openInput(entry).buffered(IO_BUFFER_SIZE).use { raw ->
                    openXz(raw).use { xz ->
                        TarArchiveInputStream(xz).use { archive ->
                            while (true) {
                                val item = archive.nextEntry ?: break
                                if (item.isDirectory || (item.isFile && !item.isSymbolicLink && !item.isLink)) {
                                    add(item.name, item.isDirectory, item.size)
                                }
                            }
                        }
                    }
                }
            entry.name.endsWith(".tar", true) -> openInput(entry).buffered(1024 * 1024).use { raw ->
                TarArchiveInputStream(raw).use { archive ->
                    while (true) {
                        val item = archive.nextEntry ?: break
                        if (item.isDirectory || (item.isFile && !item.isSymbolicLink && !item.isLink)) {
                            add(item.name, item.isDirectory, item.size)
                        }
                    }
                }
            }
            else -> openInput(entry).buffered(1024 * 1024).use { raw ->
                ZipArchiveInputStream(raw).use { archive ->
                    while (true) {
                        val item = archive.nextZipEntry ?: break
                        if (!item.isUnixSymlink) add(item.name, item.isDirectory, item.size)
                    }
                }
            }
        }
        return nodes.values.sortedWith(compareByDescending<ArchiveVirtualEntry> { it.isDirectory }.thenBy { it.path.lowercase() })
    }

    fun extractEntryToCache(entry: FileEntry, virtualPath: String): File {
        val safePath = ArchiveSafety.safePath(virtualPath).joinToString("/")
        require(safePath.isNotBlank()) { "Некорректный путь внутри архива" }
        val outputDir = File(context.cacheDir, "archive-preview").apply { mkdirs() }
        val cleanName = ArchiveSafety.safeSegment(safePath.substringAfterLast('/'))
        val target = File.createTempFile("aura-", "-${cleanName}", outputDir)
        try {
            val singleStreamFormat = detectFormat(entry.name)?.takeIf { it in SINGLE_STREAM_FORMATS }
            if (singleStreamFormat != null) {
                require(safePath == singleStreamOutputName(entry.name)) { "Файл внутри архива не найден" }
                openSingleCompressedInput(entry, singleStreamFormat).use { input ->
                    target.outputStream().buffered(IO_BUFFER_SIZE).use { output ->
                        copyLimited(input, output, MAX_SINGLE_ENTRY_BYTES, "Распакованный файл слишком велик")
                    }
                }
                return target
            }
            when {
                entry.name.endsWith(".rar", true) -> extractRarSingle(entry, safePath, target)
                entry.name.endsWith(".7z", true) -> extract7zSingle(entry, safePath, target)
                entry.name.endsWith(".tar.gz", true) || entry.name.endsWith(".tgz", true) ->
                    openInput(entry).buffered(1024 * 1024).use { raw ->
                        openGzip(raw).use { gzip -> extractTarSingle(gzip, safePath, target) }
                    }
                entry.name.endsWith(".tar.bz2", true) || entry.name.endsWith(".tbz2", true) ->
                    openInput(entry).buffered(IO_BUFFER_SIZE).use { raw ->
                        BZip2CompressorInputStream(raw, true).use { bzip2 -> extractTarSingle(bzip2, safePath, target) }
                    }
                entry.name.endsWith(".tar.xz", true) || entry.name.endsWith(".txz", true) ->
                    openInput(entry).buffered(IO_BUFFER_SIZE).use { raw ->
                        openXz(raw).use { xz -> extractTarSingle(xz, safePath, target) }
                    }
                entry.name.endsWith(".tar", true) -> openInput(entry).buffered(1024 * 1024).use { raw ->
                    extractTarSingle(raw, safePath, target)
                }
                else -> openInput(entry).buffered(1024 * 1024).use { raw -> extractZipSingle(raw, safePath, target) }
            }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun extractSingleStream(entry: FileEntry, destination: DocumentFile, format: ArchiveFormat): DocumentFile {
        val outputName = singleStreamOutputName(entry.name)
        val target = createUniqueFile(destination, BackendMime.guess(outputName), outputName)
        try {
            openSingleCompressedInput(entry, format).use { input ->
                openOutput(target).buffered(IO_BUFFER_SIZE).use { output ->
                    copyLimited(input, output, MAX_SINGLE_ENTRY_BYTES, "Распакованный файл слишком велик")
                }
            }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun openSingleCompressedInput(entry: FileEntry, format: ArchiveFormat): InputStream {
        val raw = openInput(entry).buffered(IO_BUFFER_SIZE)
        return try {
            when (format) {
                ArchiveFormat.GZIP -> openGzip(raw)
                ArchiveFormat.BZIP2 -> BZip2CompressorInputStream(raw, true)
                ArchiveFormat.XZ -> openXz(raw)
                else -> throw IllegalArgumentException("Это не одиночный поток сжатия")
            }
        } catch (error: Throwable) {
            raw.close()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun openGzip(input: InputStream): GzipCompressorInputStream =
        GzipCompressorInputStream(input, true)

    @Suppress("DEPRECATION")
    private fun openXz(input: InputStream): XZCompressorInputStream =
        XZCompressorInputStream(input, true, MAX_XZ_MEMORY_KIB)

    private fun singleStreamOutputName(archiveName: String): String {
        val stripped = when {
            archiveName.endsWith(".bz2", true) -> archiveName.dropLast(4)
            archiveName.endsWith(".xz", true) -> archiveName.dropLast(3)
            archiveName.endsWith(".gz", true) -> archiveName.dropLast(3)
            else -> archiveName
        }.trim()
        return ArchiveSafety.safeSegment(stripped.ifBlank { "Распакованный файл" })
    }

    private fun normalizedArchivePath(rawName: String): String = ArchiveSafety.safePath(rawName).joinToString("/")

    private fun extractZipSingle(input: InputStream, wanted: String, target: File) {
        ZipArchiveInputStream(input).use { archive ->
            while (true) {
                val item = archive.nextZipEntry ?: break
                if (item.isDirectory || item.isUnixSymlink) continue
                if (normalizedArchivePath(item.name) == wanted) {
                    require(item.size < 0L || item.size <= MAX_SINGLE_ENTRY_BYTES) { "Файл внутри ZIP слишком велик" }
                    target.outputStream().buffered(1024 * 1024).use { output ->
                        copyLimited(archive, output, MAX_SINGLE_ENTRY_BYTES, "Файл внутри ZIP слишком велик")
                    }
                    return
                }
            }
        }
        throw IOException("Файл внутри ZIP не найден")
    }

    private fun extractTarSingle(input: InputStream, wanted: String, target: File) {
        TarArchiveInputStream(input).use { archive ->
            while (true) {
                val item = archive.nextEntry ?: break
                if (item.isDirectory || item.isSymbolicLink || item.isLink || !item.isFile) continue
                if (normalizedArchivePath(item.name) == wanted) {
                    require(item.size < 0L || item.size <= MAX_SINGLE_ENTRY_BYTES) { "Файл внутри TAR слишком велик" }
                    target.outputStream().buffered(1024 * 1024).use { output ->
                        copyLimited(archive, output, MAX_SINGLE_ENTRY_BYTES, "Файл внутри TAR слишком велик")
                    }
                    return
                }
            }
        }
        throw IOException("Файл внутри TAR не найден")
    }

    private fun extract7zSingle(entry: FileEntry, wanted: String, target: File) = withTemporaryArchive(entry, ".7z") { temporary ->
        SevenZFile.builder().setFile(temporary).setMaxMemoryLimitKiB(MAX_7Z_MEMORY_KIB).get().use { archive ->
            while (true) {
                val item = archive.nextEntry ?: break
                if (item.isDirectory || normalizedArchivePath(item.name) != wanted) continue
                require(item.size < 0L || item.size <= MAX_SINGLE_ENTRY_BYTES) { "Файл внутри 7z слишком велик" }
                target.outputStream().buffered(IO_BUFFER_SIZE).use { output ->
                    copyLimitedSevenZ(archive, output, MAX_SINGLE_ENTRY_BYTES, "Файл внутри 7z слишком велик")
                }
                return@withTemporaryArchive
            }
            throw IOException("Файл внутри 7z не найден")
        }
    }

    private fun extractRarSingle(entry: FileEntry, wanted: String, target: File) {
        openInput(entry).buffered(1024 * 1024).use { raw ->
            Archive(raw).use { archive ->
                archive.fileHeaders.firstOrNull { header ->
                    !header.isDirectory && normalizedArchivePath(header.fileName) == wanted
                }?.let { header ->
                    require(header.fullUnpackSize < 0L || header.fullUnpackSize <= MAX_SINGLE_ENTRY_BYTES) { "Файл внутри RAR слишком велик" }
                    target.outputStream().buffered(1024 * 1024).use { output ->
                        archive.getInputStream(header).use { input ->
                            copyLimited(input, output, MAX_SINGLE_ENTRY_BYTES, "Файл внутри RAR слишком велик")
                        }
                    }
                    return
                }
            }
        }
        throw IOException("Файл внутри RAR не найден")
    }

    private fun <T> withTemporaryArchive(entry: FileEntry, suffix: String, block: (File) -> T): T {
        if (entry.uri.scheme == ContentResolver.SCHEME_FILE) {
            val source = entry.uri.path?.let(::File) ?: throw IOException("Не удалось определить путь архива")
            require(source.isFile) { "Архив недоступен" }
            return block(source)
        }
        val advertised = entry.size.coerceAtLeast(0L)
        require(advertised <= MAX_COMPRESSED_ARCHIVE_BYTES) { "Архив слишком велик для временной обработки" }
        if (advertised > 0L) {
            require(context.cacheDir.usableSpace > advertised + TEMP_SPACE_RESERVE_BYTES) {
                "Недостаточно свободного места для временной обработки архива"
            }
        }
        val temporary = File.createTempFile("aura-archive-", suffix, context.cacheDir)
        try {
            openInput(entry).use { input ->
                temporary.outputStream().buffered(IO_BUFFER_SIZE).use { output ->
                    copyLimited(input, output, MAX_COMPRESSED_ARCHIVE_BYTES, "Архив слишком велик для временной обработки")
                }
            }
            return block(temporary)
        } finally {
            temporary.delete()
        }
    }

    private fun copyLimited(input: InputStream, output: OutputStream, limit: Long, message: String): Long {
        val buffer = ByteArray(IO_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { message }
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun copyLimitedSevenZ(archive: SevenZFile, output: OutputStream, limit: Long, message: String): Long {
        val buffer = ByteArray(IO_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = archive.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { message }
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun createZip(entries: List<FileEntry>, output: OutputStream) {
        java.util.zip.ZipOutputStream(output).use { zip ->
            entries.forEach { entry -> addZipNode(zip, entry.document, ArchiveSafety.safeSegment(entry.name)) }
        }
    }

    private fun addZipNode(zip: java.util.zip.ZipOutputStream, source: DocumentFile, path: String) {
        if (source.isDirectory) {
            zip.putNextEntry(java.util.zip.ZipEntry("$path/"))
            zip.closeEntry()
            source.listFiles().forEach { child ->
                addZipNode(zip, child, "$path/${ArchiveSafety.safeSegment(child.name ?: "Без названия")}")
            }
        } else {
            zip.putNextEntry(java.util.zip.ZipEntry(path).apply {
                if (source.lastModified() > 0L) time = source.lastModified()
            })
            openInput(source).use { it.copyTo(zip, 1024 * 1024) }
            zip.closeEntry()
        }
    }

    private fun createTar(entries: List<FileEntry>, output: OutputStream) {
        TarArchiveOutputStream(output).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            entries.forEach { entry -> addTarNode(tar, entry.document, ArchiveSafety.safeSegment(entry.name)) }
            tar.finish()
        }
    }

    private fun addTarNode(tar: TarArchiveOutputStream, source: DocumentFile, path: String) {
        if (source.isDirectory) {
            val archiveEntry = TarArchiveEntry("$path/").apply {
                mode = 493 // 0755
                size = 0L
                if (source.lastModified() > 0L) modTime = java.util.Date(source.lastModified())
            }
            tar.putArchiveEntry(archiveEntry)
            tar.closeArchiveEntry()
            source.listFiles().forEach { child ->
                addTarNode(tar, child, "$path/${ArchiveSafety.safeSegment(child.name ?: "Без названия")}")
            }
        } else {
            val length = source.length().coerceAtLeast(0L)
            val archiveEntry = TarArchiveEntry(path).apply {
                size = length
                mode = 420 // 0644
                if (source.lastModified() > 0L) modTime = java.util.Date(source.lastModified())
            }
            tar.putArchiveEntry(archiveEntry)
            openInput(source).use { it.copyTo(tar, 1024 * 1024) }
            tar.closeArchiveEntry()
        }
    }

    private fun extractZip(input: InputStream, root: DocumentFile) {
        ZipArchiveInputStream(input).use { archive ->
            extractSequential(root) { archive.nextZipEntry to archive }
        }
    }

    private fun extractTar(input: InputStream, root: DocumentFile) {
        TarArchiveInputStream(input).use { archive ->
            extractSequential(root) { archive.nextEntry to archive }
        }
    }

    private fun extractSequential(
        root: DocumentFile,
        next: () -> Pair<ArchiveEntry?, InputStream>,
    ) {
        var count = 0
        var extracted = 0L
        val directoryCache = mutableMapOf("" to root)
        val buffer = ByteArray(IO_BUFFER_SIZE)
        while (true) {
            val (entry, input) = next()
            entry ?: break
            count += 1
            require(count <= MAX_ARCHIVE_ENTRIES) { "В архиве слишком много объектов" }
            if (entry is TarArchiveEntry && (entry.isSymbolicLink || entry.isLink || (!entry.isDirectory && !entry.isFile))) continue
            if (entry is ZipArchiveEntry && entry.isUnixSymlink) continue
            val declaredSize = entry.size
            require(declaredSize < 0L || declaredSize <= MAX_SINGLE_ENTRY_BYTES) { "Один объект архива слишком велик" }
            require(declaredSize < 0L || extracted + declaredSize <= MAX_EXTRACTED_BYTES) { "Архив слишком велик для безопасной распаковки" }
            val segments = ArchiveSafety.safePath(entry.name)
            if (segments.isEmpty()) continue
            if (entry.isDirectory) {
                ensureDirectories(root, segments, directoryCache)
                continue
            }
            val parent = ensureDirectories(root, segments.dropLast(1), directoryCache)
            val file = createUniqueFile(parent, BackendMime.guess(segments.last()), segments.last())
            try {
                openOutput(file).use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        extracted += read
                        require(extracted <= MAX_EXTRACTED_BYTES) { "Архив слишком велик для безопасной распаковки" }
                        output.write(buffer, 0, read)
                    }
                }
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }
    }

    private fun extractRar(entry: FileEntry, root: DocumentFile) {
        openInput(entry).buffered(IO_BUFFER_SIZE).use { raw ->
            Archive(raw).use { archive ->
                var count = 0
                var extracted = 0L
                val directoryCache = mutableMapOf("" to root)
                val buffer = ByteArray(IO_BUFFER_SIZE)
                archive.fileHeaders.forEach { header ->
                    count += 1
                    require(count <= MAX_ARCHIVE_ENTRIES) { "В RAR слишком много объектов" }
                    val segments = ArchiveSafety.safePath(header.fileName)
                    if (segments.isEmpty()) return@forEach
                    if (header.isDirectory) {
                        ensureDirectories(root, segments, directoryCache)
                    } else {
                        val declared = header.fullUnpackSize
                        require(declared < 0L || declared <= MAX_SINGLE_ENTRY_BYTES) { "Один объект RAR слишком велик" }
                        val parent = ensureDirectories(root, segments.dropLast(1), directoryCache)
                        val file = createUniqueFile(parent, BackendMime.guess(segments.last()), segments.last())
                        try {
                            openOutput(file).use { output ->
                                archive.getInputStream(header).use { input ->
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        extracted += read
                                        require(extracted <= MAX_EXTRACTED_BYTES) { "RAR слишком велик для безопасной распаковки" }
                                        output.write(buffer, 0, read)
                                    }
                                }
                            }
                        } catch (error: Throwable) {
                            file.delete()
                            throw error
                        }
                    }
                }
            }
        }
    }

    private fun extract7z(entry: FileEntry, root: DocumentFile) {
        withTemporaryArchive(entry, ".7z") { archiveFile ->
            SevenZFile.builder().setFile(archiveFile).setMaxMemoryLimitKiB(MAX_7Z_MEMORY_KIB).get().use { archive ->
                var count = 0
                var extracted = 0L
                val directoryCache = mutableMapOf("" to root)
                val buffer = ByteArray(IO_BUFFER_SIZE)
                while (true) {
                    val item = archive.nextEntry ?: break
                    count += 1
                    require(count <= MAX_ARCHIVE_ENTRIES) { "В 7z слишком много объектов" }
                    val declaredSize = item.size
                    require(declaredSize < 0L || declaredSize <= MAX_SINGLE_ENTRY_BYTES) { "Один объект 7z слишком велик" }
                    require(declaredSize < 0L || extracted + declaredSize <= MAX_EXTRACTED_BYTES) { "7z слишком велик для безопасной распаковки" }
                    val segments = ArchiveSafety.safePath(item.name)
                    if (segments.isEmpty()) continue
                    if (item.isDirectory) {
                        ensureDirectories(root, segments, directoryCache)
                    } else {
                        val parent = ensureDirectories(root, segments.dropLast(1), directoryCache)
                        val file = createUniqueFile(parent, BackendMime.guess(segments.last()), segments.last())
                        try {
                            openOutput(file).use { output ->
                                while (true) {
                                    val read = archive.read(buffer)
                                    if (read < 0) break
                                    extracted += read
                                    require(extracted <= MAX_EXTRACTED_BYTES) { "7z слишком велик для безопасной распаковки" }
                                    output.write(buffer, 0, read)
                                }
                            }
                        } catch (error: Throwable) {
                            file.delete()
                            throw error
                        }
                    }
                }
            }
        }
    }


    private fun deleteTree(document: DocumentFile) {
        if (document.isDirectory) {
            document.listFiles().forEach(::deleteTree)
        }
        runCatching { document.delete() }
    }

    private fun ensureDirectories(
        root: DocumentFile,
        segments: List<String>,
        cache: MutableMap<String, DocumentFile> = mutableMapOf("" to root),
    ): DocumentFile {
        var current = root
        var path = ""
        segments.forEach { segment ->
            path = if (path.isEmpty()) segment else "$path/$segment"
            val cached = cache[path]
            if (cached != null) {
                current = cached
            } else {
                current = current.findFile(segment)?.takeIf(DocumentFile::isDirectory)
                    ?: current.createDirectory(segment)
                    ?: throw IOException("Не удалось создать папку $segment")
                cache[path] = current
            }
        }
        return current
    }

    private fun createUniqueDirectory(parent: DocumentFile, requested: String): DocumentFile {
        var name = requested
        var index = 2
        while (parent.findFile(name) != null) name = "$requested ($index)".also { index += 1 }
        return parent.createDirectory(name) ?: throw IOException("Не удалось создать папку $name")
    }

    private fun createUniqueFile(parent: DocumentFile, mime: String, requested: String): DocumentFile {
        val lower = requested.lowercase()
        val compoundExtension = listOf(".tar.bz2", ".tar.gz", ".tar.xz")
            .firstOrNull(lower::endsWith)
        val dot = requested.lastIndexOf('.')
        val ext = compoundExtension ?: if (dot > 0) requested.substring(dot) else ""
        val base = if (ext.isNotEmpty()) requested.dropLast(ext.length) else requested
        var name = requested
        var index = 2
        while (parent.findFile(name) != null) name = "$base ($index)$ext".also { index += 1 }
        return parent.createFile(mime, name) ?: throw IOException("Не удалось создать $name")
    }

    private fun normalizeArchiveName(name: String, format: ArchiveFormat): String {
        val lower = name.lowercase()
        return when (format) {
            ArchiveFormat.ZIP -> if (lower.endsWith(".zip")) name else "$name.zip"
            ArchiveFormat.TAR -> if (lower.endsWith(".tar")) name else "$name.tar"
            ArchiveFormat.TAR_GZ -> when {
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> name
                else -> "$name.tar.gz"
            }
            ArchiveFormat.TAR_BZ2 -> when {
                lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> name
                else -> "$name.tar.bz2"
            }
            ArchiveFormat.TAR_XZ -> when {
                lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> name
                else -> "$name.tar.xz"
            }
            ArchiveFormat.GZIP -> if (lower.endsWith(".gz")) name else "$name.gz"
            ArchiveFormat.BZIP2 -> if (lower.endsWith(".bz2")) name else "$name.bz2"
            ArchiveFormat.XZ -> if (lower.endsWith(".xz")) name else "$name.xz"
        }
    }

    private fun openInput(entry: FileEntry): InputStream = openInput(entry.document)

    private fun openInput(document: DocumentFile): InputStream =
        if (document.uri.scheme == ContentResolver.SCHEME_FILE) {
            File(requireNotNull(document.uri.path)).inputStream()
        } else resolver.openInputStream(document.uri) ?: throw IOException("Не удалось открыть ${document.name}")

    private fun openOutput(document: DocumentFile): OutputStream =
        if (document.uri.scheme == ContentResolver.SCHEME_FILE) {
            File(requireNotNull(document.uri.path)).outputStream()
        } else resolver.openOutputStream(document.uri, "w") ?: throw IOException("Не удалось записать ${document.name}")

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

    companion object {
        const val MAX_ARCHIVE_ENTRIES = 100_000
        const val MAX_SINGLE_ENTRY_BYTES = 64L * 1024L * 1024L * 1024L
        const val MAX_EXTRACTED_BYTES = 128L * 1024L * 1024L * 1024L
        const val MAX_COMPRESSED_ARCHIVE_BYTES = 64L * 1024L * 1024L * 1024L
        const val TEMP_SPACE_RESERVE_BYTES = 256L * 1024L * 1024L
        const val MAX_7Z_MEMORY_KIB = 256 * 1024
        const val MAX_XZ_MEMORY_KIB = 256 * 1024
        private const val IO_BUFFER_SIZE = 1024 * 1024
        private val SINGLE_STREAM_FORMATS = setOf(ArchiveFormat.GZIP, ArchiveFormat.BZIP2, ArchiveFormat.XZ)
    }
}

private object BackendMime {
    fun guess(name: String): String = URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
}
