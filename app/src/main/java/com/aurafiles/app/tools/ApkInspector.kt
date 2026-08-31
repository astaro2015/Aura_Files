package com.aurafiles.app.tools

import com.aurafiles.app.util.toHexString

import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

data class ApkCertificateInfo(
    val subject: String,
    val issuer: String,
    val serialNumber: String,
    val validFrom: Long,
    val validUntil: Long,
    val sha256: String,
)

data class ApkInfo(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val size: Long,
    val apkSha256: String,
    val certificates: List<ApkCertificateInfo>,
    val icon: Drawable?,
)

class ApkInspector(private val context: Context) {
    data class Result(val info: ApkInfo, val temporaryFile: File)

    fun inspect(uri: Uri, displayName: String = "app.apk"): Result {
        val shareDir = File(context.cacheDir, "shares").apply { mkdirs() }
        cleanupStaleApks(shareDir)
        val temporary = File.createTempFile("aura-apk-", ".apk", shareDir)
        try {
            openInput(uri).use { input -> temporary.outputStream().buffered().use { input.copyTo(it, BUFFER_SIZE) } }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            }
            @Suppress("DEPRECATION")
            val packageInfo = context.packageManager.getPackageArchiveInfo(temporary.absolutePath, flags)
                ?: throw IOException("Android не смог прочитать AndroidManifest.xml из APK")
            val appInfo = requireNotNull(packageInfo.applicationInfo) { "В APK нет ApplicationInfo" }
            appInfo.sourceDir = temporary.absolutePath
            appInfo.publicSourceDir = temporary.absolutePath
            val label = runCatching { context.packageManager.getApplicationLabel(appInfo).toString() }
                .getOrDefault(displayName)
            val icon = runCatching { context.packageManager.getApplicationIcon(appInfo) }.getOrNull()
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.let { signing ->
                    if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
                }.orEmpty()
            } else {
                @Suppress("DEPRECATION") packageInfo.signatures.orEmpty()
            }
            val certificates = signatures.mapNotNull { signature ->
                runCatching {
                    val cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(signature.toByteArray().inputStream()) as X509Certificate
                    ApkCertificateInfo(
                        subject = cert.subjectX500Principal.name,
                        issuer = cert.issuerX500Principal.name,
                        serialNumber = cert.serialNumber.toString(16).uppercase(),
                        validFrom = cert.notBefore.time,
                        validUntil = cert.notAfter.time,
                        sha256 = ApkInspectorHashes.sha256(cert.encoded),
                    )
                }.getOrNull()
            }
            val info = ApkInfo(
                label = label,
                packageName = packageInfo.packageName,
                versionName = packageInfo.versionName.orEmpty(),
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else {
                    @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
                },
                minSdk = appInfo.minSdkVersion,
                targetSdk = appInfo.targetSdkVersion,
                size = temporary.length(),
                apkSha256 = hashFile(temporary),
                certificates = certificates,
                icon = icon,
            )
            return Result(info, temporary)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun cleanupStaleApks(directory: File) {
        val cutoff = System.currentTimeMillis() - TEMP_MAX_AGE_MS
        directory.listFiles()?.filter { it.name.startsWith("aura-apk-") && it.lastModified() < cutoff }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun openInput(uri: Uri) = if (uri.scheme == ContentResolver.SCHEME_FILE) {
        File(requireNotNull(uri.path)).inputStream()
    } else context.contentResolver.openInputStream(uri) ?: throw IOException("Не удалось открыть APK")

    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexString()
    }


    companion object {
        private const val BUFFER_SIZE = 1024 * 1024
        private const val TEMP_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    }
}


/** Pure helper kept Android-free so certificate/APK digest behavior is unit-testable. */
object ApkInspectorHashes {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHexString()
}
