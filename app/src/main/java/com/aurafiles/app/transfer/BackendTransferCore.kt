package com.aurafiles.app.transfer

import com.aurafiles.app.backend.BackendPath
import com.aurafiles.app.backend.StorageBackend
import com.aurafiles.app.backend.StorageBackendRegistry
import com.aurafiles.app.backend.StorageItem
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Protocol-neutral transfer implementation used by TransferEngine for mixed panels.
 * StorageBackend owns protocol I/O; this class owns copy/move semantics, conflicts,
 * temp files, progress and cancellation so every protocol behaves the same way.
 */
class BackendTransferCore(
    private val registry: StorageBackendRegistry,
) {
    suspend fun execute(
        request: TransferRequest,
        controller: TransferController,
        resolveConflict: suspend (TransferConflict) -> TransferConflictDecision,
        onProgress: (TransferProgress) -> Unit,
    ): TransferResult {
        val sources = request.sources.map { source ->
            source as? TransferSource.Backend
                ?: throw IOException("Смешанная операция требует Backend-источники")
        }
        val destination = request.destination as? TransferDestination.Backend
        if (request.type != TransferType.DELETE && destination == null) {
            throw IOException("Не выбран Backend назначения")
        }
        val stats = Stats(request.id)
        onProgress(stats.progress(TransferState.PREPARING))

        sources.forEach { source ->
            val backend = registry.require(source.backendId)
            val item = backend.stat(source.path)
                ?: throw IOException("Источник ${source.name} больше не существует")
            val measure = measure(backend, item, controller, stats, onProgress)
            stats.measures[source.key] = measure
        }

        val policyState = PolicyState()
        try {
            sources.forEach { source ->
                controller.checkpoint()
                val backend = registry.require(source.backendId)
                val item = backend.stat(source.path)
                    ?: throw IOException("Источник ${source.name} больше не существует")
                when (request.type) {
                    TransferType.DELETE -> {
                        backend.delete(item.path, recursive = item.isDirectory)
                        stats.complete(stats.measures[source.key] ?: Measure(1, source.size.coerceAtLeast(0L)))
                        stats.currentName = item.name
                        onProgress(stats.progress(TransferState.RUNNING))
                    }
                    TransferType.COPY, TransferType.MOVE -> {
                        val target = requireNotNull(destination)
                        val destinationBackend = registry.require(target.backendId)
                        val sameBackend = backend.descriptor.id == destinationBackend.descriptor.id
                        val destinationDirectory = destinationBackend.normalize(target.directoryPath)
                        if (sameBackend) {
                            val sourcePath = backend.normalize(item.path)
                            if (request.type == TransferType.MOVE && backend.parent(sourcePath) == destinationDirectory) {
                                throw IOException("Нельзя перемещать ${item.name} в ту же папку")
                            }
                            if (item.isDirectory && isSameOrDescendant(destinationDirectory, sourcePath)) {
                                throw IOException("Нельзя копировать или перемещать папку внутрь самой себя")
                            }
                        }
                        val targetCollision = destinationBackend.stat(destinationBackend.child(destinationDirectory, item.name))
                        if (request.type == TransferType.MOVE && sameBackend && targetCollision == null) {
                            val fastMoved = runCatching {
                                backend.move(item.path, destinationDirectory)
                            }.getOrNull()
                            if (fastMoved != null) {
                                stats.complete(stats.measures[source.key] ?: Measure(1, item.size.coerceAtLeast(0L)))
                                stats.currentName = item.name
                                onProgress(stats.progress(TransferState.RUNNING))
                                return@forEach
                            }
                        }

                        val result = copyNode(
                            sourceBackend = backend,
                            source = item,
                            destinationBackend = destinationBackend,
                            destinationDirectory = destinationDirectory,
                            request = request,
                            controller = controller,
                            stats = stats,
                            policyState = policyState,
                            resolveConflict = resolveConflict,
                            onProgress = onProgress,
                        )
                        if (request.type == TransferType.MOVE && result.copied) {
                            controller.checkpoint()
                            backend.delete(item.path, recursive = item.isDirectory)
                        }
                    }
                    else -> throw IOException("Тип ${request.type} не поддержан для универсального Backend")
                }
            }
            onProgress(stats.progress(TransferState.COMPLETED))
            return TransferResult(
                request.id,
                stats.completedItems,
                stats.skippedItems,
                stats.processedBytes,
                stats.warnings.toList(),
            )
        } catch (cancelled: CancellationException) {
            onProgress(stats.progress(TransferState.CANCELLING))
            throw cancelled
        } catch (error: Throwable) {
            onProgress(stats.progress(TransferState.FAILED, error.message))
            throw error
        }
    }

    private suspend fun measure(
        backend: StorageBackend,
        item: StorageItem,
        controller: TransferController,
        stats: Stats,
        onProgress: (TransferProgress) -> Unit,
    ): Measure {
        controller.checkpoint()
        stats.currentName = item.name
        var count = 1
        var bytes = if (item.isDirectory) 0L else item.size.coerceAtLeast(0L)
        stats.totalItems += 1
        stats.totalBytes += bytes
        onProgress(stats.progress(TransferState.PREPARING))
        if (item.isDirectory) {
            backend.list(item.path).forEach { child ->
                val nested = measure(backend, child, controller, stats, onProgress)
                count += nested.items
                bytes += nested.bytes
            }
        }
        return Measure(count, bytes)
    }

    private suspend fun copyNode(
        sourceBackend: StorageBackend,
        source: StorageItem,
        destinationBackend: StorageBackend,
        destinationDirectory: String,
        request: TransferRequest,
        controller: TransferController,
        stats: Stats,
        policyState: PolicyState,
        resolveConflict: suspend (TransferConflict) -> TransferConflictDecision,
        onProgress: (TransferProgress) -> Unit,
    ): CopyOutcome {
        controller.checkpoint()
        stats.currentName = source.name
        val decision = targetDecision(
            source,
            destinationBackend,
            destinationDirectory,
            request,
            policyState,
            resolveConflict,
        )
        if (decision.skip) {
            val skipped = countNode(sourceBackend, source, controller)
            stats.skippedItems += skipped.items
            stats.processedBytes += skipped.bytes
            onProgress(stats.progress(TransferState.RUNNING))
            return CopyOutcome(false)
        }

        if (source.isDirectory) {
            return copyDirectoryAtomic(
                sourceBackend,
                source,
                destinationBackend,
                destinationDirectory,
                decision,
                request,
                controller,
                stats,
                policyState,
                resolveConflict,
                onProgress,
            )
        }
        return copyFileAtomic(
            sourceBackend,
            source,
            destinationBackend,
            destinationDirectory,
            decision,
            controller,
            stats,
            onProgress,
        )
    }

    private suspend fun copyDirectoryAtomic(
        sourceBackend: StorageBackend,
        source: StorageItem,
        destinationBackend: StorageBackend,
        destinationDirectory: String,
        decision: TargetDecision,
        request: TransferRequest,
        controller: TransferController,
        stats: Stats,
        policyState: PolicyState,
        resolveConflict: suspend (TransferConflict) -> TransferConflictDecision,
        onProgress: (TransferProgress) -> Unit,
    ): CopyOutcome {
        val finalName = decision.name
        val finalPath = destinationBackend.child(destinationDirectory, finalName)
        val workingName = ".aura-dir-${UUID.randomUUID()}"
        val workingPath = destinationBackend.child(destinationDirectory, workingName)
        destinationBackend.mkdir(workingPath)
        var committed = false
        try {
            stats.completedItems += 1
            onProgress(stats.progress(TransferState.RUNNING))
            sourceBackend.list(source.path).forEach { child ->
                copyNode(
                    sourceBackend,
                    child,
                    destinationBackend,
                    workingPath,
                    request,
                    controller,
                    stats,
                    policyState,
                    resolveConflict,
                    onProgress,
                )
            }
            if (decision.replace) {
                swapIntoPlace(destinationBackend, workingPath, finalPath, source.name)?.let(stats.warnings::add)
            } else {
                destinationBackend.rename(workingPath, finalName)
            }
            committed = true
            return CopyOutcome(true)
        } catch (error: Throwable) {
            if (!committed) runCatching { destinationBackend.delete(workingPath, recursive = true) }
            throw error
        }
    }

    private suspend fun copyFileAtomic(
        sourceBackend: StorageBackend,
        source: StorageItem,
        destinationBackend: StorageBackend,
        destinationDirectory: String,
        decision: TargetDecision,
        controller: TransferController,
        stats: Stats,
        onProgress: (TransferProgress) -> Unit,
    ): CopyOutcome {
        val finalPath = destinationBackend.child(destinationDirectory, decision.name)
        val temporaryPath = destinationBackend.child(destinationDirectory, ".aura-part-${UUID.randomUUID()}")
        stats.currentItemBytes = 0L
        stats.currentItemTotalBytes = source.size.coerceAtLeast(0L)
        var written = 0L
        var writeHandle: com.aurafiles.app.backend.StorageWriteHandle? = null
        try {
            sourceBackend.openRead(source.path).use { inputHandle ->
                val outputHandle = destinationBackend.openWrite(temporaryPath, replace = false)
                writeHandle = outputHandle
                outputHandle.use { handle ->
                    val input = inputHandle.input.buffered(BUFFER_SIZE)
                    val output = handle.output.buffered(BUFFER_SIZE)
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        controller.checkpoint()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        stats.processedBytes += count
                        stats.currentItemBytes = written
                        stats.currentName = source.name
                        stats.recordSpeed()
                        onProgress(stats.progress(TransferState.RUNNING))
                    }
                    output.flush()
                    handle.commit()
                }
                writeHandle = null
            }
            // Many SAF/network providers report 0 when size is unknown, so only
            // enforce exact size when the backend advertised a positive size.
            if (source.size > 0L && written != source.size) {
                throw IOException("Записано $written из ${source.size} байт для ${source.name}")
            }
            if (decision.replace) {
                swapIntoPlace(destinationBackend, temporaryPath, finalPath, source.name)?.let(stats.warnings::add)
            }
            else destinationBackend.rename(temporaryPath, decision.name)
            stats.completedItems += 1
            stats.currentItemBytes = 0L
            stats.currentItemTotalBytes = 0L
            onProgress(stats.progress(TransferState.RUNNING))
            return CopyOutcome(true)
        } catch (error: Throwable) {
            runCatching { writeHandle?.abort() }
            runCatching { destinationBackend.delete(temporaryPath, recursive = false) }
            throw error
        } finally {
            stats.currentItemBytes = 0L
            stats.currentItemTotalBytes = 0L
        }
    }

    /**
     * Replacement keeps the old object until the new temp object is complete.
     * Old -> backup, temp -> final, then backup delete. If second rename fails,
     * the original name is restored best-effort.
     */
    private suspend fun swapIntoPlace(
        backend: StorageBackend,
        temporaryPath: String,
        finalPath: String,
        displayName: String,
    ): String? {
        val existing = backend.stat(finalPath)
        if (existing == null) {
            backend.rename(temporaryPath, BackendPath.name(finalPath))
            return null
        }
        val backupName = ".aura-backup-${UUID.randomUUID()}"
        val backup = backend.rename(finalPath, backupName)
        try {
            backend.rename(temporaryPath, BackendPath.name(finalPath))
        } catch (error: Throwable) {
            runCatching { backend.rename(backup.path, BackendPath.name(finalPath)) }
            throw IOException("Не удалось атомарно заменить $displayName", error)
        }
        return cleanupCommittedBackup(backend, backup, displayName)
    }

    /**
     * The replacement is already committed at this point, so cleanup failure must
     * never roll it back or report the copy itself as failed. Retry transient network
     * errors, then return a visible non-fatal warning containing the orphan path.
     */
    private suspend fun cleanupCommittedBackup(
        backend: StorageBackend,
        backup: StorageItem,
        displayName: String,
    ): String? {
        var lastError: Throwable? = null
        repeat(BACKUP_DELETE_ATTEMPTS) { attempt ->
            try {
                backend.delete(backup.path, recursive = backup.isDirectory)
                return null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                if (attempt + 1 < BACKUP_DELETE_ATTEMPTS) {
                    try {
                        backend.ping()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // The next delete attempt remains the authoritative check.
                    }
                }
            }
        }
        val detail = lastError?.message?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
        return "Файл $displayName заменён, но служебную копию ${backup.path} удалить не удалось$detail"
    }

    private suspend fun targetDecision(
        source: StorageItem,
        destinationBackend: StorageBackend,
        destinationDirectory: String,
        request: TransferRequest,
        policyState: PolicyState,
        resolveConflict: suspend (TransferConflict) -> TransferConflictDecision,
    ): TargetDecision {
        val originalPath = destinationBackend.child(destinationDirectory, source.name)
        val existing = destinationBackend.stat(originalPath) ?: return TargetDecision(source.name, replace = false)
        var policy = policyState.applyToAll ?: request.conflictPolicy
        policy = ConflictResolver.resolve(
            policy,
            source.size,
            source.modifiedAt,
            existing.size,
            existing.modifiedAt,
        )
        if (policy == TransferConflictPolicy.ASK) {
            val answer = resolveConflict(
                TransferConflict(
                    operationId = request.id,
                    sourceName = source.name,
                    sourceSize = source.size,
                    sourceModifiedAt = source.modifiedAt,
                    existingName = existing.name,
                    existingSize = existing.size,
                    existingModifiedAt = existing.modifiedAt,
                )
            )
            if (answer.applyToAll) policyState.applyToAll = answer.policy
            policy = ConflictResolver.resolve(
                answer.policy,
                source.size,
                source.modifiedAt,
                existing.size,
                existing.modifiedAt,
            )
        }
        return when (policy) {
            TransferConflictPolicy.REPLACE -> TargetDecision(source.name, replace = true)
            TransferConflictPolicy.SKIP,
            TransferConflictPolicy.REPLACE_IF_NEWER,
            TransferConflictPolicy.REPLACE_IF_SIZE_DIFFERS -> TargetDecision(source.name, replace = false, skip = true)
            TransferConflictPolicy.KEEP_BOTH -> TargetDecision(uniqueName(destinationBackend, destinationDirectory, source.name), false)
            TransferConflictPolicy.CANCEL -> throw CancellationException("Отменено пользователем")
            TransferConflictPolicy.ASK -> error("ASK должен быть разрешён до копирования")
        }
    }

    private suspend fun uniqueName(backend: StorageBackend, directory: String, requested: String): String {
        if (backend.stat(backend.child(directory, requested)) == null) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        var index = 2
        while (backend.stat(backend.child(directory, "$base ($index)$extension")) != null) index += 1
        return "$base ($index)$extension"
    }

    private fun isSameOrDescendant(candidate: String, ancestor: String): Boolean {
        val normalizedCandidate = BackendPath.normalize(candidate)
        val normalizedAncestor = BackendPath.normalize(ancestor)
        if (normalizedAncestor == "/") return true
        return normalizedCandidate == normalizedAncestor || normalizedCandidate.startsWith("$normalizedAncestor/")
    }

    private suspend fun countNode(backend: StorageBackend, item: StorageItem, controller: TransferController): Measure {
        controller.checkpoint()
        if (!item.isDirectory) return Measure(1, item.size.coerceAtLeast(0L))
        var items = 1
        var bytes = 0L
        backend.list(item.path).forEach { child ->
            val nested = countNode(backend, child, controller)
            items += nested.items
            bytes += nested.bytes
        }
        return Measure(items, bytes)
    }

    private data class PolicyState(var applyToAll: TransferConflictPolicy? = null)
    private data class TargetDecision(val name: String, val replace: Boolean, val skip: Boolean = false)
    private data class CopyOutcome(val copied: Boolean)
    private data class Measure(val items: Int, val bytes: Long)

    private class Stats(val operationId: String) {
        var currentName = ""
        var totalItems = 0
        var totalBytes = 0L
        var completedItems = 0
        var skippedItems = 0
        var currentItemBytes = 0L
        var currentItemTotalBytes = 0L
        var processedBytes = 0L
        var speed = 0L
        var speedSampleSpan = 0L
        val measures = mutableMapOf<String, Measure>()
        val warnings = mutableListOf<String>()
        private val samples = ArrayDeque<Pair<Long, Long>>()

        fun complete(measure: Measure) {
            completedItems += measure.items
            processedBytes += measure.bytes
        }

        fun recordSpeed() {
            val now = System.currentTimeMillis()
            samples.addLast(now to processedBytes)
            while (samples.size > 2 && now - samples.first().first > SPEED_WINDOW_MS) samples.removeFirst()
            val first = samples.firstOrNull() ?: return
            speedSampleSpan = now - first.first
            speed = if (speedSampleSpan > 0L) ((processedBytes - first.second) * 1_000L / speedSampleSpan).coerceAtLeast(0L) else 0L
        }

        fun progress(state: TransferState, error: String? = null): TransferProgress {
            recordSpeed()
            val remaining = (totalBytes - processedBytes).coerceAtLeast(0L)
            val eta = speed.takeIf { it > 0L && speedSampleSpan >= 2_000L }?.let { remaining * 1_000L / it }
            return TransferProgress(
                operationId = operationId,
                currentName = currentName,
                currentItem = (completedItems + skippedItems).coerceAtMost(totalItems),
                totalItems = totalItems,
                currentItemBytes = currentItemBytes,
                currentItemTotalBytes = currentItemTotalBytes,
                processedBytes = processedBytes,
                totalBytes = totalBytes,
                bytesPerSecond = speed,
                etaMillis = eta,
                state = state,
                error = error,
            )
        }
    }

    companion object {
        private const val BUFFER_SIZE = 1024 * 1024
        private const val SPEED_WINDOW_MS = 4_000L
        private const val BACKUP_DELETE_ATTEMPTS = 3
    }
}

private val TransferSource.Backend.key: String get() = "$backendId|$path"
