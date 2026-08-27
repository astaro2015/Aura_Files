package com.aurafiles.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArchiveSafetyTest {
    @Test
    fun acceptsNestedRelativePath() {
        assertEquals(listOf("Books", "book.fb2"), ArchiveSafety.safePath("Books/book.fb2"))
    }

    @Test
    fun blocksZipSlipAndAbsoluteWindowsPaths() {
        assertThrows(IllegalArgumentException::class.java) { ArchiveSafety.safePath("../secret.txt") }
        assertThrows(IllegalArgumentException::class.java) { ArchiveSafety.safePath("C:\\secret.txt") }
        assertThrows(IllegalArgumentException::class.java) { ArchiveSafety.safePath("/etc/passwd") }
    }

    @Test
    fun sanitizesSingleEntryName() {
        assertEquals("folder_file.txt", ArchiveSafety.safeSegment("folder/file.txt"))
    }
}
