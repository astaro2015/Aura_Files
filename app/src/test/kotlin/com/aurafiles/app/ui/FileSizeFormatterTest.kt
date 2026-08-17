package com.aurafiles.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FileSizeFormatterTest {
    @Test
    fun formatsBytesAndLargerUnits() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("0 Б", formatBytes(0))
            assertEquals("512 Б", formatBytes(512))
            assertEquals("1.5 КБ", formatBytes(1536))
            assertEquals("2 МБ", formatBytes(2L * 1024L * 1024L))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
