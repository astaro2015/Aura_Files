package com.aurafiles.app.sync

import com.aurafiles.app.backend.StorageBackendKind
import com.aurafiles.app.test.FakeStorageBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectoryCompareTest {
    @Test fun classifiesNameSizeAndModifiedDifferences() = runBlocking {
        val left = FakeStorageBackend("left").apply {
            putFile("/same.txt", "same", 10_000)
            putFile("/size.txt", "12345", 10_000)
            putFile("/newer.txt", "abc", 20_000)
            putFile("/left.txt", "left")
        }
        val right = FakeStorageBackend("right").apply {
            putFile("/same.txt", "same", 10_500)
            putFile("/size.txt", "1", 10_000)
            putFile("/newer.txt", "abc", 10_000)
            putFile("/right.txt", "right")
        }
        val result = DirectoryComparator().compare(left, "/", right, "/")
        val byName = result.entries.associateBy { it.relativePath }
        assertEquals(DirectoryDifference.SAME, byName["same.txt"]?.difference)
        assertEquals(DirectoryDifference.SIZE_DIFFERS, byName["size.txt"]?.difference)
        assertEquals(DirectoryDifference.LEFT_NEWER, byName["newer.txt"]?.difference)
        assertEquals(DirectoryDifference.ONLY_LEFT, byName["left.txt"]?.difference)
        assertEquals(DirectoryDifference.ONLY_RIGHT, byName["right.txt"]?.difference)
    }

    @Test fun traditionalFtpMinutePrecisionDoesNotCreateFalseNewerDifference() = runBlocking {
        val ftp = FakeStorageBackend("ftp", StorageBackendKind.FTP).apply {
            putFile("/same.bin", "same", 120_000)
        }
        val local = FakeStorageBackend("local", StorageBackendKind.LOCAL).apply {
            putFile("/same.bin", "same", 165_000)
        }

        val result = DirectoryComparator().compare(ftp, "/", local, "/")

        assertEquals(DirectoryDifference.SAME, result.entries.single().difference)
        assertEquals(
            60_000L,
            DirectoryComparator.timestampToleranceMs(StorageBackendKind.FTP, StorageBackendKind.LOCAL),
        )
    }

    @Test fun preciseBackendsStillDetectMeaningfulTimestampDifference() = runBlocking {
        val left = FakeStorageBackend("left", StorageBackendKind.LOCAL).apply {
            putFile("/same.bin", "same", 120_000)
        }
        val right = FakeStorageBackend("right", StorageBackendKind.SFTP).apply {
            putFile("/same.bin", "same", 165_000)
        }

        val result = DirectoryComparator().compare(left, "/", right, "/")

        assertEquals(DirectoryDifference.RIGHT_NEWER, result.entries.single().difference)
    }
}
