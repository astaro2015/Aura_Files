package com.aurafiles.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.StringReader

class PreviewReliabilityTest {
    @Test
    fun neighborListingFailureKeepsTheOpenedMedia() = runBlocking {
        val items = loadOptionalNeighbors(listOf("opened.mp4")) { throw IOException("Folder no longer available") }
        assertEquals(listOf("opened.mp4"), items)
    }

    @Test
    fun neighborListingCancellationIsNotSwallowed() = runBlocking {
        try {
            loadOptionalNeighbors(listOf("opened.mp4")) { throw CancellationException("cancelled") }
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("cancelled", expected.message)
        }
    }

    @Test
    fun thumbnailClassifierHandlesCommonExtensionsWithoutMime() {
        assertTrue(isPreviewImage("photo.avif", null))
        assertTrue(isPreviewImage("photo.heif", "application/octet-stream"))
        assertTrue(isPreviewVideo("movie.mkv", null))
        assertTrue(isPreviewVideo("movie.mov", "application/octet-stream"))
        assertFalse(isPreviewVideo("notes.txt", null))
    }

    @Test
    fun djvuMimeIsNotRoutedToBitmapViewer() {
        assertFalse(isPreviewImage("scan.djvu", "image/vnd.djvu"))
        assertFalse(isPreviewImage("scan.djv", "image/x-djvu"))
        assertTrue(isPreviewImage("photo.jpg", "image/jpeg"))
    }

    @Test
    fun textPreviewIsBoundedForVeryLargeFiles() {
        val preview = readTextPreview(StringReader("x".repeat(100_000)))
        assertEquals(32_768, preview.substringBefore("\n\n…").length)
        assertTrue(preview.endsWith("…предпросмотр ограничен…"))
        assertEquals("small", readTextPreview(StringReader("small")))
    }
}
