package com.aurafiles.app.cloud

import android.content.Context
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists non-secret cloud account metadata. OAuth tokens always live in CloudTokenStore.
 */
class CloudProfileRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val tokenStore = CloudTokenStore(context)

    fun profiles(): List<CloudProfile> {
        val raw = preferences.getString(KEY_PROFILES, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        return buildList {
            for (index in 0 until array.length()) {
                runCatching { array.getJSONObject(index).toProfile() }
                    .getOrNull()
                    ?.let(::add)
            }
        }.sortedBy { it.name.lowercase() }
    }

    fun profile(id: String): CloudProfile? = profiles().firstOrNull { it.id == id }

    fun save(profile: CloudProfile): CloudProfile {
        val existing = profile(profile.id)
        val now = System.currentTimeMillis()
        val normalized = profile.copy(
            id = profile.id.ifBlank { UUID.randomUUID().toString() },
            createdAtEpochMs = existing?.createdAtEpochMs ?: profile.createdAtEpochMs,
        ).normalized(now)
        val updated = profiles().filterNot { it.id == normalized.id } + normalized
        persist(updated)
        return normalized
    }

    /**
     * Stores tokens before exposing the profile. If metadata persistence fails, the freshly saved
     * token blob is rolled back so no orphaned OAuth secret remains.
     */
    fun saveAuthenticated(profile: CloudProfile, tokens: CloudAuthTokens): CloudProfile {
        val normalizedId = profile.id.ifBlank { UUID.randomUUID().toString() }
        val candidate = profile.copy(id = normalizedId)
        val previousTokens = tokenStore.get(normalizedId)
        tokenStore.put(normalizedId, tokens)
        return try {
            save(candidate)
        } catch (error: Throwable) {
            if (previousTokens != null) tokenStore.put(normalizedId, previousTokens)
            else tokenStore.remove(normalizedId)
            throw error
        }
    }

    fun tokens(profileId: String): CloudAuthTokens? = tokenStore.get(profileId)

    fun updateTokens(profileId: String, tokens: CloudAuthTokens) {
        require(profile(profileId) != null) { "Cloud-профиль не найден" }
        tokenStore.put(profileId, tokens)
    }

    fun updateAccessToken(profileId: String, accessToken: String, expiresAtEpochMs: Long?) {
        require(profile(profileId) != null) { "Cloud-профиль не найден" }
        tokenStore.updateAccessToken(profileId, accessToken, expiresAtEpochMs)
    }

    fun hasStoredTokens(profileId: String): Boolean = tokenStore.hasStoredTokens(profileId)

    fun delete(id: String) {
        if (id.isBlank()) return
        val remaining = profiles().filterNot { it.id == id }
        val previousTokens = tokenStore.get(id)
        tokenStore.remove(id)
        try {
            persist(remaining)
        } catch (error: Throwable) {
            if (previousTokens != null) tokenStore.put(id, previousTokens)
            throw error
        }
    }

    private fun persist(profiles: List<CloudProfile>) {
        val array = JSONArray()
        profiles.sortedBy { it.name.lowercase() }.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("provider", profile.provider.storageKey)
                    .put("accountId", profile.accountId)
                    .put("accountLabel", profile.accountLabel)
                    .put("oauthClientId", profile.oauthClientId)
                    .put("createdAtEpochMs", profile.createdAtEpochMs)
                    .put("updatedAtEpochMs", profile.updatedAtEpochMs)
            )
        }
        check(preferences.edit().putString(KEY_PROFILES, array.toString()).commit()) {
            "Не удалось сохранить cloud-профили"
        }
    }

    private fun JSONObject.toProfile(): CloudProfile {
        val provider = CloudProvider.fromStorageKey(getString("provider"))
            ?: throw IllegalArgumentException("Неизвестный cloud provider")
        val created = optLong("createdAtEpochMs", 0L).takeIf { it > 0L } ?: System.currentTimeMillis()
        return CloudProfile(
            id = getString("id"),
            name = optString("name"),
            provider = provider,
            accountId = optString("accountId"),
            accountLabel = optString("accountLabel"),
            oauthClientId = optString("oauthClientId"),
            createdAtEpochMs = created,
            updatedAtEpochMs = optLong("updatedAtEpochMs", created).takeIf { it > 0L } ?: created,
        ).normalized(optLong("updatedAtEpochMs", created).takeIf { it > 0L } ?: created)
    }

    companion object {
        const val PREFERENCES = "aura_cloud_profiles"
        private const val KEY_PROFILES = "profiles"
    }
}
