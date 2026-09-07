package com.aurafiles.app.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Device-bound encrypted OAuth token storage.
 *
 * Only opaque encrypted blobs are written to SharedPreferences. The AES key lives in
 * AndroidKeyStore and each blob is additionally authenticated with the cloud profile ID as AAD,
 * so copying a ciphertext to another profile ID cannot make it decrypt as that account.
 */
class CloudTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun put(profileId: String, tokens: CloudAuthTokens) {
        require(profileId.isNotBlank()) { "Пустой ID cloud-профиля" }
        val plain = JSONObject()
            .put("v", PAYLOAD_VERSION)
            .put("accessToken", tokens.accessToken)
            .put("refreshToken", tokens.refreshToken ?: JSONObject.NULL)
            .put("expiresAtEpochMs", tokens.expiresAtEpochMs ?: JSONObject.NULL)
            .put("tokenType", tokens.tokenType.ifBlank { "Bearer" })
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "Неожиданный размер IV Android Keystore: ${iv.size}" }
        cipher.updateAAD(aad(profileId))
        val encrypted = cipher.doFinal(plain)
        val payload = Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(preferenceKey(profileId), payload).commit()) {
            "Не удалось сохранить cloud-токены"
        }
    }

    fun get(profileId: String?): CloudAuthTokens? {
        if (profileId.isNullOrBlank()) return null
        val payload = preferences.getString(preferenceKey(profileId), null) ?: return null
        return runCatching {
            val decoded = Base64.decode(payload, Base64.NO_WRAP)
            require(decoded.size > IV_BYTES) { "Повреждённое cloud-хранилище" }
            val iv = decoded.copyOfRange(0, IV_BYTES)
            val encrypted = decoded.copyOfRange(IV_BYTES, decoded.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(profileId))
            val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
            require(json.optInt("v", -1) == PAYLOAD_VERSION) { "Неизвестная версия cloud-токена" }
            CloudAuthTokens(
                accessToken = json.getString("accessToken"),
                refreshToken = json.nullableString("refreshToken"),
                expiresAtEpochMs = json.nullableLong("expiresAtEpochMs"),
                tokenType = json.optString("tokenType", "Bearer").ifBlank { "Bearer" },
            )
        }.getOrNull()
    }

    /** Update a short-lived access token while preserving the provider's existing refresh token. */
    fun updateAccessToken(profileId: String, accessToken: String, expiresAtEpochMs: Long?) {
        val current = requireNotNull(get(profileId)) { "Cloud-токены не найдены" }
        put(
            profileId,
            CloudAuthTokens(
                accessToken = accessToken,
                refreshToken = current.refreshToken,
                expiresAtEpochMs = expiresAtEpochMs,
                tokenType = current.tokenType,
            )
        )
    }

    fun remove(profileId: String?) {
        if (profileId.isNullOrBlank()) return
        check(preferences.edit().remove(preferenceKey(profileId)).commit()) {
            "Не удалось удалить cloud-токены"
        }
    }

    /** True only when the encrypted token blob is present and decryptable on this device. */
    fun hasStoredTokens(profileId: String): Boolean =
        profileId.isNotBlank() && get(profileId) != null

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

    private fun aad(profileId: String): ByteArray =
        "aura-cloud-token:$PAYLOAD_VERSION:$profileId".toByteArray(Charsets.UTF_8)

    private fun preferenceKey(profileId: String): String = "profile.$profileId"

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key).takeIf(String::isNotBlank)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    companion object {
        const val PREFERENCES = "aura_cloud_tokens"
        private const val KEY_ALIAS = "aura_cloud_tokens_aes_gcm_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val PAYLOAD_VERSION = 1
    }
}
