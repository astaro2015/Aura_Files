package com.aurafiles.app.network

import android.content.Context
import com.aurafiles.app.model.FtpProfile
import com.aurafiles.app.model.SftpProfile
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
        val secretId = preserveOrStore(profile.password, existing?.secretId)
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
        val secretId = preserveOrStore(profile.password, existing?.secretId)
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

    fun save(profile: SftpProfile): NetworkProfile {
        val all = profiles()
        val existing = profile.id.takeIf(String::isNotBlank)?.let { id -> all.firstOrNull { it.id == id } }
            ?: all.firstOrNull {
                it.protocol == NetworkProtocol.SFTP && it.host.equals(profile.host, true) &&
                    it.port == profile.port && it.username == profile.username
            }
        val useKey = profile.privateKey.isNotBlank()
        val passwordId: String?
        val privateKeyId: String?
        val passphraseId: String?
        if (useKey) {
            credentials.remove(existing?.secretId)
            passwordId = null
            privateKeyId = preserveOrStore(profile.privateKey, existing?.sftpPrivateKeySecretId)
            passphraseId = storeOrClear(profile.privateKeyPassphrase, existing?.sftpKeyPassphraseSecretId)
        } else {
            credentials.remove(existing?.sftpPrivateKeySecretId)
            credentials.remove(existing?.sftpKeyPassphraseSecretId)
            passwordId = preserveOrStore(profile.password, existing?.secretId)
            privateKeyId = null
            passphraseId = null
        }
        return upsert(
            NetworkProfile(
                id = existing?.id ?: profile.id.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString(),
                name = profile.name.trim().ifBlank { "SFTP ${profile.host}" },
                protocol = NetworkProtocol.SFTP,
                host = profile.host.trim(),
                port = profile.port,
                username = profile.username,
                secretId = passwordId,
                sftpFingerprint = profile.trustedFingerprint,
                sftpUseKey = useKey,
                sftpPrivateKeySecretId = privateKeyId,
                sftpKeyPassphraseSecretId = passphraseId,
                sftpInitialPath = profile.initialPath.ifBlank { "/" },
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

    fun sftp(profile: NetworkProfile): SftpProfile {
        require(profile.protocol == NetworkProtocol.SFTP) { "Профиль не является SFTP" }
        return SftpProfile(
            id = profile.id,
            name = profile.name,
            host = profile.host,
            port = profile.port.takeIf { it > 0 } ?: 22,
            username = profile.username,
            password = if (profile.sftpUseKey) "" else credentials.get(profile.secretId),
            privateKey = if (profile.sftpUseKey) credentials.get(profile.sftpPrivateKeySecretId) else "",
            privateKeyPassphrase = if (profile.sftpUseKey) credentials.get(profile.sftpKeyPassphraseSecretId) else "",
            trustedFingerprint = profile.sftpFingerprint,
            initialPath = profile.sftpInitialPath.ifBlank { "/" },
        )
    }

    fun trustSftpFingerprint(id: String, fingerprint: String): NetworkProfile {
        require(fingerprint.startsWith("SHA256:")) { "Некорректный fingerprint SFTP" }
        val existing = profiles().firstOrNull { it.id == id && it.protocol == NetworkProtocol.SFTP }
            ?: throw IllegalArgumentException("SFTP-профиль не найден")
        return upsert(existing.copy(sftpFingerprint = fingerprint))
    }

    fun delete(id: String) {
        val all = profiles()
        all.firstOrNull { it.id == id }?.let(::removeSecrets)
        persist(all.filterNot { it.id == id })
    }

    fun duplicate(id: String): NetworkProfile? {
        val source = profiles().firstOrNull { it.id == id } ?: return null
        val duplicate = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} — копия",
            secretId = copySecret(source.secretId),
            sftpPrivateKeySecretId = copySecret(source.sftpPrivateKeySecretId),
            sftpKeyPassphraseSecretId = copySecret(source.sftpKeyPassphraseSecretId),
        )
        return upsert(duplicate)
    }

    private fun preserveOrStore(value: String, existingId: String?): String? = when {
        value.isNotEmpty() -> credentials.put(value, existingId ?: UUID.randomUUID().toString())
        existingId != null -> existingId
        else -> null
    }

    private fun storeOrClear(value: String, existingId: String?): String? {
        if (value.isEmpty()) {
            credentials.remove(existingId)
            return null
        }
        return credentials.put(value, existingId ?: UUID.randomUUID().toString())
    }

    private fun copySecret(id: String?): String? = id?.let { credentials.put(credentials.get(it)) }

    private fun removeSecrets(profile: NetworkProfile) {
        credentials.remove(profile.secretId)
        credentials.remove(profile.sftpPrivateKeySecretId)
        credentials.remove(profile.sftpKeyPassphraseSecretId)
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
                    .put("sftpFingerprint", profile.sftpFingerprint)
                    .put("sftpUseKey", profile.sftpUseKey)
                    .put("sftpPrivateKeySecretId", profile.sftpPrivateKeySecretId ?: JSONObject.NULL)
                    .put("sftpKeyPassphraseSecretId", profile.sftpKeyPassphraseSecretId ?: JSONObject.NULL)
                    .put("sftpInitialPath", profile.sftpInitialPath)
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
        secretId = nullableString("secretId"),
        smbShare = optString("share"),
        smbDomain = optString("domain"),
        tls = optBoolean("tls"),
        sftpFingerprint = optString("sftpFingerprint"),
        sftpUseKey = if (has("sftpUseKey")) optBoolean("sftpUseKey") else nullableString("sftpPrivateKeySecretId") != null,
        sftpPrivateKeySecretId = nullableString("sftpPrivateKeySecretId"),
        sftpKeyPassphraseSecretId = nullableString("sftpKeyPassphraseSecretId"),
        sftpInitialPath = optString("sftpInitialPath", "/").ifBlank { "/" },
    )

    private fun JSONObject.nullableString(key: String): String? =
        optString(key).takeUnless { it.isBlank() || it == "null" }

    companion object {
        private const val PREFERENCES = "aura_network_profiles"
        private const val KEY_PROFILES = "profiles"
    }
}
