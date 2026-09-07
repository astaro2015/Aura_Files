package com.aurafiles.app.backend

import com.aurafiles.app.cloud.CloudProfile
import com.aurafiles.app.cloud.CloudProvider
import com.aurafiles.app.cloud.yandex.YandexAccessTokenProvider
import com.aurafiles.app.cloud.yandex.YandexApiException
import com.aurafiles.app.cloud.yandex.YandexBinaryReadHandle
import com.aurafiles.app.cloud.yandex.YandexBinaryTransfer
import com.aurafiles.app.cloud.yandex.YandexBinaryWriteHandle
import com.aurafiles.app.cloud.yandex.YandexDiskApi
import com.aurafiles.app.cloud.yandex.YandexDiskInfo
import com.aurafiles.app.cloud.yandex.YandexDiskResource
import com.aurafiles.app.cloud.yandex.YandexFailureKind
import com.aurafiles.app.cloud.yandex.YandexOperationLink
import com.aurafiles.app.cloud.yandex.YandexOperationState
import com.aurafiles.app.cloud.yandex.YandexTransferLink
import com.aurafiles.app.test.FakeStorageBackend
import com.aurafiles.app.transfer.BackendTransferCore
import com.aurafiles.app.transfer.TransferConflictDecision
import com.aurafiles.app.transfer.TransferConflictPolicy
import com.aurafiles.app.transfer.TransferController
import com.aurafiles.app.transfer.TransferDestination
import com.aurafiles.app.transfer.TransferRequest
import com.aurafiles.app.transfer.TransferSource
import com.aurafiles.app.transfer.TransferType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YandexDiskStorageBackendTest {
    @Test fun unauthorizedRestCallRefreshesExactlyOnce() = runBlocking {
        val remote = FakeYandexRemote().apply { putFile("/a.txt", "A") }
        val refreshFlags = mutableListOf<Boolean>()
        val provider = YandexAccessTokenProvider { force ->
            refreshFlags += force
            if (force) "fresh" else "stale"
        }
        val backend = backend(
            remote,
            provider,
            apiFactory = { token ->
                if (token == "stale") UnauthorizedYandexApi(remote.api)
                else remote.api
            },
        )

        val files = backend.list("/")
        assertEquals(listOf("a.txt"), files.map { it.name })
        assertEquals(listOf(false, true), refreshFlags)
    }

    @Test fun commonTransferCoreCopiesToAndFromYandex() = runBlocking {
        val remote = FakeYandexRemote()
        val yandex = backend(remote, YandexAccessTokenProvider { "token" })
        val local = FakeStorageBackend("local").apply { putFile("/hello.txt", "hello cloud") }
        val restored = FakeStorageBackend("restored")
        val registry = StorageBackendRegistry().apply {
            register(local)
            register(yandex)
            register(restored)
        }

        val source = requireNotNull(local.stat("/hello.txt"))
        BackendTransferCore(registry).execute(
            TransferRequest(
                type = TransferType.COPY,
                sources = listOf(
                    TransferSource.Backend(local.descriptor.id, source.path, source.name, source.size, source.modifiedAt, false),
                ),
                destination = TransferDestination.Backend(yandex.descriptor.id, "/"),
                conflictPolicy = TransferConflictPolicy.REPLACE,
            ),
            TransferController(),
            { TransferConflictDecision(TransferConflictPolicy.REPLACE) },
            {},
        )

        assertEquals("hello cloud", remote.text("/hello.txt"))
        assertEquals("hello cloud".toByteArray().size.toLong(), remote.lastExpectedWriteSize)
        assertFalse(remote.names().any { it.startsWith(".aura-part-") })

        val cloudSource = requireNotNull(yandex.stat("/hello.txt"))
        BackendTransferCore(registry).execute(
            TransferRequest(
                type = TransferType.COPY,
                sources = listOf(
                    TransferSource.Backend(yandex.descriptor.id, cloudSource.path, cloudSource.name, cloudSource.size, cloudSource.modifiedAt, false),
                ),
                destination = TransferDestination.Backend(restored.descriptor.id, "/"),
                conflictPolicy = TransferConflictPolicy.REPLACE,
            ),
            TransferController(),
            { TransferConflictDecision(TransferConflictPolicy.REPLACE) },
            {},
        )

        assertEquals("hello cloud", String(restored.readBytes("/hello.txt")))
        registry.close()
    }

    @Test fun mkdirRenameMoveAndDeleteUseFilesystemSemantics() = runBlocking {
        val remote = FakeYandexRemote()
        val backend = backend(remote, YandexAccessTokenProvider { "token" })

        backend.mkdir("/one")
        backend.mkdir("/two")
        remote.putFile("/one/a.txt", "A")
        val renamed = backend.rename("/one/a.txt", "b.txt")
        assertEquals("/one/b.txt", renamed.path)
        val moved = backend.move("/one/b.txt", "/two")
        assertEquals("/two/b.txt", moved.path)
        assertEquals("A", remote.text("/two/b.txt"))

        remote.putFile("/one/child.txt", "child")
        val failure = runCatching { backend.delete("/one", recursive = false) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(remote.exists("/one/child.txt"))
        backend.delete("/one", recursive = true)
        assertFalse(remote.exists("/one"))
    }

    private fun backend(
        remote: FakeYandexRemote,
        provider: YandexAccessTokenProvider,
        apiFactory: (String) -> YandexDiskApi = { remote.api },
    ) = YandexDiskStorageBackend(
        profile = CloudProfile(
            id = "profile-1",
            name = "Яндекс.Диск test",
            provider = CloudProvider.YANDEX_DISK,
        ),
        tokenProvider = provider,
        apiFactory = apiFactory,
        binaryTransfer = remote.binary,
    )

    private class UnauthorizedYandexApi(
        private val delegate: YandexDiskApi,
    ) : YandexDiskApi by delegate {
        override fun list(path: String): List<YandexDiskResource> = throw YandexApiException(
            kind = YandexFailureKind.UNAUTHORIZED,
            statusCode = 401,
        )
    }

    private class FakeYandexRemote {
        private data class Node(
            val directory: Boolean,
            var bytes: ByteArray = byteArrayOf(),
            var modified: Long = 1L,
        )

        private val nodes = linkedMapOf("/" to Node(true))
        var lastExpectedWriteSize: Long? = null
            private set

        val api = object : YandexDiskApi {
            override fun diskInfo() = YandexDiskInfo()

            override fun resource(path: String): YandexDiskResource? = node(path)?.toResource(normalize(path))

            override fun list(path: String): List<YandexDiskResource> {
                val parent = normalize(path)
                val node = nodes[parent] ?: throw notFound()
                if (!node.directory) throw IOException("Not a directory")
                return nodes.keys.asSequence()
                    .filter { it != parent && BackendPath.parent(it) == parent }
                    .mapNotNull { child -> nodes[child]?.toResource(child) }
                    .sortedWith(compareByDescending<YandexDiskResource> { it.isDirectory }.thenBy { it.name.lowercase() })
                    .toList()
            }

            override fun uploadLink(path: String, overwrite: Boolean): YandexTransferLink {
                val normalized = normalize(path)
                if (!overwrite && nodes.containsKey(normalized)) throw conflict()
                return YandexTransferLink("https://fake.invalid/upload?path=${encode(normalized)}", "PUT")
            }

            override fun downloadLink(path: String): YandexTransferLink {
                val normalized = normalize(path)
                val item = nodes[normalized] ?: throw notFound()
                if (item.directory) throw IOException("Directory")
                return YandexTransferLink("https://fake.invalid/download?path=${encode(normalized)}", "GET")
            }

            override fun mkdir(path: String) {
                val normalized = normalize(path)
                if (nodes.containsKey(normalized)) throw conflict()
                ensureParents(normalized)
                nodes[normalized] = Node(true)
            }

            override fun move(sourcePath: String, destinationPath: String, overwrite: Boolean): YandexOperationLink {
                val source = normalize(sourcePath)
                val target = normalize(destinationPath)
                if (!nodes.containsKey(source)) throw notFound()
                if (!overwrite && nodes.containsKey(target)) throw conflict()
                if (overwrite) removeTree(target)
                relocate(source, target)
                return YandexOperationLink()
            }

            override fun delete(path: String, permanently: Boolean): YandexOperationLink {
                removeTree(normalize(path))
                return YandexOperationLink()
            }

            override fun operationState(href: String) = YandexOperationState.SUCCESS
        }

        val binary = object : YandexBinaryTransfer {
            override fun openRead(link: YandexTransferLink): YandexBinaryReadHandle {
                val path = decodePath(link.href)
                val item = nodes[path] ?: throw notFound()
                val stream = ByteArrayInputStream(item.bytes.copyOf())
                return object : YandexBinaryReadHandle {
                    override val input = stream
                    override fun close() = input.close()
                }
            }

            override fun openWrite(link: YandexTransferLink, expectedSize: Long?): YandexBinaryWriteHandle {
                val path = decodePath(link.href)
                lastExpectedWriteSize = expectedSize
                val buffer = ByteArrayOutputStream()
                return object : YandexBinaryWriteHandle {
                    private var finished = false
                    override val output = buffer
                    override fun commit() {
                        if (finished) return
                        ensureParents(path)
                        nodes[path] = Node(false, buffer.toByteArray(), System.currentTimeMillis())
                        finished = true
                    }
                    override fun abort() { finished = true }
                    override fun close() { if (!finished) abort() }
                }
            }
        }

        fun putFile(path: String, text: String) {
            val normalized = normalize(path)
            ensureParents(normalized)
            nodes[normalized] = Node(false, text.toByteArray(), System.currentTimeMillis())
        }

        fun text(path: String): String = String(requireNotNull(nodes[normalize(path)]).bytes)
        fun exists(path: String): Boolean = nodes.containsKey(normalize(path))
        fun names(): List<String> = nodes.keys.map(BackendPath::name)

        private fun node(path: String): Node? = nodes[normalize(path)]

        private fun Node.toResource(path: String) = YandexDiskResource(
            path = path,
            name = if (path == "/") "/" else BackendPath.name(path),
            isDirectory = directory,
            size = if (directory) 0L else bytes.size.toLong(),
            modifiedAtEpochMs = modified,
            mimeType = if (directory) null else BackendPath.guessMime(path),
        )

        private fun ensureParents(path: String) {
            var current = BackendPath.parent(path)
            val pending = mutableListOf<String>()
            while (!nodes.containsKey(current)) {
                pending += current
                if (current == "/") break
                current = BackendPath.parent(current)
            }
            pending.asReversed().forEach { nodes[it] = Node(true) }
        }

        private fun relocate(source: String, target: String) {
            ensureParents(target)
            val entries = nodes.entries
                .filter { it.key == source || it.key.startsWith("$source/") }
                .sortedBy { it.key.length }
            val copies = entries.map { (path, node) ->
                (target + path.removePrefix(source)) to node.copy(bytes = node.bytes.copyOf())
            }
            entries.sortedByDescending { it.key.length }.forEach { nodes.remove(it.key) }
            copies.forEach { (path, node) -> nodes[path] = node }
        }

        private fun removeTree(path: String) {
            if (path == "/") throw IOException("Root delete")
            nodes.keys.filter { it == path || it.startsWith("$path/") }
                .sortedByDescending(String::length)
                .forEach(nodes::remove)
        }

        private fun decodePath(url: String): String = java.net.URLDecoder.decode(
            url.substringAfter("path="),
            Charsets.UTF_8.name(),
        )

        private fun encode(path: String): String = java.net.URLEncoder.encode(path, Charsets.UTF_8.name())
        private fun normalize(path: String): String = BackendPath.normalize(path)
        private fun notFound() = YandexApiException(YandexFailureKind.NOT_FOUND, statusCode = 404)
        private fun conflict() = YandexApiException(YandexFailureKind.CONFLICT, statusCode = 409)
    }
}
