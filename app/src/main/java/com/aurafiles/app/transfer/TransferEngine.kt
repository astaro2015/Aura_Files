package com.aurafiles.app.transfer

import android.content.ContentResolver
import android.content.Context
import android.provider.DocumentsContract
import android.os.SystemClock
import com.aurafiles.app.data.FastDocumentListing
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.backend.StorageBackendRegistry
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class TransferEngine(
    context: Context,
    private val smbGateway: SmbTransferGateway? = null,
    private val backendRegistry: StorageBackendRegistry? = null,
) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val backendCore = backendRegistry?.let(::BackendTransferCore)
    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()
    private val _conflict = MutableStateFlow<TransferConflict?>(null)
    val conflict: StateFlow<TransferConflict?> = _conflict.asStateFlow()
    private var conflictReply: CompletableDeferred<TransferConflictDecision>? = null
    private var applyToAllPolicy: TransferConflictPolicy? = null
    private var lastProgressEmitAt = 0L

    fun resolveConflict(decision: TransferConflictDecision) {
        if (decision.applyToAll) applyToAllPolicy = decision.policy
        conflictReply?.complete(decision)
    }

    suspend fun execute(request: TransferRequest, controller: TransferController): TransferResult =
        withContext(Dispatchers.IO) {
            require(request.sources.isNotEmpty()) { "Не выбраны источники операции" }
            val usesBackend = request.sources.any { it is TransferSource.Backend } || request.destination is TransferDestination.Backend
            if (usesBackend) {
                val core = backendCore ?: throw IOException("Универсальные backend не подключены")
                return@withContext core.execute(
                    request = request,
                    controller = controller,
                    resolveConflict = { conflict -> awaitBackendConflict(conflict, controller) },
                    onProgress = { progress -> publishProgress(progress) },
                )
            }
            applyToAllPolicy = null
            val stats = RuntimeStats(request.id)
            _progress.value = TransferProgress(operationId = request.id, state = TransferState.PREPARING)
            request.sources.forEach { source ->
                controller.checkpoint { paused -> updatePaused(paused) }
                when (source) {
                    is TransferSource.Local -> {
                        val document = source.document()
                        val measured = measure(document, controller, stats)
                        stats.sourceSizes[source.uri.toString()] = measured
                    }
                    is TransferSource.Backend -> throw IOException("Backend-источник должен обрабатываться универсальным ядром")
                    is TransferSource.Smb -> {
                        stats.totalItems += 1
                        stats.totalBytes += source.size.coerceAtLeast(0L)
                        emit(stats, source.name, TransferState.PREPARING)
                    }
                }
            }
            try {
                when (request.type) {
                    TransferType.COPY,
                    TransferType.MOVE,
                    TransferType.TRASH,
                    TransferType.RESTORE -> executeLocalTransfer(request, controller, stats)
                    TransferType.DELETE -> executeDelete(request, controller, stats)
                    TransferType.UPLOAD -> executeSmbUpload(request, controller, stats)
                    TransferType.DOWNLOAD -> executeSmbDownload(request, controller, stats)
                }
                emit(stats, stats.currentName, TransferState.COMPLETED)
                TransferResult(request.id, stats.completedItems, stats.skippedItems, stats.processedBytes)
            } catch (cancelled: CancellationException) {
                emit(stats, stats.currentName, TransferState.CANCELLING)
                throw cancelled
            } catch (error: Throwable) {
                _progress.value = currentProgress(stats, TransferState.FAILED, error.message)
                throw error
            } finally {
                _conflict.value = null
                conflictReply = null
            }
        }

    private suspend fun executeSmbUpload(
        request: TransferRequest,
        controller: TransferController,
        stats: RuntimeStats,
    ) {
        val gateway = smbGateway ?: throw IOException("Сетевой шлюз передачи не подключён")
        val destination = request.destination as? TransferDestination.Smb
            ?: throw IOException("Не выбрана SMB-папка назначения")
        request.sources.forEach { raw ->
            val source = raw as? TransferSource.Local ?: throw IOException("Для загрузки нужен локальный файл")
            val measured = stats.sourceSizes[source.uri.toString()]
                ?: SourceMeasure(1, source.size.coerceAtLeast(0L))
            stats.currentItemBytes = 0L
            stats.currentItemTotalBytes = source.size.coerceAtLeast(0L)

            var activeFile = false
            var observedFiles = 0
            gateway.upload(source, destination, controller) { name, delta, total, started ->
                if (started) {
                    if (activeFile) {
                        stats.completedItems += 1
                        observedFiles += 1
                    }
                    activeFile = true
                    stats.currentItemBytes = 0L
                    stats.currentItemTotalBytes = total.coerceAtLeast(0L)
                }
                stats.currentName = name
                stats.currentItemBytes += delta
                stats.currentItemTotalBytes = total.coerceAtLeast(0L)
                stats.processedBytes += delta
                emit(stats, name, TransferState.RUNNING)
            }
            if (activeFile) {
                stats.completedItems += 1
                observedFiles += 1
            }
            // measure() counts files and directories; progress callbacks describe files.
            stats.completedItems += (measured.items - observedFiles).coerceAtLeast(0)
            stats.currentItemBytes = 0L
            stats.currentItemTotalBytes = 0L
            emit(stats, source.name, TransferState.RUNNING)
        }
    }

    private suspend fun executeSmbDownload(
        request: TransferRequest,
        controller: TransferController,
        stats: RuntimeStats,
    ) {
        val gateway = smbGateway ?: throw IOException("Сетевой шлюз передачи не подключён")
        val destination = request.destination as? TransferDestination.Local
            ?: throw IOException("Не выбрана локальная папка назначения")
        request.sources.forEach { raw ->
            val source = raw as? TransferSource.Smb ?: throw IOException("Для скачивания нужен SMB-объект")
            stats.currentItemBytes = 0L
            stats.currentItemTotalBytes = source.size.coerceAtLeast(0L)

            var activeFile = false
            gateway.download(source, destination, controller) { name, delta, total, started ->
                if (started) {
                    if (activeFile) stats.completedItems += 1
                    activeFile = true
                    stats.currentItemBytes = 0L
                    stats.currentItemTotalBytes = total.coerceAtLeast(0L)
                    if (source.isDirectory) {
                        stats.totalItems += 1
                        stats.totalBytes += total.coerceAtLeast(0L)
                    }
                }
                stats.currentName = name
                stats.currentItemBytes += delta
                stats.currentItemTotalBytes = total.coerceAtLeast(0L)
                stats.processedBytes += delta
                emit(stats, name, TransferState.RUNNING)
            }
            if (activeFile) stats.completedItems += 1
            if (source.isDirectory || !activeFile) stats.completedItems += 1
            stats.currentItemBytes = 0L
            stats.currentItemTotalBytes = 0L
            emit(stats, source.name, TransferState.RUNNING)
        }
    }

    private suspend fun executeLocalTransfer(
        request: TransferRequest,
        controller: TransferController,
        stats: RuntimeStats,
    ) {
        val destinationUri = (request.destination as? TransferDestination.Local)?.directoryUri
            ?: throw IOException("Не выбрана папка назначения")
        val destination = documentFromUri(destinationUri)
            ?: throw IOException("Папка назначения недоступна")
        require(destination.isDirectory && destination.canWrite()) { "Папка назначения недоступна для записи" }

        request.sources.forEach { rawSource ->
            controller.checkpoint { paused -> updatePaused(paused) }
            val source = rawSource as? TransferSource.Local
                ?: throw IOException("Этот тип источника пока не поддерживается")
            val document = source.document()
            val collision = destination.findFile(source.name)
            if (request.type != TransferType.COPY && collision == null && tryFastMove(source, destination)) {
                val measured = stats.sourceSizes[source.uri.toString()] ?: SourceMeasure(1, source.size.coerceAtLeast(0L))
                stats.completedItems += measured.items
                stats.processedBytes += measured.bytes
                stats.currentName = source.name
                emit(stats, source.name, TransferState.RUNNING)
            } else {
                copyNode(document, destination, request, controller, stats)
                if (request.type != TransferType.COPY) {
                    controller.checkpoint { paused -> updatePaused(paused) }
                    require(document.delete()) { "Копия создана, но исходник ${source.name} удалить не удалось" }
                }
            }
        }
    }

    private suspend fun executeDelete(
        request: TransferRequest,
        controller: TransferController,
        stats: RuntimeStats,
    ) {
        request.sources.forEach { rawSource ->
            val source = rawSource as? TransferSource.Local
                ?: throw IOException("Удаление сетевого источника выполняется сетевым модулем")
            controller.checkpoint { paused -> updatePaused(paused) }
            require(source.document().delete()) { "Не удалось удалить ${source.name}" }
            val measured = stats.sourceSizes[source.uri.toString()] ?: SourceMeasure(1, source.size.coerceAtLeast(0L))
            stats.completedItems += measured.items
            stats.processedBytes += measured.bytes
            emit(stats, source.name, TransferState.RUNNING)
        }
    }

    private suspend fun measure(
        document: DocumentFile,
        controller: TransferController,
        stats: RuntimeStats,
    ): SourceMeasure {
        controller.checkpoint { paused -> updatePaused(paused) }
        stats.currentName = document.name.orEmpty()
        var items = 1
        var bytes = if (document.isFile) document.length().coerceAtLeast(0L) else 0L
        stats.totalItems += 1
        stats.totalBytes += bytes
        emit(stats, stats.currentName, TransferState.PREPARING)
        if (document.isDirectory) {
            document.listFiles().forEach { child ->
                val nested = measure(child, controller, stats)
                items += nested.items
                bytes += nested.bytes
            }
        }
        return SourceMeasure(items, bytes)
    }

    private suspend fun copyNode(
        source: DocumentFile,
        destination: DocumentFile,
        request: TransferRequest,
        controller: TransferController,
        stats: RuntimeStats,
    ): DocumentFile? {
        controller.checkpoint { paused -> updatePaused(paused) }
        val sourceName = source.name ?: "Без имени"
        stats.currentName = sourceName
        val targetName = resolveTargetName(source, destination, request, controller, stats) ?: run {
            skipNode(source, stats)
            return null
        }
        return if (source.isDirectory) {
            val target = destination.findFile(targetName)?.takeIf(DocumentFile::isDirectory)
                ?: destination.createDirectory(targetName)
                ?: throw IOException("Не удалось создать папку $targetName")
            stats.completedItems += 1
            emit(stats, sourceName, TransferState.RUNNING)
            source.listFiles().forEach { child -> copyNode(child, target, request, controller, stats) }
            target
        } else {
            copyFile(source, destination, targetName, request.preserveModifiedTime, controller, stats)
        }
    }

    private suspend fun copyFile(
        source: DocumentFile,
        destination: DocumentFile,
        finalName: String,
        preserveModifiedTime: Boolean,
        controller: TransferController,
        stats: RuntimeStats,
    ): DocumentFile {
        val temporaryName = ".aura-part-${UUID.randomUUID()}"
        val temporary = destination.createFile(source.type ?: "application/octet-stream", temporaryName)
            ?: throw IOException("Не удалось создать временный файл для $finalName")
        var written = 0L
        stats.currentItemBytes = 0L
        stats.currentItemTotalBytes = source.length().coerceAtLeast(0L)
        try {
            val input = if (source.uri.scheme == ContentResolver.SCHEME_FILE) {
                File(requireNotNull(source.uri.path) { "Не удалось определить путь ${source.name}" }).inputStream()
            } else {
                resolver.openInputStream(source.uri)
                    ?: throw IOException("Не удалось прочитать ${source.name}")
            }
            val output = if (temporary.uri.scheme == ContentResolver.SCHEME_FILE) {
                File(requireNotNull(temporary.uri.path) { "Не удалось определить путь $finalName" }).outputStream()
            } else {
                resolver.openOutputStream(temporary.uri, "w")
                    ?: throw IOException("Не удалось записать $finalName")
            }
            input.buffered(BUFFER_SIZE).use { sourceStream ->
                output.buffered(BUFFER_SIZE).use { targetStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        controller.checkpoint { paused -> updatePaused(paused) }
                        val read = sourceStream.read(buffer)
                        if (read < 0) break
                        targetStream.write(buffer, 0, read)
                        written += read
                        stats.processedBytes += read
                        stats.currentItemBytes = written
                        emit(stats, source.name.orEmpty(), TransferState.RUNNING)
                    }
                    targetStream.flush()
                }
            }
            val expected = source.length()
            if (expected >= 0L && written != expected) {
                throw IOException("Размер временного файла не совпал: $written из $expected байт")
            }
            require(temporary.renameTo(finalName)) { "Не удалось завершить запись $finalName" }
            if (preserveModifiedTime) {
                // SAF does not expose a portable setter. Providers that preserve metadata do so on move/rename.
            }
            stats.completedItems += 1
            emit(stats, finalName, TransferState.RUNNING)
            return temporary
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            stats.currentItemBytes = 0L
            stats.currentItemTotalBytes = 0L
        }
    }

    private suspend fun resolveTargetName(
        source: DocumentFile,
        destination: DocumentFile,
        request: TransferRequest,
        controller: TransferController,
        stats: RuntimeStats,
    ): String? {
        val sourceName = source.name ?: "Без имени"
        val existing = destination.findFile(sourceName) ?: return sourceName
        var policy = applyToAllPolicy ?: request.conflictPolicy
        policy = ConflictResolver.resolve(
            policy,
            source.length(),
            source.lastModified(),
            existing.length(),
            existing.lastModified(),
        )
        if (policy == TransferConflictPolicy.ASK) {
            controller.checkpoint { paused -> updatePaused(paused) }
            val reply = CompletableDeferred<TransferConflictDecision>()
            conflictReply = reply
            _conflict.value = TransferConflict(
                operationId = request.id,
                sourceName = sourceName,
                sourceSize = source.length(),
                sourceModifiedAt = source.lastModified(),
                existingName = existing.name.orEmpty(),
                existingSize = existing.length(),
                existingModifiedAt = existing.lastModified(),
            )
            val decision = reply.await()
            _conflict.value = null
            conflictReply = null
            if (decision.applyToAll) applyToAllPolicy = decision.policy
            policy = ConflictResolver.resolve(
                decision.policy,
                source.length(),
                source.lastModified(),
                existing.length(),
                existing.lastModified(),
            )
        }
        return when (policy) {
            TransferConflictPolicy.REPLACE -> {
                require(existing.delete()) { "Не удалось заменить ${existing.name}" }
                sourceName
            }
            TransferConflictPolicy.SKIP -> null
            TransferConflictPolicy.KEEP_BOTH -> uniqueName(destination, sourceName)
            TransferConflictPolicy.CANCEL -> throw CancellationException("Отменено пользователем")
            TransferConflictPolicy.ASK,
            TransferConflictPolicy.REPLACE_IF_NEWER,
            TransferConflictPolicy.REPLACE_IF_SIZE_DIFFERS -> null
        }
    }

    private fun skipNode(source: DocumentFile, stats: RuntimeStats) {
        fun count(document: DocumentFile) {
            stats.skippedItems += 1
            if (document.isFile) stats.processedBytes += document.length().coerceAtLeast(0L)
            else document.listFiles().forEach(::count)
        }
        count(source)
        emit(stats, source.name.orEmpty(), TransferState.RUNNING)
    }

    private fun tryFastMove(source: TransferSource.Local, destination: DocumentFile): Boolean {
        val parentUri = source.parentUri ?: return false
        if (source.uri.scheme != "content" || destination.uri.scheme != "content") return false
        if (source.uri.authority != destination.uri.authority) return false
        return runCatching {
            DocumentsContract.moveDocument(resolver, source.uri, parentUri, destination.uri) != null
        }.getOrDefault(false)
    }

    private fun uniqueName(parent: DocumentFile, requested: String): String {
        if (parent.findFile(requested) == null) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        var index = 2
        while (parent.findFile("$base ($index)$extension") != null) index += 1
        return "$base ($index)$extension"
    }

    private fun TransferSource.Local.document(): DocumentFile =
        documentFromUri(uri) ?: throw IOException("Источник $name недоступен")

    private fun documentFromUri(uri: android.net.Uri): DocumentFile? = FastDocumentListing.resolve(appContext, uri)

    private suspend fun awaitBackendConflict(
        conflict: TransferConflict,
        controller: TransferController,
    ): TransferConflictDecision {
        controller.checkpoint { paused -> updatePaused(paused) }
        val reply = CompletableDeferred<TransferConflictDecision>()
        conflictReply = reply
        _conflict.value = conflict
        return try {
            reply.await().also { decision ->
                if (decision.applyToAll) applyToAllPolicy = decision.policy
            }
        } finally {
            _conflict.value = null
            conflictReply = null
        }
    }

    private fun updatePaused(paused: Boolean) {
        val current = _progress.value ?: return
        val desired = if (paused) TransferState.PAUSED else if (current.state == TransferState.PAUSED) TransferState.RUNNING else current.state
        if (desired != current.state) _progress.value = current.copy(state = desired)
    }

    private fun emit(stats: RuntimeStats, name: String, state: TransferState) {
        stats.currentName = name
        val now = SystemClock.elapsedRealtime()
        val force = state != TransferState.RUNNING
        if (!force && now - lastProgressEmitAt < PROGRESS_EMIT_INTERVAL_MS) return
        lastProgressEmitAt = now
        stats.recordSpeed()
        _progress.value = currentProgress(stats, state, null)
    }

    private fun publishProgress(progress: TransferProgress) {
        val now = SystemClock.elapsedRealtime()
        val force = progress.state != TransferState.RUNNING
        if (!force && now - lastProgressEmitAt < PROGRESS_EMIT_INTERVAL_MS) return
        lastProgressEmitAt = now
        _progress.value = progress
    }

    private fun currentProgress(stats: RuntimeStats, state: TransferState, error: String?): TransferProgress {
        val remaining = (stats.totalBytes - stats.processedBytes).coerceAtLeast(0L)
        val eta = stats.speed.takeIf { it > 0L && stats.speedSampleSpan >= 2_000L }
            ?.let { remaining * 1_000L / it }
        return TransferProgress(
            operationId = stats.operationId,
            currentName = stats.currentName,
            currentItem = (stats.completedItems + stats.skippedItems).coerceAtMost(stats.totalItems),
            totalItems = stats.totalItems,
            currentItemBytes = stats.currentItemBytes,
            currentItemTotalBytes = stats.currentItemTotalBytes,
            processedBytes = stats.processedBytes,
            totalBytes = stats.totalBytes,
            bytesPerSecond = stats.speed,
            etaMillis = eta,
            state = state,
            error = error,
        )
    }

    private data class SourceMeasure(val items: Int, val bytes: Long)

    private class RuntimeStats(val operationId: String) {
        var currentName: String = ""
        var totalItems: Int = 0
        var totalBytes: Long = 0L
        var completedItems: Int = 0
        var skippedItems: Int = 0
        var currentItemBytes: Long = 0L
        var currentItemTotalBytes: Long = 0L
        var processedBytes: Long = 0L
        var speed: Long = 0L
        var speedSampleSpan: Long = 0L
        val sourceSizes = mutableMapOf<String, SourceMeasure>()
        private val samples = ArrayDeque<Pair<Long, Long>>()

        fun recordSpeed() {
            val now = System.currentTimeMillis()
            samples.addLast(now to processedBytes)
            while (samples.size > 2 && now - samples.first().first > SPEED_WINDOW_MS) samples.removeFirst()
            val first = samples.firstOrNull() ?: return
            speedSampleSpan = now - first.first
            speed = if (speedSampleSpan > 0L) {
                ((processedBytes - first.second) * 1_000L / speedSampleSpan).coerceAtLeast(0L)
            } else 0L
        }
    }

    companion object {
        private const val BUFFER_SIZE = 1024 * 1024
        private const val SPEED_WINDOW_MS = 4_000L
        private const val PROGRESS_EMIT_INTERVAL_MS = 100L
    }
}
