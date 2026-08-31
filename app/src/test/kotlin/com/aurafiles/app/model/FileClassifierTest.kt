package com.aurafiles.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FileClassifierTest {
    @Test
    fun archiveExtensionsAreClassifiedAsArchives() {
        val names = listOf(
            "a.zip", "a.rar", "a.7z", "a.tar", "a.tgz",
            "a.tar.gz", "a.gz", "a.bz2", "a.xz",
            "a.tbz2", "a.tar.bz2", "a.txz", "a.tar.xz",
        )
        names.forEach { name ->
            assertEquals(name, FileCategory.Archives, FileClassifier.category(name, mimeType = null))
        }
    }

    @Test
    fun comicAndFb2ContainersStayInBooks() {
        listOf("book.cbz", "book.cbr", "book.fb2.zip").forEach { name ->
            assertEquals(name, FileCategory.Books, FileClassifier.category(name, mimeType = null))
        }
    }

    @Test
    fun mediaExtensionsAreUsedWhenMimeIsMissing() {
        mapOf(
            "photo.HEIC" to FileCategory.Images,
            "scan.avif" to FileCategory.Images,
            "movie.mkv" to FileCategory.Video,
            "camera.m2ts" to FileCategory.Video,
            "music.flac" to FileCategory.Audio,
            "voice.opus" to FileCategory.Audio,
        ).forEach { (name, expected) ->
            assertEquals(name, expected, FileClassifier.category(name, mimeType = null))
        }
    }

    @Test
    fun mediaExtensionsAreUsedForGenericMime() {
        assertEquals(FileCategory.Images, FileClassifier.category("picture.webp", mimeType = "application/octet-stream"))
        assertEquals(FileCategory.Video, FileClassifier.category("clip.webm", mimeType = "binary/octet-stream"))
        assertEquals(FileCategory.Audio, FileClassifier.category("recording.m4a", mimeType = "*/*"))
    }

    @Test
    fun specificMimeStillTakesPriorityOverExtensionFallback() {
        assertEquals(FileCategory.Documents, FileClassifier.category("misnamed.mp4", mimeType = "application/pdf"))
        assertEquals(FileCategory.Images, FileClassifier.category("misnamed.bin", mimeType = "image/jpeg"))
    }
}
