package com.aurafiles.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurafiles.app.backend.BackendFactory
import com.aurafiles.app.backend.BackendPath
import com.aurafiles.app.backend.StorageBackend
import com.aurafiles.app.backend.StorageBackendDescriptor
import com.aurafiles.app.backend.StorageBackendKind
import com.aurafiles.app.backend.StorageBackendRegistry
import com.aurafiles.app.backend.StorageItem
import com.aurafiles.app.data.FileRepository
import com.aurafiles.app.model.SftpHostKeyException
import com.aurafiles.app.model.SftpProfile
import com.aurafiles.app.network.NetworkProfileRepository
import com.aurafiles.app.network.NetworkProtocol
import com.aurafiles.app.sync.DirectoryComparator
import com.aurafiles.app.sync.DirectoryCompareResult
import com.aurafiles.app.sync.DirectorySyncExecutor
import com.aurafiles.app.sync.DirectorySyncPlan
import com.aurafiles.app.sync.DirectorySyncPlanner
import com.aurafiles.app.sync.SyncDirection
import com.aurafiles.app.transfer.TransferConflict
import com.aurafiles.app.transfer.TransferConflictDecision
import com.aurafiles.app.transfer.TransferConflictPolicy
import com.aurafiles.app.transfer.TransferController
import com.aurafiles.app.transfer.TransferDestination
import com.aurafiles.app.transfer.TransferEngine
import com.aurafiles.app.transfer.TransferProgress
import com.aurafiles.app.transfer.TransferRequest
import com.aurafiles.app.transfer.TransferSource
import com.aurafiles.app.transfer.TransferType
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BackendPaneState(
    val backendId: String? = null,
    val path: String = "/",
    val items: List<StorageItem> = emptyList(),
    val loading: Boolean = false,
    val back: List<String> = emptyList(),
    val forward: List<String> = emptyList(),
    val recent: List<String> = emptyList(),
    val selected: Set<String> = emptySet(),
)

internal data class PendingHostKey(
    val profileId: String,
    val host: String,
    val fingerprint: String,
    val previous: String?,
    val paneLeft: Boolean,
)

internal data class WorkspaceState(
    val backends: List<StorageBackendDescriptor> = emptyList(),
    val left: BackendPaneState = BackendPaneState(),
    val right: BackendPaneState = BackendPaneState(),
    val transfer: TransferProgress? = null,
    val conflict: TransferConflict? = null,
    val comparison: DirectoryCompareResult? = null,
    val syncPlan: DirectorySyncPlan? = null,
    val pendingHostKey: PendingHostKey? = null,
    val busyLabel: String? = null,
    val message: String? = null,
)

internal class BackendWorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val fileRepository = FileRepository(application)
    private val profileRepository = NetworkProfileRepository(application)
    private val registry = StorageBackendRegistry()
    private val factory = BackendFactory(application, profileRepository)
    private val transferEngine = TransferEngine(application, smbGateway = null, backendRegistry = registry)
    private val comparator = DirectoryComparator()
    private val syncExecutor = DirectorySyncExecutor()
    private val _state = MutableStateFlow(WorkspaceState())
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()
    private var operation: Job? = null
    private var controller: TransferController? = null
    private var leftRefreshJob: Job? = null
    private var rightRefreshJob: Job? = null
    private var leftRefreshGeneration = 0L
    private var rightRefreshGeneration = 0L

    init {
        rebuildBackends()
        viewModelScope.launch {
            transferEngine.progress.collect { progress -> _state.update { it.copy(transfer = progress) } }
        }
        viewModelScope.launch {
            transferEngine.conflict.collect { conflict -> _state.update { it.copy(conflict = conflict) } }
        }
    }

    override fun onCleared() {
        operation?.cancel()
        leftRefreshJob?.cancel()
        rightRefreshJob?.cancel()
        registry.close()
        super.onCleared()
    }

    fun rebuildBackends() {
        invalidateRefresh(true)
        invalidateRefresh(false)
        val oldIds = registry.descriptors().map { it.id }
        oldIds.forEach(registry::remove)
        fileRepository.restoreRoot()?.let { root ->
            registry.register(factory.local(root.uri, root.name ?: "Локальная память"))
        }
        profileRepository.profiles().forEach { profile ->
            if (profile.protocol == NetworkProtocol.SMB && profile.smbShare.isBlank()) return@forEach
            runCatching { registry.register(factory.network(profile)) }
        }
        val descriptors = registry.descriptors()
        val validBackendIds = descriptors.mapTo(hashSetOf(), StorageBackendDescriptor::id)
        _state.update { state ->
            val first = descriptors.firstOrNull { it.kind == StorageBackendKind.LOCAL } ?: descriptors.firstOrNull()
            val second = descriptors.firstOrNull { it.id != first?.id } ?: first
            state.copy(
                backends = descriptors,
                left = state.left.takeIf { pane -> pane.backendId in validBackendIds }
                    ?.copy(items = emptyList(), loading = false, selected = emptySet())
                    ?: BackendPaneState(backendId = first?.id, path = first?.rootPath ?: "/"),
                right = state.right.takeIf { pane -> pane.backendId in validBackendIds }
                    ?.copy(items = emptyList(), loading = false, selected = emptySet())
                    ?: BackendPaneState(backendId = second?.id, path = second?.rootPath ?: "/"),
                comparison = null,
                syncPlan = null,
            )
        }
        refresh(true)
        refresh(false)
    }

    fun selectBackend(left: Boolean, backendId: String) {
        val descriptor = _state.value.backends.firstOrNull { it.id == backendId } ?: return
        updatePane(left) { BackendPaneState(backendId = backendId, path = descriptor.rootPath) }
        refresh(left)
    }

    fun openBackendFromNetwork(backendId: String) {
        val descriptor = _state.value.backends.firstOrNull { it.id == backendId } ?: return
        val local = _state.value.backends.firstOrNull { it.kind == StorageBackendKind.LOCAL && it.id != backendId }
        updatePane(true) { BackendPaneState(backendId = backendId, path = descriptor.rootPath) }
        if (local != null) {
            updatePane(false) { BackendPaneState(backendId = local.id, path = local.rootPath) }
        }
        refresh(true)
        if (local != null) refresh(false)
    }

    fun open(left: Boolean, item: StorageItem) {
        if (!item.isDirectory) return
        updatePane(left) { pane ->
            pane.copy(
                path = item.path,
                back = (pane.back + pane.path).takeLast(HISTORY_LIMIT),
                forward = emptyList(),
                recent = (listOf(item.path) + pane.recent.filterNot { it == item.path }).take(RECENT_LIMIT),
                selected = emptySet(),
            )
        }
        refresh(left)
    }

    fun back(left: Boolean) {
        val pane = pane(left)
        val target = pane.back.lastOrNull() ?: return
        updatePane(left) {
            it.copy(path = target, back = it.back.dropLast(1), forward = (listOf(it.path) + it.forward).take(HISTORY_LIMIT), selected = emptySet())
        }
        refresh(left)
    }

    fun forward(left: Boolean) {
        val pane = pane(left)
        val target = pane.forward.firstOrNull() ?: return
        updatePane(left) {
            it.copy(path = target, back = (it.back + it.path).takeLast(HISTORY_LIMIT), forward = it.forward.drop(1), selected = emptySet())
        }
        refresh(left)
    }

    fun goRecent(left: Boolean, path: String) {
        val pane = pane(left)
        if (pane.path == path) return
        updatePane(left) { it.copy(path = path, back = (it.back + it.path).takeLast(HISTORY_LIMIT), forward = emptyList(), selected = emptySet()) }
        refresh(left)
    }

    fun toggleSelection(left: Boolean, item: StorageItem) {
        updatePane(left) { pane ->
            if (pane.items.none { listed -> listed.path == item.path }) pane
            else pane.copy(selected = pane.selected.toMutableSet().apply { if (!add(item.path)) remove(item.path) })
        }
    }

    fun clearSelection(left: Boolean) = updatePane(left) { it.copy(selected = emptySet()) }

    fun createFolder(left: Boolean, requestedName: String) {
        val name = requestedName.trim()
        if (name.isBlank()) {
            _state.update { it.copy(message = "Введите название папки") }
            return
        }
        mutatePane(left, "Создание папки") { backend, pane ->
            backend.mkdir(BackendPath.child(pane.path, name))
            "Папка «$name» создана"
        }
    }

    fun renameSelected(left: Boolean, requestedName: String) {
        val pane = pane(left)
        val selected = pane.items.filter { it.path in pane.selected }
        if (selected.size != 1) {
            _state.update { it.copy(message = "Для переименования выберите один объект") }
            return
        }
        val name = requestedName.trim()
        if (name.isBlank()) {
            _state.update { it.copy(message = "Введите новое имя") }
            return
        }
        val item = selected.single()
        mutatePane(left, "Переименование") { backend, _ ->
            backend.rename(item.path, name)
            "${item.name} переименован в $name"
        }
    }

    fun deleteSelected(left: Boolean) {
        val pane = pane(left)
        val selected = pane.items.filter { it.path in pane.selected }
        if (selected.isEmpty()) {
            _state.update { it.copy(message = "Выберите объекты для удаления") }
            return
        }
        mutatePane(left, "Удаление объектов") { backend, _ ->
            // Network backends have no common trash semantics. Recursive deletion is explicit
            // and is only called after the UI confirmation dialog.
            selected.forEach { backend.delete(it.path, recursive = it.isDirectory) }
            "Удалено объектов: ${selected.size}"
        }
    }

    fun refresh(left: Boolean, preserveMessage: Boolean = false) {
        val snapshot = pane(left)
        val backendId = snapshot.backendId
        val backend = backendId?.let(registry::get)
        if (backendId == null || backend == null) {
            invalidateRefresh(left)
            updatePane(left) { current ->
                if (current.backendId == backendId) {
                    current.copy(items = emptyList(), loading = false, selected = emptySet())
                } else current
            }
            return
        }

        val generation = beginRefresh(left)
        val request = PaneRefreshRequest(backendId, snapshot.path, generation)
        updatePane(left) { current ->
            if (request.isCurrentFor(current, activeRefreshGeneration(left))) current.copy(loading = true)
            else current
        }
        val job = viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { backend.list(request.path) }
                updatePane(left) { current ->
                    if (request.isCurrentFor(current, activeRefreshGeneration(left))) {
                        current.copy(
                            items = items,
                            loading = false,
                            selected = reconcilePaneSelection(current.selected, items),
                        )
                    } else current
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val isCurrent = request.isCurrentFor(pane(left), activeRefreshGeneration(left))
                if (isCurrent) {
                    updatePane(left) { current ->
                        if (request.isCurrentFor(current, activeRefreshGeneration(left))) current.copy(loading = false)
                        else current
                    }
                    handleBackendError(left, backendId, error, preserveMessage)
                }
            }
        }
        setRefreshJob(left, job)
    }

    fun copySelected(fromLeft: Boolean, move: Boolean = false) {
        val sourcePane = pane(fromLeft)
        val selected = if (sourcePane.selected.isEmpty()) emptyList() else sourcePane.items.filter { it.path in sourcePane.selected }
        if (selected.isEmpty()) {
            _state.update { it.copy(message = "Выберите файлы или папки") }
            return
        }
        transfer(fromLeft, selected, move)
    }

    fun transferSingle(fromLeft: Boolean, item: StorageItem, move: Boolean = false) = transfer(fromLeft, listOf(item), move)

    private fun transfer(fromLeft: Boolean, items: List<StorageItem>, move: Boolean) {
        if (operation?.isActive == true) return
        val sourcePane = pane(fromLeft)
        val destinationPane = pane(!fromLeft)
        val sourceBackend = sourcePane.backendId ?: return
        val destinationBackend = destinationPane.backendId ?: return
        val request = TransferRequest(
            type = if (move) TransferType.MOVE else TransferType.COPY,
            sources = items.map { item ->
                TransferSource.Backend(
                    backendId = sourceBackend,
                    path = item.path,
                    name = item.name,
                    size = item.size,
                    modifiedAt = item.modifiedAt,
                    isDirectory = item.isDirectory,
                    mimeType = item.mimeType,
                )
            },
            destination = TransferDestination.Backend(destinationBackend, destinationPane.path),
            conflictPolicy = TransferConflictPolicy.ASK,
            preserveModifiedTime = true,
        )
        controller = TransferController()
        val activeController = requireNotNull(controller)
        operation = viewModelScope.launch {
            _state.update { it.copy(busyLabel = if (move) "Перемещение между панелями" else "Копирование между панелями") }
            try {
                val result = withContext(Dispatchers.IO) { transferEngine.execute(request, activeController) }
                clearSelectionIfCurrent(fromLeft, sourcePane)
                val completed = if (move) "Перемещение завершено" else "Копирование завершено"
                _state.update { it.copy(message = completed + result.warningSuffix()) }
            } catch (_: CancellationException) {
                _state.update { it.copy(message = "Передача остановлена") }
            } catch (error: Throwable) {
                _state.update { it.copy(message = error.message ?: "Ошибка передачи") }
            } finally {
                controller = null
                _state.update { it.copy(busyLabel = null, comparison = null, syncPlan = null) }
                refresh(true, preserveMessage = true)
                refresh(false, preserveMessage = true)
            }
        }
    }

    fun pause() = controller?.pause()
    fun resume() = controller?.resume()
    fun cancel() { controller?.cancel(); operation?.cancel() }
    fun resolveConflict(policy: TransferConflictPolicy, applyToAll: Boolean) =
        transferEngine.resolveConflict(TransferConflictDecision(policy, applyToAll))

    fun comparePanels() {
        if (operation?.isActive == true) return
        val left = pane(true); val right = pane(false)
        val lb = left.backendId?.let(registry::get) ?: return
        val rb = right.backendId?.let(registry::get) ?: return
        operation = viewModelScope.launch {
            _state.update { it.copy(busyLabel = "Сравнение каталогов", comparison = null) }
            try {
                val comparison = withContext(Dispatchers.IO) {
                    comparator.compare(lb, left.path, rb, right.path, recursive = true)
                }
                if (paneLocationMatches(true, left) && paneLocationMatches(false, right)) {
                    _state.update { it.copy(comparison = comparison) }
                }
            } catch (_: CancellationException) {
                _state.update { it.copy(message = "Сравнение остановлено") }
            } catch (error: Throwable) {
                _state.update { state -> state.copy(message = error.message ?: "Ошибка сравнения") }
            } finally {
                _state.update { it.copy(busyLabel = null) }
            }
        }
    }

    fun prepareSync(direction: SyncDirection, deleteExtraneous: Boolean = false) {
        val comparison = _state.value.comparison ?: return
        _state.update { it.copy(syncPlan = DirectorySyncPlanner.build(comparison, direction, deleteExtraneous)) }
    }

    fun executeSync() {
        val plan = _state.value.syncPlan ?: return
        if (operation?.isActive == true) return
        val leftPane = pane(true); val rightPane = pane(false)
        val left = leftPane.backendId?.let(registry::get) ?: return
        val right = rightPane.backendId?.let(registry::get) ?: return
        val activeController = TransferController().also { controller = it }
        operation = viewModelScope.launch {
            _state.update { it.copy(busyLabel = "Синхронизация каталогов") }
            val cleanupWarnings = mutableListOf<String>()
            try {
                withContext(Dispatchers.IO) {
                    syncExecutor.execute(
                        plan, left, leftPane.path, right, rightPane.path,
                        copyFile = { sourceBackend, sourcePath, destinationBackend, destinationDirectory, replace ->
                            val source = sourceBackend.stat(sourcePath) ?: error("Источник исчез: $sourcePath")
                            val request = TransferRequest(
                                type = TransferType.COPY,
                                sources = listOf(
                                    TransferSource.Backend(
                                        backendId = sourceBackend.descriptor.id,
                                        path = source.path,
                                        name = source.name,
                                        size = source.size,
                                        modifiedAt = source.modifiedAt,
                                        isDirectory = source.isDirectory,
                                        mimeType = source.mimeType,
                                    )
                                ),
                                destination = TransferDestination.Backend(destinationBackend.descriptor.id, destinationDirectory),
                                conflictPolicy = if (replace) TransferConflictPolicy.REPLACE else TransferConflictPolicy.SKIP,
                            )
                            cleanupWarnings += transferEngine.execute(request, activeController).warnings
                        },
                    )
                }
                _state.update {
                    it.copy(
                        syncPlan = null,
                        comparison = null,
                        message = "Синхронизация завершена" + cleanupWarnings.warningSuffix(),
                    )
                }
            } catch (_: CancellationException) {
                _state.update { it.copy(message = "Синхронизация остановлена") }
            } catch (error: Throwable) {
                _state.update { it.copy(message = error.message ?: "Ошибка синхронизации") }
            } finally {
                controller = null
                _state.update { it.copy(busyLabel = null, comparison = null, syncPlan = null) }
                refresh(true, preserveMessage = true)
                refresh(false, preserveMessage = true)
            }
        }
    }

    fun dismissComparison() = _state.update { it.copy(comparison = null) }
    fun dismissSyncPlan() = _state.update { it.copy(syncPlan = null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun acceptHostKey() {
        val pending = _state.value.pendingHostKey ?: return
        profileRepository.trustSftpFingerprint(pending.profileId, pending.fingerprint)
        _state.update { it.copy(pendingHostKey = null, message = "Ключ сервера сохранён") }
        rebuildBackends()
        refresh(pending.paneLeft)
    }

    fun rejectHostKey() = _state.update { it.copy(pendingHostKey = null) }

    fun saveSftp(profile: SftpProfile) {
        profileRepository.save(profile)
        rebuildBackends()
    }

    fun sftpProfiles(): List<SftpProfile> = profileRepository.profiles()
        .filter { it.protocol == NetworkProtocol.SFTP }
        .map(profileRepository::sftp)

    private fun mutatePane(
        left: Boolean,
        label: String,
        block: suspend (StorageBackend, BackendPaneState) -> String,
    ) {
        if (operation?.isActive == true) {
            _state.update { it.copy(message = "Сначала дождитесь текущей операции") }
            return
        }
        val snapshot = pane(left)
        val backend = snapshot.backendId?.let(registry::get) ?: return
        operation = viewModelScope.launch {
            _state.update { it.copy(busyLabel = label) }
            var succeeded = false
            try {
                val message = withContext(Dispatchers.IO) { block(backend, snapshot) }
                succeeded = true
                _state.update { it.copy(message = message) }
            } catch (_: CancellationException) {
                _state.update { it.copy(message = "$label отменено") }
            } catch (error: Throwable) {
                handleBackendError(left, backend.descriptor.id, error)
            } finally {
                if (succeeded) clearSelectionIfCurrent(left, snapshot)
                _state.update { it.copy(busyLabel = null, comparison = null, syncPlan = null) }
                // A batch may have changed several objects before a later item failed.
                // Always reconcile the pane with the backend, including cancellation/error.
                refresh(left, preserveMessage = true)
            }
        }
    }

    private fun handleBackendError(
        left: Boolean,
        backendId: String,
        error: Throwable,
        preserveMessage: Boolean = false,
    ) {
        val hostKey = generateSequence(error) { it.cause }.filterIsInstance<SftpHostKeyException>().firstOrNull()
        if (hostKey != null) {
            val profileId = profileRepository.profiles().firstOrNull { profile ->
                profile.protocol == NetworkProtocol.SFTP && backendId == "sftp:${profile.id}"
            }?.id.orEmpty()
            _state.update {
                it.copy(
                    pendingHostKey = PendingHostKey(profileId, hostKey.host, hostKey.fingerprint, hostKey.previousFingerprint, left),
                    message = if (preserveMessage) it.message else null,
                )
            }
        } else {
            val failure = error.message ?: "Ошибка backend"
            _state.update { current ->
                val message = if (preserveMessage && !current.message.isNullOrBlank()) {
                    val side = if (left) "левая" else "правая"
                    "${current.message}\nНе обновлена $side панель: $failure"
                } else failure
                current.copy(message = message)
            }
        }
    }

    private fun pane(left: Boolean): BackendPaneState = if (left) _state.value.left else _state.value.right
    private fun updatePane(left: Boolean, transform: (BackendPaneState) -> BackendPaneState) {
        _state.update { state ->
            val before = if (left) state.left else state.right
            val transformed = transform(before)
            val locationChanged = before.backendId != transformed.backendId || before.path != transformed.path
            val after = if (locationChanged) {
                transformed.copy(items = emptyList(), loading = false, selected = emptySet())
            } else transformed
            val updated = if (left) state.copy(left = after) else state.copy(right = after)
            if (locationChanged) updated.copy(comparison = null, syncPlan = null) else updated
        }
    }

    private fun beginRefresh(left: Boolean): Long {
        if (left) {
            leftRefreshJob?.cancel()
            leftRefreshGeneration += 1
            return leftRefreshGeneration
        }
        rightRefreshJob?.cancel()
        rightRefreshGeneration += 1
        return rightRefreshGeneration
    }

    private fun invalidateRefresh(left: Boolean) {
        if (left) {
            leftRefreshJob?.cancel()
            leftRefreshJob = null
            leftRefreshGeneration += 1
        } else {
            rightRefreshJob?.cancel()
            rightRefreshJob = null
            rightRefreshGeneration += 1
        }
    }

    private fun activeRefreshGeneration(left: Boolean): Long =
        if (left) leftRefreshGeneration else rightRefreshGeneration

    private fun setRefreshJob(left: Boolean, job: Job) {
        if (left) leftRefreshJob = job else rightRefreshJob = job
    }

    private fun paneLocationMatches(left: Boolean, snapshot: BackendPaneState): Boolean {
        val current = pane(left)
        return current.backendId == snapshot.backendId && current.path == snapshot.path
    }

    private fun clearSelectionIfCurrent(left: Boolean, snapshot: BackendPaneState) {
        updatePane(left) { current ->
            if (current.backendId == snapshot.backendId && current.path == snapshot.path) {
                current.copy(selected = emptySet())
            } else current
        }
    }

    private fun com.aurafiles.app.transfer.TransferResult.warningSuffix(): String = warnings.warningSuffix()

    private fun List<String>.warningSuffix(): String =
        if (isEmpty()) "" else "\nВнимание: ${distinct().joinToString("; ")}"

    companion object {
        private const val HISTORY_LIMIT = 80
        private const val RECENT_LIMIT = 20
    }
}
