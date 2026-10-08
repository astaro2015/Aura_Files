package com.aurafiles.app.transfer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.os.SystemClock
import com.aurafiles.app.data.FastDocumentListing
import com.aurafiles.app.data.DocumentTreeSafety
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.backend.StorageBackendRegistry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
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
    private val localReplaceJournalStore = LocalReplaceJournalStore(appContext.noBackupFilesDir)
    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()
    private val _conflict = MutableStateFlow<TransferConflict?>(null)
    val conflict: StateFlow<TransferConflict?> = _conflict.asStateFlow()
    private var conflictReply: CompletableDeferred<TransferConflictDecision>? = null
    private var applyToAllPolicy: TransferConflictPolicy? = null
    private var lastProgressEmitAt = 0L

    fun recoverPendingLocalTransactions(): String? = synchronized(LOCAL_REPLACE_TRANSACTION_LOCK) {
        val records = localReplaceJournalStore.list()
        if (records.isEmpty()) return@synchronized null
        val messages = records.map(::recoverLocalReplaceTransaction)
        "Восстановлены незавершённые локальные операции: ${messages.joinToString("; ")}"
    }

    private fun recoverLocalReplaceTransaction(record: LocalReplaceJournal): String {
        val parentUri = try {
            Uri.parse(record.parentUri)
        } catch (error: Exception) {
            throw IOException("Некорректная папка в журнале локальной замены", error)
        }
        val parent = documentFromUri(parentUri)
            ?: throw IOException("Папка незавершённой локальной замены недоступна: ${record.parentUri}")
        require(parent.isDirectory && parent.canWrite()) { "Папка незавершённой локальной замены недоступна для записи" }
        DocumentTreeSafety.requireNotFilesystemSymlink(parent, "Восстановление локальной замены")

        fun current(name: String): DocumentFile? = findUniqueChildStrict(parent, name)
        var final = current(record.finalName)
        val temporary = current(record.temporaryName)
        var backup = current(record.backupName)

        if (final != null && temporary == null && backup == null) {
            localReplaceJournalStore.remove(record)
            return "${record.finalName}: завершение подтверждено"
        }

        if (final == null && backup != null) {
            when (renameAndProbe(parent, backup, record.backupName, record.finalName)) {
                LocalRenameOutcome.COMMITTED -> {
                    final = current(record.finalName)
                    backup = current(record.backupName)
                    if (final == null || backup != null) {
                        throw IOException("Не удалось подтвердить восстановление ${record.finalName} из страховочной копии")
                    }
                    localReplaceJournalStore.remove(record)
                    return if (temporary != null) {
                        "${record.finalName}: старая версия восстановлена, новая страховочная ${record.temporaryName} сохранена"
                    } else {
                        "${record.finalName}: старая версия восстановлена"
                    }
                }
                else -> throw IOException(
                    "Не удалось безопасно восстановить ${record.finalName}; журнал и страховочные объекты сохранены"
                )
            }
        }

        if (final != null && temporary == null && backup != null) {
            if (record.phase == LocalReplacePhase.FINAL_DONE || record.phase == LocalReplacePhase.CLEANUP_PENDING) {
                when (deleteAndProbe(parent, backup, record.backupName)) {
                    DeleteOutcome.COMMITTED -> {
                        localReplaceJournalStore.remove(record)
                        return "${record.finalName}: новая версия подтверждена, страховочная копия очищена"
                    }
                    else -> throw IOException(
                        "Новая версия ${record.finalName} подтверждена, но страховочную копию нельзя безопасно удалить"
                    )
                }
            }
            localReplaceJournalStore.remove(record)
            return "${record.finalName}: новая версия видна, старая ${record.backupName} сохранена как страховка"
        }

        if (final != null && temporary != null && backup == null) {
            localReplaceJournalStore.remove(record)
            return "${record.finalName}: исходная версия сохранена, новая страховочная ${record.temporaryName} оставлена"
        }

        throw IOException(
            "Состояние незавершённой замены ${record.finalName} неоднозначно; " +
                "Aura ничего не удаляет и оставляет журнал для безопасного разбора"
        )
    }

    fun resolveConflict(decision: TransferConflictDecision) {
        if (decision.applyToAll) applyToAllPolicy = decision.policy
        conflictReply?.complete(decision)
    }

    suspend fun execute(request: TransferRequest, controller: TransferController): TransferResult =
        withContext(Dispatchers.IO) {
            require(request.sources.isNotEmpty()) { "Не выбраны источники операции" }
            val usesBackend = request.sources.any { it is TransferSource.Backend } || request.destination is TransferDestination.Backend
            if (!usesBackend) recoverPendingLocalTransactions()
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
                        stats.sourceFingerprints[source.uri.toString()] = captureSourceFingerprint(document)
                        val measured = measure(
                            document = document,
                            controller = controller,
                            stats = stats,
                            forbiddenDestinationUri = (request.destination as? TransferDestination.Local)?.directoryUri,
                        )
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
                        stats.completedItems = Math.addExact(stats.completedItems, 1)
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
        DocumentTreeSafety.requireNotFilesystemSymlink(destination, "Операция с папкой назначения")

        request.sources.forEach { rawSource ->
            controller.checkpoint { paused -> updatePaused(paused) }
            val source = rawSource as? TransferSource.Local
                ?: throw IOException("Этот тип источника пока не поддерживается")
            val document = source.document()
            val fingerprint = stats.sourceFingerprints[source.uri.toString()] ?: captureSourceFingerprint(document)
            requireSourceStillMatches(source, fingerprint)
            if (request.type != TransferType.COPY &&
                source.parentUri?.let { DocumentTreeSafety.sameIdentity(it, destination.uri) } == true
            ) {
                throw IOException("Нельзя перемещать ${source.name} в ту же папку")
            }
            val collision = findUniqueChildStrict(destination, source.name)
            if (request.type != TransferType.COPY && collision == null && tryFastMove(source, destination)) {
                val measured = stats.sourceSizes[source.uri.toString()] ?: SourceMeasure(1, source.size.coerceAtLeast(0L))
                stats.completedItems += measured.items
                stats.processedBytes += measured.bytes
                stats.currentName = source.name
                emit(stats, source.name, TransferState.RUNNING)
            } else {
                val copied = copyNode(
                    source = document,
                    destination = destination,
                    request = request,
                    controller = controller,
                    stats = stats,
                    visitedDirectories = mutableSetOf(),
                    depth = 0,
                    forbiddenDestinationUri = destination.uri,
                )
                // SKIP returns null. MOVE/TRASH/RESTORE may delete the source only after a
                // destination object was actually committed and confirmed.
                if (request.type != TransferType.COPY && copied != null) {
                    controller.checkpoint { paused -> updatePaused(paused) }
                    deleteSourceAfterCommittedMove(source, fingerprint)
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
            val fingerprint = stats.sourceFingerprints[source.uri.toString()]
                ?: captureSourceFingerprint(source.document())
            requireSourceStillMatches(source, fingerprint)
            deleteLocalAndConfirm(source, fingerprint)
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
        forbiddenDestinationUri: android.net.Uri?,
        visitedDirectories: MutableSet<String> = mutableSetOf(),
        depth: Int = 0,
    ): SourceMeasure {
        controller.checkpoint { paused -> updatePaused(paused) }
        requireSafeTraversalNode(document, depth, visitedDirectories, forbiddenDestinationUri)
        stats.currentName = document.name.orEmpty()
        var items = 1
        var bytes = if (document.isFile) document.length().coerceAtLeast(0L) else 0L
        stats.totalItems = Math.addExact(stats.totalItems, 1)
        stats.totalBytes = Math.addExact(stats.totalBytes, bytes)
        emit(stats, stats.currentName, TransferState.PREPARING)
        if (document.isDirectory) {
            for (child in FastDocumentListing.listStrict(appContext, document)) {
                val nested = measure(
                    document = child.document,
                    controller = controller,
                    stats = stats,
                    forbiddenDestinationUri = forbiddenDestinationUri,
                    visitedDirectories = visitedDirectories,
                    depth = depth + 1,
                )
                items = Math.addExact(items, nested.items)
                bytes = Math.addExact(bytes, nested.bytes)
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
        visitedDirectories: MutableSet<String>,
        depth: Int,
        forbiddenDestinationUri: android.net.Uri,
    ): DocumentFile? {
        controller.checkpoint { paused -> updatePaused(paused) }
        requireSafeTraversalNode(source, depth, visitedDirectories, forbiddenDestinationUri)
        val sourceName = source.name ?: "Без имени"
        stats.currentName = sourceName
        val decision = resolveTargetName(source, destination, request, controller, stats) ?: run {
            skipNode(source, stats, mutableSetOf(), depth = 0)
            return null
        }
        return if (source.isDirectory) {
            copyDirectoryAtomic(
                source, destination, decision, request, controller, stats,
                visitedDirectories, depth, forbiddenDestinationUri,
            )
        } else {
            copyFileAtomic(source, destination, decision, request.preserveModifiedTime, controller, stats)
        }
    }

    private suspend fun copyDirectoryAtomic(
        source: DocumentFile,
        destination: DocumentFile,
        decision: LocalTargetDecision,
        request: TransferRequest,
        controller: TransferController,
        stats: RuntimeStats,
        visitedDirectories: MutableSet<String>,
        depth: Int,
        forbiddenDestinationUri: android.net.Uri,
    ): DocumentFile {
        val serviceName = allocateLocalServiceName(destination, ".aura-dir-")
        val temporary = destination.createDirectory(serviceName)
            ?: throw IOException("Не удалось создать временную папку для ${decision.name}")
        val actualTemporaryName = temporary.name ?: serviceName
        var preserveTemporary = false
        try {
            for (child in FastDocumentListing.listStrict(appContext, source)) {
                copyNode(
                    source = child.document,
                    destination = temporary,
                    request = request.copy(conflictPolicy = TransferConflictPolicy.KEEP_BOTH),
                    controller = controller,
                    stats = stats,
                    visitedDirectories = visitedDirectories,
                    depth = depth + 1,
                    forbiddenDestinationUri = forbiddenDestinationUri,
                )
            }
            controller.checkpoint { paused -> updatePaused(paused) }
            val final = finalizeLocalTemporary(
                destination = destination,
                temporary = temporary,
                temporaryName = actualTemporaryName,
                finalName = decision.name,
                replace = decision.replace,
                operationId = stats.operationId,
            )
            stats.completedItems = Math.addExact(stats.completedItems, 1)
            emit(stats, decision.name, TransferState.RUNNING)
            return final
        } catch (error: PreserveLocalTemporaryException) {
            preserveTemporary = true
            throw error
        } finally {
            if (!preserveTemporary) cleanupTemporaryIfStillNamed(destination, temporary, actualTemporaryName)
        }
    }

    private suspend fun copyFileAtomic(
        source: DocumentFile,
        destination: DocumentFile,
        decision: LocalTargetDecision,
        preserveModifiedTime: Boolean,
        controller: TransferController,
        stats: RuntimeStats,
    ): DocumentFile {
        val serviceName = allocateLocalServiceName(destination, ".aura-part-")
        val temporary = destination.createFile(source.type ?: "application/octet-stream", serviceName)
            ?: throw IOException("Не удалось создать временный файл для ${decision.name}")
        val actualTemporaryName = temporary.name ?: serviceName
        var written = 0L
        var preserveTemporary = false
        val expectedSize = source.length()
        stats.currentItemBytes = 0L
        stats.currentItemTotalBytes = expectedSize.coerceAtLeast(0L)
        try {
            val input = if (source.uri.scheme == ContentResolver.SCHEME_FILE) {
                File(requireNotNull(source.uri.path) { "Не удалось определить путь ${source.name}" }).inputStream()
            } else {
                resolver.openInputStream(source.uri)
                    ?: throw IOException("Не удалось прочитать ${source.name}")
            }
            val output = if (temporary.uri.scheme == ContentResolver.SCHEME_FILE) {
                File(requireNotNull(temporary.uri.path) { "Не удалось определить путь ${decision.name}" }).outputStream()
            } else {
                resolver.openOutputStream(temporary.uri, "w")
                    ?: throw IOException("Не удалось записать ${decision.name}")
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
            if (expectedSize >= 0L && written != expectedSize) {
                throw IOException("Размер временного файла не совпал: $written из $expectedSize байт")
            }
            controller.checkpoint { paused -> updatePaused(paused) }
            val final = finalizeLocalTemporary(
                destination = destination,
                temporary = temporary,
                temporaryName = actualTemporaryName,
                finalName = decision.name,
                replace = decision.replace,
                operationId = stats.operationId,
            )
            if (preserveModifiedTime) {
                // SAF does not expose a portable setter. Providers that preserve metadata do so on move/rename.
            }
            stats.completedItems += 1
            emit(stats, decision.name, TransferState.RUNNING)
            return final
        } catch (error: PreserveLocalTemporaryException) {
            preserveTemporary = true
            throw error
        } finally {
            if (!preserveTemporary) cleanupTemporaryIfStillNamed(destination, temporary, actualTemporaryName)
            stats.currentItemBytes = 0L
            stats.currentItemTotalBytes = 0L
        }
    }

    private fun finalizeLocalTemporary(
        destination: DocumentFile,
        temporary: DocumentFile,
        temporaryName: String,
        finalName: String,
        replace: Boolean,
        operationId: String,
    ): DocumentFile {
        if (!replace) {
            if (findUniqueChildStrict(destination, finalName) != null) {
                throw PreserveLocalTemporaryException(
                    "Пока копировался $finalName, объект с таким именем появился. " +
                        "Готовая временная копия сохранена как $temporaryName"
                )
            }
            return when (renameAndProbe(destination, temporary, temporaryName, finalName)) {
                LocalRenameOutcome.COMMITTED -> findUniqueChildStrict(destination, finalName)
                    ?: throw PreserveLocalTemporaryException(
                        "Переименование $finalName подтверждено не полностью; временная копия сохранена"
                    )
                LocalRenameOutcome.NOT_COMMITTED -> throw IOException("Не удалось завершить запись $finalName")
                LocalRenameOutcome.AMBIGUOUS -> throw PreserveLocalTemporaryException(
                    "Не удалось однозначно подтвердить запись $finalName. " +
                        "Aura не удаляет временную копию $temporaryName"
                )
            }
        }

        val existing = findUniqueChildStrict(destination, finalName)
        if (existing == null) {
            // The old target disappeared while the copy was running. Do not recreate an unsafe
            // delete; just finalize the complete temporary object as a normal commit.
            return finalizeLocalTemporary(
                destination = destination,
                temporary = temporary,
                temporaryName = temporaryName,
                finalName = finalName,
                replace = false,
                operationId = operationId,
            )
        }
        return synchronized(LOCAL_REPLACE_TRANSACTION_LOCK) {
            val backupName = allocateLocalServiceName(destination, ".aura-backup-")
            var journal = localReplaceJournalStore.create(
                operationId = operationId,
                parentUri = destination.uri.toString(),
                temporaryName = temporaryName,
                finalName = finalName,
                backupName = backupName,
            )
            journal = localReplaceJournalStore.update(journal, LocalReplacePhase.BACKUP_PENDING)
            when (renameAndProbe(destination, existing, finalName, backupName)) {
                LocalRenameOutcome.COMMITTED -> Unit
                LocalRenameOutcome.NOT_COMMITTED -> {
                    localReplaceJournalStore.remove(journal)
                    throw PreserveLocalTemporaryException(
                        "Не удалось подготовить безопасную замену $finalName; новая копия сохранена как $temporaryName"
                    )
                }
                LocalRenameOutcome.AMBIGUOUS -> throw PreserveLocalTemporaryException(
                    "Состояние старой версии $finalName после подготовки замены неоднозначно. " +
                        "Aura сохранила журнал, временную копию $temporaryName и не продолжила destructive шаг"
                )
            }
            journal = localReplaceJournalStore.update(journal, LocalReplacePhase.BACKUP_DONE)

            val backup = findUniqueChildStrict(destination, backupName)
                ?: throw PreserveLocalTemporaryException(
                    "Страховочная копия $finalName не найдена после переименования; журнал и временная копия сохранены"
                )
            journal = localReplaceJournalStore.update(journal, LocalReplacePhase.FINAL_PENDING)
            when (renameAndProbe(destination, temporary, temporaryName, finalName)) {
                LocalRenameOutcome.COMMITTED -> {
                    journal = localReplaceJournalStore.update(journal, LocalReplacePhase.FINAL_DONE)
                    journal = localReplaceJournalStore.update(journal, LocalReplacePhase.CLEANUP_PENDING)
                    when (deleteAndProbe(destination, backup, backupName)) {
                        DeleteOutcome.COMMITTED -> Unit
                        DeleteOutcome.NOT_COMMITTED,
                        DeleteOutcome.AMBIGUOUS -> throw IOException(
                            "Новая версия $finalName сохранена, но страховочную копию $backupName удалить безопасно не удалось; " +
                                "журнал оставлен для восстановления"
                        )
                    }
                    val final = findUniqueChildStrict(destination, finalName)
                        ?: throw IOException("Новая версия $finalName была подтверждена, но больше не видна в папке")
                    localReplaceJournalStore.remove(journal)
                    final
                }
                LocalRenameOutcome.NOT_COMMITTED -> {
                    journal = localReplaceJournalStore.update(journal, LocalReplacePhase.ROLLBACK_PENDING)
                    when (renameAndProbe(destination, backup, backupName, finalName)) {
                        LocalRenameOutcome.COMMITTED -> {
                            localReplaceJournalStore.remove(journal)
                            throw IOException("Не удалось заменить $finalName; старая версия восстановлена")
                        }
                        else -> throw PreserveLocalTemporaryException(
                            "Не удалось завершить замену $finalName и безопасно восстановить старую версию. " +
                                "Журнал, страховочная копия $backupName и новая $temporaryName сохранены"
                        )
                    }
                }
                LocalRenameOutcome.AMBIGUOUS -> throw PreserveLocalTemporaryException(
                    "Не удалось однозначно подтвердить замену $finalName. " +
                        "Журнал, страховочная копия $backupName и временная копия $temporaryName сохранены"
                )
            }
        }
    }

    private suspend fun resolveTargetName(
        source: DocumentFile,
        destination: DocumentFile,
        request: TransferRequest,
        controller: TransferController,
        stats: RuntimeStats,
    ): LocalTargetDecision? {
        val sourceName = source.name ?: "Без имени"
        val existing = findUniqueChildStrict(destination, sourceName)
            ?: return LocalTargetDecision(sourceName, replace = false)
        if (DocumentTreeSafety.sameIdentity(source.uri, existing.uri)) {
            if (request.type == TransferType.COPY) {
                return LocalTargetDecision(uniqueName(destination, sourceName), replace = false)
            }
            throw IOException("Источник $sourceName уже находится в папке назначения")
        }
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
            TransferConflictPolicy.REPLACE -> LocalTargetDecision(sourceName, replace = true)
            TransferConflictPolicy.SKIP -> null
            TransferConflictPolicy.KEEP_BOTH -> LocalTargetDecision(uniqueName(destination, sourceName), replace = false)
            TransferConflictPolicy.CANCEL -> throw CancellationException("Отменено пользователем")
            TransferConflictPolicy.ASK,
            TransferConflictPolicy.REPLACE_IF_NEWER,
            TransferConflictPolicy.REPLACE_IF_SIZE_DIFFERS -> null
        }
    }

    private fun skipNode(
        source: DocumentFile,
        stats: RuntimeStats,
        visitedDirectories: MutableSet<String>,
        depth: Int,
    ) {
        requireSafeTraversalNode(source, depth, visitedDirectories, forbiddenDestinationUri = null)
        stats.skippedItems = Math.addExact(stats.skippedItems, 1)
        if (source.isFile) {
            stats.processedBytes = Math.addExact(stats.processedBytes, source.length().coerceAtLeast(0L))
        } else if (source.isDirectory) {
            for (child in FastDocumentListing.listStrict(appContext, source)) {
                skipNode(child.document, stats, visitedDirectories, depth + 1)
            }
        }
        emit(stats, source.name.orEmpty(), TransferState.RUNNING)
    }

    private fun tryFastMove(source: TransferSource.Local, destination: DocumentFile): Boolean {
        val parentUri = source.parentUri ?: return false
        if (source.uri.scheme != ContentResolver.SCHEME_CONTENT || destination.uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        if (source.uri.authority != destination.uri.authority) return false
        var returnedUri: android.net.Uri? = null
        try {
            returnedUri = DocumentsContract.moveDocument(resolver, source.uri, parentUri, destination.uri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A provider can commit the move and still lose/throw the response. Reconcile below.
        }

        val oldParent = documentFromUri(parentUri)
            ?: throw IOException("Не удалось проверить исходную папку после быстрого перемещения ${source.name}")
        val oldProbe = runCatching { findIdentityStrict(oldParent, source.uri) }
        val expectedDestinationUri = returnedUri ?: source.uri
        val newProbe = runCatching { findIdentityStrict(destination, expectedDestinationUri) }
        val nameProbe = runCatching { findUniqueChildStrict(destination, source.name) }
        if (oldProbe.isFailure || newProbe.isFailure || nameProbe.isFailure) {
            throw IOException(
                "Не удалось однозначно проверить быстрое перемещение ${source.name}; " +
                    "Aura не запускает fallback copy/delete",
                oldProbe.exceptionOrNull() ?: newProbe.exceptionOrNull() ?: nameProbe.exceptionOrNull(),
            )
        }
        val oldState = oldProbe.getOrNull()
        val newState = newProbe.getOrNull()
        val namedState = nameProbe.getOrNull()
        return when {
            oldState == null && newState != null -> true
            oldState != null && newState == null && namedState == null -> false
            // A same-name object without the expected identity may be a concurrent unrelated
            // object. Never treat it as proof that this move committed.
            else -> throw IOException(
                "Не удалось однозначно подтвердить быстрое перемещение ${source.name}; " +
                    "Aura не запускает fallback copy/delete, чтобы не повредить данные"
            )
        }
    }

    private fun deleteSourceAfterCommittedMove(
        source: TransferSource.Local,
        fingerprint: LocalSourceFingerprint,
    ) {
        requireSourceStillMatches(source, fingerprint)
        val document = source.document()
        try {
            document.delete()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Reconcile from the parent below.
        }
        val parentUri = source.parentUri ?: throw IOException(
            "Копия ${source.name} создана, но состояние исходника после удаления нельзя подтвердить; " +
                "destination сохранён"
        )
        val parent = documentFromUri(parentUri) ?: throw IOException(
            "Копия ${source.name} создана, но исходная папка недоступна для проверки; destination сохранён"
        )
        val probe = runCatching { findIdentityStrict(parent, source.uri) }
        if (probe.isFailure) {
            throw IOException(
                "Копия ${source.name} создана, но состояние исходника после удаления неизвестно; destination сохранён",
                probe.exceptionOrNull(),
            )
        }
        if (probe.getOrNull() == null) return
        throw IOException("Копия ${source.name} создана, но исходник удалить не удалось; обе копии сохранены")
    }

    private fun deleteLocalAndConfirm(
        source: TransferSource.Local,
        fingerprint: LocalSourceFingerprint,
    ) {
        requireSourceStillMatches(source, fingerprint)
        val document = source.document()
        try {
            document.delete()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Providers may commit and then throw; parent identity probe decides the result.
        }
        val parentUri = source.parentUri ?: throw IOException(
            "Не удалось однозначно подтвердить удаление ${source.name}: исходная папка неизвестна"
        )
        val parent = documentFromUri(parentUri)
            ?: throw IOException("Не удалось проверить удаление ${source.name}: исходная папка недоступна")
        val probe = runCatching { findIdentityStrict(parent, source.uri) }
        if (probe.isFailure) {
            throw IOException("Состояние ${source.name} после удаления неизвестно", probe.exceptionOrNull())
        }
        if (probe.getOrNull() == null) return
        throw IOException("Не удалось удалить ${source.name}")
    }


    private fun captureSourceFingerprint(document: DocumentFile): LocalSourceFingerprint {
        val fileKey = if (document.uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = document.uri.path
            if (path == null) null else runCatching {
                Files.readAttributes(
                    File(path).toPath(),
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ).fileKey()?.toString()
            }.getOrNull()
        } else {
            null
        }
        return LocalSourceFingerprint(
            identity = DocumentTreeSafety.identityKey(document),
            fileKey = fileKey,
            name = document.name,
            isDirectory = document.isDirectory,
            size = if (document.isFile) document.length().coerceAtLeast(0L) else -1L,
            modifiedAt = document.lastModified().coerceAtLeast(0L),
        )
    }

    private fun requireSourceStillMatches(source: TransferSource.Local, expected: LocalSourceFingerprint) {
        val current = source.document()
        val actual = captureSourceFingerprint(current)
        if (actual.identity != expected.identity) {
            throw IOException("Исходник ${source.name} изменился во время операции; удаление отменено")
        }
        if (expected.fileKey != null) {
            if (actual.fileKey != expected.fileKey) {
                throw IOException("Исходник ${source.name} был заменён другим объектом; удаление отменено")
            }
            return
        }
        if (actual.name != expected.name ||
            actual.isDirectory != expected.isDirectory ||
            (!expected.isDirectory && actual.size != expected.size) ||
            actual.modifiedAt != expected.modifiedAt
        ) {
            throw IOException("Исходник ${source.name} изменился во время операции; удаление отменено")
        }
    }

    private fun allocateLocalServiceName(parent: DocumentFile, prefix: String): String {
        repeat(LOCAL_TEMP_NAME_ATTEMPTS) {
            val candidate = "$prefix${UUID.randomUUID()}"
            if (findUniqueChildStrict(parent, candidate) == null) return candidate
        }
        throw IOException("Не удалось подобрать безопасное служебное имя")
    }

    private fun renameAndProbe(
        parent: DocumentFile,
        document: DocumentFile,
        fromName: String,
        toName: String,
    ): LocalRenameOutcome {
        try {
            document.renameTo(toName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Lost ACK is reconciled by strict parent listing below.
        }
        val sourceProbe = runCatching { findUniqueChildStrict(parent, fromName) }
        val targetProbe = runCatching { findUniqueChildStrict(parent, toName) }
        if (sourceProbe.isFailure || targetProbe.isFailure) return LocalRenameOutcome.AMBIGUOUS
        val sourceExists = sourceProbe.getOrNull() != null
        val targetExists = targetProbe.getOrNull() != null
        return when {
            !sourceExists && targetExists -> LocalRenameOutcome.COMMITTED
            sourceExists && !targetExists -> LocalRenameOutcome.NOT_COMMITTED
            else -> LocalRenameOutcome.AMBIGUOUS
        }
    }

    private fun deleteAndProbe(parent: DocumentFile, document: DocumentFile, name: String): DeleteOutcome {
        var reported = false
        try {
            reported = document.delete()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Lost ACK is reconciled below.
        }
        val probe = runCatching { findUniqueChildStrict(parent, name) }
        if (probe.isFailure) return DeleteOutcome.AMBIGUOUS
        return when {
            probe.getOrNull() == null -> DeleteOutcome.COMMITTED
            reported -> DeleteOutcome.AMBIGUOUS
            else -> DeleteOutcome.NOT_COMMITTED
        }
    }

    private fun cleanupTemporaryIfStillNamed(parent: DocumentFile, temporary: DocumentFile, name: String) {
        val probe = runCatching { findUniqueChildStrict(parent, name) }
        if (probe.isFailure) return
        val leftover = probe.getOrNull() ?: return
        // The old service name can be reused concurrently after a successful rename. Delete only
        // the exact Aura-created object, never an unrelated file that merely reused its name.
        if (!DocumentTreeSafety.sameIdentity(leftover.uri, temporary.uri)) return
        try {
            leftover.delete()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Unit
        }
    }

    private fun requireSafeTraversalNode(
        document: DocumentFile,
        depth: Int,
        visitedDirectories: MutableSet<String>,
        forbiddenDestinationUri: android.net.Uri?,
    ) {
        DocumentTreeSafety.requireDepth(depth, "Операция с папкой")
        DocumentTreeSafety.requireNotFilesystemSymlink(document, "Операция с папкой")
        if (!document.isDirectory) return
        if (forbiddenDestinationUri != null && DocumentTreeSafety.sameIdentity(document.uri, forbiddenDestinationUri)) {
            throw IOException("Нельзя копировать или перемещать папку внутрь самой себя или её потомка")
        }
        DocumentTreeSafety.requireUniqueDirectoryVisit(
            directory = document,
            depth = depth,
            visited = visitedDirectories,
            operation = "Операция с папкой",
        )
    }

    private fun findUniqueChildStrict(parent: DocumentFile, name: String): DocumentFile? {
        val matches = FastDocumentListing.listStrict(appContext, parent).filter { it.name == name }
        if (matches.size > 1) {
            throw IOException("Имя $name неоднозначно: найдено объектов ${matches.size}")
        }
        return matches.singleOrNull()?.document
    }

    private fun findIdentityStrict(parent: DocumentFile, uri: android.net.Uri): DocumentFile? {
        val matches = FastDocumentListing.listStrict(appContext, parent)
            .filter { DocumentTreeSafety.sameIdentity(it.uri, uri) }
        if (matches.size > 1) {
            throw IOException("Идентификатор объекта неоднозначен: $uri")
        }
        return matches.singleOrNull()?.document
    }

    private fun uniqueName(parent: DocumentFile, requested: String): String {
        if (findUniqueChildStrict(parent, requested) == null) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        for (index in 2..LOCAL_UNIQUE_NAME_ATTEMPTS) {
            val candidate = "$base ($index)$extension"
            if (findUniqueChildStrict(parent, candidate) == null) return candidate
        }
        throw IOException("Не удалось подобрать уникальное имя для $requested")
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

    private data class LocalTargetDecision(val name: String, val replace: Boolean)

    private data class LocalSourceFingerprint(
        val identity: String,
        val fileKey: String?,
        val name: String?,
        val isDirectory: Boolean,
        val size: Long,
        val modifiedAt: Long,
    )

    private enum class LocalRenameOutcome { COMMITTED, NOT_COMMITTED, AMBIGUOUS }

    private enum class DeleteOutcome { COMMITTED, NOT_COMMITTED, AMBIGUOUS }

    private class PreserveLocalTemporaryException(message: String) : IOException(message)

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
        val sourceFingerprints = mutableMapOf<String, LocalSourceFingerprint>()
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
        private val LOCAL_REPLACE_TRANSACTION_LOCK = Any()
        private const val BUFFER_SIZE = 1024 * 1024
        private const val LOCAL_TEMP_NAME_ATTEMPTS = 64
        private const val LOCAL_UNIQUE_NAME_ATTEMPTS = 10_000
        private const val SPEED_WINDOW_MS = 4_000L
        private const val PROGRESS_EMIT_INTERVAL_MS = 100L
    }
}
