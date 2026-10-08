package com.aurafiles.app.tools

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.DisplayMetrics
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Aura's transport container for an installed split application.
 *
 * The file is a regular ZIP with the .apks extension. It is intentionally
 * device-derived: it contains exactly the base/split APKs installed on the
 * source device, not a reconstructed universal APK.
 */
object AuraApksBundle {
    const val MIME_TYPE = "application/vnd.aurafiles.apks+zip"
    const val EXTENSION = "apks"
    const val FORMAT_ID = "aurafiles-installed-apks"
    const val FORMAT_VERSION = 1
    const val MANIFEST_ENTRY = "META-INF/aura-apks.json"

    private const val BUFFER_SIZE = 256 * 1024
    private const val MAX_PARTS = 256
    private const val MAX_TOTAL_UNCOMPRESSED = 8L * 1024L * 1024L * 1024L
    private const val CONTAINER_OVERHEAD_BYTES = 64L * 1024L * 1024L
    private const val CACHE_SPACE_RESERVE_BYTES = 128L * 1024L * 1024L
    private const val MAX_CONTAINER_BYTES = MAX_TOTAL_UNCOMPRESSED + CONTAINER_OVERHEAD_BYTES

    data class InstalledPackageSource(
        val packageName: String,
        val label: String,
        val versionName: String,
        val versionCode: Long,
        val baseApkPath: String,
        val splitApkPaths: List<String>,
    )

    data class Part(
        val entryName: String,
        val originalName: String,
        val role: String,
        val size: Long,
        val sha256: String,
        val file: File? = null,
    ) {
        val isBase: Boolean get() = role == "base"
    }

    data class Manifest(
        val packageName: String,
        val label: String,
        val versionName: String,
        val versionCode: Long,
        val exportedAt: Long,
        val sourceManufacturer: String,
        val sourceModel: String,
        val sourceSdk: Int,
        val sourceDensityDpi: Int,
        val sourceSupportedAbis: List<String>,
        val parts: List<Part>,
    )

    data class PreparedBundle(
        val manifest: Manifest,
        val workingDir: File,
        val parts: List<Part>,
        val basePackageInfo: PackageInfo,
        val totalSize: Long,
        val nativeAbis: Set<String>,
        val warnings: List<String>,
    ) {
        fun cleanup() {
            workingDir.deleteRecursively()
        }
    }

    suspend fun exportInstalledPackage(context: Context, source: InstalledPackageSource): File {
        require(source.splitApkPaths.isNotEmpty()) { "Приложение не является SPLIT" }
        val base = File(source.baseApkPath)
        require(base.isFile) { "base.apk приложения недоступен" }
        val splits = source.splitApkPaths.map(::File)
        require(splits.all(File::isFile)) { "Одна или несколько SPLIT-частей недоступны" }

        var expectedPayload = 0L
        (listOf(base) + splits).forEach { file ->
            currentCoroutineContext().ensureActive()
            val size = file.length()
            require(size > 0L) { "APK недоступен или пуст: ${file.name}" }
            expectedPayload = Math.addExact(expectedPayload, size)
            require(expectedPayload <= MAX_TOTAL_UNCOMPRESSED) { "SPLIT-комплект слишком большой" }
        }

        val shareDir = File(context.cacheDir, "shares").apply { mkdirs() }
        cleanOldExports(shareDir)
        requireCacheSpace(shareDir, Math.addExact(expectedPayload, CONTAINER_OVERHEAD_BYTES))
        val safeLabel = safeFilePart(source.label, source.packageName.substringAfterLast('.'), 48)
        val safeVersion = safeFilePart(source.versionName, source.versionCode.toString(), 24)
        val target = uniqueFile(shareDir, "installed-$safeLabel-$safeVersion.apks")
        val temporary = File(shareDir, ".${target.name}.${UUID.randomUUID()}.tmp")

        val writtenParts = mutableListOf<Part>()
        try {
            currentCoroutineContext().ensureActive()
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary), BUFFER_SIZE)).use { zip ->
                // APKs are already compressed. Re-deflating them wastes CPU and battery.
                zip.setLevel(Deflater.NO_COMPRESSION)
                writtenParts += writeApkPart(zip, base, "base.apk", "base")
                val usedNames = linkedSetOf("base.apk", MANIFEST_ENTRY.lowercase(Locale.ROOT))
                splits.forEachIndexed { index, file ->
                    val original = file.name.ifBlank { "split-${index + 1}.apk" }
                    val suggested = safeArchiveName(original, "split-${index + 1}.apk")
                    var entryName = suggested
                    var suffix = 2
                    while (!usedNames.add(entryName.lowercase(Locale.ROOT))) {
                        val stem = suggested.removeSuffix(".apk")
                        entryName = "$stem-$suffix.apk"
                        suffix += 1
                    }
                    writtenParts += writeApkPart(zip, file, entryName, "split")
                }

                val manifest = Manifest(
                    packageName = source.packageName,
                    label = source.label,
                    versionName = source.versionName,
                    versionCode = source.versionCode,
                    exportedAt = System.currentTimeMillis(),
                    sourceManufacturer = Build.MANUFACTURER.orEmpty(),
                    sourceModel = Build.MODEL.orEmpty(),
                    sourceSdk = Build.VERSION.SDK_INT,
                    sourceDensityDpi = context.resources.displayMetrics.densityDpi,
                    sourceSupportedAbis = Build.SUPPORTED_ABIS.toList(),
                    parts = writtenParts,
                )
                val manifestBytes = manifestToJson(manifest).toString(2).toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY).apply { time = System.currentTimeMillis() })
                zip.write(manifestBytes)
                zip.closeEntry()
            }
            FileOutputStream(temporary, true).use { it.fd.sync() }
            currentCoroutineContext().ensureActive()
            require(temporary.renameTo(target)) { "Не удалось завершить экспорт SPLIT-комплекта" }
            return target
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    suspend fun prepareForInstall(context: Context, input: InputStream, displayName: String): PreparedBundle {
        val parent = File(context.cacheDir, "split-install").apply { mkdirs() }
        cleanupOldInstallDirs(parent)
        requireCacheSpace(parent, 0L)
        val working = File(parent, "${System.currentTimeMillis()}-${UUID.randomUUID()}").apply { mkdirs() }
        val bundleFile = File(working, safeFilePart(displayName, "package.apks", 96).let {
            if (it.lowercase(Locale.ROOT).endsWith(".apks")) it else "$it.apks"
        })
        try {
            BufferedInputStream(input, BUFFER_SIZE).use { source ->
                FileOutputStream(bundleFile).use { fileOut ->
                    val target = BufferedOutputStream(fileOut, BUFFER_SIZE)
                    copyBoundedCancellable(source, target, MAX_CONTAINER_BYTES, working)
                    target.flush()
                    fileOut.fd.sync()
                }
            }
            return prepareFromFile(context, bundleFile, working)
        } catch (error: Throwable) {
            working.deleteRecursively()
            throw error
        }
    }

    private suspend fun prepareFromFile(context: Context, bundleFile: File, working: File): PreparedBundle {
        require(bundleFile.isFile && bundleFile.length() > 0L) { "Файл комплекта пуст или недоступен" }
        require(bundleFile.length() <= MAX_CONTAINER_BYTES) { "SPLIT-комплект слишком большой" }
        val extractedDir = File(working, "parts").apply { mkdirs() }
        ZipFile(bundleFile).use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST_ENTRY)
                ?: throw IllegalArgumentException("Это не комплект Aura Files: нет $MANIFEST_ENTRY")
            require(!manifestEntry.isDirectory && manifestEntry.size in 1..(1024L * 1024L)) {
                "Повреждён служебный манифест SPLIT-комплекта"
            }
            val json = zip.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val manifest = manifestFromJson(json)
            require(manifest.parts.isNotEmpty()) { "В комплекте нет APK" }
            require(manifest.parts.size <= MAX_PARTS) { "Слишком много APK-частей: ${manifest.parts.size}" }
            require(manifest.parts.count(Part::isBase) == 1) { "В комплекте должен быть ровно один base.apk" }

            val seen = HashSet<String>()
            var totalSize = 0L
            val validatedEntries = ArrayList<Pair<Part, ZipEntry>>(manifest.parts.size)
            manifest.parts.forEach { part ->
                currentCoroutineContext().ensureActive()
                validateArchiveEntryName(part.entryName)
                require(seen.add(part.entryName.lowercase(Locale.ROOT))) { "Повтор APK-части: ${part.entryName}" }
                require(part.size > 0L) { "Некорректный размер ${part.entryName}" }
                totalSize = Math.addExact(totalSize, part.size)
                require(totalSize <= MAX_TOTAL_UNCOMPRESSED) { "SPLIT-комплект слишком большой" }
                val entry = zip.getEntry(part.entryName)
                    ?: throw IllegalArgumentException("В архиве отсутствует ${part.entryName}")
                require(!entry.isDirectory) { "Некорректная APK-часть ${part.entryName}" }
                if (entry.size >= 0L) {
                    require(entry.size == part.size) { "Размер ${part.entryName} не совпадает с манифестом" }
                }
                validatedEntries += part to entry
            }
            requireCacheSpace(working, totalSize)

            val extracted = ArrayList<Part>(manifest.parts.size)
            validatedEntries.forEachIndexed { index, (part, entry) ->
                currentCoroutineContext().ensureActive()
                val output = File(extractedDir, "%03d-%s".format(index, safeArchiveName(part.entryName, "part-$index.apk")))
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                zip.getInputStream(entry).use { raw ->
                    BufferedInputStream(raw, BUFFER_SIZE).use { source ->
                        BufferedOutputStream(FileOutputStream(output), BUFFER_SIZE).use { target ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = source.read(buffer)
                                if (read < 0) break
                                copied += read
                                require(copied <= part.size) { "${part.entryName} больше заявленного размера" }
                                digest.update(buffer, 0, read)
                                target.write(buffer, 0, read)
                                requireCacheSpace(working, 0L)
                            }
                        }
                    }
                }
                require(copied == part.size) { "${part.entryName} обрезан" }
                val actualHash = digest.digest().toHex()
                require(actualHash.equals(part.sha256, ignoreCase = true)) {
                    "Контрольная сумма ${part.entryName} не совпадает"
                }
                extracted += part.copy(file = output)
            }

            val base = extracted.single(Part::isBase).file ?: error("base.apk не извлечён")
            val baseInfo = packageArchiveInfo(context.packageManager, base)
                ?: throw IllegalArgumentException("Android не смог прочитать base.apk")
            require(baseInfo.packageName == manifest.packageName) {
                "Package в base.apk (${baseInfo.packageName}) не совпадает с ${manifest.packageName}"
            }
            require(longVersionCode(baseInfo) == manifest.versionCode) {
                "Версия base.apk не совпадает с манифестом"
            }

            // When PackageManager can parse an individual split, validate it early.
            // Some Android builds return null for a standalone config split; in that
            // case PackageInstaller remains the authoritative signature/package check.
            val baseSigner = signingDigests(baseInfo)
            extracted.filterNot(Part::isBase).forEach { part ->
                val info = part.file?.let { packageArchiveInfo(context.packageManager, it) } ?: return@forEach
                require(info.packageName == manifest.packageName) {
                    "${part.originalName}: другой package (${info.packageName})"
                }
                require(longVersionCode(info) == manifest.versionCode) {
                    "${part.originalName}: другая версия приложения"
                }
                val splitSigner = signingDigests(info)
                if (baseSigner.isNotEmpty() && splitSigner.isNotEmpty()) {
                    require(baseSigner == splitSigner) { "${part.originalName}: подпись отличается от base.apk" }
                }
            }

            val nativeAbis = extracted.asSequence()
                .mapNotNull(Part::file)
                .flatMap(::nativeAbisInApk)
                .toSet()
            val warnings = buildWarnings(context.packageManager, manifest, baseInfo, nativeAbis)
            val minSdk = baseInfo.applicationInfo?.minSdkVersion ?: 1
            require(minSdk <= Build.VERSION.SDK_INT) {
                "Приложению нужен Android API $minSdk, на этом телефоне API ${Build.VERSION.SDK_INT}"
            }
            if (nativeAbis.isNotEmpty()) {
                val supported = Build.SUPPORTED_ABIS.orEmpty().toSet()
                require(nativeAbis.any(supported::contains)) {
                    "Архитектура приложения (${nativeAbis.joinToString()}) несовместима с телефоном (${supported.joinToString()})"
                }
            }

            return PreparedBundle(
                manifest = manifest,
                workingDir = working,
                parts = extracted,
                basePackageInfo = baseInfo,
                totalSize = totalSize,
                nativeAbis = nativeAbis,
                warnings = warnings,
            )
        }
    }

    private suspend fun writeApkPart(zip: ZipOutputStream, file: File, entryName: String, role: String): Part {
        require(file.isFile && file.length() > 0L) { "APK недоступен: ${file.name}" }
        val expectedSize = file.length()
        val digest = MessageDigest.getInstance("SHA-256")
        zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
        var copied = 0L
        BufferedInputStream(file.inputStream(), BUFFER_SIZE).use { source ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer)
                if (read < 0) break
                copied = Math.addExact(copied, read.toLong())
                require(copied <= expectedSize) { "APK изменился во время экспорта: ${file.name}" }
                digest.update(buffer, 0, read)
                zip.write(buffer, 0, read)
            }
        }
        zip.closeEntry()
        require(copied == expectedSize && file.length() == expectedSize) {
            "APK изменился во время экспорта: ${file.name}"
        }
        return Part(
            entryName = entryName,
            originalName = file.name,
            role = role,
            size = expectedSize,
            sha256 = digest.digest().toHex(),
        )
    }

    private suspend fun copyBoundedCancellable(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        cacheDirectory: File,
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            copied = Math.addExact(copied, read.toLong())
            require(copied <= maxBytes) { "Входящий SPLIT-комплект превышает допустимый размер" }
            output.write(buffer, 0, read)
            requireCacheSpace(cacheDirectory, 0L)
        }
        return copied
    }

    private fun requireCacheSpace(directory: File, expectedBytes: Long) {
        val usable = directory.usableSpace
        if (usable <= CACHE_SPACE_RESERVE_BYTES) {
            throw IOException("Недостаточно свободного места: Aura сохраняет резерв 128 МиБ")
        }
        if (expectedBytes > 0L && expectedBytes > usable - CACHE_SPACE_RESERVE_BYTES) {
            throw IOException("Недостаточно свободного места для SPLIT-комплекта")
        }
    }

    private fun manifestToJson(manifest: Manifest): JSONObject = JSONObject().apply {
        put("format", FORMAT_ID)
        put("formatVersion", FORMAT_VERSION)
        put("packageName", manifest.packageName)
        put("label", manifest.label)
        put("versionName", manifest.versionName)
        put("versionCode", manifest.versionCode)
        put("exportedAt", manifest.exportedAt)
        put("sourceDevice", JSONObject().apply {
            put("manufacturer", manifest.sourceManufacturer)
            put("model", manifest.sourceModel)
            put("sdk", manifest.sourceSdk)
            put("densityDpi", manifest.sourceDensityDpi)
            put("supportedAbis", JSONArray(manifest.sourceSupportedAbis))
        })
        put("parts", JSONArray().apply {
            manifest.parts.forEach { part ->
                put(JSONObject().apply {
                    put("entry", part.entryName)
                    put("originalName", part.originalName)
                    put("role", part.role)
                    put("size", part.size)
                    put("sha256", part.sha256)
                })
            }
        })
    }

    private fun manifestFromJson(json: JSONObject): Manifest {
        require(json.optString("format") == FORMAT_ID) { "Неизвестный формат SPLIT-комплекта" }
        require(json.optInt("formatVersion", -1) == FORMAT_VERSION) {
            "Версия SPLIT-комплекта не поддерживается"
        }
        val packageName = json.optString("packageName").trim()
        require(packageName.isNotEmpty()) { "В манифесте нет packageName" }
        val device = json.optJSONObject("sourceDevice") ?: JSONObject()
        val partsJson = json.optJSONArray("parts") ?: JSONArray()
        val parts = buildList {
            for (index in 0 until partsJson.length()) {
                val item = partsJson.optJSONObject(index) ?: continue
                val entry = item.optString("entry").trim()
                val originalName = item.optString("originalName").trim().ifBlank { entry }
                val role = item.optString("role").trim()
                require(role == "base" || role == "split") { "Некорректная роль APK-части" }
                val sha256 = item.optString("sha256").trim()
                require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Некорректный SHA-256 для $entry" }
                add(
                    Part(
                        entryName = entry,
                        originalName = originalName,
                        role = role,
                        size = item.optLong("size", -1L),
                        sha256 = sha256.lowercase(Locale.ROOT),
                    )
                )
            }
        }
        val abisJson = device.optJSONArray("supportedAbis") ?: JSONArray()
        val sourceAbis = buildList {
            for (index in 0 until abisJson.length()) {
                abisJson.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        }
        return Manifest(
            packageName = packageName,
            label = json.optString("label").trim().ifBlank { packageName },
            versionName = json.optString("versionName").trim(),
            versionCode = json.optLong("versionCode", -1L).also { require(it >= 0L) { "Некорректная версия" } },
            exportedAt = json.optLong("exportedAt", 0L),
            sourceManufacturer = device.optString("manufacturer").trim(),
            sourceModel = device.optString("model").trim(),
            sourceSdk = device.optInt("sdk", 0),
            sourceDensityDpi = device.optInt("densityDpi", 0),
            sourceSupportedAbis = sourceAbis,
            parts = parts,
        )
    }

    private fun packageArchiveInfo(pm: PackageManager, apk: File): PackageInfo? {
        val flagsLong = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES.toLong()
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flagsLong))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, flagsLong.toInt())
        }
    }

    private fun signingDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }.orEmpty()
        return signatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
        }
    }

    private fun nativeAbisInApk(apk: File): Sequence<String> {
        val values = linkedSetOf<String>()
        runCatching {
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (!name.startsWith("lib/")) continue
                    val abi = name.substringAfter("lib/").substringBefore('/')
                    if (abi.isNotBlank()) values += abi
                }
            }
        }
        return values.asSequence()
    }

    private fun buildWarnings(
        pm: PackageManager,
        manifest: Manifest,
        baseInfo: PackageInfo,
        nativeAbis: Set<String>,
    ): List<String> = buildList {
        add("Комплект снят с другого/этого устройства и не является универсальным APK.")
        add("Данные приложения, OBB и отдельные Play Asset Delivery-пакеты в комплект не входят.")
        val sourceDevice = listOf(manifest.sourceManufacturer, manifest.sourceModel)
            .filter(String::isNotBlank)
            .joinToString(" ")
        if (sourceDevice.isNotBlank()) {
            add("Источник: $sourceDevice · Android API ${manifest.sourceSdk.takeIf { it > 0 } ?: "?"}.")
        }
        if (nativeAbis.isNotEmpty()) {
            add("Нативные ABI в комплекте: ${nativeAbis.joinToString()}.")
        } else {
            val currentAbis = Build.SUPPORTED_ABIS.orEmpty().toSet()
            if (manifest.sourceSupportedAbis.isNotEmpty() && manifest.sourceSupportedAbis.none { it in currentAbis }) {
                add("ABI исходного телефона отличаются; установка может оказаться несовместимой.")
            }
        }
        if (manifest.sourceDensityDpi > 0 &&
            kotlin.math.abs(manifest.sourceDensityDpi - displayDensityDpi()) >= DisplayMetrics.DENSITY_XXXHIGH
        ) {
            add("Плотность экрана заметно отличается от исходного устройства; ресурсные split могут быть неоптимальны.")
        }
        val installed = installedPackageInfo(pm, manifest.packageName)
        if (installed != null) {
            val installedVersion = longVersionCode(installed)
            when {
                installedVersion > manifest.versionCode -> add("На телефоне уже установлена более новая версия; Android обычно запрещает понижение версии.")
                installedVersion == manifest.versionCode -> add("Эта же версия уже установлена; установка будет повторной/обновляющей.")
                else -> add("Будет обновлена установленная версия ${installed.versionName.orEmpty()} (${installedVersion}).")
            }
        }
        val targetSdk = baseInfo.applicationInfo?.targetSdkVersion ?: 0
        if (targetSdk > 0) add("Target SDK приложения: $targetSdk.")
    }

    private fun installedPackageInfo(pm: PackageManager, packageName: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
    }.getOrNull()

    private fun longVersionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }

    private fun validateArchiveEntryName(name: String) {
        require(name.isNotBlank() && name.lowercase(Locale.ROOT).endsWith(".apk")) { "Некорректное имя APK-части" }
        require(!name.startsWith('/') && !name.startsWith('\\')) { "Опасный путь в SPLIT-комплекте" }
        val normalized = name.replace('\\', '/')
        require(normalized.split('/').none { it == ".." || it.isBlank() }) { "Опасный путь в SPLIT-комплекте" }
    }

    private fun safeArchiveName(value: String, fallback: String): String {
        val candidate = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('.', '_')
            .take(96)
            .ifBlank { fallback }
        return if (candidate.lowercase(Locale.ROOT).endsWith(".apk")) candidate else "$candidate.apk"
    }

    private fun safeFilePart(value: String, fallback: String, max: Int): String = value
        .replace(Regex("[^\\p{L}\\p{N}._ -]+"), "_")
        .trim()
        .trim('.', ' ')
        .take(max)
        .ifBlank { fallback.take(max) }

    private fun uniqueFile(dir: File, name: String): File {
        val direct = File(dir, name)
        if (!direct.exists()) return direct
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var index = 2
        while (true) {
            val candidate = File(dir, "$stem ($index)${if (ext.isBlank()) "" else ".$ext"}")
            if (!candidate.exists()) return candidate
            index += 1
        }
    }

    private fun cleanOldExports(dir: File) {
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        dir.listFiles().orEmpty()
            .filter { it.name.startsWith("installed-") && it.lastModified() < cutoff }
            .forEach(File::delete)
    }

    private fun cleanupOldInstallDirs(parent: File) {
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        parent.listFiles().orEmpty()
            .filter { it.isDirectory && it.lastModified() < cutoff }
            .forEach(File::deleteRecursively)
    }

    private fun displayDensityDpi(): Int = try {
        android.content.res.Resources.getSystem().displayMetrics.densityDpi
    } catch (_: Throwable) {
        0
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
