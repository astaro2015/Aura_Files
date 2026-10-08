package com.aurafiles.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class VaultStoragePathsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun migratesLegacyDirectoryWithoutChangingEncryptedBytes() {
        val storage = temporary.root
        val oldData = File(storage, ".AuraVault/data").apply { mkdirs() }
        val encrypted = byteArrayOf(0, 1, -1, 42)
        File(oldData, "item.avf").writeBytes(encrypted)
        File(storage, ".AuraVault/.nomedia").createNewFile()

        val result = VaultStoragePaths.dataDirectory(storage, create = false)

        assertEquals(File(storage, ".AuraSafe/data"), result)
        assertArrayEquals(encrypted, File(result, "item.avf").readBytes())
        assertTrue(File(storage, ".AuraSafe/.nomedia").isFile)
        assertFalse(File(storage, ".AuraVault").exists())
        assertEquals(result, VaultStoragePaths.dataDirectory(storage, create = false))
    }

    @Test
    fun refusesConflictingDirectoriesWithoutDeletingEither() {
        val storage = temporary.root
        File(storage, ".AuraVault/data").apply { mkdirs() }
        File(storage, ".AuraVault/data/old.avf").writeText("old")
        File(storage, ".AuraSafe/data").apply { mkdirs() }
        File(storage, ".AuraSafe/data/new.avf").writeText("new")

        try {
            VaultStoragePaths.dataDirectory(storage, create = true)
            fail("Both vault directories must be reported as a conflict")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains(".AuraVault"))
            assertTrue(expected.message.orEmpty().contains(".AuraSafe"))
        }

        assertEquals("old", File(storage, ".AuraVault/data/old.avf").readText())
        assertEquals("new", File(storage, ".AuraSafe/data/new.avf").readText())
    }

    @Test
    fun createsOnlyNewFolderOnFreshStorage() {
        val storage = temporary.root
        assertEquals(File(storage, ".AuraSafe/data"), VaultStoragePaths.dataDirectory(storage, create = true))
        assertTrue(File(storage, ".AuraSafe/data").isDirectory)
        assertFalse(File(storage, ".AuraVault").exists())
    }

    @Test
    fun rejectsLegacySymlinkWithoutMovingItsTarget() {
        val storage = temporary.root
        val actual = temporary.newFolder("actual").apply { File(this, "item.avf").writeText("encrypted") }
        Files.createSymbolicLink(File(storage, ".AuraVault").toPath(), actual.toPath())

        try {
            VaultStoragePaths.dataDirectory(storage, create = false)
            fail("A symlink must not be migrated")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains(".AuraVault"))
        }

        assertTrue(Files.isSymbolicLink(File(storage, ".AuraVault").toPath()))
        assertEquals("encrypted", File(actual, "item.avf").readText())
    }

    @Test
    fun protectsBothCurrentAndLegacyNames() {
        assertTrue(VaultStoragePaths.isVaultFolder(".AuraSafe"))
        assertTrue(VaultStoragePaths.isVaultFolder(".AuraVault"))
        assertFalse(VaultStoragePaths.isVaultFolder("ordinary"))
    }

    @Test
    fun rejectsVaultPathsInsideDocumentTreeUris() {
        assertTrue(VaultStoragePaths.isVaultPath("/tree/primary:.AuraSafe/data/item.avf"))
        assertTrue(VaultStoragePaths.isVaultPath("/tree/primary:.AuraVault/data/item.avf"))
        assertFalse(VaultStoragePaths.isVaultPath("/tree/primary:Documents/report.txt"))
    }
}
