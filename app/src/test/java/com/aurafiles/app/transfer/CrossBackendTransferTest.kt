package com.aurafiles.app.transfer

import com.aurafiles.app.backend.StorageBackendKind
import com.aurafiles.app.backend.StorageBackendRegistry
import com.aurafiles.app.test.FakeStorageBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossBackendTransferTest {
    @Test fun recursivelyCopiesBetweenDifferentBackends() = runBlocking {
        val left = FakeStorageBackend("left", StorageBackendKind.LOCAL).apply {
            putDirectory("/Album")
            putFile("/Album/a.txt", "alpha")
            putFile("/Album/вложенный/🙂.txt", "unicode")
        }
        val right = FakeStorageBackend("right", StorageBackendKind.SFTP)
        val registry = StorageBackendRegistry().apply { register(left); register(right) }
        val source = requireNotNull(left.stat("/Album"))
        val request = TransferRequest(
            type = TransferType.COPY,
            sources = listOf(TransferSource.Backend(left.descriptor.id, source.path, source.name, source.size, source.modifiedAt, true)),
            destination = TransferDestination.Backend(right.descriptor.id, "/"),
            conflictPolicy = TransferConflictPolicy.REPLACE,
        )
        val result = BackendTransferCore(registry).execute(
            request, TransferController(),
            resolveConflict = { TransferConflictDecision(TransferConflictPolicy.REPLACE) },
            onProgress = {},
        )
        assertTrue(right.exists("/Album/a.txt"))
        assertEquals("unicode", String(right.readBytes("/Album/вложенный/🙂.txt")))
        assertTrue(result.completedItems >= 4)
        registry.close()
    }

    @Test fun failedReplacementKeepsOldTargetAndCleansTemporaryFiles() = runBlocking {
        val sourceBackend = FakeStorageBackend("source").apply {
            putFile("/data.bin", ByteArray(64) { it.toByte() })
            failReadPath = "/data.bin"
            failAfterBytes = 16
        }
        val destination = FakeStorageBackend("destination").apply { putFile("/data.bin", "OLD") }
        val registry = StorageBackendRegistry().apply { register(sourceBackend); register(destination) }
        val source = requireNotNull(sourceBackend.stat("/data.bin"))
        val request = TransferRequest(
            type = TransferType.COPY,
            sources = listOf(TransferSource.Backend(sourceBackend.descriptor.id, source.path, source.name, source.size, 1L, false)),
            destination = TransferDestination.Backend(destination.descriptor.id, "/"),
            conflictPolicy = TransferConflictPolicy.REPLACE,
        )
        val failure = runCatching {
            BackendTransferCore(registry).execute(request, TransferController(), { TransferConflictDecision(TransferConflictPolicy.REPLACE) }, {})
        }.exceptionOrNull()
        assertTrue(failure != null)
        assertArrayEquals("OLD".toByteArray(), destination.readBytes("/data.bin"))
        assertFalse(destination.hiddenNames().any { it.startsWith(".aura-part-") })
        registry.close()
    }

    @Test fun committedReplacementRetriesTransientBackupCleanupFailure() = runBlocking {
        val sourceBackend = FakeStorageBackend("source").apply { putFile("/data.bin", "NEW") }
        val destination = FakeStorageBackend("destination").apply {
            putFile("/data.bin", "OLD")
            backupDeleteFailuresRemaining = 1
        }
        val registry = StorageBackendRegistry().apply { register(sourceBackend); register(destination) }
        val source = requireNotNull(sourceBackend.stat("/data.bin"))
        val result = BackendTransferCore(registry).execute(
            TransferRequest(
                type = TransferType.COPY,
                sources = listOf(TransferSource.Backend(sourceBackend.descriptor.id, source.path, source.name, source.size, 1L, false)),
                destination = TransferDestination.Backend(destination.descriptor.id, "/"),
                conflictPolicy = TransferConflictPolicy.REPLACE,
            ),
            TransferController(),
            { TransferConflictDecision(TransferConflictPolicy.REPLACE) },
            {},
        )

        assertEquals("NEW", String(destination.readBytes("/data.bin")))
        assertEquals(2, destination.backupDeleteAttempts)
        assertTrue(result.warnings.isEmpty())
        assertFalse(destination.hiddenNames().any { it.startsWith(".aura-backup-") })
        registry.close()
    }

    @Test fun committedReplacementReportsPersistentBackupCleanupFailureWithoutLosingNewFile() = runBlocking {
        val sourceBackend = FakeStorageBackend("source").apply { putFile("/data.bin", "NEW") }
        val destination = FakeStorageBackend("destination").apply {
            putFile("/data.bin", "OLD")
            backupDeleteFailuresRemaining = Int.MAX_VALUE
        }
        val registry = StorageBackendRegistry().apply { register(sourceBackend); register(destination) }
        val source = requireNotNull(sourceBackend.stat("/data.bin"))
        val result = BackendTransferCore(registry).execute(
            TransferRequest(
                type = TransferType.COPY,
                sources = listOf(TransferSource.Backend(sourceBackend.descriptor.id, source.path, source.name, source.size, 1L, false)),
                destination = TransferDestination.Backend(destination.descriptor.id, "/"),
                conflictPolicy = TransferConflictPolicy.REPLACE,
            ),
            TransferController(),
            { TransferConflictDecision(TransferConflictPolicy.REPLACE) },
            {},
        )

        assertEquals("NEW", String(destination.readBytes("/data.bin")))
        assertEquals(3, destination.backupDeleteAttempts)
        assertTrue(result.warnings.single().contains("служебную копию"))
        assertTrue(destination.hiddenNames().any { it.startsWith(".aura-backup-") })
        registry.close()
    }
    @Test fun moveToSameDirectoryIsRejectedWithoutDeletingSource() = runBlocking {
        val backend = FakeStorageBackend("same").apply { putFile("/safe.txt", "SAFE") }
        val registry = StorageBackendRegistry().apply { register(backend) }
        val source = requireNotNull(backend.stat("/safe.txt"))
        val request = TransferRequest(
            type = TransferType.MOVE,
            sources = listOf(TransferSource.Backend(backend.descriptor.id, source.path, source.name, source.size, source.modifiedAt, false)),
            destination = TransferDestination.Backend(backend.descriptor.id, "/"),
            conflictPolicy = TransferConflictPolicy.REPLACE,
        )
        val failure = runCatching {
            BackendTransferCore(registry).execute(request, TransferController(), { TransferConflictDecision(TransferConflictPolicy.REPLACE) }, {})
        }.exceptionOrNull()
        assertTrue(failure != null)
        assertEquals("SAFE", String(backend.readBytes("/safe.txt")))
        registry.close()
    }

    @Test fun directoryCannotBeCopiedIntoOwnDescendant() = runBlocking {
        val backend = FakeStorageBackend("same").apply { putFile("/Dir/sub/a.txt", "A") }
        val registry = StorageBackendRegistry().apply { register(backend) }
        val source = requireNotNull(backend.stat("/Dir"))
        val request = TransferRequest(
            type = TransferType.COPY,
            sources = listOf(TransferSource.Backend(backend.descriptor.id, source.path, source.name, source.size, source.modifiedAt, true)),
            destination = TransferDestination.Backend(backend.descriptor.id, "/Dir/sub"),
            conflictPolicy = TransferConflictPolicy.KEEP_BOTH,
        )
        val failure = runCatching {
            BackendTransferCore(registry).execute(request, TransferController(), { TransferConflictDecision(TransferConflictPolicy.KEEP_BOTH) }, {})
        }.exceptionOrNull()
        assertTrue(failure != null)
        assertFalse(backend.exists("/Dir/sub/Dir"))
        registry.close()
    }

    @Test fun failedDirectoryReplacementKeepsOldTreeAndCleansTemporaryTree() = runBlocking {
        val sourceBackend = FakeStorageBackend("source").apply {
            putFile("/Dir/new.bin", ByteArray(64) { 7 })
            failReadPath = "/Dir/new.bin"
            failAfterBytes = 16
        }
        val destination = FakeStorageBackend("destination").apply { putFile("/Dir/old.txt", "OLD") }
        val registry = StorageBackendRegistry().apply { register(sourceBackend); register(destination) }
        val source = requireNotNull(sourceBackend.stat("/Dir"))
        val request = TransferRequest(
            type = TransferType.COPY,
            sources = listOf(TransferSource.Backend(sourceBackend.descriptor.id, source.path, source.name, 0L, source.modifiedAt, true)),
            destination = TransferDestination.Backend(destination.descriptor.id, "/"),
            conflictPolicy = TransferConflictPolicy.REPLACE,
        )
        val failure = runCatching {
            BackendTransferCore(registry).execute(request, TransferController(), { TransferConflictDecision(TransferConflictPolicy.REPLACE) }, {})
        }.exceptionOrNull()
        assertTrue(failure != null)
        assertEquals("OLD", String(destination.readBytes("/Dir/old.txt")))
        assertFalse(destination.exists("/Dir/new.bin"))
        assertFalse(destination.hiddenNames().any { it.startsWith(".aura-dir-") || it.startsWith(".aura-backup-") || it.startsWith(".aura-part-") })
        registry.close()
    }

    @Test fun rootDirectoryCannotBeCopiedIntoOwnDescendant() = runBlocking {
        val backend = FakeStorageBackend("same").apply { putFile("/sub/a.txt", "A") }
        val registry = StorageBackendRegistry().apply { register(backend) }
        val source = requireNotNull(backend.stat("/"))
        val request = TransferRequest(
            type = TransferType.COPY,
            sources = listOf(TransferSource.Backend(backend.descriptor.id, source.path, source.name, source.size, source.modifiedAt, true)),
            destination = TransferDestination.Backend(backend.descriptor.id, "/sub"),
            conflictPolicy = TransferConflictPolicy.KEEP_BOTH,
        )
        val failure = runCatching {
            BackendTransferCore(registry).execute(request, TransferController(), { TransferConflictDecision(TransferConflictPolicy.KEEP_BOTH) }, {})
        }.exceptionOrNull()
        assertTrue(failure != null)
        assertFalse(backend.hiddenNames().any { it.startsWith(".aura-dir-") })
        registry.close()
    }

}
