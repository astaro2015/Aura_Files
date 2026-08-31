package com.aurafiles.app.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import com.aurafiles.app.test.FakeStorageBackend
import kotlinx.coroutines.runBlocking

class StorageBackendTest {
    @Test fun pathsAreNormalizedAndCannotEscapeRoot() {
        assertEquals("/Movies/2026", BackendPath.normalize("//Movies/./2026/"))
        assertEquals("/Movies/a.mkv", BackendPath.child("/Movies", "a.mkv"))
        assertEquals("/Movies", BackendPath.parent("/Movies/a.mkv"))
        assertThrows(IllegalArgumentException::class.java) { BackendPath.normalize("/Movies/../secret") }
        assertThrows(IllegalArgumentException::class.java) { BackendPath.child("/", "../secret") }
    }
    @Test fun commonCrudContractWorksForFilesAndDirectories() = runBlocking {
        val backend = FakeStorageBackend("crud")
        backend.mkdir("/Folder")
        backend.putFile("/Folder/old.txt", "x")
        val renamed = backend.rename("/Folder/old.txt", "new.txt")
        assertEquals("/Folder/new.txt", renamed.path)
        backend.delete("/Folder", recursive = true)
        assertEquals(null, backend.stat("/Folder"))
    }

}
