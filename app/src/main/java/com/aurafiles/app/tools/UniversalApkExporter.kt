package com.aurafiles.app.tools

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Orchestrates installed split APK fusion, signing, verification and atomic publish. */
object UniversalApkExporter {
    private const val MAX_INPUT_BYTES = 8L * 1024L * 1024L * 1024L
    private const val CACHE_RESERVE_BYTES = 128L * 1024L * 1024L
    private const val MERGE_OVERHEAD_BYTES = 64L * 1024L * 1024L
    private const val EXPORT_MAX_AGE_MS = 24L * 60L * 60L * 1000L

    data class ExportedApk(
        val file: File,
        val signerSha256: String,
        val warning: String,
    )

    suspend fun export(
        context: Context,
        source: AuraApksBundle.InstalledPackageSource,
    ): ExportedApk {
        require(source.splitApkPaths.isNotEmpty()) { "Приложение не является SPLIT" }

        val inputs = (listOf(source.baseApkPath) + source.splitApkPaths).map(::File)
        var totalInputBytes = 0L
        val snapshots = inputs.map { file ->
            currentCoroutineContext().ensureActive()
            require(file.isFile) { "APK-часть недоступна: ${file.name}" }
            val length = file.length()
            require(length > 0L) { "APK-часть пуста: ${file.name}" }
            totalInputBytes = Math.addExact(totalInputBytes, length)
            require(totalInputBytes <= MAX_INPUT_BYTES) { "SPLIT-приложение слишком большое" }
            InputSnapshot(file, length, file.lastModified())
        }

        val shareDir = File(context.cacheDir, "shares").apply { mkdirs() }
        require(shareDir.isDirectory) { "Не удалось открыть кэш экспорта" }
        cleanupOldExports(shareDir)

        val estimatedWorkingBytes = Math.addExact(
            Math.multiplyExact(totalInputBytes, 2L),
            MERGE_OVERHEAD_BYTES,
        )
        requireCacheSpace(shareDir, estimatedWorkingBytes)

        val requestedName = UniversalApkExportPolicy.outputFileName(
            source.label,
            source.packageName,
            source.versionName,
            source.versionCode,
        )
        val target = uniqueFile(shareDir, requestedName)
        val token = UUID.randomUUID().toString()
        val unsigned = File(shareDir, ".${target.name}.$token.unsigned.apk")
        val signedTemp = File(shareDir, ".${target.name}.$token.signed.apk")

        try {
            currentCoroutineContext().ensureActive()
            UniversalApkMerger.merge(inputs, unsigned)
            currentCoroutineContext().ensureActive()
            snapshots.forEach(InputSnapshot::verifyUnchanged)
            require(unsigned.length() in 1..MAX_INPUT_BYTES) { "Объединённый APK слишком большой или пуст" }
            requireCacheSpace(shareDir, unsigned.length())

            val signerSha256 = ApkExportSigner.sign(context, unsigned, signedTemp)
            currentCoroutineContext().ensureActive()
            ApkExportSigner.verify(signedTemp)
            verifyIdentity(context.packageManager, signedTemp, source)
            require(signedTemp.length() in 1..MAX_INPUT_BYTES) { "Подписанный APK слишком большой или пуст" }

            currentCoroutineContext().ensureActive()
            require(!target.exists()) { "Файл назначения уже существует" }
            require(signedTemp.renameTo(target)) { "Не удалось завершить экспорт единого APK" }
            return ExportedApk(
                file = target,
                signerSha256 = signerSha256,
                warning = "APK переподписан Aura Files. Он предназначен для чистой установки или обновления APK, ранее экспортированного этой же установкой Aura Files; поверх версии из Google Play/другого источника с иной подписью он не установится.",
            )
        } catch (error: Throwable) {
            signedTemp.delete()
            target.delete()
            throw error
        } finally {
            unsigned.delete()
            signedTemp.delete()
        }
    }

    private fun verifyIdentity(
        packageManager: PackageManager,
        apk: File,
        source: AuraApksBundle.InstalledPackageSource,
    ) {
        val info = packageArchiveInfo(packageManager, apk)
            ?: throw IOException("Android не смог прочитать объединённый APK")
        require(info.packageName == source.packageName) {
            "После объединения изменилось имя пакета: ${info.packageName}"
        }
        val actualVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        require(actualVersionCode == source.versionCode) {
            "После объединения изменилась версия APK: $actualVersionCode вместо ${source.versionCode}"
        }
    }

    private fun packageArchiveInfo(packageManager: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        }

    private fun cleanupOldExports(directory: File) {
        val cutoff = System.currentTimeMillis() - EXPORT_MAX_AGE_MS
        directory.listFiles().orEmpty()
            .filter { file ->
                file.isFile && file.lastModified() < cutoff &&
                    (file.name.startsWith("installed-") || file.name.startsWith(".installed-"))
            }
            .forEach(File::delete)
    }

    private fun requireCacheSpace(directory: File, expectedBytes: Long) {
        val usable = directory.usableSpace
        if (usable <= CACHE_RESERVE_BYTES) {
            throw IOException("Недостаточно свободного места: Aura сохраняет резерв 128 МиБ")
        }
        if (expectedBytes > usable - CACHE_RESERVE_BYTES) {
            throw IOException("Недостаточно свободного места для объединения APK")
        }
    }

    private fun uniqueFile(directory: File, requestedName: String): File {
        val direct = File(directory, requestedName)
        if (!direct.exists()) return direct
        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        for (index in 2..9999) {
            val candidate = File(directory, "$base ($index)$extension")
            if (!candidate.exists()) return candidate
        }
        throw IOException("Не удалось подобрать имя экспортируемого APK")
    }

    private data class InputSnapshot(
        val file: File,
        val length: Long,
        val lastModified: Long,
    ) {
        fun verifyUnchanged() {
            require(file.isFile && file.length() == length) { "APK-часть изменилась во время объединения: ${file.name}" }
            if (lastModified > 0L && file.lastModified() > 0L) {
                require(file.lastModified() == lastModified) { "APK-часть обновилась во время объединения: ${file.name}" }
            }
        }
    }
}
