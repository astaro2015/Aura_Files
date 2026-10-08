package com.aurafiles.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UniversalApkExportPolicyTest {
    @Test
    fun splitPrimaryActionSharesApkNotApks() {
        assertEquals("Поделиться APK…", UniversalApkExportPolicy.shareLabel(isSplit = true, exporting = false))
        assertEquals("Готовим APK…", UniversalApkExportPolicy.shareLabel(isSplit = true, exporting = true))
    }

    @Test
    fun outputNameIsSafeSingleApk() {
        val name = UniversalApkExportPolicy.outputFileName(
            label = "Test / App 🔥",
            packageName = "com.example.test",
            versionName = "1.2 beta",
            versionCode = 12,
        )
        assertEquals("installed-Test _ App-1.2_beta.apk", name)
        assertFalse(name.endsWith(".apks", ignoreCase = true))
    }

    @Test
    fun packageNameFallbackIsUsedForBlankLabelAndVersion() {
        assertEquals(
            "installed-test-12.apk",
            UniversalApkExportPolicy.outputFileName("", "com.example.test", "", 12),
        )
    }
}
