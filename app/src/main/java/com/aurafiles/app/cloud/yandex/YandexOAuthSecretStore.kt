package com.aurafiles.app.cloud.yandex

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-bound storage for Yandex OAuth application secrets.
 *
 * Yandex device-code token exchange can require the OAuth application's client_secret.
 * The secret is keyed by client_id, encrypted with an AndroidKeyStore AES-GCM key and never
 * written to preferences in plaintext.
 */
class YandexOAuthSecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun put(clientId: String, clientSecret: String) {
        val id = clientId.trim()
        val secret = clientSecret.trim()
        require(id.isNotBlank()) { "Пустой Yandex OAuth Client ID" }
        require(secret.isNotBlank()) { "Пустой Yandex OAuth Client Secret" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "Неожиданный размер IV Android Keystore: ${iv.size}" }
        cipher.updateAAD(aad(id))
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(preferenceKey(id), payload).commit()) {
            "Не удалось сохранить Yandex OAuth Client Secret"
        }
    }

    fun get(clientId: String?): String? {
        val id = clientId?.trim().orEmpty()
        if (id.isBlank()) return null
        val payload = preferences.getString(preferenceKey(id), null) ?: return null
        return runCatching {
            val decoded = Base64.decode(payload, Base64.NO_WRAP)
            require(decoded.size > IV_BYTES) { "Повреждено хранилище Yandex OAuth Secret" }
            val iv = decoded.copyOfRange(0, IV_BYTES)
            val encrypted = decoded.copyOfRange(IV_BYTES, decoded.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(id))
            String(cipher.doFinal(encrypted), Charsets.UTF_8).trim().takeIf(String::isNotBlank)
        }.getOrNull()
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun aad(clientId: String): ByteArray =
        "aura-yandex-oauth-secret:v1:$clientId".toByteArray(Charsets.UTF_8)

    private fun preferenceKey(clientId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(clientId.toByteArray(Charsets.UTF_8))
        val key = Base64.encodeToString(digest, Base64.NO_WRAP or Base64.URL_SAFE).trimEnd('=')
        return "client.$key"
    }

    companion object {
        const val PREFERENCES = "aura_yandex_oauth_secrets"
        private const val KEY_ALIAS = "aura_yandex_oauth_secret_aes_gcm_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
