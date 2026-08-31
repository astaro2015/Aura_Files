package com.aurafiles.app.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurafiles.app.archive.ExtendedArchiveRepository
import com.aurafiles.app.data.FileRepository
import com.aurafiles.app.data.FtpRepository
import com.aurafiles.app.data.LanDiscoveryRepository
import com.aurafiles.app.data.SmbRepository
import com.aurafiles.app.data.SmbTransferGatewayAdapter
import com.aurafiles.app.data.SystemSoundRepository
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.index.IndexScanProgress
import com.aurafiles.app.index.IndexScanState
import com.aurafiles.app.index.StorageIndexer
import com.aurafiles.app.network.NetworkProfile
import com.aurafiles.app.network.NetworkProfileRepository
import com.aurafiles.app.network.NetworkProtocol
import com.aurafiles.app.model.ClipboardMode
import com.aurafiles.app.model.DeleteAnimationMode
import com.aurafiles.app.model.FileClipboard
import com.aurafiles.app.model.FileEntry
import com.aurafiles.app.model.FileCategory
import com.aurafiles.app.model.FileCollectionGroup
import com.aurafiles.app.model.FileSortMode
import com.aurafiles.app.model.FileViewMode
import com.aurafiles.app.model.FolderCrumb
import com.aurafiles.app.model.FtpEntry
import com.aurafiles.app.model.FtpProfile
import com.aurafiles.app.model.LanDevice
import com.aurafiles.app.model.MainSection
import com.aurafiles.app.model.StorageSnapshot
import com.aurafiles.app.model.StorageVolumeInfo
import com.aurafiles.app.model.StorageAnalysis
import com.aurafiles.app.model.StorageAccessMode
import com.aurafiles.app.model.SmbEntry
import com.aurafiles.app.model.SmbProfile
import com.aurafiles.app.model.SftpProfile
import com.aurafiles.app.model.SystemSoundType
import com.aurafiles.app.model.TrashRecord
import com.aurafiles.app.model.isTemporaryCandidate
import com.aurafiles.app.model.isThumbnailCache
import com.aurafiles.app.model.matchesCategory
import com.aurafiles.app.model.sourceLabel
import com.aurafiles.app.model.displayLocation
import com.aurafiles.app.transfer.TransferConflict
import com.aurafiles.app.transfer.TransferConflictDecision
import com.aurafiles.app.transfer.TransferConflictPolicy
import com.aurafiles.app.transfer.TransferController
import com.aurafiles.app.transfer.TransferDestination
import com.aurafiles.app.transfer.TransferEngine
import com.aurafiles.app.transfer.TransferProgress
import com.aurafiles.app.transfer.TransferRequest
import com.aurafiles.app.transfer.TransferSource
import com.aurafiles.app.transfer.TransferState
import com.aurafiles.app.transfer.TransferType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

private const val LARGE_FILE_BYTES = 50L * 1024L * 1024L

private data class CategoryCollectionCacheEntry(
    val rootUri: String,
    val scannedAt: Long,
    val showThumbnailFiles: Boolean,
    val items: List<FileEntry>,
    val groups: List<FileCollectionGroup>,
)

data class FileManagerUiState(
    val rootConnected: Boolean = false,
    val accessMode: StorageAccessMode = StorageAccessMode.Folder,
    val fullAccessGranted: Boolean = false,
    val browserOpen: Boolean = false,
    val folderStack: List<FolderCrumb> = emptyList(),
    val items: List<FileEntry> = emptyList(),
    val dualPane: Boolean = false,
    val secondaryFolderStack: List<FolderCrumb> = emptyList(),
    val secondaryItems: List<FileEntry> = emptyList(),
    val secondaryLoading: Boolean = false,
    val collectionTitle: String? = null,
    val collectionGroups: List<FileCollectionGroup> = emptyList(),
    val duplicateOriginalUris: Set<Uri> = emptySet(),
    val recentItems: List<FileEntry> = emptyList(),
    val favoriteItems: List<FileEntry> = emptyList(),
    val favoriteUris: Set<Uri> = emptySet(),
    val trashRecords: List<TrashRecord> = emptyList(),
    val activeSection: MainSection = MainSection.Browse,
    val clipboard: FileClipboard? = null,
    val sortMode: FileSortMode = FileSortMode.Name,
    val sortAscending: Boolean = true,
    val viewMode: FileViewMode = FileViewMode.List,
    val showHidden: Boolean = false,
    val showThumbnailFiles: Boolean = false,
    val showGridThumbnails: Boolean = true,
    val showFavoritesOnHome: Boolean = true,
    val deleteAnimationMode: DeleteAnimationMode = DeleteAnimationMode.Dissolve,
    val deletingUris: Set<Uri> = emptySet(),
    val storage: StorageSnapshot = StorageSnapshot(0L, 0L),
    val storageVolumes: List<StorageVolumeInfo> = emptyList(),
    val analysis: StorageAnalysis? = null,
    val analyzing: Boolean = false,
    val indexProgress: IndexScanProgress? = null,
    val loading: Boolean = false,
    val operationInProgress: Boolean = false,
    val operationLabel: String? = null,
    val operationProgress: Float = 0f,
    val operationCancelable: Boolean = false,
    val transferProgress: TransferProgress? = null,
    val transferConflict: TransferConflict? = null,
    val transferPaused: Boolean = false,
    val fileHashes: Map<Uri, String> = emptyMap(),
    val hashingUris: Set<Uri> = emptySet(),
    val undoTrash: List<TrashRecord> = emptyList(),
    val ftpProfile: FtpProfile? = null,
    val ftpConnected: Boolean = false,
    val ftpPath: String = "/",
    val ftpItems: List<FtpEntry> = emptyList(),
    val ftpLoading: Boolean = false,
    val ftpTransferLabel: String? = null,
    val lanDevices: List<LanDevice> = emptyList(),
    val lanScanning: Boolean = false,
    val networkProfiles: List<NetworkProfile> = emptyList(),
    val smbProfile: SmbProfile? = null,
    val smbConnected: Boolean = false,
    val smbShares: List<String> = emptyList(),
    val smbPath: String = "/",
    val smbItems: List<SmbEntry> = emptyList(),
    val smbLoading: Boolean = false,
    val smbTransferLabel: String? = null,
    val message: String? = null,
)

class FileManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FileRepository(application)
    private val archiveRepository = ExtendedArchiveRepository(application)
    private val ftpRepository = FtpRepository(application)
    private val lanDiscoveryRepository = LanDiscoveryRepository(application)
    private val smbRepository = SmbRepository(application)
    private val systemSoundRepository = SystemSoundRepository(application)
    private val transferEngine = TransferEngine(
        application,
        SmbTransferGatewayAdapter(application, smbRepository),
    )
    private val storageIndexer = StorageIndexer(application)
    private val networkProfileRepository = NetworkProfileRepository(application)
    private val _state = MutableStateFlow(
        FileManagerUiState(
            storage = repository.storageSnapshot(),
            accessMode = repository.currentAccessMode(),
            fullAccessGranted = repository.hasFullAccess(),
            storageVolumes = repository.storageVolumes(),
            showHidden = repository.showHiddenFiles(),
            showThumbnailFiles = repository.showThumbnailFiles(),
            showGridThumbnails = repository.showGridThumbnails(),
            showFavoritesOnHome = repository.showFavoritesOnHome(),
            deleteAnimationMode = repository.deleteAnimationMode(),
        )
    )
    val state: StateFlow<FileManagerUiState> = _state.asStateFlow()
    private var operationJob: Job? = null
    private var transferController: TransferController? = null
    private var ftpKeepAliveJob: Job? = null
    private var folderLoadJob: Job? = null
    private var secondaryFolderLoadJob: Job? = null
    private var collectionLoadJob: Job? = null
    private var categoryCacheWarmJob: Job? = null
    private val categoryCollectionCache = mutableMapOf<FileCategory, CategoryCollectionCacheEntry>()
    private var categoryToOpenAfterAnalysis: FileCategory? = null

    init {
        val savedProfiles = networkProfileRepository.profiles()
        if (savedProfiles.none { it.protocol == NetworkProtocol.FTP || it.protocol == NetworkProtocol.FTPS }) {
            ftpRepository.loadProfile()?.let(networkProfileRepository::save)
        }
        val initialProfiles = networkProfileRepository.profiles()
        val initialFtp = initialProfiles
            .firstOrNull { it.protocol == NetworkProtocol.FTP || it.protocol == NetworkProtocol.FTPS }
            ?.let(networkProfileRepository::ftp)
        _state.update { it.copy(ftpProfile = initialFtp, networkProfiles = initialProfiles) }
        viewModelScope.launch {
            var lastTransferUiAt = 0L
            transferEngine.progress.collect { progress ->
                val now = SystemClock.elapsedRealtime()
                val terminal = progress == null || progress.state !in setOf(TransferState.PREPARING, TransferState.RUNNING)
                if (!terminal && now - lastTransferUiAt < 100L) return@collect
                lastTransferUiAt = now
                _state.update { state ->
                    val networkLabel = if (state.smbTransferLabel != null) {
                        progress?.let(::transferLabel) ?: state.smbTransferLabel
                    } else null
                    state.copy(
                        transferProgress = progress,
                        transferPaused = progress?.state == TransferState.PAUSED,
                        operationProgress = progress?.fraction ?: state.operationProgress,
                        operationLabel = progress?.let(::transferLabel) ?: state.operationLabel,
                        smbTransferLabel = networkLabel,
                    )
                }
            }
        }
        viewModelScope.launch {
            transferEngine.conflict.collect { conflict ->
                _state.update { it.copy(transferConflict = conflict) }
            }
        }
        viewModelScope.launch {
            var lastUiProgressAt = 0L
            storageIndexer.progress.collect { progress ->
                if (progress.state == IndexScanState.IDLE) return@collect
                val now = SystemClock.elapsedRealtime()
                val terminal = progress.state in setOf(
                    IndexScanState.CANCELLED, IndexScanState.FAILED, IndexScanState.COMPLETED
                )
                if (!terminal && now - lastUiProgressAt < 150L) return@collect
                lastUiProgressAt = now
                _state.update {
                    it.copy(
                        indexProgress = progress,
                        operationLabel = when (progress.state) {
                            IndexScanState.SCANNING -> "Анализ: ${progress.filesCount} файлов · ${progress.currentFolder}"
                            IndexScanState.HASHING -> "Проверка дубликатов: ${progress.currentFile}"
                            IndexScanState.CANCELLED -> "Анализ отменён"
                            IndexScanState.FAILED -> "Ошибка анализа"
                            IndexScanState.COMPLETED -> "Анализ завершён"
                            IndexScanState.IDLE -> it.operationLabel
                        },
                    )
                }
            }
        }
        restoreRoot()
    }

    fun attachRoot(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.attachRoot(uri) }
            }.onSuccess { root ->
                val cachedAnalysis = withContext(Dispatchers.IO) { storageIndexer.load(root) }
                _state.update {
                    it.copy(
                        rootConnected = true,
                        accessMode = StorageAccessMode.Folder,
                        fullAccessGranted = repository.hasFullAccess(),
                        browserOpen = true,
                        folderStack = listOf(FolderCrumb(root, root.name ?: "Хранилище")),
                        items = emptyList(),
                        collectionTitle = null,
                        collectionGroups = emptyList(),
                        dualPane = false,
                        secondaryFolderStack = emptyList(),
                        secondaryItems = emptyList(),
                        recentItems = cachedAnalysis?.files?.sortedByDescending(FileEntry::modifiedAt)?.take(12).orEmpty(),
                        analysis = cachedAnalysis,
                        clipboard = null,
                    )
                }
                invalidateCategoryCollectionCache()
                if (cachedAnalysis != null) warmCategoryCollectionCache(root, cachedAnalysis)
                refreshCurrentFolder()
                refreshMetadata()
            }.onFailure(::showFailure)
        }
    }

    fun activateFullAccess() {
        viewModelScope.launch {
            if (!repository.hasFullAccess()) {
                _state.update {
                    it.copy(
                        fullAccessGranted = false,
                        message = "Разрешите Aura управление всеми файлами в настройках Android",
                    )
                }
                return@launch
            }
            runCatching { withContext(Dispatchers.IO) { repository.attachFullRoot() } }
                .onSuccess { root ->
                    val cachedAnalysis = withContext(Dispatchers.IO) { storageIndexer.load(root) }
                    _state.update {
                        it.copy(
                            rootConnected = true,
                            browserOpen = true,
                            accessMode = StorageAccessMode.Full,
                            fullAccessGranted = true,
                            folderStack = listOf(FolderCrumb(root, "Внутренняя память")),
                            items = emptyList(),
                            collectionTitle = null,
                            collectionGroups = emptyList(),
                            dualPane = false,
                            secondaryFolderStack = emptyList(),
                            secondaryItems = emptyList(),
                            recentItems = cachedAnalysis?.files?.sortedByDescending(FileEntry::modifiedAt)?.take(12).orEmpty(),
                            analysis = cachedAnalysis,
                            clipboard = null,
                        )
                    }
                    invalidateCategoryCollectionCache()
                    if (cachedAnalysis != null) warmCategoryCollectionCache(root, cachedAnalysis)
                    refreshCurrentFolder()
                    refreshMetadata()
                }
                .onFailure(::showFailure)
        }
    }

    fun refreshFullAccessStatus() {
        val granted = repository.hasFullAccess()
        _state.update {
            it.copy(
                fullAccessGranted = granted,
                message = if (granted) it.message else "Полный доступ не выдан",
            )
        }
        if (granted) activateFullAccess()
    }

    fun refreshStorageVolumes() {
        viewModelScope.launch {
            val volumes = withContext(Dispatchers.IO) { repository.storageVolumes() }
            _state.update { it.copy(storageVolumes = volumes) }
        }
    }

    fun openRoot() {
        if (_state.value.folderStack.isEmpty()) {
            _state.update { it.copy(message = "Сначала выберите папку на устройстве") }
            return
        }
        _state.update {
            it.copy(browserOpen = true, activeSection = MainSection.Browse, collectionTitle = null, collectionGroups = emptyList(), duplicateOriginalUris = emptySet())
        }
        refreshCurrentFolder()
    }

    fun openEntry(entry: FileEntry) {
        if (!entry.isDirectory) return
        _state.update {
            it.copy(
                folderStack = it.folderStack + FolderCrumb(entry.document, entry.name),
                collectionTitle = null,
                collectionGroups = emptyList(),
                duplicateOriginalUris = emptySet(),
            )
        }
        refreshCurrentFolder()
    }

    fun toggleDualPane() {
        val current = _state.value
        if (current.folderStack.isEmpty()) return
        if (current.dualPane) {
            _state.update { it.copy(dualPane = false) }
            return
        }
        val initialStack = current.secondaryFolderStack.ifEmpty {
            listOf(current.folderStack.first())
        }
        _state.update { it.copy(dualPane = true, secondaryFolderStack = initialStack) }
        refreshSecondaryFolder()
    }

    fun openSecondaryEntry(entry: FileEntry) {
        if (!entry.isDirectory) return
        _state.update {
            it.copy(secondaryFolderStack = it.secondaryFolderStack + FolderCrumb(entry.document, entry.name))
        }
        refreshSecondaryFolder()
    }

    fun navigateSecondaryBack(): Boolean {
        val stack = _state.value.secondaryFolderStack
        if (stack.size <= 1) return false
        _state.update { it.copy(secondaryFolderStack = it.secondaryFolderStack.dropLast(1)) }
        refreshSecondaryFolder()
        return true
    }

    fun copyToOtherPane(entry: FileEntry, fromPrimary: Boolean) {
        if (_state.value.operationInProgress) {
            _state.update { it.copy(message = "Сначала дождитесь текущей операции") }
            return
        }
        val destination = if (fromPrimary) {
            _state.value.secondaryFolderStack.lastOrNull()?.document
        } else {
            _state.value.folderStack.lastOrNull()?.document
        }
        if (destination == null) return
        if (entry.parentUri == destination.uri) {
            _state.update { it.copy(message = "Объект уже находится в этой папке") }
            return
        }
        val targetStack = if (fromPrimary) _state.value.secondaryFolderStack else _state.value.folderStack
        if (entry.isDirectory && targetStack.any { it.document.uri == entry.uri }) {
            _state.update { it.copy(message = "Нельзя копировать папку внутрь самой себя") }
            return
        }
        runFileOperation("Копирование между панелями") {
            repository.copy(entry, destination)
            "${entry.name} скопирован в соседнюю панель"
        }
    }

    fun refreshSecondaryFolder() {
        val directory = _state.value.secondaryFolderStack.lastOrNull()?.document ?: return
        val requestedUri = directory.uri
        secondaryFolderLoadJob?.cancel()
        secondaryFolderLoadJob = viewModelScope.launch {
            _state.update { it.copy(secondaryLoading = true) }
            runCatching { withContext(Dispatchers.IO) { repository.listChildren(directory) } }
                .onSuccess { items ->
                    if (_state.value.secondaryFolderStack.lastOrNull()?.document?.uri != requestedUri) return@onSuccess
                    _state.update { it.copy(secondaryLoading = false, secondaryItems = items) }
                }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    if (_state.value.secondaryFolderStack.lastOrNull()?.document?.uri == requestedUri) {
                        _state.update { it.copy(secondaryLoading = false) }
                        showFailure(error)
                    }
                }
        }
    }

    fun navigateBack(): Boolean {
        val current = _state.value
        if (!current.browserOpen) return false
        if (current.collectionTitle != null) {
            _state.update { it.copy(browserOpen = false, collectionTitle = null, collectionGroups = emptyList(), duplicateOriginalUris = emptySet()) }
        } else if (current.folderStack.size > 1) {
            _state.update { it.copy(folderStack = it.folderStack.dropLast(1)) }
            refreshCurrentFolder()
        } else {
            _state.update { it.copy(browserOpen = false) }
        }
        return true
    }

    fun selectSection(section: MainSection) {
        _state.update { it.copy(activeSection = section, browserOpen = false) }
        if (section == MainSection.Network && !_state.value.ftpConnected && !_state.value.ftpLoading) {
            _state.value.ftpProfile?.let { connectFtp(it, save = false) }
        }
        if (section == MainSection.Network && _state.value.lanDevices.isEmpty() && !_state.value.lanScanning) {
            scanLan()
        }
    }

    fun applyExternalDeletions(uris: Set<Uri>) {
        if (uris.isEmpty()) return
        invalidateCategoryCollectionCache()
        val before = _state.value
        val knownEntries = sequence {
            yieldAll(before.items)
            yieldAll(before.secondaryItems)
            yieldAll(before.recentItems)
            yieldAll(before.favoriteItems)
            yieldAll(before.collectionGroups.flatMap(FileCollectionGroup::entries))
            before.analysis?.let { analysis ->
                yieldAll(analysis.files)
                yieldAll(analysis.largeFiles)
                yieldAll(analysis.duplicateGroups.flatten())
            }
        }.filter { it.uri in uris }.distinctBy(FileEntry::uri).toList()

        _state.update { current ->
            val groups = current.collectionGroups.mapNotNull { group ->
                val updated = group.copy(entries = group.entries.filterNot { it.uri in uris })
                val keep = if (current.collectionTitle == "Дубликаты") updated.entries.size > 1 else updated.entries.isNotEmpty()
                updated.takeIf { keep }
            }
            current.copy(
                items = current.items.filterNot { it.uri in uris },
                secondaryItems = current.secondaryItems.filterNot { it.uri in uris },
                recentItems = current.recentItems.filterNot { it.uri in uris },
                favoriteItems = current.favoriteItems.filterNot { it.uri in uris },
                favoriteUris = current.favoriteUris - uris,
                collectionGroups = groups,
                duplicateOriginalUris = current.duplicateOriginalUris - uris,
                fileHashes = current.fileHashes - uris,
                hashingUris = current.hashingUris - uris,
                deletingUris = current.deletingUris - uris,
                analysis = if (knownEntries.isNotEmpty()) current.analysis?.withoutEntries(knownEntries) else current.analysis,
            )
        }

        val root = _state.value.folderStack.firstOrNull()?.document
        if (root != null) {
            viewModelScope.launch {
                val refreshedAnalysis = withContext(Dispatchers.IO) {
                    storageIndexer.removeUris(root, uris)
                    repository.clearAnalysisCache()
                    storageIndexer.load(root)
                }
                if (refreshedAnalysis != null) {
                    _state.update { current ->
                        current.copy(
                            analysis = refreshedAnalysis,
                            recentItems = refreshedAnalysis.files
                                .filterNot { it.uri in uris }
                                .sortedByDescending(FileEntry::modifiedAt)
                                .take(12),
                        )
                    }
                    warmCategoryCollectionCache(root, refreshedAnalysis)
                }
                refreshMetadata()
                if (_state.value.browserOpen && _state.value.collectionTitle == null) {
                    refreshCurrentFolder()
                }
            }
        }
    }

    /** Reconcile the visible local view after another app may have changed the storage. */
    fun onAppResumed() {
        refreshStorageVolumes()
        val snapshot = _state.value
        val root = snapshot.folderStack.firstOrNull()?.document ?: return
        if (snapshot.operationInProgress || snapshot.analyzing) return
        val title = snapshot.collectionTitle
        val category = categoryForCollectionTitle(title)
        if (title == null) {
            if (snapshot.activeSection == MainSection.Recent) {
                viewModelScope.launch {
                    val (recent, refreshed) = withContext(Dispatchers.IO) {
                        storageIndexer.recentEntries(root, 500).take(12) to storageIndexer.load(root)
                    }
                    if (_state.value.activeSection != MainSection.Recent || _state.value.browserOpen) return@launch
                    _state.update {
                        it.copy(
                            recentItems = recent,
                            analysis = refreshed ?: it.analysis,
                        )
                    }
                }
            }
            if (snapshot.browserOpen) refreshCurrentFolder()
            if (snapshot.dualPane) refreshSecondaryFolder()
            refreshMetadata()
            return
        }

        invalidateCategoryCollectionCache()
        if (category == null) {
            if (title == "Избранное") {
                viewModelScope.launch {
                    val favorites = withContext(Dispatchers.IO) { repository.favoriteEntries() }
                    if (_state.value.collectionTitle != title) return@launch
                    _state.update {
                        it.copy(
                            favoriteItems = favorites,
                            favoriteUris = favorites.map(FileEntry::uri).toSet(),
                            items = favorites,
                            collectionGroups = buildSourceGroups(favorites),
                        )
                    }
                    refreshMetadata()
                }
                return
            }
            refreshOpenCollectionAfterIndexChange(title)
            refreshMetadata()
            return
        }
        collectionLoadJob?.cancel()
        viewModelScope.launch {
            val refreshed = withContext(Dispatchers.IO) {
                // Resolving the category also prunes Room rows deleted by another app.
                storageIndexer.categoryEntries(root, category)
                storageIndexer.load(root)
            }
            if (_state.value.collectionTitle != title) return@launch
            if (refreshed != null) {
                _state.update {
                    it.copy(
                        analysis = refreshed,
                        recentItems = refreshed.files.sortedByDescending(FileEntry::modifiedAt).take(12),
                    )
                }
            }
            openCategory(category)
            refreshMetadata()
        }
    }

    fun refreshCurrentFolder() {
        val directory = _state.value.folderStack.lastOrNull()?.document ?: return
        val requestedUri = directory.uri
        collectionLoadJob?.cancel()
        folderLoadJob?.cancel()
        folderLoadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching { withContext(Dispatchers.IO) { repository.listChildren(directory) } }
                .onSuccess { items ->
                    if (_state.value.folderStack.lastOrNull()?.document?.uri != requestedUri) return@onSuccess
                    _state.update {
                        it.copy(
                            loading = false,
                            items = items,
                            collectionTitle = null,
                            collectionGroups = emptyList(),
                            duplicateOriginalUris = emptySet(),
                            recentItems = (items.filterNot(FileEntry::isDirectory) + it.recentItems)
                                .distinctBy(FileEntry::uri)
                                .sortedByDescending(FileEntry::modifiedAt)
                                .take(12),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    if (_state.value.folderStack.lastOrNull()?.document?.uri == requestedUri) {
                        _state.update { it.copy(loading = false) }
                        showFailure(error)
                    }
                }
        }
    }

    fun createFolder(name: String) = withCurrentDirectory { directory ->
        repository.createFolder(directory, name)
        "Папка создана"
    }

    fun rename(entry: FileEntry, name: String) = runFileOperation(invalidateAnalysis = false) {
        val root = _state.value.folderStack.firstOrNull()?.document
        val newUri = repository.rename(entry, name)
        val updated = entry.copy(
            document = entry.document,
            name = name.trim(),
            uri = newUri,
            modifiedAt = entry.document.lastModified().takeIf { it > 0L } ?: entry.modifiedAt,
        )
        if (root != null) {
            storageIndexer.replaceEntry(root, entry.uri, updated)
            updateAnalysisFromIndex(root)
        }
        replaceEntry(entry.uri, newUri) { updated }
        "Название изменено"
    }

    fun delete(entry: FileEntry) = delete(listOf(entry))

    fun delete(entries: List<FileEntry>) {
        val root = _state.value.folderStack.firstOrNull()?.document ?: return
        if (entries.isEmpty()) return
        val collectionWasOpen = _state.value.collectionTitle != null
        if (collectionWasOpen) {
            // Do not let an older asynchronous recommendation query repaint deleted rows.
            collectionLoadJob?.cancel()
            collectionLoadJob = null
        }
        val requestedUris = entries.map(FileEntry::uri).toSet()
        val deleteDelay = _state.value.deleteAnimationMode.preDeleteDelayMillis()
        operationJob = viewModelScope.launch {
            _state.update { it.copy(deletingUris = it.deletingUris + requestedUris) }
            if (deleteDelay > 0L) delay(deleteDelay)
            _state.update {
                it.copy(
                    operationInProgress = true,
                    operationLabel = "Перемещение в корзину",
                    operationProgress = 0f,
                    operationCancelable = true,
                )
            }
            val moved = mutableListOf<TrashRecord>()
            try {
                entries.forEachIndexed { index, entry ->
                    ensureActive()
                    moved += withContext(Dispatchers.IO) { repository.moveToTrash(root, entry) }
                    _state.update { it.copy(operationProgress = (index + 1f) / entries.size) }
                }
                reconcileTrashMovement(
                    root = root,
                    requestedEntries = entries,
                    moved = moved,
                    collectionWasOpen = collectionWasOpen,
                    requestedUris = requestedUris,
                    message = if (moved.size == 1) "${moved.first().originalName} перемещён в корзину"
                    else "В корзину перемещено: ${moved.size}",
                    completed = true,
                )
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    reconcileTrashMovement(
                        root = root,
                        requestedEntries = entries,
                        moved = moved,
                        collectionWasOpen = collectionWasOpen,
                        requestedUris = requestedUris,
                        message = "Операция остановлена после ${moved.size} объектов",
                        completed = false,
                    )
                }
            } catch (error: Throwable) {
                if (moved.isNotEmpty()) {
                    reconcileTrashMovement(
                        root = root,
                        requestedEntries = entries,
                        moved = moved,
                        collectionWasOpen = collectionWasOpen,
                        requestedUris = requestedUris,
                        message = "${error.message ?: "Удаление прервано"}. В корзину перемещено: ${moved.size}",
                        completed = false,
                    )
                } else {
                    _state.update {
                        it.copy(
                            operationInProgress = false,
                            operationLabel = null,
                            operationCancelable = false,
                            deletingUris = it.deletingUris - requestedUris,
                        )
                    }
                    showFailure(error)
                    refreshMetadata()
                }
            }
        }
    }

    fun undoLastTrash() {
        val records = _state.value.undoTrash
        if (records.isEmpty()) return
        restoreTrashRecords(records, successMessage = "Удаление отменено")
    }

    fun dismissUndo() {
        _state.update { it.copy(undoTrash = emptyList()) }
    }

    fun restoreTrash(record: TrashRecord) {
        restoreTrashRecords(listOf(record), successMessage = "${record.originalName} восстановлен")
    }

    fun permanentlyDelete(record: TrashRecord) = runFileOperation(
        label = "Безвозвратное удаление",
        deleteUris = setOf(record.entry.uri),
    ) {
        repository.permanentlyDelete(record)
        "${record.originalName} удалён безвозвратно"
    }

    fun emptyTrash() {
        val root = _state.value.folderStack.firstOrNull()?.document ?: return
        val trashUris = _state.value.trashRecords.map { it.entry.uri }.toSet()
        runFileOperation(label = "Очистка корзины", deleteUris = trashUris) {
            repository.emptyTrash(root)
            "Корзина очищена"
        }
    }

    fun toggleFavorites(entries: List<FileEntry>) {
        if (entries.isEmpty()) return
        viewModelScope.launch {
            val uris = withContext(Dispatchers.IO) { repository.toggleFavorites(entries) }
            val favorites = withContext(Dispatchers.IO) { repository.favoriteEntries() }
            _state.update {
                it.copy(
                    favoriteUris = uris,
                    favoriteItems = favorites,
                    message = if (entries.all { entry -> entry.uri in uris }) "Добавлено в избранное" else "Удалено из избранного",
                )
            }
        }
    }

    fun batchRename(entries: List<FileEntry>, names: List<String>) = runFileOperation(
        label = "Пакетное переименование",
        invalidateAnalysis = false,
    ) {
        val root = _state.value.folderStack.firstOrNull()?.document
        val cleaned = names.map(String::trim)
        val newUris = repository.batchRename(entries, cleaned)
        val replacements = entries.zip(cleaned).zip(newUris).map { (entryAndName, newUri) ->
            val (entry, replacement) = entryAndName
            val updated = entry.copy(
                document = entry.document,
                name = replacement,
                uri = newUri,
                modifiedAt = entry.document.lastModified().takeIf { it > 0L } ?: entry.modifiedAt,
            )
            Triple(entry.uri, newUri, updated)
        }
        if (root != null) {
            storageIndexer.replaceEntries(root, replacements.map { (oldUri, _, updated) -> oldUri to updated })
            updateAnalysisFromIndex(root)
        }
        replacements.forEach { (oldUri, newUri, updated) ->
            replaceEntry(oldUri, newUri) { updated }
        }
        "Переименовано объектов: ${entries.size}"
    }

    fun calculateHash(entry: FileEntry) {
        if (entry.isDirectory || entry.uri in _state.value.hashingUris) return
        viewModelScope.launch {
            _state.update { it.copy(hashingUris = it.hashingUris + entry.uri) }
            runCatching { withContext(Dispatchers.IO) { repository.sha256(entry) } }
                .onSuccess { hash ->
                    _state.update {
                        it.copy(
                            hashingUris = it.hashingUris - entry.uri,
                            fileHashes = it.fileHashes + (entry.uri to hash),
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(hashingUris = it.hashingUris - entry.uri) }
                    showFailure(error)
                }
        }
    }

    fun assignSystemSound(entry: FileEntry, type: SystemSoundType) {
        viewModelScope.launch {
            _state.update { it.copy(operationInProgress = true, operationLabel = "Назначение системного звука") }
            runCatching { withContext(Dispatchers.IO) { systemSoundRepository.assign(entry, type) } }
                .onSuccess { message ->
                    _state.update { it.copy(operationInProgress = false, operationLabel = null, message = message) }
                }
                .onFailure { error ->
                    _state.update { it.copy(operationInProgress = false, operationLabel = null) }
                    showFailure(error)
                }
        }
    }

    fun setLastModified(entries: List<FileEntry>, timestampMillis: Long) = runFileOperation(invalidateAnalysis = false) {
        require(entries.isNotEmpty()) { "Выберите хотя бы один файл" }
        val root = _state.value.folderStack.firstOrNull()?.document
        val completed = mutableListOf<Pair<FileEntry, FileEntry>>()
        try {
            entries.forEach { changed ->
                repository.setLastModified(changed, timestampMillis)
                completed += changed to changed.copy(modifiedAt = timestampMillis)
            }
        } catch (error: Throwable) {
            // Several providers can accept the first file and reject a later one. Persist
            // the successful prefix before reporting the error so neither Room nor the
            // visible collection keeps the old dates for files already changed on disk.
            if (completed.isNotEmpty()) {
                if (root != null) {
                    storageIndexer.replaceEntries(root, completed.map { (old, replacement) -> old.uri to replacement })
                    updateAnalysisFromIndex(root)
                }
                completed.forEach { (old, replacement) -> replaceEntry(old.uri) { replacement } }
                invalidateCategoryCollectionCache()
            }
            throw IOException(
                "${error.message ?: "Не удалось изменить дату"}. " +
                    "Дата изменена у ${completed.size} из ${entries.size} файлов",
                error,
            )
        }
        val updated = completed.map(Pair<FileEntry, FileEntry>::second)
        if (root != null) {
            storageIndexer.replaceEntries(root, entries.zip(updated).map { (old, replacement) -> old.uri to replacement })
            updateAnalysisFromIndex(root)
        }
        entries.zip(updated).forEach { (changed, replacement) ->
            replaceEntry(changed.uri) { replacement }
        }
        if (entries.size == 1) "Дата файла изменена" else "Дата изменена у ${entries.size} файлов"
    }

    fun setSortMode(mode: FileSortMode) {
        _state.update {
            if (it.sortMode == mode) it.copy(sortAscending = !it.sortAscending)
            else it.copy(
                sortMode = mode,
                sortAscending = mode == FileSortMode.Name || mode == FileSortMode.Type,
            )
        }
    }

    fun setViewMode(mode: FileViewMode) {
        _state.update { it.copy(viewMode = mode) }
    }

    fun toggleHiddenFiles() {
        val value = !_state.value.showHidden
        repository.setShowHiddenFiles(value)
        _state.update { it.copy(showHidden = value) }
    }

    fun setShowHiddenFiles(value: Boolean) {
        repository.setShowHiddenFiles(value)
        _state.update { it.copy(showHidden = value) }
    }

    fun setShowThumbnailFiles(value: Boolean) {
        repository.setShowThumbnailFiles(value)
        _state.update { current ->
            val visibleItems = if (value) current.items else current.items.filterNot(FileEntry::isThumbnailCache)
            current.copy(
                showThumbnailFiles = value,
                items = if (current.collectionTitle != null) visibleItems else current.items,
                collectionGroups = if (current.collectionTitle != null) buildSourceGroups(visibleItems) else current.collectionGroups,
            )
        }
        invalidateCategoryCollectionCache()
        val root = _state.value.folderStack.firstOrNull()?.document
        val analysis = _state.value.analysis
        if (root != null && analysis != null) warmCategoryCollectionCache(root, analysis)
    }

    fun setShowGridThumbnails(value: Boolean) {
        repository.setShowGridThumbnails(value)
        _state.update { it.copy(showGridThumbnails = value) }
    }

    fun setShowFavoritesOnHome(value: Boolean) {
        repository.setShowFavoritesOnHome(value)
        _state.update { it.copy(showFavoritesOnHome = value) }
    }

    fun setDeleteAnimationMode(value: DeleteAnimationMode) {
        repository.setDeleteAnimationMode(value)
        _state.update { it.copy(deleteAnimationMode = value) }
    }

    fun analyzeStorage() {
        if (_state.value.analyzing) return
        val root = _state.value.folderStack.firstOrNull()?.document
        if (root == null) {
            _state.update { it.copy(message = "Сначала подключите папку") }
            return
        }
        invalidateCategoryCollectionCache()
        operationJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    analyzing = true,
                    operationInProgress = true,
                    operationLabel = "Подготовка анализа…",
                    operationCancelable = true,
                    operationProgress = 0f,
                )
            }
            runCatching { withContext(Dispatchers.IO) { storageIndexer.scan(root) } }
                .onSuccess { analysis ->
                    val pendingCategory = categoryToOpenAfterAnalysis
                    categoryToOpenAfterAnalysis = null
                    val indexedCount = storageIndexer.progress.value.filesCount
                    val recent = withContext(Dispatchers.IO) { storageIndexer.recentEntries(root, 12) }
                    _state.update {
                        it.copy(
                            analyzing = false,
                            operationInProgress = false,
                            operationLabel = null,
                            operationCancelable = false,
                            analysis = analysis,
                            recentItems = recent,
                            message = "Анализ завершён: $indexedCount файлов",
                        )
                    }
                    warmCategoryCollectionCache(root, analysis)
                    if (pendingCategory != null) openCategory(pendingCategory)
                }
                .onFailure { error ->
                    categoryToOpenAfterAnalysis = null
                    _state.update {
                        it.copy(
                            analyzing = false,
                            operationInProgress = false,
                            operationLabel = null,
                            operationCancelable = false,
                        )
                    }
                    if (error is CancellationException) {
                        _state.update { it.copy(message = "Анализ остановлен; предыдущий завершённый индекс сохранён") }
                    } else showFailure(error)
                }
        }
    }

    fun openCategory(category: FileCategory) {
        val analysis = _state.value.analysis
        if (analysis == null) {
            categoryToOpenAfterAnalysis = category
            analyzeStorage()
            return
        }
        val title = when (category) {
            FileCategory.Images -> "Изображения"
            FileCategory.Video -> "Видео"
            FileCategory.Audio -> "Аудио"
            FileCategory.Documents -> "Документы"
            FileCategory.Archives -> "Архивы"
            FileCategory.Books -> "Книги"
            FileCategory.Apk -> "APK"
            FileCategory.Downloads -> "Загрузки"
            FileCategory.Camera -> "Камера"
            FileCategory.Other -> "Другие файлы"
        }
        val root = _state.value.folderStack.firstOrNull()?.document ?: return
        val showThumbnails = _state.value.showThumbnailFiles
        val cached = categoryCollectionCache[category]?.takeIf { entry ->
            entry.rootUri == root.uri.toString() &&
                entry.scannedAt == analysis.scannedAt &&
                entry.showThumbnailFiles == showThumbnails
        }

        if (cached != null) {
            collectionLoadJob?.cancel()
            _state.update {
                it.copy(
                    loading = false,
                    browserOpen = true,
                    activeSection = MainSection.Browse,
                    collectionTitle = title,
                    items = cached.items,
                    collectionGroups = cached.groups,
                    duplicateOriginalUris = emptySet(),
                )
            }
            return
        }

        // No prepared collection yet. Put a small preview from the already-loaded recent index
        // on screen immediately, then replace it with the complete Room result in the background.
        // Limiting this to 120 entries keeps the tap path tiny even when the index contains thousands.
        val previewItems = analysis.files.asSequence()
            .filter { file -> file.matchesCategory(category) && (showThumbnails || !file.isThumbnailCache()) }
            .take(120)
            .toList()
        val previewGroups = buildSourceGroups(previewItems)
        val expectedCount = analysis.categories.firstOrNull { it.category == category }?.count ?: 0
        _state.update {
            it.copy(
                loading = previewItems.isEmpty() && expectedCount > 0,
                browserOpen = true,
                activeSection = MainSection.Browse,
                collectionTitle = title,
                items = previewItems,
                collectionGroups = previewGroups,
                duplicateOriginalUris = emptySet(),
            )
        }
        collectionLoadJob?.cancel()
        collectionLoadJob = viewModelScope.launch {
            val matching = withContext(Dispatchers.IO) {
                storageIndexer.categoryEntries(root, category)
                    .filter { file -> showThumbnails || !file.isThumbnailCache() }
            }
            val groups = withContext(Dispatchers.Default) { buildSourceGroups(matching) }
            val currentAnalysis = _state.value.analysis
            if (currentAnalysis?.scannedAt == analysis.scannedAt &&
                _state.value.folderStack.firstOrNull()?.document?.uri == root.uri &&
                _state.value.showThumbnailFiles == showThumbnails
            ) {
                categoryCollectionCache[category] = CategoryCollectionCacheEntry(
                    rootUri = root.uri.toString(),
                    scannedAt = analysis.scannedAt,
                    showThumbnailFiles = showThumbnails,
                    items = matching,
                    groups = groups,
                )
            }
            if (_state.value.collectionTitle != title || !_state.value.browserOpen) return@launch
            _state.update { it.copy(loading = false, items = matching, collectionGroups = groups) }
        }
    }

    fun openFavorites() {
        val favorites = _state.value.favoriteItems
        _state.update {
            it.copy(
                browserOpen = true,
                activeSection = MainSection.Browse,
                collectionTitle = "Избранное",
                items = favorites,
                collectionGroups = buildSourceGroups(favorites),
                duplicateOriginalUris = emptySet(),
            )
        }
    }

    fun openTemporaryFiles() {
        val analysis = _state.value.analysis
        if (analysis == null) {
            analyzeStorage()
            return
        }
        val root = _state.value.folderStack.firstOrNull()?.document ?: return
        val title = "Временные файлы"
        _state.update {
            it.copy(
                loading = true,
                browserOpen = true,
                activeSection = MainSection.Cleanup,
                collectionTitle = title,
                items = emptyList(),
                collectionGroups = emptyList(),
                duplicateOriginalUris = emptySet(),
            )
        }
        collectionLoadJob?.cancel()
        collectionLoadJob = viewModelScope.launch {
            val temporary = withContext(Dispatchers.IO) { storageIndexer.temporaryEntries(root) }
            val groups = withContext(Dispatchers.Default) { buildSourceGroups(temporary) }
            if (_state.value.collectionTitle != title || !_state.value.browserOpen) return@launch
            _state.update { it.copy(loading = false, items = temporary, collectionGroups = groups) }
        }
    }

    fun openLargeFiles() {
        val analysis = _state.value.analysis
        if (analysis == null) {
            analyzeStorage()
            return
        }
        val root = _state.value.folderStack.firstOrNull()?.document ?: return
        val title = "Крупные файлы"
        _state.update {
            it.copy(
                loading = analysis.largeFiles.isEmpty() && analysis.largeFileCount > 0,
                browserOpen = true,
                activeSection = MainSection.Browse,
                collectionTitle = title,
                items = analysis.largeFiles,
                collectionGroups = emptyList(),
                duplicateOriginalUris = emptySet(),
                sortMode = FileSortMode.Size,
                sortAscending = false,
            )
        }
        collectionLoadJob?.cancel()
        collectionLoadJob = viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { storageIndexer.largeEntries(root) }
            if (_state.value.collectionTitle != title || !_state.value.browserOpen) return@launch
            _state.update { it.copy(loading = false, items = files, collectionGroups = emptyList()) }
        }
    }

    fun openDuplicates() {
        val analysis = _state.value.analysis
        if (analysis == null) {
            analyzeStorage()
            return
        }
        val originals = mutableSetOf<Uri>()
        val groups = analysis.duplicateGroups.mapIndexed { index, entries ->
            val original = chooseDuplicateOriginal(entries)
            if (original != null) originals += original.uri
            val ordered = entries.sortedWith(
                compareByDescending<FileEntry> { it.uri == original?.uri }
                    .thenBy { it.isTemporaryCandidate() }
                    .thenBy { it.modifiedAt.takeIf { value -> value > 0L } ?: Long.MAX_VALUE }
                    .thenBy { it.name.lowercase() }
            )
            FileCollectionGroup(
                title = "Набор ${index + 1} · файлов: ${entries.size}",
                entries = ordered,
            )
        }
        _state.update {
            it.copy(
                browserOpen = true,
                activeSection = MainSection.Browse,
                collectionTitle = "Дубликаты",
                items = groups.flatMap(FileCollectionGroup::entries),
                collectionGroups = groups,
                duplicateOriginalUris = originals,
                sortMode = FileSortMode.Size,
                sortAscending = false,
            )
        }
    }

    /**
     * Exact duplicates contain identical bytes, so there is no mathematically provable
     * "original". Aura marks the safest copy to keep: prefer Camera/DCIM/Pictures, avoid
     * cache/temp/messenger copies, then prefer the older timestamp.
     */
    private fun chooseDuplicateOriginal(entries: List<FileEntry>): FileEntry? = entries.minWithOrNull(
        compareBy<FileEntry> { entry ->
            when {
                entry.isTemporaryCandidate() -> 100
                entry.sourceLabel() == "Камера" -> 0
                entry.displayLocation().contains("DCIM", ignoreCase = true) -> 1
                entry.displayLocation().contains("Pictures", ignoreCase = true) -> 2
                entry.sourceLabel() == "Загрузки" -> 20
                entry.sourceLabel() in setOf("WhatsApp", "Telegram") -> 30
                else -> 10
            }
        }.thenBy { entry -> entry.modifiedAt.takeIf { it > 0L } ?: Long.MAX_VALUE }
            .thenBy { it.uri.toString() }
    )

    fun createArchive(entries: List<FileEntry>, name: String) = withCurrentDirectory { directory ->
        val archive = archiveRepository.create(entries, directory, name)
        "Архив ${archive.name ?: name} создан · объектов: ${entries.size}"
    }

    fun extractArchive(entry: FileEntry) = withCurrentDirectory { directory ->
        archiveRepository.extract(entry, directory)
        "Архив распакован"
    }

    fun putOnClipboard(entries: List<FileEntry>, mode: ClipboardMode) {
        if (entries.isEmpty()) return
        val parent = _state.value.folderStack.lastOrNull()?.document ?: return
        _state.update {
            it.copy(
                clipboard = FileClipboard(entries, parent, mode),
                message = if (mode == ClipboardMode.Copy) {
                    if (entries.size == 1) "${entries.first().name} готов к копированию"
                    else "Выбрано для копирования: ${entries.size}"
                } else {
                    if (entries.size == 1) "${entries.first().name} готов к перемещению"
                    else "Выбрано для перемещения: ${entries.size}"
                },
            )
        }
    }

    fun putOnClipboard(entry: FileEntry, mode: ClipboardMode) {
        putOnClipboard(listOf(entry), mode)
    }

    fun clearClipboard() {
        _state.update { it.copy(clipboard = null) }
    }

    fun paste() {
        val current = _state.value
        val clipboard = current.clipboard ?: return
        val destination = current.folderStack.lastOrNull()?.document ?: return

        val destinationContainsSource = clipboard.entries.any { entry ->
            entry.isDirectory && current.folderStack.any { it.document.uri == entry.uri }
        }
        if (destinationContainsSource) {
            _state.update { it.copy(message = "Нельзя поместить папку внутрь самой себя") }
            return
        }

        val controller = TransferController()
        transferController = controller
        val request = TransferRequest(
            type = if (clipboard.mode == ClipboardMode.Copy) TransferType.COPY else TransferType.MOVE,
            sources = clipboard.entries.map { entry ->
                TransferSource.Local(
                    uri = entry.uri,
                    parentUri = entry.parentUri,
                    name = entry.name,
                    size = entry.size,
                    modifiedAt = entry.modifiedAt,
                    isDirectory = entry.isDirectory,
                    mimeType = entry.mimeType,
                )
            },
            destination = TransferDestination.Local(destination.uri),
            conflictPolicy = TransferConflictPolicy.ASK,
            preserveModifiedTime = true,
        )
        operationJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    operationInProgress = true,
                    operationLabel = if (clipboard.mode == ClipboardMode.Copy) "Копирование" else "Перемещение",
                    operationProgress = 0f,
                    operationCancelable = true,
                )
            }
            try {
                withContext(Dispatchers.IO) { transferEngine.execute(request, controller) }
                withContext(Dispatchers.IO) { repository.clearAnalysisCache() }
                _state.update {
                    it.copy(
                        operationInProgress = false,
                        operationLabel = null,
                        operationCancelable = false,
                        transferPaused = false,
                        clipboard = null,
                        analysis = null,
                        message = if (clipboard.mode == ClipboardMode.Copy) {
                            "Копирование завершено"
                        } else {
                            "Перемещение завершено"
                        },
                    )
                }
                refreshCurrentFolder()
            } catch (_: CancellationException) {
                withContext(Dispatchers.IO) { repository.clearAnalysisCache() }
                _state.update {
                    it.copy(
                        operationInProgress = false,
                        operationLabel = null,
                        operationCancelable = false,
                        transferPaused = false,
                        clipboard = null,
                        analysis = null,
                        message = "Операция остановлена",
                    )
                }
                refreshCurrentFolder()
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        operationInProgress = false,
                        operationLabel = null,
                        operationCancelable = false,
                        transferPaused = false,
                    )
                }
                showFailure(error)
            } finally {
                transferController = null
            }
        }
    }

    fun cancelOperation() {
        transferController?.cancel()
        operationJob?.cancel()
    }

    fun pauseOperation() {
        transferController?.pause()
        _state.update { it.copy(transferPaused = transferController != null) }
    }

    fun resumeOperation() {
        transferController?.resume()
        _state.update { it.copy(transferPaused = false) }
    }

    fun resolveTransferConflict(policy: TransferConflictPolicy, applyToAll: Boolean) {
        transferEngine.resolveConflict(TransferConflictDecision(policy, applyToAll))
    }

    fun connectFtp(profile: FtpProfile, save: Boolean = true) {
        if (_state.value.ftpLoading) return
        viewModelScope.launch {
            _state.update { it.copy(ftpLoading = true, ftpProfile = profile) }
            runCatching {
                withContext(Dispatchers.IO) {
                    if (save) networkProfileRepository.save(profile)
                    ftpRepository.connect(profile)
                }
            }.onSuccess { (path, items) ->
                _state.update {
                    it.copy(
                        ftpProfile = profile,
                        ftpConnected = true,
                        ftpLoading = false,
                        ftpPath = path,
                        ftpItems = items,
                        networkProfiles = networkProfileRepository.profiles(),
                        message = "FTP подключён: ${profile.name}",
                    )
                }
                startFtpKeepAlive()
            }.onFailure { error ->
                _state.update { it.copy(ftpConnected = false, ftpLoading = false, ftpItems = emptyList()) }
                showFailure(error)
            }
        }
    }

    fun disconnectFtp() {
        ftpKeepAliveJob?.cancel()
        ftpKeepAliveJob = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ftpRepository.disconnect() }
            _state.update {
                it.copy(ftpConnected = false, ftpItems = emptyList(), ftpPath = "/", message = "FTP отключён")
            }
        }
    }

    fun refreshFtp() = loadFtpPath(_state.value.ftpPath)

    fun openFtpEntry(entry: FtpEntry) {
        if (entry.isDirectory) loadFtpPath(entry.path)
    }

    fun navigateFtpBack(): Boolean {
        val path = _state.value.ftpPath
        if (path == "/") return false
        val parent = path.substringBeforeLast('/', "").ifBlank { "/" }
        loadFtpPath(parent)
        return true
    }

    fun createFtpFolder(name: String) = runFtpOperation("Создание папки") {
        val (path, items) = ftpRepository.createDirectory(name)
        _state.update { it.copy(ftpPath = path, ftpItems = items) }
        "Папка создана на FTP"
    }

    fun renameFtp(entry: FtpEntry, name: String) = runFtpOperation("Переименование") {
        val (path, items) = ftpRepository.rename(entry, name)
        _state.update { it.copy(ftpPath = path, ftpItems = items) }
        "Название изменено"
    }

    fun deleteFtp(entry: FtpEntry) = runFtpOperation("Удаление с FTP") {
        val (path, items) = ftpRepository.delete(entry)
        _state.update { it.copy(ftpPath = path, ftpItems = items) }
        "${entry.name} удалён с FTP"
    }

    fun uploadToFtp(uris: List<Uri>) {
        if (uris.isEmpty()) return
        runFtpOperation("Загрузка на FTP") {
            val count = ftpRepository.upload(uris)
            val (path, items) = ftpRepository.list(_state.value.ftpPath)
            _state.update { it.copy(ftpPath = path, ftpItems = items) }
            "Загружено файлов: $count"
        }
    }

    fun downloadFromFtp(entry: FtpEntry) {
        val destination = _state.value.folderStack.lastOrNull()?.document
        val root = _state.value.folderStack.firstOrNull()?.document
        if (destination == null) {
            _state.update { it.copy(message = "Сначала подключите локальную папку для скачивания") }
            return
        }
        runFtpOperation("Скачивание ${entry.name}") {
            val downloaded = ftpRepository.download(entry, destination)
            if (root != null) {
                val indexedEntry = FileEntry(
                    document = downloaded,
                    name = downloaded.name ?: entry.name,
                    uri = downloaded.uri,
                    isDirectory = false,
                    mimeType = downloaded.type,
                    size = downloaded.length(),
                    modifiedAt = downloaded.lastModified(),
                    parentUri = destination.uri,
                )
                storageIndexer.replaceEntry(root, indexedEntry.uri, indexedEntry)
                updateAnalysisFromIndex(root)
                withContext(Dispatchers.Main) {
                    invalidateCategoryCollectionCache()
                    _state.value.analysis?.let { warmCategoryCollectionCache(root, it) }
                    refreshCurrentFolder()
                }
            } else {
                refreshCurrentFolder()
            }
            "${entry.name} скачан в ${_state.value.folderStack.lastOrNull()?.label ?: "локальную папку"}"
        }
    }

    private fun loadFtpPath(path: String) {
        if (_state.value.ftpLoading) return
        viewModelScope.launch {
            _state.update { it.copy(ftpLoading = true) }
            runCatching { withContext(Dispatchers.IO) { ftpRepository.list(path) } }
                .onSuccess { (actualPath, items) ->
                    _state.update {
                        it.copy(ftpConnected = true, ftpLoading = false, ftpPath = actualPath, ftpItems = items)
                    }
                    startFtpKeepAlive()
                }
                .onFailure { error ->
                    _state.update { it.copy(ftpConnected = false, ftpLoading = false) }
                    showFailure(error)
                }
        }
    }

    private fun runFtpOperation(label: String, block: suspend () -> String) {
        if (_state.value.ftpTransferLabel != null) {
            _state.update { it.copy(message = "Сначала дождитесь текущей FTP-операции") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(ftpTransferLabel = label) }
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { message ->
                    _state.update { it.copy(ftpConnected = true, ftpTransferLabel = null, message = message) }
                    startFtpKeepAlive()
                }
                .onFailure { error ->
                    _state.update { it.copy(ftpTransferLabel = null) }
                    showFailure(error)
                }
        }
    }

    private fun startFtpKeepAlive() {
        ftpKeepAliveJob?.cancel()
        ftpKeepAliveJob = viewModelScope.launch {
            while (true) {
                delay(25_000L)
                if (_state.value.ftpTransferLabel != null) continue
                val alive = withContext(Dispatchers.IO) { ftpRepository.keepAlive() }
                _state.update { it.copy(ftpConnected = alive) }
                if (!alive) {
                    _state.update { it.copy(message = "FTP-связь прервалась — Aura переподключится при следующей команде") }
                    break
                }
            }
        }
    }

    fun scanLan() {
        if (_state.value.lanScanning) return
        viewModelScope.launch {
            _state.update { it.copy(lanScanning = true) }
            runCatching { withContext(Dispatchers.IO) { lanDiscoveryRepository.scan() } }
                .onSuccess { devices ->
                    _state.update {
                        it.copy(
                            lanScanning = false,
                            lanDevices = devices,
                            message = if (devices.isEmpty()) {
                                "Устройства не найдены. Проверьте Wi‑Fi, общий доступ и изоляцию клиентов в роутере."
                            } else null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(lanScanning = false) }
                    showFailure(error)
                }
        }
    }

    fun connectSmb(profile: SmbProfile) {
        if (_state.value.smbLoading) return
        viewModelScope.launch {
            val normalized = profile.copy(share = profile.share.trim().trim('/', '\\'))
            withContext(Dispatchers.IO) { networkProfileRepository.save(normalized) }
            _state.update {
                it.copy(
                    smbLoading = true,
                    smbProfile = normalized,
                    smbShares = emptyList(),
                    smbItems = emptyList(),
                    networkProfiles = networkProfileRepository.profiles(),
                )
            }
            if (normalized.share.isBlank()) {
                runCatching { withContext(Dispatchers.IO) { smbRepository.discoverShares(normalized) } }
                    .onSuccess { shares ->
                        _state.update {
                            it.copy(
                                smbLoading = false,
                                smbConnected = true,
                                smbShares = shares,
                                smbPath = "/",
                                message = if (shares.isEmpty()) {
                                    "SMB подключён, но обычные общие папки не найдены. Можно указать шару в расширенных настройках."
                                } else null,
                            )
                        }
                    }
                    .onFailure { error ->
                        _state.update { it.copy(smbLoading = false, smbConnected = false, smbShares = emptyList()) }
                        showFailure(error)
                    }
            } else {
                runCatching { withContext(Dispatchers.IO) { smbRepository.connect(normalized) } }
                    .onSuccess { (path, items) ->
                        _state.update {
                            it.copy(
                                smbLoading = false,
                                smbConnected = true,
                                smbPath = path,
                                smbItems = items,
                                message = "SMB подключён: ${normalized.name}",
                            )
                        }
                    }
                    .onFailure { error ->
                        _state.update { it.copy(smbLoading = false, smbConnected = false, smbItems = emptyList()) }
                        showFailure(error)
                    }
            }
        }
    }

    fun selectSmbShare(shareName: String) {
        if (_state.value.smbLoading) return
        viewModelScope.launch {
            _state.update { it.copy(smbLoading = true) }
            runCatching { withContext(Dispatchers.IO) { smbRepository.connectShare(shareName) } }
                .onSuccess { (path, items) ->
                    _state.update {
                        it.copy(
                            smbLoading = false,
                            smbConnected = true,
                            smbProfile = it.smbProfile?.copy(share = shareName),
                            smbPath = path,
                            smbItems = items,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(smbLoading = false, smbItems = emptyList()) }
                    showFailure(error)
                }
        }
    }

    fun disconnectSmb() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { smbRepository.disconnect() }
            _state.update {
                it.copy(
                    smbConnected = false,
                    smbShares = emptyList(),
                    smbItems = emptyList(),
                    smbPath = "/",
                    message = "SMB отключён",
                )
            }
        }
    }

    fun refreshSmb() {
        val profile = _state.value.smbProfile ?: return
        if (profile.share.isBlank()) connectSmb(profile) else loadSmbPath(_state.value.smbPath)
    }

    fun openSmbEntry(entry: SmbEntry) {
        if (entry.isDirectory) loadSmbPath(entry.path)
    }

    fun navigateSmbBack(): Boolean {
        val path = _state.value.smbPath.trimEnd('/')
        if (path.isBlank()) {
            val state = _state.value
            if (state.smbProfile?.share.isNullOrBlank() || state.smbShares.isEmpty()) return false
            viewModelScope.launch {
                withContext(Dispatchers.IO) { smbRepository.returnToShareList() }
                _state.update {
                    it.copy(
                        smbProfile = it.smbProfile?.copy(share = ""),
                        smbItems = emptyList(),
                        smbPath = "/",
                    )
                }
            }
            return true
        }
        val parent = path.substringBeforeLast('/', "").ifBlank { "/" }
        loadSmbPath(parent)
        return true
    }

    fun createSmbFolder(name: String) = runSmbMutation("Создание папки") {
        smbRepository.createDirectory(name)
    }

    fun renameSmb(entry: SmbEntry, name: String) = runSmbMutation("Переименование") {
        smbRepository.rename(entry, name)
    }

    fun deleteSmb(entry: SmbEntry, recursive: Boolean) = runSmbMutation(
        if (entry.isDirectory) "Удаление папки" else "Удаление файла"
    ) {
        smbRepository.delete(entry, recursive)
    }

    fun uploadToSmb(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.smbTransferLabel != null) return
        val sources = uris.mapNotNull { uri ->
            val document = runCatching { DocumentFile.fromTreeUri(getApplication(), uri) }.getOrNull()
                ?: runCatching { DocumentFile.fromSingleUri(getApplication(), uri) }.getOrNull()
            document?.let {
                TransferSource.Local(
                    uri = uri,
                    parentUri = null,
                    name = it.name ?: "Без имени",
                    size = it.length(),
                    modifiedAt = it.lastModified(),
                    isDirectory = it.isDirectory,
                    mimeType = it.type,
                )
            }
        }
        if (sources.size != uris.size) {
            _state.update { it.copy(message = "Некоторые выбранные файлы недоступны") }
            return
        }
        runNetworkTransfer(
            request = TransferRequest(
                type = TransferType.UPLOAD,
                sources = sources,
                destination = TransferDestination.Smb(_state.value.smbPath),
                conflictPolicy = TransferConflictPolicy.KEEP_BOTH,
            ),
            initialLabel = "Подготовка загрузки",
            successMessage = "На SMB загружено файлов: ${uris.size}",
            refreshSmb = true,
        )
    }

    fun downloadFromSmb(entry: SmbEntry) {
        val destination = _state.value.folderStack.lastOrNull()?.document
        if (destination == null) {
            _state.update { it.copy(message = "Сначала подключите локальную папку для скачивания") }
            return
        }
        if (_state.value.smbTransferLabel != null) return
        runNetworkTransfer(
            request = TransferRequest(
                type = TransferType.DOWNLOAD,
                sources = listOf(
                    TransferSource.Smb(
                        path = entry.path,
                        name = entry.name,
                        size = entry.size,
                        modifiedAt = entry.modifiedAt,
                        isDirectory = entry.isDirectory,
                    )
                ),
                destination = TransferDestination.Local(destination.uri),
                conflictPolicy = TransferConflictPolicy.KEEP_BOTH,
            ),
            initialLabel = "Скачивание ${entry.name}",
            successMessage = "${entry.name} скачан в ${_state.value.folderStack.lastOrNull()?.label ?: "локальную папку"}",
            refreshSmb = false,
            refreshLocal = true,
        )
    }

    private fun runNetworkTransfer(
        request: TransferRequest,
        initialLabel: String,
        successMessage: String,
        refreshSmb: Boolean,
        refreshLocal: Boolean = false,
    ) {
        val controller = TransferController()
        transferController = controller
        operationJob = viewModelScope.launch {
            _state.update { it.copy(smbTransferLabel = initialLabel) }
            try {
                withContext(Dispatchers.IO) { transferEngine.execute(request, controller) }
                _state.update { it.copy(smbTransferLabel = null, message = successMessage) }
                if (refreshSmb) loadSmbPath(_state.value.smbPath)
                if (refreshLocal) refreshCurrentFolder()
            } catch (_: CancellationException) {
                _state.update { it.copy(smbTransferLabel = null, message = "Передача остановлена") }
            } catch (error: Throwable) {
                _state.update { it.copy(smbTransferLabel = null) }
                showFailure(error)
            } finally {
                transferController = null
            }
        }
    }

    private fun loadSmbPath(path: String) {
        if (_state.value.smbLoading) return
        viewModelScope.launch {
            _state.update { it.copy(smbLoading = true) }
            runCatching { withContext(Dispatchers.IO) { smbRepository.list(path) } }
                .onSuccess { (actualPath, items) ->
                    _state.update {
                        it.copy(smbConnected = true, smbLoading = false, smbPath = actualPath, smbItems = items)
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(smbConnected = false, smbLoading = false) }
                    showFailure(error)
                }
        }
    }

    private fun runSmbMutation(
        label: String,
        block: suspend () -> Pair<String, List<SmbEntry>>,
    ) {
        if (_state.value.smbLoading || _state.value.smbTransferLabel != null) return
        viewModelScope.launch {
            _state.update { it.copy(smbLoading = true, smbTransferLabel = label) }
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { (path, items) ->
                    _state.update {
                        it.copy(
                            smbLoading = false,
                            smbTransferLabel = null,
                            smbPath = path,
                            smbItems = items,
                            message = "$label завершено",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(smbLoading = false, smbTransferLabel = null) }
                    showFailure(error)
                }
        }
    }

    fun sftpProfile(profile: NetworkProfile?): SftpProfile? = profile
        ?.takeIf { it.protocol == NetworkProtocol.SFTP }
        ?.let(networkProfileRepository::sftp)

    fun saveSftpProfile(profile: SftpProfile): NetworkProfile {
        val saved = networkProfileRepository.save(profile)
        _state.update {
            it.copy(
                networkProfiles = networkProfileRepository.profiles(),
                message = "SFTP-подключение «${saved.name}» сохранено",
            )
        }
        return saved
    }

    fun connectNetworkProfile(profile: NetworkProfile) {
        when (profile.protocol) {
            NetworkProtocol.FTP,
            NetworkProtocol.FTPS -> connectFtp(networkProfileRepository.ftp(profile), save = false)
            NetworkProtocol.SMB -> connectSmb(networkProfileRepository.smb(profile))
            NetworkProtocol.SFTP -> _state.update {
                it.copy(message = "SFTP-профиль доступен в «Расширенные возможности 0.14» → универсальные панели")
            }
        }
    }

    fun deleteNetworkProfile(profile: NetworkProfile) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { networkProfileRepository.delete(profile.id) }
            _state.update {
                it.copy(
                    networkProfiles = networkProfileRepository.profiles(),
                    message = "Подключение «${profile.name}» удалено",
                )
            }
        }
    }

    fun duplicateNetworkProfile(profile: NetworkProfile) {
        viewModelScope.launch {
            val duplicate = withContext(Dispatchers.IO) { networkProfileRepository.duplicate(profile.id) }
            _state.update {
                it.copy(
                    networkProfiles = networkProfileRepository.profiles(),
                    message = duplicate?.let { saved -> "Создано подключение «${saved.name}»" },
                )
            }
        }
    }

    fun testNetworkProfile(profile: NetworkProfile) {
        _state.update { it.copy(message = "Проверка подключения «${profile.name}»…") }
        connectNetworkProfile(profile)
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun prepareShare(entries: List<FileEntry>, onReady: (Intent) -> Unit) {
        if (entries.isEmpty()) return
        viewModelScope.launch {
            val includesFolders = entries.any(FileEntry::isDirectory)
            _state.update {
                it.copy(
                    operationInProgress = true,
                    operationLabel = if (includesFolders) "Подготовка ZIP для отправки" else "Подготовка отправки",
                    operationProgress = 0f,
                    operationCancelable = false,
                )
            }
            runCatching { withContext(Dispatchers.IO) { repository.shareIntent(entries) } }
                .onSuccess { intent ->
                    _state.update {
                        it.copy(operationInProgress = false, operationLabel = null, operationProgress = 1f)
                    }
                    onReady(intent)
                }
                .onFailure { error ->
                    _state.update { it.copy(operationInProgress = false, operationLabel = null) }
                    showFailure(error)
                }
        }
    }

    private fun restoreRoot() {
        viewModelScope.launch {
            val root = runCatching {
                withContext(Dispatchers.IO) { repository.restoreRoot() }
            }.getOrNull() ?: return@launch
            val cachedAnalysis = withContext(Dispatchers.IO) { storageIndexer.load(root) }

            _state.update {
                it.copy(
                    rootConnected = true,
                    accessMode = repository.currentAccessMode(),
                    fullAccessGranted = repository.hasFullAccess(),
                    folderStack = listOf(
                        FolderCrumb(
                            root,
                            if (repository.currentAccessMode() == StorageAccessMode.Full) "Внутренняя память"
                            else root.name ?: "Хранилище",
                        )
                    ),
                    analysis = cachedAnalysis,
                    recentItems = cachedAnalysis?.files
                        ?.sortedByDescending(FileEntry::modifiedAt)
                        ?.take(12)
                        .orEmpty(),
                )
            }
            invalidateCategoryCollectionCache()
            if (cachedAnalysis != null) warmCategoryCollectionCache(root, cachedAnalysis)
            refreshCurrentFolder()
            refreshMetadata()
        }
    }

    private fun withCurrentDirectory(block: (androidx.documentfile.provider.DocumentFile) -> String) {
        val directory = _state.value.folderStack.lastOrNull()?.document ?: return
        runFileOperation { block(directory) }
    }

    private fun refreshMetadata() {
        val root = _state.value.folderStack.firstOrNull()?.document ?: return
        viewModelScope.launch {
            val trash = runCatching { withContext(Dispatchers.IO) { repository.listTrash(root) } }.getOrDefault(emptyList())
            val favorites = runCatching { withContext(Dispatchers.IO) { repository.favoriteEntries() } }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    trashRecords = trash,
                    favoriteItems = favorites,
                    favoriteUris = favorites.map(FileEntry::uri).toSet(),
                )
            }
        }
    }

    private fun restoreTrashRecords(records: List<TrashRecord>, successMessage: String) {
        val root = _state.value.folderStack.firstOrNull()?.document ?: return
        val collectionTitle = _state.value.collectionTitle
        operationJob = viewModelScope.launch {
            val restored = mutableListOf<Pair<TrashRecord, FileEntry>>()
            _state.update { it.copy(operationInProgress = true, operationLabel = "Восстановление", operationCancelable = false) }
            try {
                records.forEach { record ->
                    val entry = withContext(Dispatchers.IO) { repository.restoreFromTrash(root, record) }
                    restored += record to entry
                    withContext(Dispatchers.IO) {
                        storageIndexer.replaceEntry(root, record.originalUri ?: entry.uri, entry)
                    }
                }
                reconcileRestoredEntries(root, restored, collectionTitle, successMessage)
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    reconcileRestoredEntries(
                        root,
                        restored,
                        collectionTitle,
                        "Восстановлено объектов: ${restored.size}",
                    )
                }
            } catch (error: Throwable) {
                if (restored.isNotEmpty()) {
                    reconcileRestoredEntries(
                        root,
                        restored,
                        collectionTitle,
                        "${error.message ?: "Восстановление прервано"}. Восстановлено: ${restored.size}",
                    )
                } else {
                    _state.update { it.copy(operationInProgress = false, operationLabel = null) }
                    showFailure(error)
                }
            }
        }
    }

    private suspend fun reconcileRestoredEntries(
        root: DocumentFile,
        restored: List<Pair<TrashRecord, FileEntry>>,
        collectionTitle: String?,
        message: String,
    ) {
        val refreshedAnalysis = withContext(Dispatchers.IO) {
            storageIndexer.replaceEntries(
                root,
                restored.map { (record, entry) -> (record.originalUri ?: entry.uri) to entry },
            )
            repository.clearAnalysisCache()
            storageIndexer.load(root)
        }
        invalidateCategoryCollectionCache()
        val restoredTrashUris = restored.map { it.first.entry.uri }.toSet()
        _state.update { current ->
            current.copy(
                operationInProgress = false,
                operationLabel = null,
                undoTrash = current.undoTrash.filterNot { it.entry.uri in restoredTrashUris },
                analysis = refreshedAnalysis ?: current.analysis,
                recentItems = refreshedAnalysis?.files
                    ?.sortedByDescending(FileEntry::modifiedAt)
                    ?.take(12)
                    ?: current.recentItems,
                message = message,
            )
        }
        refreshedAnalysis?.let { warmCategoryCollectionCache(root, it) }
        refreshMetadata()
        refreshOpenCollectionAfterIndexChange(collectionTitle)
    }

    private suspend fun reconcileTrashMovement(
        root: DocumentFile,
        requestedEntries: List<FileEntry>,
        moved: List<TrashRecord>,
        collectionWasOpen: Boolean,
        requestedUris: Set<Uri>,
        message: String,
        completed: Boolean,
    ) {
        val movedEntries = requestedEntries.take(moved.size)
        val removedUris = movedEntries.map(FileEntry::uri).toSet()
        val refreshedAnalysis = withContext(Dispatchers.IO) {
            storageIndexer.removeUris(root, removedUris)
            repository.clearAnalysisCache()
            storageIndexer.load(root)
        }
        invalidateCategoryCollectionCache()
        _state.update { current ->
            val groups = current.collectionGroups.mapNotNull { group ->
                val updated = group.copy(entries = group.entries.filterNot { it.uri in removedUris })
                val keep = if (current.collectionTitle == "Дубликаты") updated.entries.size > 1 else updated.entries.isNotEmpty()
                updated.takeIf { keep }
            }
            current.copy(
                operationInProgress = false,
                operationLabel = null,
                operationCancelable = false,
                operationProgress = if (completed) 1f else moved.size.toFloat() / requestedEntries.size.coerceAtLeast(1),
                undoTrash = moved,
                items = current.items.filterNot { it.uri in removedUris },
                secondaryItems = current.secondaryItems.filterNot { it.uri in removedUris },
                collectionGroups = groups,
                recentItems = refreshedAnalysis?.files
                    ?.sortedByDescending(FileEntry::modifiedAt)
                    ?.take(12)
                    ?: current.recentItems.filterNot { it.uri in removedUris },
                favoriteItems = current.favoriteItems.filterNot { it.uri in removedUris },
                favoriteUris = current.favoriteUris - removedUris,
                duplicateOriginalUris = current.duplicateOriginalUris - removedUris,
                fileHashes = current.fileHashes - removedUris,
                hashingUris = current.hashingUris - removedUris,
                deletingUris = current.deletingUris - requestedUris,
                analysis = refreshedAnalysis ?: current.analysis?.withoutEntries(movedEntries),
                message = message,
            )
        }
        refreshedAnalysis?.let { warmCategoryCollectionCache(root, it) }
        if (!collectionWasOpen) refreshCurrentFolder()
        refreshMetadata()
        refillRecommendationCollectionIfNeeded()
    }

    private fun updateAnalysisFromIndex(root: DocumentFile): StorageAnalysis? {
        val refreshed = storageIndexer.load(root)
        if (refreshed != null) {
            _state.update {
                it.copy(
                    analysis = refreshed,
                    recentItems = refreshed.files.sortedByDescending(FileEntry::modifiedAt).take(12),
                )
            }
        }
        return refreshed
    }

    private fun refreshOpenCollectionAfterIndexChange(title: String?) {
        when (title) {
            null -> refreshCurrentFolder()
            "Временные файлы" -> openTemporaryFiles()
            "Крупные файлы" -> openLargeFiles()
            "Дубликаты" -> openDuplicates()
            "Избранное" -> openFavorites()
            else -> categoryForCollectionTitle(title)?.let(::openCategory)
        }
    }

    private fun categoryForCollectionTitle(title: String?): FileCategory? = when (title) {
        "Изображения" -> FileCategory.Images
        "Видео" -> FileCategory.Video
        "Аудио" -> FileCategory.Audio
        "Документы" -> FileCategory.Documents
        "Архивы" -> FileCategory.Archives
        "Книги" -> FileCategory.Books
        "APK" -> FileCategory.Apk
        "Загрузки" -> FileCategory.Downloads
        "Камера" -> FileCategory.Camera
        "Другие файлы" -> FileCategory.Other
        else -> null
    }

    private fun runFileOperation(
        label: String = "Выполняется операция",
        invalidateAnalysis: Boolean = true,
        deleteUris: Set<Uri> = emptySet(),
        block: suspend () -> String,
    ) {
        val collectionWasOpen = _state.value.collectionTitle != null
        val deleteDelay = if (deleteUris.isEmpty()) 0L else _state.value.deleteAnimationMode.preDeleteDelayMillis()
        operationJob = viewModelScope.launch {
            if (deleteUris.isNotEmpty()) {
                _state.update { it.copy(deletingUris = it.deletingUris + deleteUris) }
                if (deleteDelay > 0L) delay(deleteDelay)
            }
            _state.update {
                it.copy(
                    operationInProgress = true,
                    operationLabel = label,
                    operationProgress = 0f,
                    operationCancelable = false,
                )
            }
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { message ->
                    if (invalidateAnalysis) withContext(Dispatchers.IO) { repository.clearAnalysisCache() }
                    _state.update {
                        it.copy(
                            operationInProgress = false,
                            operationLabel = null,
                            operationProgress = 1f,
                            message = message,
                            deletingUris = it.deletingUris - deleteUris,
                            analysis = if (invalidateAnalysis) null else it.analysis,
                        )
                    }
                    invalidateCategoryCollectionCache()
                    val currentRoot = _state.value.folderStack.firstOrNull()?.document
                    val currentAnalysis = _state.value.analysis
                    if (currentRoot != null && currentAnalysis != null) {
                        warmCategoryCollectionCache(currentRoot, currentAnalysis)
                    }
                    if (!collectionWasOpen) refreshCurrentFolder()
                    if (_state.value.dualPane) refreshSecondaryFolder()
                    refreshMetadata()
                }
                .onFailure { error ->
                    if (invalidateAnalysis) withContext(Dispatchers.IO) { repository.clearAnalysisCache() }
                    _state.update {
                        it.copy(
                            operationInProgress = false,
                            operationLabel = null,
                            deletingUris = it.deletingUris - deleteUris,
                            analysis = if (invalidateAnalysis) null else it.analysis,
                        )
                    }
                    invalidateCategoryCollectionCache()
                    showFailure(error)
                    if (!collectionWasOpen) refreshCurrentFolder()
                    if (_state.value.dualPane) refreshSecondaryFolder()
                    refreshMetadata()
                }
        }
    }

    private fun invalidateCategoryCollectionCache() {
        categoryCacheWarmJob?.cancel()
        categoryCacheWarmJob = null
        categoryCollectionCache.clear()
    }

    private fun warmCategoryCollectionCache(
        root: androidx.documentfile.provider.DocumentFile,
        analysis: StorageAnalysis,
    ) {
        categoryCacheWarmJob?.cancel()
        val rootUri = root.uri.toString()
        val scannedAt = analysis.scannedAt
        val showThumbnails = _state.value.showThumbnailFiles
        val counts = analysis.categories.associate { it.category to it.count }
        val priority = listOf(
            FileCategory.Images,
            FileCategory.Camera,
            FileCategory.Video,
            FileCategory.Audio,
            FileCategory.Documents,
            FileCategory.Downloads,
            FileCategory.Archives,
            FileCategory.Books,
            FileCategory.Apk,
            FileCategory.Other,
        )
        categoryCacheWarmJob = viewModelScope.launch {
            for (category in priority) {
                ensureActive()
                if ((counts[category] ?: 0) <= 0) continue
                if (categoryCollectionCache[category]?.let { cached ->
                        cached.rootUri == rootUri &&
                            cached.scannedAt == scannedAt &&
                            cached.showThumbnailFiles == showThumbnails
                    } == true
                ) continue

                val files = withContext(Dispatchers.IO) {
                    storageIndexer.categoryEntries(root, category)
                        .filter { file -> showThumbnails || !file.isThumbnailCache() }
                }
                val groups = withContext(Dispatchers.Default) { buildSourceGroups(files) }
                ensureActive()
                val current = _state.value
                if (current.folderStack.firstOrNull()?.document?.uri?.toString() != rootUri ||
                    current.analysis?.scannedAt != scannedAt ||
                    current.showThumbnailFiles != showThumbnails
                ) return@launch
                categoryCollectionCache[category] = CategoryCollectionCacheEntry(
                    rootUri = rootUri,
                    scannedAt = scannedAt,
                    showThumbnailFiles = showThumbnails,
                    items = files,
                    groups = groups,
                )
            }
        }
    }

    private fun showFailure(error: Throwable) {
        _state.update {
            it.copy(message = error.message ?: "Операция не выполнена")
        }
    }

    private fun buildSourceGroups(entries: List<FileEntry>): List<FileCollectionGroup> {
        val preferredOrder = listOf(
            "Камера",
            "Загрузки",
            "WhatsApp",
            "Telegram",
            "Снимки экрана",
            "Bluetooth",
            "Документы",
            "Миниатюры и кэш",
            "Другие папки",
        )
        return entries.groupBy(FileEntry::sourceLabel)
            .map { (title, grouped) -> FileCollectionGroup(title, grouped) }
            .sortedBy { preferredOrder.indexOf(it.title).takeIf { index -> index >= 0 } ?: preferredOrder.size }
    }

    private fun replaceEntry(uri: Uri, replacementUri: Uri = uri, transform: (FileEntry) -> FileEntry) {
        _state.update { current ->
            fun updated(entry: FileEntry): FileEntry = if (entry.uri == uri) transform(entry) else entry
            val analysis = current.analysis?.let { existing ->
                val files = existing.files.map(::updated)
                existing.copy(
                    files = files,
                    largeFiles = existing.largeFiles.map(::updated),
                    duplicateGroups = existing.duplicateGroups.map { group -> group.map(::updated) },
                )
            }
            current.copy(
                items = current.items.map(::updated),
                recentItems = current.recentItems.map(::updated),
                favoriteItems = current.favoriteItems.map(::updated),
                favoriteUris = if (uri in current.favoriteUris) current.favoriteUris - uri + replacementUri else current.favoriteUris,
                collectionGroups = current.collectionGroups.map { group -> group.copy(entries = group.entries.map(::updated)) },
                analysis = analysis,
            )
        }
    }


    private fun refillRecommendationCollectionIfNeeded() {
        if (!_state.value.browserOpen) return
        when (_state.value.collectionTitle) {
            "Временные файлы" -> openTemporaryFiles()
            "Крупные файлы" -> openLargeFiles()
        }
    }

    private fun StorageAnalysis.withoutEntries(removedEntries: Collection<FileEntry>): StorageAnalysis {
        if (removedEntries.isEmpty()) return this
        val removedUris = removedEntries.map(FileEntry::uri).toSet()
        val remaining = files.filterNot { it.uri in removedUris }
        val remainingDuplicates = duplicateGroups
            .map { group -> group.filterNot { it.uri in removedUris } }
            .filter { it.size > 1 }
        val removedBytes = removedEntries.sumOf { it.size.coerceAtLeast(0L) }
        val removedTemporary = removedEntries.filter(FileEntry::isTemporaryCandidate)
        val updatedCategories = categories.map { summary ->
            val removedInCategory = removedEntries.filter { it.matchesCategory(summary.category) }
            summary.copy(
                count = (summary.count - removedInCategory.size).coerceAtLeast(0),
                bytes = (summary.bytes - removedInCategory.sumOf { it.size.coerceAtLeast(0L) }).coerceAtLeast(0L),
            )
        }
        return copy(
            files = remaining,
            // `files` is intentionally only a Recent cache (max 2,000 items), so recomputing
            // totals from it produced the stale/incorrect second lines in Cleanup.
            totalBytes = (totalBytes - removedBytes).coerceAtLeast(0L),
            categories = updatedCategories,
            largeFiles = largeFiles.filterNot { it.uri in removedUris },
            largeFileCount = (largeFileCount - removedEntries.count { it.size >= LARGE_FILE_BYTES }).coerceAtLeast(0),
            duplicateGroups = remainingDuplicates,
            temporaryFileCount = (temporaryFileCount - removedTemporary.size).coerceAtLeast(0),
            temporaryBytes = (temporaryBytes - removedTemporary.sumOf { it.size.coerceAtLeast(0L) }).coerceAtLeast(0L),
        )
    }

    private fun transferLabel(progress: TransferProgress): String = when (progress.state) {
        TransferState.PREPARING -> "Подготовка… ${progress.currentName}"
        TransferState.RUNNING -> progress.currentName.ifBlank { "Передача файлов" }
        TransferState.PAUSED -> "Приостановлено · ${progress.currentName}"
        TransferState.CANCELLING -> "Отмена операции…"
        TransferState.COMPLETED -> "Операция завершена"
        TransferState.FAILED -> progress.error ?: "Ошибка операции"
    }

}
