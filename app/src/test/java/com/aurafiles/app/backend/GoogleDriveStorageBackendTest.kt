package com.aurafiles.app.backend

import com.aurafiles.app.cloud.CloudProfile
import com.aurafiles.app.cloud.CloudProvider
import com.aurafiles.app.cloud.google.GOOGLE_DRIVE_FOLDER_MIME
import com.aurafiles.app.cloud.google.GoogleAccessTokenProvider
import com.aurafiles.app.cloud.google.GoogleBinaryReadHandle
import com.aurafiles.app.cloud.google.GoogleBinaryTransfer
import com.aurafiles.app.cloud.google.GoogleBinaryWriteHandle
import com.aurafiles.app.cloud.google.GoogleDriveAbout
import com.aurafiles.app.cloud.google.GoogleDriveApi
import com.aurafiles.app.cloud.google.GoogleDriveApiException
import com.aurafiles.app.cloud.google.GoogleDriveFailureKind
import com.aurafiles.app.cloud.google.GoogleDriveFile
import com.aurafiles.app.cloud.google.GoogleDriveUser
import com.aurafiles.app.cloud.google.GoogleExportSpec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveStorageBackendTest {
    @Test
    fun `duplicate provider names keep distinct id paths and 401 refresh happens once`() = runBlocking {
        val files = linkedMapOf(
            "a" to GoogleDriveFile("a", "same.txt", "text/plain", size = 3, parents = listOf("root")),
            "b" to GoogleDriveFile("b", "same.txt", "text/plain", size = 4, parents = listOf("root")),
        )
        val tokens = FakeTokens()
        val backend = backend(tokens, files)

        val listed = backend.list("/")
        val duplicates = listed.filter { it.name == "same.txt" }

        assertEquals(1, tokens.invalidations)
        assertEquals(2, duplicates.size)
        assertNotEquals(duplicates[0].path, duplicates[1].path)
        assertEquals("same.txt", backend.stat(duplicates[0].path)?.name)

        var ambiguousRejected = false
        try {
            backend.stat(backend.child("/", "same.txt"))
        } catch (_: IOException) {
            ambiguousRejected = true
        }
        assertTrue("name-based duplicate lookup must be rejected", ambiguousRejected)
    }

    @Test
    fun `basic folder mutations use real google ids`() = runBlocking {
        val files = linkedMapOf<String, GoogleDriveFile>()
        val backend = backend(FakeTokens(initiallyFresh = true), files)

        val created = backend.mkdir(backend.child("/", "Created"))
        assertTrue(created.isDirectory)
        assertEquals("Created", created.name)

        val renamed = backend.rename(created.path, "Renamed")
        assertEquals("Renamed", renamed.name)

        backend.delete(renamed.path, recursive = true)
        assertTrue(files.isEmpty())
    }

    private fun backend(
        tokens: FakeTokens,
        files: MutableMap<String, GoogleDriveFile>,
    ) = GoogleDriveStorageBackend(
        profile = CloudProfile(
            name = "Google Drive — test@example.com",
            provider = CloudProvider.GOOGLE_DRIVE,
            accountLabel = "test@example.com",
        ),
        tokenProvider = tokens,
        apiFactory = { token -> FakeApi(token, files) },
        binaryTransfer = FakeBinaryTransfer(),
    )

    private class FakeTokens(initiallyFresh: Boolean = false) : GoogleAccessTokenProvider {
        var invalidations = if (initiallyFresh) 1 else 0
            private set

        override suspend fun accessToken(forceRefresh: Boolean): String =
            if (invalidations == 0) "expired-token" else "fresh-token"

        override suspend fun invalidate(accessToken: String) {
            invalidations += 1
        }
    }

    private class FakeApi(
        private val token: String,
        private val files: MutableMap<String, GoogleDriveFile>,
    ) : GoogleDriveApi {
        private var nextId = files.size + 1

        private fun requireAuth() {
            if (token == "expired-token") {
                throw GoogleDriveApiException(GoogleDriveFailureKind.UNAUTHORIZED, statusCode = 401)
            }
        }

        override fun about(): GoogleDriveAbout {
            requireAuth()
            return GoogleDriveAbout(GoogleDriveUser("permission", "Test", "test@example.com"))
        }

        override fun file(fileId: String): GoogleDriveFile? {
            requireAuth()
            return files[fileId]
        }

        override fun listChildren(parentId: String): List<GoogleDriveFile> {
            requireAuth()
            return files.values.filter { parentId in it.parents }
        }

        override fun findChildren(parentId: String, name: String): List<GoogleDriveFile> {
            requireAuth()
            return files.values.filter { parentId in it.parents && it.name == name }
        }

        override fun createFolder(parentId: String, name: String): GoogleDriveFile {
            requireAuth()
            val file = GoogleDriveFile(
                id = "folder-${nextId++}",
                name = name,
                mimeType = GOOGLE_DRIVE_FOLDER_MIME,
                parents = listOf(parentId),
            )
            files[file.id] = file
            return file
        }

        override fun rename(fileId: String, newName: String): GoogleDriveFile {
            requireAuth()
            return files.getValue(fileId).copy(name = newName).also { files[fileId] = it }
        }

        override fun move(fileId: String, destinationParentId: String, currentParents: List<String>): GoogleDriveFile {
            requireAuth()
            return files.getValue(fileId).copy(parents = listOf(destinationParentId)).also { files[fileId] = it }
        }

        override fun trash(fileId: String) {
            requireAuth()
            files.remove(fileId)
        }

        override fun startResumableUpload(
            fileId: String?,
            parentId: String,
            name: String,
            mimeType: String,
            expectedSize: Long?,
        ): String {
            requireAuth()
            return "https://example.invalid/upload"
        }
    }

    private class FakeBinaryTransfer : GoogleBinaryTransfer {
        override fun openRead(
            accessToken: String,
            file: GoogleDriveFile,
            exportSpec: GoogleExportSpec?,
        ): GoogleBinaryReadHandle = object : GoogleBinaryReadHandle {
            override val input: InputStream = ByteArrayInputStream(ByteArray(0))
            override fun close() = input.close()
        }

        override fun openWrite(
            sessionUrl: String,
            expectedSize: Long?,
            mimeType: String,
        ): GoogleBinaryWriteHandle = object : GoogleBinaryWriteHandle {
            override val output: OutputStream = ByteArrayOutputStream()
            override fun commit() = Unit
            override fun abort() = Unit
            override fun close() = Unit
        }
    }
}
