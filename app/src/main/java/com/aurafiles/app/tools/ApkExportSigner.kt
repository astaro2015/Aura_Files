package com.aurafiles.app.tools

import android.content.Context
import android.os.Build
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Calendar
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Signs APKs exported from installed split applications.
 *
 * The export identity is intentionally separate from Aura Files' own application-signing key.
 * It is a normal software RSA key stored only under noBackupFilesDir. apksig can therefore use
 * the PrivateKey through standard JCA providers instead of depending on AndroidKeyStore provider
 * compatibility. noBackupFilesDir survives ordinary app upgrades but is removed on uninstall and
 * is excluded from Android backup/restore.
 */
object ApkExportSigner {
    private const val SIGNER_NAME = "AURAEXPORT"
    private const val KEY_DIRECTORY = "apk-export-signing"
    private const val PRIVATE_KEY_FILE = "aura-apk-export-v1.pk8"
    private const val CERTIFICATE_FILE = "aura-apk-export-v1.cer"
    private const val KEY_BITS = 2048

    fun sign(context: Context, unsignedApk: File, signedApk: File): String {
        require(unsignedApk.isFile && unsignedApk.length() > 0L) { "Временный APK пуст или недоступен" }
        signedApk.parentFile?.let { parent ->
            require(parent.isDirectory || parent.mkdirs()) { "Не удалось подготовить каталог для подписи APK" }
        }
        if (signedApk.exists() && !signedApk.delete()) {
            throw IOException("Не удалось удалить старый подписанный APK")
        }

        val material = loadOrCreateSigningMaterial(context)
        val signerConfig = ApkSigner.SignerConfig.Builder(
            SIGNER_NAME,
            material.privateKey,
            listOf(material.certificate),
        ).build()

        try {
            ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(unsignedApk)
                .setOutputApk(signedApk)
                .setOtherSignersSignaturesPreserved(false)
                // Aura itself only runs on Android 8.0+ (API 26). Use only APK signing-block
                // schemes needed by the exported APK. v1/JAR is unnecessary for this floor, and
                // v4 produces a separate .idsig sidecar which is not part of the single-APK export.
                .setMinSdkVersion(Build.VERSION_CODES.O)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .build()
                .sign()
            require(signedApk.isFile && signedApk.length() > 0L) { "Подписанный APK не был создан" }
            verify(signedApk)
            return sha256(material.certificate.encoded)
        } catch (error: Throwable) {
            signedApk.delete()
            if (error is IOException && error.message?.startsWith("Не удалось подписать APK") == true) {
                throw error
            }
            throw IOException("Не удалось подписать APK: ${diagnostic(error)}", error)
        }
    }

    fun verify(apk: File) {
        require(apk.isFile && apk.length() > 0L) { "APK для проверки пуст или недоступен" }
        val result = ApkVerifier.Builder(apk)
            // Verify against Aura's supported install floor. A fused APK may declare an older
            // minSdk, but Aura cannot run on devices below API 26 to produce it in the first place.
            .setMinCheckedPlatformVersion(Build.VERSION_CODES.O)
            .build()
            .verify()
        if (!result.isVerified) {
            val details = result.errors
                .take(4)
                .joinToString("; ") { it.toString() }
                .ifBlank { "неизвестная ошибка проверки подписи" }
            throw IOException("Подпись объединённого APK не прошла проверку: $details")
        }
    }

    @Synchronized
    private fun loadOrCreateSigningMaterial(context: Context): SigningMaterial {
        val directory = File(context.noBackupFilesDir, KEY_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Не удалось создать приватный каталог ключа Aura APK Export")
        }
        val keyFile = File(directory, PRIVATE_KEY_FILE)
        val certificateFile = File(directory, CERTIFICATE_FILE)

        val keyExists = keyFile.isFile
        val certificateExists = certificateFile.isFile
        if (!keyExists && !certificateExists) {
            val generated = createSigningMaterial()
            persistNewIdentity(keyFile, certificateFile, generated)
            return generated
        }
        if (keyExists != certificateExists) {
            throw IOException(
                "Ключ Aura APK Export неполный или повреждён. " +
                    "Удалите данные Aura только если готовы потерять совместимость обновлений ранее экспортированных APK.",
            )
        }

        return try {
            val privateKey = FileInputStream(keyFile).use { input ->
                val bytes = input.readBytesChecked(64 * 1024)
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
            }
            val certificate = FileInputStream(certificateFile).use { input ->
                CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
            }
            validateSigningMaterial(privateKey, certificate)
            SigningMaterial(privateKey, certificate)
        } catch (error: Throwable) {
            throw IOException(
                "Ключ Aura APK Export неполный или повреждён: ${diagnostic(error)}. " +
                    "Aura не создаёт новый ключ автоматически, чтобы не менять подпись ранее экспортированных APK.",
                error,
            )
        }
    }

    private fun createSigningMaterial(): SigningMaterial {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(KEY_BITS, SecureRandom())
        }.generateKeyPair()
        val certificate = selfSignedCertificate(keyPair)
        validateSigningMaterial(keyPair.private, certificate)
        return SigningMaterial(keyPair.private, certificate)
    }

    private fun selfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60L * 60L * 1000L)
        val notAfter = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.YEAR, 25)
        }.time
        val serial = BigInteger(127, SecureRandom()).add(BigInteger.ONE)
        val subject = X500Name("CN=Aura APK Export,O=Aura Files")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public,
        )
        val contentSigner = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(contentSigner)).apply {
            checkValidity(Date())
            verify(keyPair.public)
        }
    }

    private fun validateSigningMaterial(privateKey: PrivateKey, certificate: X509Certificate) {
        require(privateKey.algorithm.equals("RSA", ignoreCase = true)) { "Экспортный ключ не RSA" }
        require(certificate.publicKey.algorithm.equals("RSA", ignoreCase = true)) { "Сертификат экспортного ключа не RSA" }
        certificate.checkValidity(Date())

        val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(challenge)
            sign()
        }
        val verified = Signature.getInstance("SHA256withRSA").run {
            initVerify(certificate.publicKey)
            update(challenge)
            verify(signature)
        }
        require(verified) { "Приватный ключ и сертификат Aura APK Export не совпадают" }
    }

    private fun persistNewIdentity(
        keyFile: File,
        certificateFile: File,
        material: SigningMaterial,
    ) {
        val keyBytes = material.privateKey.encoded
            ?: throw IOException("JCA не позволил сохранить приватный ключ Aura APK Export")
        val certificateBytes = material.certificate.encoded
        val keyTemp = File(keyFile.parentFile, ".${keyFile.name}.tmp")
        val certificateTemp = File(certificateFile.parentFile, ".${certificateFile.name}.tmp")
        keyTemp.delete()
        certificateTemp.delete()

        try {
            writeDurably(keyTemp, keyBytes)
            writeDurably(certificateTemp, certificateBytes)
            if (!keyTemp.renameTo(keyFile)) {
                throw IOException("Не удалось сохранить приватный ключ Aura APK Export")
            }
            if (!certificateTemp.renameTo(certificateFile)) {
                keyFile.delete()
                throw IOException("Не удалось сохранить сертификат Aura APK Export")
            }
        } catch (error: Throwable) {
            keyTemp.delete()
            certificateTemp.delete()
            if (!certificateFile.exists()) keyFile.delete()
            throw error
        }
    }

    private fun writeDurably(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun FileInputStream.readBytesChecked(maxBytes: Int): ByteArray {
        val bytes = readBytes()
        if (bytes.isEmpty() || bytes.size > maxBytes) {
            throw IOException("Некорректный размер сохранённого ключа")
        }
        return bytes
    }

    private fun diagnostic(error: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = error
        while (current != null && parts.size < 4) {
            val throwable = current
            val text = buildString {
                append(throwable.javaClass.simpleName.ifBlank { throwable.javaClass.name })
                throwable.message?.takeIf { it.isNotBlank() }?.let { message ->
                    append(": ")
                    append(message)
                }
            }
            if (parts.lastOrNull() != text) parts += text
            current = throwable.cause
        }
        return parts.joinToString(" → ").ifBlank { "неизвестная ошибка" }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    private data class SigningMaterial(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
    )
}
