package com.aurafiles.app.network

import android.content.Context
import com.aurafiles.app.model.FtpProfile
import com.aurafiles.app.model.SmbProfile
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class NetworkProfileRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val credentials = CredentialStore(context)

    fun profiles(): List<NetworkProfile> = runCatching {
        val array = JSONArray(preferences.getString(KEY_PROFILES, "[]"))
        buildList {
            for (index in 0 until array.length()) add(array.getJSONObject(index).toProfile())
        }
    }.getOrDefault(emptyList())

    fun save(profile: FtpProfile): NetworkProfile {
        val protocol = if (profile.useTls) NetworkProtocol.FTPS else NetworkProtocol.FTP
        val existing = profiles().firstOrNull {
            it.protocol == protocol && it.host.equals(profile.host, true) && it.port == profile.port &&
                it.username == profile.username
        }
        val secretId = when {
            profile.password.isNotEmpty() -> credentials.put(profile.password, existing?.secretId ?: UUID.randomUUID().toString())
            existing != null -> existing.secretId
            else -> null
        }
        return upsert(
            NetworkProfile(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = profile.name,
                protocol = protocol,
                host = profile.host,
                port = profile.port,
                username = profile.username,
                secretId = secretId,
                tls = profile.useTls,
            )
        )
    }

    fun save(profile: SmbProfile): NetworkProfile {
        val existing = profiles().firstOrNull {
            it.protocol == NetworkProtocol.SMB && it.host.equals(profile.host, true) &&
                it.smbShare.equals(profile.share, true) && it.username == profile.username
        }
        val secretId = when {
            profile.password.isNotEmpty() -> credentials.put(profile.password, existing?.secretId ?: UUID.randomUUID().toString())
            existing != null -> existing.secretId
            else -> null
        }
        return upsert(
            NetworkProfile(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = profile.name,
                protocol = NetworkProtocol.SMB,
                host = profile.host,
                port = 445,
                username = profile.username,
                secretId = secretId,
                smbShare = profile.share,
                smbDomain = profile.domain,
            )
        )
    }

    fun ftp(profile: NetworkProfile): FtpProfile = FtpProfile(
        name = profile.name,
        host = profile.host,
        port = profile.port,
        username = profile.username,
        password = credentials.get(profile.secretId),
        useTls = profile.protocol == NetworkProtocol.FTPS || profile.tls,
    )

    fun smb(profile: NetworkProfile): SmbProfile = SmbProfile(
        name = profile.name,
        host = profile.host,
        share = profile.smbShare,
        username = profile.username,
        password = credentials.get(profile.secretId),
        domain = profile.smbDomain,
    )

    fun delete(id: String) {
        val all = profiles()
        all.firstOrNull { it.id == id }?.let { credentials.remove(it.secretId) }
        persist(all.filterNot { it.id == id })
    }

    fun duplicate(id: String): NetworkProfile? {
        val source = profiles().firstOrNull { it.id == id } ?: return null
        val copiedSecret = source.secretId?.let { credentials.put(credentials.get(it)) }
        return upsert(
            source.copy(
                id = UUID.randomUUID().toString(),
                name = "${source.name} — копия",
                secretId = copiedSecret,
            )
        )
    }

    private fun upsert(profile: NetworkProfile): NetworkProfile {
        val updated = profiles().filterNot { it.id == profile.id } + profile
        persist(updated.sortedBy { it.name.lowercase() })
        return profile
    }

    private fun persist(profiles: List<NetworkProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("protocol", profile.protocol.name)
                    .put("host", profile.host)
                    .put("port", profile.port)
                    .put("username", profile.username)
                    .put("secretId", profile.secretId ?: JSONObject.NULL)
                    .put("share", profile.smbShare)
                    .put("domain", profile.smbDomain)
                    .put("tls", profile.tls)
            )
        }
        preferences.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    private fun JSONObject.toProfile(): NetworkProfile = NetworkProfile(
        id = getString("id"),
        name = getString("name"),
        protocol = NetworkProtocol.valueOf(getString("protocol")),
        host = getString("host"),
        port = getInt("port"),
        username = optString("username"),
        secretId = optString("secretId").takeUnless { it.isBlank() || it == "null" },
        smbShare = optString("share"),
        smbDomain = optString("domain"),
        tls = optBoolean("tls"),
    )

    companion object {
        private const val PREFERENCES = "aura_network_profiles"
        private const val KEY_PROFILES = "profiles"
    }
}

