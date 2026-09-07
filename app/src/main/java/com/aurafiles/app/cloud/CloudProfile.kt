package com.aurafiles.app.cloud

import java.util.UUID

enum class CloudProvider(val storageKey: String, val displayName: String) {
    YANDEX_DISK("yandex_disk", "Яндекс.Диск"),
    GOOGLE_DRIVE("google_drive", "Google Drive");

    companion object {
        fun fromStorageKey(value: String): CloudProvider? = entries.firstOrNull {
            it.storageKey.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true)
        }
    }
}

data class CloudProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val provider: CloudProvider,
    /** Stable account identifier returned by the provider when available. */
    val accountId: String = "",
    /** User-facing login/e-mail. It is metadata, never an OAuth secret. */
    val accountLabel: String = "",
    /** Public OAuth client identifier tied to this authorization, when provider refresh requires it. */
    val oauthClientId: String = "",
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = createdAtEpochMs,
) {
    fun normalized(nowEpochMs: Long = System.currentTimeMillis()): CloudProfile {
        require(id.isNotBlank()) { "Пустой ID cloud-профиля" }
        val normalizedAccountLabel = accountLabel.trim()
        val normalizedName = name.trim().ifBlank {
            if (normalizedAccountLabel.isBlank()) provider.displayName
            else "${provider.displayName} — $normalizedAccountLabel"
        }
        val created = createdAtEpochMs.takeIf { it > 0L } ?: nowEpochMs
        return copy(
            id = id.trim(),
            name = normalizedName,
            accountId = accountId.trim(),
            accountLabel = normalizedAccountLabel,
            oauthClientId = oauthClientId.trim(),
            createdAtEpochMs = created,
            updatedAtEpochMs = nowEpochMs.coerceAtLeast(created),
        )
    }
}
