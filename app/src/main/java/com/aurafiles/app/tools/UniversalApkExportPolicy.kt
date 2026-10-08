package com.aurafiles.app.tools

/** Pure naming/UI policy for installed-application APK exports. */
object UniversalApkExportPolicy {
    private val labelUnsafe = Regex("[^\\p{L}\\p{N}._ -]+")
    private val versionUnsafe = Regex("[^A-Za-z0-9._-]+")

    fun shareLabel(isSplit: Boolean, exporting: Boolean): String = when {
        exporting -> "Готовим APK…"
        isSplit -> "Поделиться APK…"
        else -> "Поделиться APK…"
    }

    fun outputFileName(
        label: String,
        packageName: String,
        versionName: String,
        versionCode: Long,
    ): String {
        val fallbackLabel = packageName.substringAfterLast('.').ifBlank { "app" }
        val safeLabel = safePart(label, fallbackLabel, 48, labelUnsafe)
        val safeVersion = safePart(versionName, versionCode.toString(), 24, versionUnsafe)
        return "installed-$safeLabel-$safeVersion.apk"
    }

    private fun safePart(value: String, fallback: String, maxLength: Int, unsafe: Regex): String {
        val sanitized = value
            .replace(unsafe, "_")
            .trim()
            .trim('_', '-', '.')
            .take(maxLength)
            .trim()
            .trim('_', '-', '.')
        return sanitized.ifBlank { fallback.take(maxLength) }
    }
}
