package com.aurafiles.app.ui

import android.app.Application
import android.net.Uri
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurafiles.app.data.FileRepository
import com.aurafiles.app.data.FtpRepository
import com.aurafiles.app.model.ClipboardMode
import com.aurafiles.app.model.FileClipboard
import com.aurafiles.app.model.FileEntry
import com.aurafiles.app.model.FileCategory
import com.aurafiles.app.model.FileSortMode
import com.aurafiles.app.model.FileViewMode
import com.aurafiles.app.model.FolderCrumb
import com.aurafiles.app.model.FtpEntry
import com.aurafiles.app.model.FtpProfile
import com.aurafiles.app.model.MainSection
import com.aurafiles.app.model.StorageSnapshot
import com.aurafiles.app.model.StorageAnalysis
import com.aurafiles.app.model.StorageAccessMode
import com.aurafiles.app.model.TrashRecord
import com.aurafiles.app.model.category
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    val storage: StorageSnapshot = StorageSnapshot(0L, 0L),
    val analysis: StorageAnalysis? = null,
    val analyzing: Boolean = false,
    val loading: Boolean = false,
    val operationInProgress: Boolean = false,
    val operationLabel: String? = null,
    val operationProgress: Float = 0f,
    val operationCancelable: Boolean = false,
    val fileHashes: Map<Uri, String> = emptyMap(),
    val hashingUris: Set<Uri> = emptySet(),
    val undoTrash: List<TrashRecord> = emptyList(),
    val ftpProfile: FtpProfile? = null,
    val ftpConnected: Boolean = false,
    val ftpPath: String = "/",
    val ftpItems: List<FtpEntry> = emptyList(),
    val ftpLoading: Boolean = false,
    val ftpTransferLabel: String? = null,
    val message: String? = null,
)

class FileManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FileRepository(application)
    private val ftpRepository = FtpRepository(application)
    private val _state = MutableStateFlow(
        FileManagerUiState(
            storage = repository.storageSnapshot(),
            accessMode = repository.currentAccessMode(),
            fullAccessGranted = repository.hasFullAccess(),
        )
    )
    val state: StateFlow<FileManagerUiState> = _state.asStateFlow()
    private var operationJob: Job? = null
    private var ftpKeepAliveJob: Job? = null

    init {
        _state.update { it.copy(ftpProfile = ftpRepository.loadProfile()) }
        restoreRoot()
    }

    fun attachRoot(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.attachRoot(uri) }
            }.onSuccess { root ->
                _state.update {
                    it.copy(
                        rootConnected = true,
                        accessMode = StorageAccessMode.Folder,
                        fullAccessGranted = repository.hasFullAccess(),
                        browserOpen = true,
                        folderStack = listOf(FolderCrumb(root, root.name ?: "Хранилище")),
                        items = emptyList(),
                        collectionTitle = null,
                        dualPane = false,
                        secondaryFolderStack = emptyList(),
                        secondaryItems = emptyList(),
                        recentItems = emptyList(),
                        analysis = null,
                        clipboard = null,
                    )
                }
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
                    _state.update {
                        it.copy(
                            rootConnected = true,
                            browserOpen = true,
                            accessMode = StorageAccessMode.Full,
                            fullAccessGranted = true,
                            folderStack = listOf(FolderCrumb(root, "Внутренняя память")),
                            items = emptyList(),
                            collectionTitle = null,
                            dualPane = false,
                            secondaryFolderStack = emptyList(),
                            secondaryItems = emptyList(),
                            recentItems = emptyList(),
                            analysis = null,
                            clipboard = null,
                        )
                    }
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

    fun openRoot() {
        if (_state.value.folderStack.isEmpty()) {
            _state.update { it.copy(message = "Сначала выберите папку на устройстве") }
            return
        }
        _state.update { it.copy(browserOpen = true, activeSection = MainSection.Browse, collectionTitle = null) }
        refreshCurrentFolder()
    }

    fun openEntry(entry: FileEntry) {
        if (!entry.isDirectory) return
        _state.update {
            it.copy(folderStack = it.folderStack + FolderCrumb(entry.document, entry.name), collectionTitle = null)
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
        viewModelScope.launch {
            _state.update { it.copy(secondaryLoading = true) }
            runCatching { withContext(Dispatchers.IO) { repository.listChildren(directory) } }
                .onSuccess { items -> _state.update { it.copy(secondaryLoading = false, secondaryItems = items) } }
                .onFailure { error ->
                    _state.update { it.copy(secondaryLoading = false) }
                    showFailure(error)
                }
        }
    }

    fun navigateBack(): Boolean {
        val current = _state.value
        if (!current.browserOpen) return false
        if (current.folderStack.size > 1) {
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
    }

    fun refreshCurrentFolder() {
        val directory = _state.value.folderStack.lastOrNull()?.document ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching {
                withContext(Dispatchers.IO) { repository.listChildren(directory) }
            }.onSuccess { items ->
                _state.update {
                    it.copy(
                        loading = false,
                        items = items,
                        collectionTitle = null,
                        recentItems = (items.filterNot(FileEntry::isDirectory) + it.recentItems)
                            .distinctBy(FileEntry::uri)
                            .sortedByDescending(FileEntry::modifiedAt)
                            .take(12),
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(loading = false) }
                showFailure(error)
            }
        }
    }

    fun createFolder(name: String) = withCurrentDirectory { directory ->
        repository.createFolder(directory, name)
        "Папка создана"
    }

    fun rename(entry: FileEntry, name: String) = runFileOperation {
        repository.rename(entry, name)
        "Название изменено"
    }

    fun delete(entry: FileEntry) = delete(listOf(entry))

    fun delete(entries: List<FileEntry>) {
        val root = _state.value.folderStack.firstOrNull()?.document ?: return
        if (entries.isEmpty()) return
        operationJob = viewModelScope.launch {
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
                _state.update {
                    it.copy(
                        operationInProgress = false,
                        operationLabel = null,
                        operationCancelable = false,
                        operationProgress = 1f,
                        undoTrash = moved,
                        analysis = null,
                        message = if (moved.size == 1) "${moved.first().originalName} перемещён в корзину"
                        else "В корзину перемещено: ${moved.size}",
                    )
                }
                refreshCurrentFolder()
                refreshMetadata()
            } catch (_: CancellationException) {
                _state.update {
                    it.copy(
                        operationInProgress = false,
                        operationLabel = null,
                        operationCancelable = false,
                        undoTrash = moved,
                        message = "Операция остановлена после ${moved.size} объектов",
                    )
                }
                refreshCurrentFolder()
                refreshMetadata()
            } catch (error: Throwable) {
                _state.update { it.copy(operationInProgress = false, operationLabel = null, operationCancelable = false) }
                showFailure(error)
                refreshMetadata()
            }
        }
    }

    fun undoLastTrash() {
        val root = _state.value.folderStack.firstOrNull()?.document ?: return
        val records = _state.value.undoTrash
        if (records.isEmpty()) return
        operationJob = viewModelScope.launch {
            _state.update { it.copy(operationInProgress = true, operationLabel = "Восстановление", operationCancelable = false) }
            runCatching {
                withContext(Dispatchers.IO) { records.forEach { repository.restoreFromTrash(root, it) } }
            }.onSuccess {
                _state.update {
                    it.copy(
                        operationInProgress = false,
                        operationLabel = null,
                        undoTrash = emptyList(),
                        message = "Удаление отменено",
                    )
                }
                refreshCurrentFolder()
                refreshMetadata()
            }.onFailure { error ->
                _state.update { it.copy(operationInProgress = false, operationLabel = null) }
                showFailure(error)
            }
        }
    }

    fun dismissUndo() {
        _state.update { it.copy(undoTrash = emptyList()) }
    }

    fun restoreTrash(record: TrashRecord) {
        val root = _state.value.folderStack.firstOrNull()?.document ?: return
        runFileOperation("Восстановление") {
            repository.restoreFromTrash(root, record)
            "${record.originalName} восстановлен"
        }
    }

    fun permanentlyDelete(record: TrashRecord) = runFileOperation("Безвозвратное удаление") {
        repository.permanentlyDelete(record)
        "${record.originalName} удалён безвозвратно"
    }

    fun emptyTrash() {
        val root = _state.value.folderStack.firstOrNull()?.document ?: return
        runFileOperation("Очистка корзины") {
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

    fun batchRename(entries: List<FileEntry>, names: List<String>) = runFileOperation("Пакетное переименование") {
        repository.batchRename(entries, names)
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

    fun setLastModified(entries: List<FileEntry>, timestampMillis: Long) = runFileOperation {
        require(entries.isNotEmpty()) { "Выберите хотя бы один файл" }
        entries.forEach { repository.setLastModified(it, timestampMillis) }
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
        _state.update { it.copy(showHidden = !it.showHidden) }
    }

    fun analyzeStorage() {
        val root = _state.value.folderStack.firstOrNull()?.document
        if (root == null) {
            _state.update { it.copy(message = "Сначала подключите папку") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(analyzing = true) }
            runCatching { withContext(Dispatchers.IO) { repository.analyze(root) } }
                .onSuccess { analysis ->
                    _state.update {
                        it.copy(
                            analyzing = false,
                            analysis = analysis,
                            recentItems = (analysis.files.sortedByDescending(FileEntry::modifiedAt) + it.recentItems)
                                .distinctBy(FileEntry::uri)
                                .take(12),
                            message = if (analysis.limitReached) {
                                "Показаны первые 10 000 файлов"
                            } else {
                                "Анализ завершён: ${analysis.files.size} файлов"
                            },
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(analyzing = false) }
                    showFailure(error)
                }
        }
    }

    fun openCategory(category: FileCategory) {
        val analysis = _state.value.analysis
        if (analysis == null) {
            analyzeStorage()
            return
        }
        val title = when (category) {
            FileCategory.Images -> "Изображения"
            FileCategory.Video -> "Видео"
            FileCategory.Audio -> "Аудио"
            FileCategory.Documents -> "Документы"
            FileCategory.Archives -> "Архивы"
            FileCategory.Other -> "Другие файлы"
        }
        _state.update {
            it.copy(
                browserOpen = true,
                activeSection = MainSection.Browse,
                collectionTitle = title,
                items = analysis.files.filter { file -> file.category() == category },
            )
        }
    }

    fun openLargeFiles() {
        val analysis = _state.value.analysis
        if (analysis == null) {
            analyzeStorage()
            return
        }
        _state.update {
            it.copy(
                browserOpen = true,
                activeSection = MainSection.Browse,
                collectionTitle = "Крупные файлы",
                items = analysis.largeFiles,
                sortMode = FileSortMode.Size,
                sortAscending = false,
            )
        }
    }

    fun openDuplicates() {
        val analysis = _state.value.analysis
        if (analysis == null) {
            analyzeStorage()
            return
        }
        _state.update {
            it.copy(
                browserOpen = true,
                activeSection = MainSection.Browse,
                collectionTitle = "Дубликаты",
                items = analysis.duplicateGroups.flatten(),
                sortMode = FileSortMode.Size,
                sortAscending = false,
            )
        }
    }

    fun createArchive(entries: List<FileEntry>, name: String) = withCurrentDirectory { directory ->
        repository.createZip(entries, directory, name)
        if (entries.size == 1) "ZIP-архив создан" else "В архив добавлено объектов: ${entries.size}"
    }

    fun extractArchive(entry: FileEntry) = withCurrentDirectory { directory ->
        repository.extractZip(entry, directory)
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
                clipboard.entries.forEachIndexed { index, entry ->
                    ensureActive()
                    withContext(Dispatchers.IO) {
                        repository.copy(entry, destination)
                        if (clipboard.mode == ClipboardMode.Move) repository.delete(entry)
                    }
                    _state.update { it.copy(operationProgress = (index + 1f) / clipboard.entries.size) }
                }
                _state.update {
                    it.copy(
                        operationInProgress = false,
                        operationLabel = null,
                        operationCancelable = false,
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
                _state.update {
                    it.copy(
                        operationInProgress = false,
                        operationLabel = null,
                        operationCancelable = false,
                        clipboard = null,
                        analysis = null,
                        message = "Операция остановлена",
                    )
                }
                refreshCurrentFolder()
            } catch (error: Throwable) {
                _state.update { it.copy(operationInProgress = false, operationLabel = null, operationCancelable = false) }
                showFailure(error)
            }
        }
    }

    fun cancelOperation() {
        operationJob?.cancel()
    }

    fun connectFtp(profile: FtpProfile, save: Boolean = true) {
        if (_state.value.ftpLoading) return
        viewModelScope.launch {
            _state.update { it.copy(ftpLoading = true, ftpProfile = profile) }
            runCatching {
                withContext(Dispatchers.IO) {
                    if (save) ftpRepository.saveProfile(profile)
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
        if (destination == null) {
            _state.update { it.copy(message = "Сначала подключите локальную папку для скачивания") }
            return
        }
        runFtpOperation("Скачивание ${entry.name}") {
            ftpRepository.download(entry, destination)
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
                )
            }
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

    private fun runFileOperation(label: String = "Выполняется операция", block: suspend () -> String) {
        operationJob = viewModelScope.launch {
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
                    _state.update {
                        it.copy(
                            operationInProgress = false,
                            operationLabel = null,
                            operationProgress = 1f,
                            message = message,
                            analysis = null,
                        )
                    }
                    refreshCurrentFolder()
                    if (_state.value.dualPane) refreshSecondaryFolder()
                    refreshMetadata()
                }
                .onFailure { error ->
                    _state.update { it.copy(operationInProgress = false, operationLabel = null) }
                    showFailure(error)
                }
        }
    }

    private fun showFailure(error: Throwable) {
        _state.update {
            it.copy(message = error.message ?: "Операция не выполнена")
        }
    }
}
