package com.aurafiles.app.ui

import android.content.ActivityNotFoundException
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Splitscreen
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import kotlinx.coroutines.withTimeoutOrNull
import com.aurafiles.app.model.ClipboardMode
import com.aurafiles.app.model.FileEntry
import com.aurafiles.app.model.FileCategory
import com.aurafiles.app.model.FileSortMode
import com.aurafiles.app.model.FileViewMode
import com.aurafiles.app.model.FtpEntry
import com.aurafiles.app.model.FtpProfile
import com.aurafiles.app.model.FtpServerConfig
import com.aurafiles.app.model.FtpServerStatus
import com.aurafiles.app.data.FtpServerService
import com.aurafiles.app.model.MainSection
import com.aurafiles.app.model.StorageSnapshot
import com.aurafiles.app.model.StorageAnalysis
import com.aurafiles.app.model.StorageAccessMode
import com.aurafiles.app.model.TrashRecord
import com.aurafiles.app.model.category
import com.aurafiles.app.ui.theme.AuraBlue
import com.aurafiles.app.ui.theme.AuraGreen
import com.aurafiles.app.ui.theme.AuraOrange
import com.aurafiles.app.ui.theme.AuraPink
import com.aurafiles.app.ui.theme.AuraPurple
import com.aurafiles.app.ui.theme.AuraRed
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.security.SecureRandom

@Composable
fun AuraFileManagerApp(viewModel: FileManagerViewModel = viewModel()) {
    val uiState by viewModel.state.collectAsState()
    val ftpServerStatus by FtpServerService.status.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var previewEntry by remember { mutableStateOf<FileEntry?>(null) }
    var pendingServerConfig by remember { mutableStateOf<FtpServerConfig?>(null) }
    val treeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri -> if (uri != null) viewModel.attachRoot(uri) },
    )
    val fullAccessSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { viewModel.refreshFullAccessStatus() },
    )
    val legacyStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { viewModel.refreshFullAccessStatus() },
    )
    val ftpUploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = viewModel::uploadToFtp,
    )
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {
            pendingServerConfig?.let { config -> startFtpServer(context, uiState, config) }
            pendingServerConfig = null
        },
    )
    val requestFullAccess = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appSettings = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            runCatching { fullAccessSettingsLauncher.launch(appSettings) }
                .onFailure {
                    fullAccessSettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
        } else {
            legacyStorageLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }

    LaunchedEffect(uiState.message, uiState.undoTrash) {
        uiState.message?.let {
            val result = withTimeoutOrNull(10_000L) {
                snackbarHostState.showSnackbar(
                    message = it,
                    actionLabel = if (uiState.undoTrash.isNotEmpty()) "Отменить" else null,
                    duration = SnackbarDuration.Indefinite,
                )
            }
            if (result == SnackbarResult.ActionPerformed) viewModel.undoLastTrash()
            else viewModel.dismissUndo()
            viewModel.consumeMessage()
        }
    }

    BackHandler(enabled = uiState.browserOpen) {
        viewModel.navigateBack()
    }
    BackHandler(
        enabled = !uiState.browserOpen && uiState.activeSection == MainSection.Network && uiState.ftpPath != "/",
    ) {
        viewModel.navigateFtpBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            AuraBottomNavigation(
                selected = uiState.activeSection,
                onSelect = viewModel::selectSection,
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = uiState.browserOpen,
            label = "main-screen",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { browserOpen ->
            if (browserOpen) {
                BrowserScreen(
                    state = uiState,
                    onBack = viewModel::navigateBack,
                    onOpen = viewModel::openEntry,
                    onOpenFile = { previewEntry = it },
                    onRefresh = viewModel::refreshCurrentFolder,
                    onCreateFolder = viewModel::createFolder,
                    onRename = viewModel::rename,
                    onDelete = viewModel::delete,
                    onClipboard = viewModel::putOnClipboard,
                    onClipboardMany = viewModel::putOnClipboard,
                    onDeleteMany = viewModel::delete,
                    onPaste = viewModel::paste,
                    onClearClipboard = viewModel::clearClipboard,
                    onSetLastModified = viewModel::setLastModified,
                    onCreateArchive = viewModel::createArchive,
                    onExtractArchive = viewModel::extractArchive,
                    onToggleFavorites = viewModel::toggleFavorites,
                    onBatchRename = viewModel::batchRename,
                    onCalculateHash = viewModel::calculateHash,
                    onShare = { entries ->
                        viewModel.prepareShare(entries) { intent -> shareFiles(context, intent) }
                    },
                    onSort = viewModel::setSortMode,
                    onViewMode = viewModel::setViewMode,
                    onToggleHidden = viewModel::toggleHiddenFiles,
                    onCancelOperation = viewModel::cancelOperation,
                    onToggleDualPane = viewModel::toggleDualPane,
                    onOpenSecondary = viewModel::openSecondaryEntry,
                    onSecondaryBack = viewModel::navigateSecondaryBack,
                    onRefreshSecondary = viewModel::refreshSecondaryFolder,
                    onCopyToOtherPane = viewModel::copyToOtherPane,
                )
            } else {
                when (uiState.activeSection) {
                    MainSection.Browse -> HomeScreen(
                        state = uiState,
                        onChooseRoot = { treeLauncher.launch(null) },
                        onOpenRoot = viewModel::openRoot,
                        onOpenFile = { previewEntry = it },
                        onReconnect = { treeLauncher.launch(null) },
                        onAnalyze = viewModel::analyzeStorage,
                        onOpenCategory = viewModel::openCategory,
                        onOpenFavorite = viewModel::openEntry,
                        onFullAccess = {
                            if (uiState.fullAccessGranted) viewModel.activateFullAccess() else requestFullAccess()
                        },
                    )
                    MainSection.Recent -> RecentScreen(
                        state = uiState,
                        onOpenFile = { previewEntry = it },
                        onRename = viewModel::rename,
                        onDeleteMany = viewModel::delete,
                        onClipboardMany = viewModel::putOnClipboard,
                        onSetLastModified = viewModel::setLastModified,
                        onCreateArchive = viewModel::createArchive,
                        onExtractArchive = viewModel::extractArchive,
                        onToggleFavorites = viewModel::toggleFavorites,
                        onBatchRename = viewModel::batchRename,
                        onCalculateHash = viewModel::calculateHash,
                        onShare = { entries ->
                            viewModel.prepareShare(entries) { intent -> shareFiles(context, intent) }
                        },
                    )
                    MainSection.Network -> FtpScreen(
                        state = uiState,
                        serverStatus = ftpServerStatus,
                        onConnect = viewModel::connectFtp,
                        onDisconnect = viewModel::disconnectFtp,
                        onRefresh = viewModel::refreshFtp,
                        onBack = viewModel::navigateFtpBack,
                        onOpen = viewModel::openFtpEntry,
                        onUpload = { ftpUploadLauncher.launch(arrayOf("*/*")) },
                        onDownload = viewModel::downloadFromFtp,
                        onCreateFolder = viewModel::createFtpFolder,
                        onRename = viewModel::renameFtp,
                        onDelete = viewModel::deleteFtp,
                        onStartServer = { config ->
                            if (
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                pendingServerConfig = config
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                startFtpServer(context, uiState, config)
                            }
                        },
                        onStopServer = { FtpServerService.stop(context) },
                    )
                    MainSection.Cleanup -> CleanupScreen(
                        state = uiState,
                        onAnalyze = viewModel::analyzeStorage,
                        onOpenLargeFiles = viewModel::openLargeFiles,
                        onOpenDuplicates = viewModel::openDuplicates,
                        onOpenCategory = viewModel::openCategory,
                        onRestoreTrash = viewModel::restoreTrash,
                        onDeleteTrash = viewModel::permanentlyDelete,
                        onEmptyTrash = viewModel::emptyTrash,
                    )
                }
            }
        }
    }
    previewEntry?.let { entry ->
        FilePreviewDialog(
            entry = entry,
            onOpenExternal = { openFile(context, entry) },
            onDismiss = { previewEntry = null },
        )
    }
}

@Composable
private fun HomeScreen(
    state: FileManagerUiState,
    onChooseRoot: () -> Unit,
    onOpenRoot: () -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onReconnect: () -> Unit,
    onAnalyze: () -> Unit,
    onOpenCategory: (FileCategory) -> Unit,
    onOpenFavorite: (FileEntry) -> Unit,
    onFullAccess: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf(AdvancedSearchFilters()) }
    var filtersOpen by remember { mutableStateOf(false) }
    val filterActive = filters.isActive
    val recent = if (query.isBlank() && !filterActive) {
        state.recentItems.take(4)
    } else {
        (state.analysis?.files ?: state.recentItems)
            .filter { entry ->
                entry.name.contains(query, ignoreCase = true) &&
                    (filters.category == null || entry.category() == filters.category) &&
                    entry.size >= filters.minBytes &&
                    (filters.maxBytes == 0L || entry.size <= filters.maxBytes) &&
                    (filters.modifiedAfter == 0L || entry.modifiedAt >= filters.modifiedAfter)
            }
            .take(10)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            LargeTitleRow(title = "Файлы")
        }
        item {
            AuraSearchField(
                query = query,
                onQueryChange = { query = it },
                onAdvanced = { filtersOpen = true },
                advancedActive = filterActive,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LocationCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Smartphone,
                    title = "На устройстве",
                    subtitle = state.folderStack.firstOrNull()?.label
                        ?: if (state.rootConnected) "Папка подключена" else "Выбрать папку",
                    tint = AuraBlue,
                    onClick = if (state.rootConnected) onOpenRoot else onChooseRoot,
                    onReconnect = if (state.rootConnected) onReconnect else null,
                )
                LocationCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Storage,
                    title = "Весь накопитель",
                    subtitle = when {
                        state.accessMode == StorageAccessMode.Full -> "Активен"
                        state.fullAccessGranted -> "Разрешён"
                        else -> "Настроить доступ"
                    },
                    tint = AuraOrange,
                    onClick = onFullAccess,
                )
            }
        }
        item {
            SectionHeader(
                title = "Категории",
                action = when {
                    state.analyzing -> "Анализ…"
                    state.analysis == null -> "Проанализировать"
                    else -> null
                },
                onAction = if (!state.analyzing && state.analysis == null) onAnalyze else null,
            )
        }
        item {
            CategoryGrid(
                state = state,
                onCategory = onOpenCategory,
                onAnalyze = onAnalyze,
            )
        }
        item {
            SectionHeader(title = if (query.isBlank() && !filterActive) "Недавние" else "Результаты поиска")
        }
        if (recent.isEmpty()) {
            item {
                EmptyRecentCard(connected = state.rootConnected, onChooseRoot = onChooseRoot)
            }
        } else {
            item {
                FileListCard(
                    entries = recent,
                    onClick = { if (it.isDirectory) onOpenRoot() else onOpenFile(it) },
                )
            }
        }
        if (state.favoriteItems.isNotEmpty() && query.isBlank() && !filterActive) {
            item { SectionHeader(title = "Избранное") }
            item {
                FileListCard(
                    entries = state.favoriteItems.take(6),
                    onClick = { entry -> if (entry.isDirectory) onOpenFavorite(entry) else onOpenFile(entry) },
                )
            }
        }
        item {
            StorageCard(storage = state.storage)
        }
    }

    if (filtersOpen) {
        AdvancedSearchDialog(
            initial = filters,
            onDismiss = { filtersOpen = false },
            onConfirm = { updated ->
                filters = updated
                filtersOpen = false
                if (state.analysis == null) onAnalyze()
            },
        )
    }
}

@Composable
private fun FtpScreen(
    state: FileManagerUiState,
    serverStatus: FtpServerStatus,
    onConnect: (FtpProfile) -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Boolean,
    onOpen: (FtpEntry) -> Unit,
    onUpload: () -> Unit,
    onDownload: (FtpEntry) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRename: (FtpEntry, String) -> Unit,
    onDelete: (FtpEntry) -> Unit,
    onStartServer: (FtpServerConfig) -> Unit,
    onStopServer: () -> Unit,
) {
    var settingsOpen by remember { mutableStateOf(false) }
    var createFolderOpen by remember { mutableStateOf(false) }
    var serverSettingsOpen by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { LargeTitleRow("FTP") }
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(Icons.Rounded.Language, if (state.ftpConnected) AuraGreen else AuraOrange)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(state.ftpProfile?.name ?: "FTP-сервер", fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    state.ftpLoading -> "Подключение…"
                                    state.ftpConnected -> "Подключено · keep-alive 25 с"
                                    state.ftpProfile != null -> "Соединение закрыто"
                                    else -> "Добавьте подключение"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                        TextButton(onClick = { settingsOpen = true }) { Text("Настроить") }
                    }
                    if (!state.ftpConnected) {
                        Button(
                            onClick = {
                                val profile = state.ftpProfile
                                if (profile == null) settingsOpen = true else onConnect(profile)
                            },
                            enabled = !state.ftpLoading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.ftpProfile == null) "Добавить FTP" else "Подключиться снова")
                        }
                    } else {
                        Text(
                            "Если Android усыпит сеть, Aura автоматически выполнит повторный вход перед следующей командой.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        item {
            FtpServerCard(
                status = serverStatus,
                rootConnected = state.rootConnected,
                onConfigure = { serverSettingsOpen = true },
                onStop = onStopServer,
            )
        }

        if (state.ftpConnected) {
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { onBack() }, enabled = state.ftpPath != "/") {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "На уровень выше")
                            }
                            Text(
                                state.ftpPath,
                                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                maxLines = 1,
                                fontWeight = FontWeight.Medium,
                            )
                            IconButton(onClick = onRefresh, enabled = !state.ftpLoading) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Обновить FTP")
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            TextButton(onClick = onUpload, enabled = state.ftpTransferLabel == null) {
                                Icon(Icons.Rounded.UploadFile, contentDescription = null)
                                Spacer(Modifier.width(5.dp))
                                Text("Загрузить")
                            }
                            TextButton(onClick = { createFolderOpen = true }, enabled = state.ftpTransferLabel == null) {
                                Icon(Icons.Rounded.Add, contentDescription = null)
                                Spacer(Modifier.width(5.dp))
                                Text("Папка")
                            }
                            TextButton(onClick = onDisconnect) { Text("Отключить") }
                        }
                    }
                }
            }
            state.ftpTransferLabel?.let { label ->
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(label, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            when {
                state.ftpLoading -> item {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.ftpItems.isEmpty() -> item {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                        Text(
                            "Папка пуста",
                            modifier = Modifier.fillMaxWidth().padding(30.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> item {
                    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                        Column {
                            state.ftpItems.forEachIndexed { index, entry ->
                                FtpFileRow(
                                    entry = entry,
                                    busy = state.ftpTransferLabel != null,
                                    onOpen = { onOpen(entry) },
                                    onDownload = { onDownload(entry) },
                                    onRename = { name -> onRename(entry, name) },
                                    onDelete = { onDelete(entry) },
                                )
                                if (index != state.ftpItems.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 66.dp),
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        FtpConnectionDialog(
            initial = state.ftpProfile,
            onDismiss = { settingsOpen = false },
            onConfirm = { profile -> settingsOpen = false; onConnect(profile) },
        )
    }
    if (createFolderOpen) {
        NameDialog(
            title = "Новая папка на FTP",
            initialValue = "",
            confirmLabel = "Создать",
            onDismiss = { createFolderOpen = false },
            onConfirm = { name -> createFolderOpen = false; onCreateFolder(name) },
        )
    }
    if (serverSettingsOpen) {
        FtpServerConfigDialog(
            onDismiss = { serverSettingsOpen = false },
            onConfirm = { config ->
                serverSettingsOpen = false
                onStartServer(config)
            },
        )
    }
}

@Composable
private fun FtpServerCard(
    status: FtpServerStatus,
    rootConnected: Boolean,
    onConfigure: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(Icons.Rounded.Smartphone, if (status.running) AuraGreen else AuraBlue)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Сервер на телефоне", fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            status.starting -> "Запуск…"
                            status.running -> "Работает · подключений: ${status.clients}"
                            status.error != null -> "Не запущен"
                            else -> "Доступ к файлам с компьютера"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            status.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            if (status.running) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Адрес", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    SelectionContainer {
                        Column {
                            status.endpoints.forEach { endpoint -> Text(endpoint, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                    PropertyRow("Логин", status.username)
                    PropertyRow("Пароль", status.password)
                    PropertyRow("Папка", status.rootLabel)
                    Text(
                        if (status.readOnly) "Режим: только чтение" else "Режим: чтение и запись",
                        color = if (status.readOnly) AuraBlue else AuraOrange,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val details = buildString {
                                appendLine(status.endpoints.firstOrNull().orEmpty())
                                appendLine("Логин: ${status.username}")
                                append("Пароль: ${status.password}")
                            }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Aura FTP", details))
                            Toast.makeText(context, "Реквизиты скопированы", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        Text("Копировать")
                    }
                    Button(modifier = Modifier.weight(1f), onClick = onStop) { Text("Остановить") }
                }
                Text(
                    "Обычный FTP не шифрует трафик — используйте только в доверенной локальной сети.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            } else {
                Button(
                    onClick = onConfigure,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = rootConnected && !status.starting,
                ) { Text(if (rootConnected) "Запустить сервер" else "Сначала подключите папку") }
                Text(
                    "Сервер публикует только подключённую в Aura папку и работает до нажатия «Остановить».",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun FtpServerConfigDialog(
    onDismiss: () -> Unit,
    onConfirm: (FtpServerConfig) -> Unit,
) {
    var port by remember { mutableStateOf("2121") }
    var username by remember { mutableStateOf("aura") }
    var password by remember { mutableStateOf(generateFtpPassword()) }
    var readOnly by remember { mutableStateOf(true) }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Smartphone, contentDescription = null) },
        title = { Text("FTP-сервер на телефоне") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Корнем станет подключённая в Aura папка. Адрес появится после запуска.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Порт") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.take(64) },
                    label = { Text("Логин") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(64) },
                    label = { Text("Пароль") },
                    singleLine = true,
                    supportingText = { Text("Показывается открыто, чтобы ввести на компьютере") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = if (readOnly) AuraGreen else AuraOrange)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Только чтение", fontWeight = FontWeight.Medium)
                        Text(
                            if (readOnly) "С компьютера нельзя изменять файлы" else "Разрешены загрузка, удаление и переименование",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                    Switch(checked = readOnly, onCheckedChange = { readOnly = it })
                }
                Text(
                    "Сервер принимает подключения только из локальной сети. Foreground-служба и Wi‑Fi lock увеличивают расход батареи.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedPort = port.toIntOrNull()
                when {
                    parsedPort == null || parsedPort !in 1024..65535 -> localError = "Порт должен быть от 1024 до 65535"
                    username.length !in 3..64 || username.any(Char::isWhitespace) -> localError = "Логин: 3–64 символа без пробелов"
                    password.length < 8 -> localError = "Пароль должен содержать не менее 8 символов"
                    password.any { it == '\r' || it == '\n' || it == '\u0000' } -> localError = "Некорректный пароль"
                    else -> onConfirm(FtpServerConfig(parsedPort, username, password, readOnly))
                }
            }) { Text("Запустить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun generateFtpPassword(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    val random = SecureRandom()
    return buildString(12) { repeat(12) { append(alphabet[random.nextInt(alphabet.length)]) } }
}

@Composable
private fun FtpFileRow(
    entry: FtpEntry,
    busy: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.isDirectory && !busy, onClick = onOpen)
            .padding(start = 14.dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.isDirectory) AppleFolderGlyph()
        else AppleFileGlyph(Icons.AutoMirrored.Rounded.InsertDriveFile, AuraBlue)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                if (entry.isDirectory) "Папка FTP" else formatBytes(entry.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = !busy) {
                Icon(Icons.Rounded.MoreHoriz, contentDescription = "Действия с ${entry.name}")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (!entry.isDirectory) {
                    DropdownMenuItem(
                        text = { Text("Скачать на устройство") },
                        leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                        onClick = { menuOpen = false; onDownload() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Переименовать") },
                    leadingIcon = { Icon(Icons.Rounded.TextFields, contentDescription = null) },
                    onClick = { menuOpen = false; renameOpen = true },
                )
                DropdownMenuItem(
                    text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; deleteOpen = true },
                )
            }
        }
    }
    if (renameOpen) {
        NameDialog(
            title = "Переименовать на FTP",
            initialValue = entry.name,
            confirmLabel = "Готово",
            onDismiss = { renameOpen = false },
            onConfirm = { renameOpen = false; onRename(it) },
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("Удалить ${entry.name} с FTP?") },
            text = { Text(if (entry.isDirectory) "Удалить можно только пустую папку." else "Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(onClick = { deleteOpen = false; onDelete() }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun FtpConnectionDialog(
    initial: FtpProfile?,
    onDismiss: () -> Unit,
    onConfirm: (FtpProfile) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "Мой FTP") }
    var host by remember(initial) { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember(initial) { mutableStateOf((initial?.port ?: 21).toString()) }
    var username by remember(initial) { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember(initial) { mutableStateOf(initial?.password.orEmpty()) }
    var tls by remember(initial) { mutableStateOf(initial?.useTls ?: false) }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Language, contentDescription = null) },
        title = { Text("FTP-подключение") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Сервер или IP") }, singleLine = true)
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Порт") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Логин") }, singleLine = true)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = if (tls) AuraGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Шифрование FTPS (TLS)", fontWeight = FontWeight.Medium)
                        Text("Включайте, если сервер поддерживает явный TLS", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = tls, onCheckedChange = { tls = it })
                }
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedPort = port.toIntOrNull()
                when {
                    host.isBlank() -> localError = "Введите адрес сервера"
                    username.isBlank() -> localError = "Введите логин"
                    parsedPort == null || parsedPort !in 1..65535 -> localError = "Некорректный порт"
                    else -> onConfirm(FtpProfile(name.ifBlank { host }, host, parsedPort, username, password, tls))
                }
            }) { Text("Сохранить и подключить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun DualPaneBrowser(
    state: FileManagerUiState,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onRefreshPrimary: () -> Unit,
    onRefreshSecondary: () -> Unit,
    onOpenPrimary: (FileEntry) -> Unit,
    onOpenSecondary: (FileEntry) -> Unit,
    onSecondaryBack: () -> Boolean,
    onCopyToOtherPane: (FileEntry, Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 10.dp, end = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
            }
            Column(Modifier.weight(1f)) {
                Text("Две панели", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Нажмите стрелку, чтобы скопировать", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onRefreshPrimary(); onRefreshSecondary() }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Обновить обе панели")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Splitscreen, contentDescription = "Закрыть две панели", tint = MaterialTheme.colorScheme.primary)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
            val wide = maxWidth >= 700.dp
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocalFilePane(
                        modifier = Modifier.weight(1f),
                        title = "Левая",
                        path = state.folderStack.lastOrNull()?.label ?: "Папка",
                        entries = state.items,
                        loading = state.loading,
                        busy = state.operationInProgress,
                        canGoBack = state.folderStack.size > 1,
                        copyForward = true,
                        onBack = onBack,
                        onOpen = onOpenPrimary,
                        onCopy = { onCopyToOtherPane(it, true) },
                    )
                    LocalFilePane(
                        modifier = Modifier.weight(1f),
                        title = "Правая",
                        path = state.secondaryFolderStack.lastOrNull()?.label ?: "Папка",
                        entries = state.secondaryItems,
                        loading = state.secondaryLoading,
                        busy = state.operationInProgress,
                        canGoBack = state.secondaryFolderStack.size > 1,
                        copyForward = false,
                        onBack = { onSecondaryBack() },
                        onOpen = onOpenSecondary,
                        onCopy = { onCopyToOtherPane(it, false) },
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocalFilePane(
                        modifier = Modifier.weight(1f),
                        title = "Верхняя",
                        path = state.folderStack.lastOrNull()?.label ?: "Папка",
                        entries = state.items,
                        loading = state.loading,
                        busy = state.operationInProgress,
                        canGoBack = state.folderStack.size > 1,
                        copyForward = true,
                        onBack = onBack,
                        onOpen = onOpenPrimary,
                        onCopy = { onCopyToOtherPane(it, true) },
                    )
                    LocalFilePane(
                        modifier = Modifier.weight(1f),
                        title = "Нижняя",
                        path = state.secondaryFolderStack.lastOrNull()?.label ?: "Папка",
                        entries = state.secondaryItems,
                        loading = state.secondaryLoading,
                        busy = state.operationInProgress,
                        canGoBack = state.secondaryFolderStack.size > 1,
                        copyForward = false,
                        onBack = { onSecondaryBack() },
                        onOpen = onOpenSecondary,
                        onCopy = { onCopyToOtherPane(it, false) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalFilePane(
    modifier: Modifier,
    title: String,
    path: String,
    entries: List<FileEntry>,
    loading: Boolean,
    busy: Boolean,
    canGoBack: Boolean,
    copyForward: Boolean,
    onBack: () -> Unit,
    onOpen: (FileEntry) -> Unit,
    onCopy: (FileEntry) -> Unit,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, enabled = canGoBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Выше")
                }
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Пусто", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(entries, key = { it.uri.toString() }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = entry.isDirectory) { onOpen(entry) }
                                .padding(start = 10.dp, top = 7.dp, bottom = 7.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FileIcon(entry)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                Text(
                                    if (entry.isDirectory) "Папка" else formatBytes(entry.size),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onCopy(entry) }, enabled = !busy) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowForward,
                                    contentDescription = "Копировать в соседнюю панель",
                                    modifier = Modifier.graphicsLayer { rotationZ = if (copyForward) 0f else 180f },
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserScreen(
    state: FileManagerUiState,
    onBack: () -> Unit,
    onOpen: (FileEntry) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onRefresh: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onRename: (FileEntry, String) -> Unit,
    onDelete: (FileEntry) -> Unit,
    onClipboard: (FileEntry, ClipboardMode) -> Unit,
    onClipboardMany: (List<FileEntry>, ClipboardMode) -> Unit,
    onDeleteMany: (List<FileEntry>) -> Unit,
    onPaste: () -> Unit,
    onClearClipboard: () -> Unit,
    onSetLastModified: (List<FileEntry>, Long) -> Unit,
    onCreateArchive: (List<FileEntry>, String) -> Unit,
    onExtractArchive: (FileEntry) -> Unit,
    onToggleFavorites: (List<FileEntry>) -> Unit,
    onBatchRename: (List<FileEntry>, List<String>) -> Unit,
    onCalculateHash: (FileEntry) -> Unit,
    onShare: (List<FileEntry>) -> Unit,
    onSort: (FileSortMode) -> Unit,
    onViewMode: (FileViewMode) -> Unit,
    onToggleHidden: () -> Unit,
    onCancelOperation: () -> Unit,
    onToggleDualPane: () -> Unit,
    onOpenSecondary: (FileEntry) -> Unit,
    onSecondaryBack: () -> Boolean,
    onRefreshSecondary: () -> Unit,
    onCopyToOtherPane: (FileEntry, Boolean) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var createDialog by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var dateDialogEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var propertiesEntry by remember { mutableStateOf<FileEntry?>(null) }
    var deleteEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var archiveEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var batchRenameEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    val context = LocalContext.current
    val shownItems = sortEntries(
        entries = state.items.filter {
            (state.showHidden || !it.name.startsWith('.')) && it.name.contains(query, ignoreCase = true)
        },
        mode = state.sortMode,
        ascending = state.sortAscending,
    )
    val selectedEntries = state.items.filter { it.uri in selectedUris }

    if (state.dualPane) {
        DualPaneBrowser(
            state = state,
            onBack = onBack,
            onClose = onToggleDualPane,
            onRefreshPrimary = onRefresh,
            onRefreshSecondary = onRefreshSecondary,
            onOpenPrimary = onOpen,
            onOpenSecondary = onOpenSecondary,
            onSecondaryBack = onSecondaryBack,
            onCopyToOtherPane = onCopyToOtherPane,
        )
        return
    }

    LaunchedEffect(state.folderStack.lastOrNull()?.document?.uri) {
        selectedUris = emptySet()
    }

    BackHandler(enabled = selectedEntries.isNotEmpty()) {
        selectedUris = emptySet()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selectedEntries.isEmpty()) {
            BrowserHeader(
                title = state.collectionTitle ?: state.folderStack.lastOrNull()?.label ?: "Хранилище",
                onBack = onBack,
                onRefresh = onRefresh,
                onCreateFolder = { createDialog = true },
                onDualPane = onToggleDualPane,
            )
        } else {
            SelectionHeader(
                count = selectedEntries.size,
                shareEnabled = selectedEntries.isNotEmpty(),
                dateEnabled = selectedEntries.none(FileEntry::isDirectory),
                extractEnabled = selectedEntries.size == 1 && selectedEntries.first().name.endsWith(".zip", true),
                allFavorited = selectedEntries.isNotEmpty() && selectedEntries.all { it.uri in state.favoriteUris },
                batchRenameEnabled = selectedEntries.size > 1,
                onClear = { selectedUris = emptySet() },
                onChangeDate = { dateDialogEntries = selectedEntries },
                onCopy = {
                    onClipboardMany(selectedEntries, ClipboardMode.Copy)
                    selectedUris = emptySet()
                },
                onMove = {
                    onClipboardMany(selectedEntries, ClipboardMode.Move)
                    selectedUris = emptySet()
                },
                onArchive = { archiveEntries = selectedEntries },
                onExtract = {
                    onExtractArchive(selectedEntries.first())
                    selectedUris = emptySet()
                },
                onFavorite = {
                    onToggleFavorites(selectedEntries)
                    selectedUris = emptySet()
                },
                onBatchRename = { batchRenameEntries = selectedEntries },
                onShare = { onShare(selectedEntries) },
                onDelete = { deleteEntries = selectedEntries },
            )
        }
        if (state.collectionTitle == null) Breadcrumbs(state = state)
        AuraSearchField(
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        BrowserControls(
            sortMode = state.sortMode,
            ascending = state.sortAscending,
            viewMode = state.viewMode,
            onSort = onSort,
            onViewMode = onViewMode,
            showHidden = state.showHidden,
            onToggleHidden = onToggleHidden,
        )
        AnimatedVisibility(
            visible = state.clipboard != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ClipboardBar(
                state = state,
                onPaste = onPaste,
                onClear = onClearClipboard,
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                shownItems.isEmpty() -> EmptyFolder(modifier = Modifier.align(Alignment.Center))
                state.viewMode == FileViewMode.List -> BrowserListContent(
                    entries = shownItems,
                    selectedUris = selectedUris,
                    selectionMode = selectedEntries.isNotEmpty(),
                    onOpen = { entry -> if (entry.isDirectory) onOpen(entry) else onOpenFile(entry) },
                    onToggleSelection = { entry ->
                        selectedUris = toggleSelection(selectedUris, entry.uri)
                    },
                    onRename = onRename,
                    onDelete = onDelete,
                    onCopy = { onClipboard(it, ClipboardMode.Copy) },
                    onMove = { onClipboard(it, ClipboardMode.Move) },
                    onShare = { onShare(listOf(it)) },
                    onProperties = { propertiesEntry = it },
                )
                else -> BrowserGridContent(
                    entries = shownItems,
                    selectedUris = selectedUris,
                    selectionMode = selectedEntries.isNotEmpty(),
                    onOpen = { entry -> if (entry.isDirectory) onOpen(entry) else onOpenFile(entry) },
                    onToggleSelection = { entry ->
                        selectedUris = toggleSelection(selectedUris, entry.uri)
                    },
                    onRename = onRename,
                    onDelete = onDelete,
                    onCopy = { onClipboard(it, ClipboardMode.Copy) },
                    onMove = { onClipboard(it, ClipboardMode.Move) },
                    onShare = { onShare(listOf(it)) },
                    onProperties = { propertiesEntry = it },
                )
            }

            if (state.operationInProgress) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(20.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    tonalElevation = 4.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                state.operationLabel ?: "Выполняется операция…",
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                            )
                            if (state.operationProgress > 0f) {
                                LinearProgressIndicator(
                                    progress = { state.operationProgress },
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    trackColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.2f),
                                    gapSize = 0.dp,
                                    drawStopIndicator = {},
                                )
                            }
                        }
                        if (state.operationCancelable) {
                            TextButton(onClick = onCancelOperation) { Text("Стоп") }
                        }
                    }
                }
            }
        }
    }

    if (createDialog) {
        NameDialog(
            title = "Новая папка",
            initialValue = "",
            confirmLabel = "Создать",
            onDismiss = { createDialog = false },
            onConfirm = {
                onCreateFolder(it)
                createDialog = false
            },
        )
    }

    if (dateDialogEntries.isNotEmpty()) {
        DateTimeEditorDialog(
            entries = dateDialogEntries,
            onDismiss = { dateDialogEntries = emptyList() },
            onConfirm = { timestamp ->
                onSetLastModified(dateDialogEntries, timestamp)
                dateDialogEntries = emptyList()
                selectedUris = emptySet()
            },
        )
    }

    if (archiveEntries.isNotEmpty()) {
        NameDialog(
            title = "Создать ZIP-архив",
            initialValue = if (archiveEntries.size == 1) archiveEntries.first().name.substringBeforeLast('.') else "Архив",
            confirmLabel = "Создать",
            onDismiss = { archiveEntries = emptyList() },
            onConfirm = { name ->
                onCreateArchive(archiveEntries, name)
                archiveEntries = emptyList()
                selectedUris = emptySet()
            },
        )
    }

    if (batchRenameEntries.isNotEmpty()) {
        BatchRenameDialog(
            entries = batchRenameEntries,
            onDismiss = { batchRenameEntries = emptyList() },
            onConfirm = { names ->
                onBatchRename(batchRenameEntries, names)
                batchRenameEntries = emptyList()
                selectedUris = emptySet()
            },
        )
    }

    propertiesEntry?.let { entry ->
        FilePropertiesDialog(
            entry = entry,
            hash = state.fileHashes[entry.uri],
            hashing = entry.uri in state.hashingUris,
            favorite = entry.uri in state.favoriteUris,
            onCalculateHash = { onCalculateHash(entry) },
            onToggleFavorite = { onToggleFavorites(listOf(entry)) },
            onDismiss = { propertiesEntry = null },
        )
    }

    if (deleteEntries.isNotEmpty()) {
        DeleteEntriesDialog(
            entries = deleteEntries,
            onDismiss = { deleteEntries = emptyList() },
            onConfirm = {
                onDeleteMany(deleteEntries)
                deleteEntries = emptyList()
                selectedUris = emptySet()
            },
        )
    }
}

@Composable
private fun RecentScreen(
    state: FileManagerUiState,
    onOpenFile: (FileEntry) -> Unit,
    onRename: (FileEntry, String) -> Unit,
    onDeleteMany: (List<FileEntry>) -> Unit,
    onClipboardMany: (List<FileEntry>, ClipboardMode) -> Unit,
    onSetLastModified: (List<FileEntry>, Long) -> Unit,
    onCreateArchive: (List<FileEntry>, String) -> Unit,
    onExtractArchive: (FileEntry) -> Unit,
    onToggleFavorites: (List<FileEntry>) -> Unit,
    onBatchRename: (List<FileEntry>, List<String>) -> Unit,
    onCalculateHash: (FileEntry) -> Unit,
    onShare: (List<FileEntry>) -> Unit,
) {
    val items = state.recentItems
    var selectedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var propertiesEntry by remember { mutableStateOf<FileEntry?>(null) }
    var deleteEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var dateEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var archiveEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var batchRenameEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    val selectedEntries = items.filter { it.uri in selectedUris }

    BackHandler(enabled = selectedEntries.isNotEmpty()) { selectedUris = emptySet() }

    Column(Modifier.fillMaxSize()) {
        if (selectedEntries.isEmpty()) {
            Box(Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 12.dp)) {
                LargeTitleRow("Недавние")
            }
        } else {
            SelectionHeader(
                count = selectedEntries.size,
                shareEnabled = true,
                dateEnabled = true,
                extractEnabled = selectedEntries.size == 1 && selectedEntries.first().name.endsWith(".zip", true),
                allFavorited = selectedEntries.all { it.uri in state.favoriteUris },
                batchRenameEnabled = selectedEntries.size > 1,
                onClear = { selectedUris = emptySet() },
                onChangeDate = { dateEntries = selectedEntries },
                onCopy = { onClipboardMany(selectedEntries, ClipboardMode.Copy); selectedUris = emptySet() },
                onMove = { onClipboardMany(selectedEntries, ClipboardMode.Move); selectedUris = emptySet() },
                onArchive = { archiveEntries = selectedEntries },
                onExtract = { onExtractArchive(selectedEntries.first()); selectedUris = emptySet() },
                onFavorite = { onToggleFavorites(selectedEntries); selectedUris = emptySet() },
                onBatchRename = { batchRenameEntries = selectedEntries },
                onShare = { onShare(selectedEntries); selectedUris = emptySet() },
                onDelete = { deleteEntries = selectedEntries },
            )
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
                EmptyStateCard(
                    icon = Icons.Rounded.Description,
                    title = "Здесь пока пусто",
                    subtitle = "Открытые файлы появятся в этом разделе",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                item {
                    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                        Column {
                            items.forEachIndexed { index, entry ->
                                BrowserFileRow(
                                    entry = entry,
                                    selected = entry.uri in selectedUris,
                                    selectionMode = selectedEntries.isNotEmpty(),
                                    onClick = { onOpenFile(entry) },
                                    onToggleSelection = { selectedUris = toggleSelection(selectedUris, entry.uri) },
                                    onRename = { onRename(entry, it) },
                                    onDelete = { deleteEntries = listOf(entry) },
                                    onCopy = { onClipboardMany(listOf(entry), ClipboardMode.Copy) },
                                    onMove = { onClipboardMany(listOf(entry), ClipboardMode.Move) },
                                    onShare = { onShare(listOf(entry)) },
                                    onProperties = { propertiesEntry = entry },
                                )
                                if (index != items.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 64.dp),
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (dateEntries.isNotEmpty()) {
        DateTimeEditorDialog(
            entries = dateEntries,
            onDismiss = { dateEntries = emptyList() },
            onConfirm = { timestamp ->
                onSetLastModified(dateEntries, timestamp)
                dateEntries = emptyList()
                selectedUris = emptySet()
            },
        )
    }
    if (archiveEntries.isNotEmpty()) {
        NameDialog(
            title = "Создать ZIP",
            initialValue = if (archiveEntries.size == 1) archiveEntries.first().name.substringBeforeLast('.') else "Архив",
            confirmLabel = "Создать",
            onDismiss = { archiveEntries = emptyList() },
            onConfirm = { name ->
                onCreateArchive(archiveEntries, name)
                archiveEntries = emptyList()
                selectedUris = emptySet()
            },
        )
    }
    if (batchRenameEntries.isNotEmpty()) {
        BatchRenameDialog(
            entries = batchRenameEntries,
            onDismiss = { batchRenameEntries = emptyList() },
            onConfirm = { names ->
                onBatchRename(batchRenameEntries, names)
                batchRenameEntries = emptyList()
                selectedUris = emptySet()
            },
        )
    }
    propertiesEntry?.let { entry ->
        FilePropertiesDialog(
            entry = entry,
            hash = state.fileHashes[entry.uri],
            hashing = entry.uri in state.hashingUris,
            favorite = entry.uri in state.favoriteUris,
            onCalculateHash = { onCalculateHash(entry) },
            onToggleFavorite = { onToggleFavorites(listOf(entry)) },
            onDismiss = { propertiesEntry = null },
        )
    }
    if (deleteEntries.isNotEmpty()) {
        DeleteEntriesDialog(
            entries = deleteEntries,
            onDismiss = { deleteEntries = emptyList() },
            onConfirm = {
                onDeleteMany(deleteEntries)
                deleteEntries = emptyList()
                selectedUris = emptySet()
            },
        )
    }
}

@Composable
private fun CleanupScreen(
    state: FileManagerUiState,
    onAnalyze: () -> Unit,
    onOpenLargeFiles: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onOpenCategory: (FileCategory) -> Unit,
    onRestoreTrash: (TrashRecord) -> Unit,
    onDeleteTrash: (TrashRecord) -> Unit,
    onEmptyTrash: () -> Unit,
) {
    val analysis = state.analysis
    var trashOpen by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 20.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { LargeTitleRow("Хранилище") }
        item { StorageCard(state.storage) }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Умный обзор", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            state.analyzing -> "Проверяем папки и сравниваем содержимое файлов…"
                            analysis == null -> "Aura просканирует только подключённую папку и ничего не удалит без подтверждения."
                            else -> "${analysis.files.size} файлов · ${formatBytes(analysis.totalBytes)}"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Button(onClick = onAnalyze, enabled = state.rootConnected && !state.analyzing) {
                        if (state.analyzing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (analysis == null) "Начать анализ" else "Обновить анализ")
                    }
                }
            }
        }
        if (analysis != null) {
            item { StorageMapCard(analysis = analysis) }
            item { SectionHeader("По типу") }
            item { CategoryGrid(state = state, onCategory = onOpenCategory, onAnalyze = onAnalyze) }
        }
        item { SectionHeader("Рекомендации") }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    RecommendationRow(
                        icon = Icons.Rounded.Storage,
                        tint = AuraOrange,
                        title = "Крупные файлы",
                        subtitle = when {
                            analysis == null -> "Сначала выполните анализ"
                            analysis.largeFiles.isEmpty() -> "Крупных файлов не найдено"
                            else -> "Показать ${analysis.largeFiles.size} самых крупных"
                        },
                        onClick = if (analysis == null) onAnalyze else onOpenLargeFiles,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 64.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                    )
                    RecommendationRow(
                        icon = Icons.Rounded.ContentCopy,
                        tint = AuraPurple,
                        title = "Дубликаты",
                        subtitle = when {
                            analysis == null -> "Сравнение по SHA-256 после анализа"
                            analysis.duplicateFileCount == 0 -> "Совпадений по содержимому нет"
                            else -> "${analysis.duplicateFileCount} лишних копий · до ${formatBytes(analysis.reclaimableDuplicateBytes)}"
                        },
                        onClick = if (analysis == null) onAnalyze else onOpenDuplicates,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 64.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                    )
                    RecommendationRow(
                        icon = Icons.Rounded.RestoreFromTrash,
                        tint = AuraBlue,
                        title = "Корзина",
                        subtitle = if (state.trashRecords.isEmpty()) "Корзина пуста" else "Объектов: ${state.trashRecords.size}",
                        onClick = { trashOpen = true },
                    )
                }
            }
        }
        if (analysis?.limitReached == true) {
            item {
                Text(
                    "Достигнут безопасный предел в 10 000 файлов. Остальные объекты не включены в отчёт.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
    if (trashOpen) {
        TrashDialog(
            records = state.trashRecords,
            onRestore = onRestoreTrash,
            onDelete = onDeleteTrash,
            onEmpty = onEmptyTrash,
            onDismiss = { trashOpen = false },
        )
    }
}

@Composable
private fun AuraBottomNavigation(selected: MainSection, onSelect: (MainSection) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 0.dp,
    ) {
        NavigationItem(
            selected = selected == MainSection.Browse,
            icon = Icons.Rounded.Folder,
            label = "Обзор",
            onClick = { onSelect(MainSection.Browse) },
        )
        NavigationItem(
            selected = selected == MainSection.Recent,
            icon = Icons.Rounded.Description,
            label = "Недавние",
            onClick = { onSelect(MainSection.Recent) },
        )
        NavigationItem(
            selected = selected == MainSection.Network,
            icon = Icons.Rounded.Language,
            label = "FTP",
            onClick = { onSelect(MainSection.Network) },
        )
        NavigationItem(
            selected = selected == MainSection.Cleanup,
            icon = Icons.Rounded.Storage,
            label = "Очистка",
            onClick = { onSelect(MainSection.Cleanup) },
        )
    }
}

@Composable
private fun RowScope.NavigationItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun LargeTitleRow(title: String) {
    var aboutOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 32.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.8).sp,
        )
        FilledIconButton(
            onClick = { aboutOpen = true },
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Icon(Icons.Rounded.MoreHoriz, contentDescription = "Дополнительные действия")
        }
    }

    if (aboutOpen) {
        AlertDialog(
            onDismissRequest = { aboutOpen = false },
            title = { Text("Aura Files") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Быстрый и аккуратный файловый менеджер для Android.")
                    PropertyRow("Разработчик", "Привалов Олег")
                    PropertyRow("Версия", "0.7.0")
                }
            },
            confirmButton = {
                TextButton(onClick = { aboutOpen = false }) { Text("Готово") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuraSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onAdvanced: (() -> Unit)? = null,
    advancedActive: Boolean = false,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("Поиск") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = onAdvanced?.let { action ->
            {
                IconButton(onClick = action) {
                    Icon(
                        Icons.Rounded.Tune,
                        contentDescription = "Фильтры поиска",
                        tint = if (advancedActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(15.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun LocationCard(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit,
    onReconnect: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBubble(icon = icon, tint = tint)
                if (onReconnect == null) {
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreHoriz, contentDescription = "Действия с хранилищем")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Выбрать другую папку") },
                                leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onReconnect()
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CategoryGrid(
    state: FileManagerUiState,
    onCategory: (FileCategory) -> Unit,
    onAnalyze: () -> Unit,
) {
    val tiles = listOf(
        Triple(FileCategory.Images, Icons.Rounded.Image, AuraPink),
        Triple(FileCategory.Video, Icons.Rounded.Movie, AuraPurple),
        Triple(FileCategory.Audio, Icons.Rounded.MusicNote, AuraGreen),
        Triple(FileCategory.Documents, Icons.Rounded.Description, AuraBlue),
        Triple(FileCategory.Archives, Icons.Rounded.Archive, AuraOrange),
        Triple(FileCategory.Other, Icons.AutoMirrored.Rounded.InsertDriveFile, MaterialTheme.colorScheme.onSurfaceVariant),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowTiles.forEach { (category, icon, tint) ->
                    val summary = state.analysis?.categories?.firstOrNull { it.category == category }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { if (state.analysis == null) onAnalyze() else onCategory(category) },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            IconBubble(icon, tint)
                            Spacer(Modifier.height(12.dp))
                            Text(categoryLabel(category), fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                when {
                                    state.analyzing -> "Анализ…"
                                    summary == null -> "Найти файлы"
                                    else -> "${summary.count} · ${formatBytes(summary.bytes)}"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun categoryLabel(category: FileCategory): String = when (category) {
    FileCategory.Images -> "Изображения"
    FileCategory.Video -> "Видео"
    FileCategory.Audio -> "Аудио"
    FileCategory.Documents -> "Документы"
    FileCategory.Archives -> "Архивы"
    FileCategory.Other -> "Другие"
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        action?.let {
            Text(
                it,
                modifier = if (onAction != null) Modifier.clickable(onClick = onAction).padding(6.dp) else Modifier,
                color = if (onAction != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun FileListCard(entries: List<FileEntry>, onClick: (FileEntry) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column {
            entries.forEachIndexed { index, entry ->
                SimpleFileRow(entry = entry, onClick = { onClick(entry) })
                if (index != entries.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 64.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SimpleFileRow(entry: FileEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(entry)
        Spacer(Modifier.width(12.dp))
        FileCopy(entry, Modifier.weight(1f))
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BrowserControls(
    sortMode: FileSortMode,
    ascending: Boolean,
    viewMode: FileViewMode,
    onSort: (FileSortMode) -> Unit,
    onViewMode: (FileViewMode) -> Unit,
    showHidden: Boolean,
    onToggleHidden: () -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            TextButton(onClick = { sortMenuOpen = true }) {
                Icon(
                    if (ascending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(sortMode.label())
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                FileSortMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.label()) },
                        trailingIcon = {
                            if (mode == sortMode) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                            }
                        },
                        onClick = {
                            sortMenuOpen = false
                            onSort(mode)
                        },
                    )
                }
            }
        }
        IconButton(onClick = onToggleHidden) {
            Icon(
                if (showHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                contentDescription = if (showHidden) "Скрытые файлы показаны" else "Показать скрытые файлы",
                tint = if (showHidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row {
                IconButton(onClick = { onViewMode(FileViewMode.List) }) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ViewList,
                        contentDescription = "Список",
                        tint = if (viewMode == FileViewMode.List) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onViewMode(FileViewMode.Grid) }) {
                    Icon(
                        Icons.Rounded.GridView,
                        contentDescription = "Сетка",
                        tint = if (viewMode == FileViewMode.Grid) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserListContent(
    entries: List<FileEntry>,
    selectedUris: Set<Uri>,
    selectionMode: Boolean,
    onOpen: (FileEntry) -> Unit,
    onToggleSelection: (FileEntry) -> Unit,
    onRename: (FileEntry, String) -> Unit,
    onDelete: (FileEntry) -> Unit,
    onCopy: (FileEntry) -> Unit,
    onMove: (FileEntry) -> Unit,
    onShare: (FileEntry) -> Unit,
    onProperties: (FileEntry) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
    ) {
        itemsIndexed(entries, key = { _, entry -> entry.uri.toString() }) { index, entry ->
            val shape = when {
                entries.size == 1 -> RoundedCornerShape(22.dp)
                index == 0 -> RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                index == entries.lastIndex -> RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
                else -> RoundedCornerShape(0.dp)
            }
            Surface(shape = shape, color = MaterialTheme.colorScheme.surface) {
                Column {
                    BrowserFileRow(
                        entry = entry,
                        selected = entry.uri in selectedUris,
                        selectionMode = selectionMode,
                        onClick = { onOpen(entry) },
                        onToggleSelection = { onToggleSelection(entry) },
                        onRename = { onRename(entry, it) },
                        onDelete = { onDelete(entry) },
                        onCopy = { onCopy(entry) },
                        onMove = { onMove(entry) },
                        onShare = { onShare(entry) },
                        onProperties = { onProperties(entry) },
                    )
                    if (index != entries.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 64.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserGridContent(
    entries: List<FileEntry>,
    selectedUris: Set<Uri>,
    selectionMode: Boolean,
    onOpen: (FileEntry) -> Unit,
    onToggleSelection: (FileEntry) -> Unit,
    onRename: (FileEntry, String) -> Unit,
    onDelete: (FileEntry) -> Unit,
    onCopy: (FileEntry) -> Unit,
    onMove: (FileEntry) -> Unit,
    onShare: (FileEntry) -> Unit,
    onProperties: (FileEntry) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(entries, key = { it.uri.toString() }) { entry ->
            GridFileTile(
                entry = entry,
                selected = entry.uri in selectedUris,
                selectionMode = selectionMode,
                onClick = { onOpen(entry) },
                onToggleSelection = { onToggleSelection(entry) },
                onRename = { onRename(entry, it) },
                onDelete = { onDelete(entry) },
                onCopy = { onCopy(entry) },
                onMove = { onMove(entry) },
                onShare = { onShare(entry) },
                onProperties = { onProperties(entry) },
            )
        }
    }
}

@Composable
private fun GridFileTile(
    entry: FileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onProperties: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .aspectRatio(1.08f)
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelection() else onClick() },
                onLongClick = onToggleSelection,
            ),
        shape = RoundedCornerShape(22.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Box {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FileIcon(entry, showThumbnail = true)
                Spacer(Modifier.height(10.dp))
                Text(
                    entry.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (entry.isDirectory) "Папка" else formatBytes(entry.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { if (selectionMode) onToggleSelection() else menuOpen = true }) {
                    Icon(
                        if (selectionMode) Icons.Rounded.CheckCircle else Icons.Rounded.MoreHoriz,
                        contentDescription = if (selectionMode) "Изменить выделение" else "Действия",
                        tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Копировать") },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                        onClick = { menuOpen = false; onCopy() },
                    )
                    DropdownMenuItem(
                        text = { Text("Переместить") },
                        leadingIcon = { Icon(Icons.Rounded.ContentCut, contentDescription = null) },
                        onClick = { menuOpen = false; onMove() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (entry.isDirectory) "Отправить как ZIP" else "Отправить") },
                        leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                        onClick = { menuOpen = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text("Свойства") },
                        leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                        onClick = { menuOpen = false; onProperties() },
                    )
                    DropdownMenuItem(
                        text = { Text("Переименовать") },
                        leadingIcon = { Icon(Icons.Rounded.TextFields, contentDescription = null) },
                        onClick = { menuOpen = false; renameOpen = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menuOpen = false; deleteOpen = true },
                    )
                }
            }
        }
    }

    if (renameOpen) {
        NameDialog(
            title = "Переименовать",
            initialValue = entry.name,
            confirmLabel = "Готово",
            onDismiss = { renameOpen = false },
            onConfirm = { onRename(it); renameOpen = false },
        )
    }
    if (deleteOpen) {
        DeleteEntriesDialog(
            entries = listOf(entry),
            onDismiss = { deleteOpen = false },
            onConfirm = { onDelete(); deleteOpen = false },
        )
    }
}

@Composable
private fun BrowserFileRow(
    entry: FileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onProperties: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                else Color.Transparent
            )
            .combinedClickable(
                onClick = {
                    if (selectionMode) onToggleSelection() else onClick()
                },
                onLongClick = onToggleSelection,
            )
            .padding(start = 14.dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(entry)
        Spacer(Modifier.width(12.dp))
        FileCopy(entry, Modifier.weight(1f))
        if (selectionMode) {
            IconButton(onClick = onToggleSelection) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = if (selected) "Снять выделение" else "Выделить",
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreHoriz, contentDescription = "Действия с ${entry.name}")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Копировать") },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                    onClick = { menuOpen = false; onCopy() },
                )
                DropdownMenuItem(
                    text = { Text("Переместить") },
                    leadingIcon = { Icon(Icons.Rounded.ContentCut, contentDescription = null) },
                    onClick = { menuOpen = false; onMove() },
                )
                DropdownMenuItem(
                    text = { Text(if (entry.isDirectory) "Отправить как ZIP" else "Отправить") },
                    leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                    onClick = { menuOpen = false; onShare() },
                )
                DropdownMenuItem(
                    text = { Text("Свойства") },
                    leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                    onClick = { menuOpen = false; onProperties() },
                )
                DropdownMenuItem(
                    text = { Text("Переименовать") },
                    leadingIcon = { Icon(Icons.Rounded.TextFields, contentDescription = null) },
                    onClick = { menuOpen = false; renameOpen = true },
                )
                DropdownMenuItem(
                    text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; deleteOpen = true },
                )
            }
        }
    }

    if (renameOpen) {
        NameDialog(
            title = "Переименовать",
            initialValue = entry.name,
            confirmLabel = "Готово",
            onDismiss = { renameOpen = false },
            onConfirm = { onRename(it); renameOpen = false },
        )
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null) },
            title = { Text("Переместить ${entry.name} в корзину?") },
            text = { Text("Объект будет перемещён в корзину, откуда его можно восстановить.") },
            confirmButton = {
                TextButton(onClick = { onDelete(); deleteOpen = false }) {
                    Text("В корзину", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SelectionHeader(
    count: Int,
    shareEnabled: Boolean,
    dateEnabled: Boolean,
    extractEnabled: Boolean,
    allFavorited: Boolean,
    batchRenameEnabled: Boolean,
    onClear: () -> Unit,
    onChangeDate: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onArchive: () -> Unit,
    onExtract: () -> Unit,
    onFavorite: () -> Unit,
    onBatchRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear) {
            Icon(Icons.Rounded.Close, contentDescription = "Снять выделение")
        }
        Text(
            "$count выбрано",
            modifier = Modifier.weight(1f),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = onShare, enabled = shareEnabled) {
            Icon(Icons.Rounded.Share, contentDescription = "Поделиться")
        }
        IconButton(onClick = onChangeDate, enabled = dateEnabled) {
            Icon(Icons.Rounded.CalendarMonth, contentDescription = "Изменить дату")
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreHoriz, contentDescription = "Другие действия")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Копировать") },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                    onClick = { menuOpen = false; onCopy() },
                )
                DropdownMenuItem(
                    text = { Text("Переместить") },
                    leadingIcon = { Icon(Icons.Rounded.ContentCut, contentDescription = null) },
                    onClick = { menuOpen = false; onMove() },
                )
                DropdownMenuItem(
                    text = { Text("Создать ZIP") },
                    leadingIcon = { Icon(Icons.Rounded.Archive, contentDescription = null) },
                    onClick = { menuOpen = false; onArchive() },
                )
                DropdownMenuItem(
                    text = { Text(if (allFavorited) "Убрать из избранного" else "В избранное") },
                    leadingIcon = {
                        Icon(if (allFavorited) Icons.Rounded.Star else Icons.Rounded.StarBorder, contentDescription = null)
                    },
                    onClick = { menuOpen = false; onFavorite() },
                )
                if (batchRenameEnabled) {
                    DropdownMenuItem(
                        text = { Text("Пакетно переименовать") },
                        leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null) },
                        onClick = { menuOpen = false; onBatchRename() },
                    )
                }
                if (extractEnabled) {
                    DropdownMenuItem(
                        text = { Text("Распаковать ZIP") },
                        leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                        onClick = { menuOpen = false; onExtract() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun DateTimeEditorDialog(
    entries: List<FileEntry>,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val context = LocalContext.current
    val initialTimestamp = entries.firstOrNull()?.modifiedAt?.takeIf { it > 0L }
        ?: System.currentTimeMillis()
    var timestamp by remember(entries) { mutableLongStateOf(initialTimestamp) }
    val formatted = remember(timestamp) {
        DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(timestamp))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) },
        title = { Text(if (entries.size == 1) "Изменить дату файла" else "Изменить дату файлов") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    if (entries.size == 1) entries.first().name else "Выбрано файлов: ${entries.size}",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(formatted, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
                            DatePickerDialog(
                                context,
                                { _, year, month, day ->
                                    val updated = Calendar.getInstance().apply {
                                        timeInMillis = timestamp
                                        set(Calendar.YEAR, year)
                                        set(Calendar.MONTH, month)
                                        set(Calendar.DAY_OF_MONTH, day)
                                    }
                                    timestamp = updated.timeInMillis
                                },
                                calendar.get(Calendar.YEAR),
                                calendar.get(Calendar.MONTH),
                                calendar.get(Calendar.DAY_OF_MONTH),
                            ).show()
                        },
                    ) { Text("Выбрать дату") }
                    TextButton(
                        onClick = {
                            val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
                            TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    val updated = Calendar.getInstance().apply {
                                        timeInMillis = timestamp
                                        set(Calendar.HOUR_OF_DAY, hour)
                                        set(Calendar.MINUTE, minute)
                                        set(Calendar.SECOND, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }
                                    timestamp = updated.timeInMillis
                                },
                                calendar.get(Calendar.HOUR_OF_DAY),
                                calendar.get(Calendar.MINUTE),
                                true,
                            ).show()
                        },
                    ) { Text("Выбрать время") }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(timestamp) }) { Text("Изменить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun FilePropertiesDialog(
    entry: FileEntry,
    hash: String?,
    hashing: Boolean,
    favorite: Boolean,
    onCalculateHash: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { FileIcon(entry) },
        title = {
            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                PropertyRow("Тип", if (entry.isDirectory) "Папка" else entry.mimeType ?: "Неизвестный")
                if (!entry.isDirectory) PropertyRow("Размер", formatBytes(entry.size))
                PropertyRow("URI", entry.uri.toString())
                PropertyRow(
                    "Изменён",
                    if (entry.modifiedAt > 0L) {
                        DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT)
                            .format(Date(entry.modifiedAt))
                    } else {
                        "Неизвестно"
                    },
                )
                if (!entry.isDirectory) {
                    when {
                        hash != null -> PropertyRow("SHA-256", hash)
                        hashing -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Вычисляем SHA-256…", fontSize = 13.sp)
                        }
                        else -> TextButton(onClick = onCalculateHash) { Text("Рассчитать SHA-256") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        dismissButton = {
            TextButton(onClick = onToggleFavorite) {
                Icon(
                    if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (favorite) "Убрать" else "В избранное")
            }
        },
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Text(value, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DeleteEntriesDialog(
    entries: List<FileEntry>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null) },
        title = {
            Text(if (entries.size == 1) "Переместить ${entries.first().name} в корзину?" else "Переместить в корзину: ${entries.size}?")
        },
        text = { Text("Объекты будут перемещены в корзину. Их можно восстановить или удалить безвозвратно позже.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("В корзину", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun FileIcon(entry: FileEntry, showThumbnail: Boolean = false) {
    if (entry.isDirectory) {
        AppleFolderGlyph()
        return
    }

    val mime = entry.mimeType.orEmpty().lowercase()
    val extension = entry.name.substringAfterLast('.', "").lowercase()
    val (icon, tint) = when {
        mime.startsWith("image/") || extension in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "svg") ->
            Icons.Rounded.Image to AuraPink
        mime.startsWith("video/") || extension in setOf("mp4", "mkv", "avi", "mov", "webm", "3gp") ->
            Icons.Rounded.Movie to AuraPurple
        mime.startsWith("audio/") || extension in setOf("mp3", "m4a", "wav", "flac", "ogg", "aac") ->
            Icons.Rounded.MusicNote to AuraGreen
        mime == "application/pdf" || extension == "pdf" -> Icons.Rounded.Description to AuraRed
        mime.contains("zip") || mime.contains("archive") || extension in setOf("zip", "rar", "7z", "tar", "gz") ->
            Icons.Rounded.Archive to AuraOrange
        mime.startsWith("text/") || extension in setOf("txt", "md", "doc", "docx", "rtf", "odt") ->
            Icons.Rounded.Description to AuraBlue
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile to MaterialTheme.colorScheme.onSurfaceVariant
    }
    if (showThumbnail) {
        FileThumbnail(
            entry = entry,
            modifier = Modifier.size(width = 44.dp, height = 40.dp),
            fallback = { AppleFileGlyph(icon = icon, tint = tint) },
        )
    } else {
        AppleFileGlyph(icon = icon, tint = tint)
    }
}

@Composable
private fun AppleFolderGlyph() {
    Box(modifier = Modifier.size(width = 44.dp, height = 40.dp)) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 3.dp)
                .width(22.dp)
                .height(11.dp)
                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                .background(AuraBlue.copy(alpha = 0.72f)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AuraBlue),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(Color.White.copy(alpha = 0.12f)),
            )
        }
    }
}

@Composable
private fun AppleFileGlyph(icon: ImageVector, tint: Color) {
    Surface(
        modifier = Modifier.size(width = 36.dp, height = 42.dp),
        shape = RoundedCornerShape(7.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 1.dp,
    ) {
        Box {
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.align(Alignment.Center).size(20.dp),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(10.dp)
                    .clip(RoundedCornerShape(bottomStart = 5.dp))
                    .background(tint.copy(alpha = 0.20f)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(tint),
            )
        }
    }
}

@Composable
private fun IconBubble(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun FileCopy(entry: FileEntry, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            entry.name,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            fileDetails(entry),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StorageCard(storage: StorageSnapshot) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Хранилище", fontWeight = FontWeight.SemiBold)
                Text(
                    "${formatBytes(storage.usedBytes)} из ${formatBytes(storage.totalBytes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { storage.usedFraction },
                modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(9.dp))
            Text(
                "Свободно ${formatBytes(storage.availableBytes)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun BrowserHeader(
    title: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCreateFolder: () -> Unit,
    onDualPane: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onBack() }) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRefresh) {
            Icon(Icons.Rounded.Refresh, contentDescription = "Обновить")
        }
        IconButton(onClick = onDualPane) {
            Icon(Icons.Rounded.Splitscreen, contentDescription = "Две панели")
        }
        IconButton(onClick = onCreateFolder) {
            Icon(Icons.Rounded.Add, contentDescription = "Создать папку")
        }
    }
}

@Composable
private fun Breadcrumbs(state: FileManagerUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.folderStack.forEachIndexed { index, crumb ->
            if (index > 0) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                crumb.label,
                color = if (index == state.folderStack.lastIndex) {
                    MaterialTheme.colorScheme.onBackground
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ClipboardBar(state: FileManagerUiState, onPaste: () -> Unit, onClear: () -> Unit) {
    val clipboard = state.clipboard ?: return
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (clipboard.mode == ClipboardMode.Copy) Icons.Rounded.ContentCopy else Icons.Rounded.ContentCut,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (clipboard.entries.size == 1) clipboard.entries.first().name
                else "Выбрано объектов: ${clipboard.entries.size}",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
            )
            TextButton(onClick = onClear) { Text("Отмена") }
            Button(onClick = onPaste, enabled = !state.operationInProgress) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Вставить")
            }
        }
    }
}

@Composable
private fun EmptyRecentCard(connected: Boolean, onChooseRoot: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBubble(Icons.Rounded.FolderOpen, AuraBlue)
            Spacer(Modifier.height(12.dp))
            Text(if (connected) "Недавних файлов пока нет" else "Подключите хранилище", fontWeight = FontWeight.SemiBold)
            Text(
                if (connected) "Откройте папку и выберите файл" else "Вы сами решаете, к каким папкам дать доступ",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            if (!connected) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onChooseRoot) { Text("Выбрать папку") }
            }
        }
    }
}

@Composable
private fun EmptyFolder(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(10.dp))
        Text("Папка пуста", fontWeight = FontWeight.SemiBold)
        Text("Добавьте файлы или создайте папку", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun EmptyStateCard(icon: ImageVector, title: String, subtitle: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBubble(icon, AuraBlue)
            Spacer(Modifier.height(12.dp))
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun RecommendationRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(icon, tint)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

private data class AdvancedSearchFilters(
    val category: FileCategory? = null,
    val minMegabytes: Int = 0,
    val maxMegabytes: Int = 0,
    val modifiedWithinDays: Int = 0,
) {
    val minBytes: Long get() = minMegabytes.coerceAtLeast(0) * 1024L * 1024L
    val maxBytes: Long get() = maxMegabytes.coerceAtLeast(0) * 1024L * 1024L
    val modifiedAfter: Long
        get() = if (modifiedWithinDays <= 0) 0L
        else System.currentTimeMillis() - modifiedWithinDays * 24L * 60L * 60L * 1000L
    val isActive: Boolean
        get() = category != null || minMegabytes > 0 || maxMegabytes > 0 || modifiedWithinDays > 0
}

@Composable
private fun AdvancedSearchDialog(
    initial: AdvancedSearchFilters,
    onDismiss: () -> Unit,
    onConfirm: (AdvancedSearchFilters) -> Unit,
) {
    var category by remember(initial) { mutableStateOf(initial.category) }
    var minSize by remember(initial) { mutableStateOf(initial.minMegabytes.takeIf { it > 0 }?.toString().orEmpty()) }
    var maxSize by remember(initial) { mutableStateOf(initial.maxMegabytes.takeIf { it > 0 }?.toString().orEmpty()) }
    var days by remember(initial) { mutableStateOf(initial.modifiedWithinDays.takeIf { it > 0 }?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
        title = { Text("Фильтры поиска") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Тип файла", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf<FileCategory?>(null, *FileCategory.entries.toTypedArray()).forEach { option ->
                        TextButton(onClick = { category = option }) {
                            Text(
                                if (option == null) "Все" else categoryLabel(option),
                                color = if (category == option) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (category == option) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minSize,
                        onValueChange = { minSize = it.filter(Char::isDigit).take(7) },
                        modifier = Modifier.weight(1f),
                        label = { Text("От, МБ") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = maxSize,
                        onValueChange = { maxSize = it.filter(Char::isDigit).take(7) },
                        modifier = Modifier.weight(1f),
                        label = { Text("До, МБ") },
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = days,
                    onValueChange = { days = it.filter(Char::isDigit).take(5) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Изменён за последние, дней") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        AdvancedSearchFilters(
                            category = category,
                            minMegabytes = minSize.toIntOrNull() ?: 0,
                            maxMegabytes = maxSize.toIntOrNull() ?: 0,
                            modifiedWithinDays = days.toIntOrNull() ?: 0,
                        )
                    )
                }
            ) { Text("Применить") }
        },
        dismissButton = {
            TextButton(onClick = { onConfirm(AdvancedSearchFilters()) }) { Text("Сбросить") }
        },
    )
}

@Composable
private fun BatchRenameDialog(
    entries: List<FileEntry>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var find by remember(entries) { mutableStateOf("") }
    var replace by remember(entries) { mutableStateOf("") }
    var prefix by remember(entries) { mutableStateOf("") }
    var start by remember(entries) { mutableStateOf("1") }
    val names = remember(entries, find, replace, prefix, start) {
        val firstNumber = start.toIntOrNull() ?: 1
        val digits = (firstNumber + entries.size - 1).toString().length.coerceAtLeast(2)
        entries.mapIndexed { index, entry ->
            val dot = entry.name.lastIndexOf('.').takeIf { !entry.isDirectory && it > 0 } ?: -1
            val base = if (dot > 0) entry.name.substring(0, dot) else entry.name
            val extension = if (dot > 0) entry.name.substring(dot) else ""
            val replaced = if (find.isBlank()) base else base.replace(find, replace, ignoreCase = true)
            "${prefix}${replaced}_${(firstNumber + index).toString().padStart(digits, '0')}$extension"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null) },
        title = { Text("Пакетное переименование") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = find,
                        onValueChange = { find = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Найти") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = replace,
                        onValueChange = { replace = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Заменить") },
                        singleLine = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = prefix,
                        onValueChange = { prefix = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Префикс") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = start,
                        onValueChange = { start = it.filter(Char::isDigit).take(6) },
                        modifier = Modifier.width(105.dp),
                        label = { Text("Номер") },
                        singleLine = true,
                    )
                }
                Text("Предпросмотр", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                names.take(4).forEach { name ->
                    Text(name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (names.size > 4) Text("…и ещё ${names.size - 4}", fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(names) }) { Text("Переименовать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun StorageMapCard(analysis: StorageAnalysis) {
    val summaries = analysis.categories.filter { it.bytes > 0L }
    if (summaries.isEmpty()) return
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Карта файлов", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth().height(14.dp).clip(CircleShape),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                summaries.forEach { summary ->
                    Box(
                        Modifier
                            .weight(summary.bytes.coerceAtLeast(1L).toFloat())
                            .fillMaxSize()
                            .background(categoryColor(summary.category))
                    )
                }
            }
            summaries.forEach { summary ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(categoryColor(summary.category)))
                    Spacer(Modifier.width(8.dp))
                    Text(categoryLabel(summary.category), modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Text(formatBytes(summary.bytes), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun categoryColor(category: FileCategory): Color = when (category) {
    FileCategory.Images -> AuraPink
    FileCategory.Video -> AuraPurple
    FileCategory.Audio -> AuraGreen
    FileCategory.Documents -> AuraBlue
    FileCategory.Archives -> AuraOrange
    FileCategory.Other -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun TrashDialog(
    records: List<TrashRecord>,
    onRestore: (TrashRecord) -> Unit,
    onDelete: (TrashRecord) -> Unit,
    onEmpty: () -> Unit,
    onDismiss: () -> Unit,
) {
    var permanentRecord by remember { mutableStateOf<TrashRecord?>(null) }
    var emptyConfirmation by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.RestoreFromTrash, contentDescription = null) },
        title = { Text("Корзина") },
        text = {
            if (records.isEmpty()) {
                Text("Корзина пуста. Удалённые через Aura объекты появятся здесь.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    items(records.size) { index ->
                        val record = records[index]
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FileIcon(record.entry)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(record.originalName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                                Text(
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.deletedAt)),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                )
                            }
                            IconButton(onClick = { onRestore(record) }) {
                                Icon(Icons.Rounded.RestoreFromTrash, contentDescription = "Восстановить")
                            }
                            IconButton(onClick = { permanentRecord = record }) {
                                Icon(Icons.Rounded.DeleteForever, contentDescription = "Удалить навсегда", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        dismissButton = if (records.isNotEmpty()) {
            { TextButton(onClick = { emptyConfirmation = true }) { Text("Очистить", color = MaterialTheme.colorScheme.error) } }
        } else null,
    )
    permanentRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { permanentRecord = null },
            icon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null) },
            title = { Text("Удалить безвозвратно?") },
            text = { Text(record.originalName) },
            confirmButton = {
                TextButton(onClick = { onDelete(record); permanentRecord = null }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { permanentRecord = null }) { Text("Отмена") } },
        )
    }
    if (emptyConfirmation) {
        AlertDialog(
            onDismissRequest = { emptyConfirmation = false },
            icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null) },
            title = { Text("Очистить корзину?") },
            text = { Text("Все ${records.size} объектов будут удалены без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = { onEmpty(); emptyConfirmation = false }) {
                    Text("Очистить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { emptyConfirmation = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = { Text("Название") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun startFtpServer(context: Context, state: FileManagerUiState, config: FtpServerConfig) {
    val root = state.folderStack.firstOrNull()
    if (root == null) {
        Toast.makeText(context, "Сначала подключите локальную папку", Toast.LENGTH_LONG).show()
        return
    }
    runCatching {
        FtpServerService.start(context, root.document.uri, root.label, config)
    }.onFailure {
        Toast.makeText(context, it.message ?: "Не удалось запустить FTP-сервер", Toast.LENGTH_LONG).show()
    }
}

private fun openFile(context: Context, entry: FileEntry) {
    val repository = com.aurafiles.app.data.FileRepository(context)
    try {
        context.startActivity(repository.openIntent(entry))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Нет приложения для открытия этого файла", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "Нет доступа к файлу", Toast.LENGTH_SHORT).show()
    }
}

private fun shareFiles(context: Context, intent: Intent) {
    try {
        context.startActivity(Intent.createChooser(intent, "Поделиться файлами"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Нет приложения для отправки файлов", Toast.LENGTH_SHORT).show()
    }
}

private fun toggleSelection(selected: Set<Uri>, uri: Uri): Set<Uri> {
    return if (uri in selected) selected - uri else selected + uri
}

private fun sortEntries(
    entries: List<FileEntry>,
    mode: FileSortMode,
    ascending: Boolean,
): List<FileEntry> {
    val valueComparator = when (mode) {
        FileSortMode.Name -> compareBy<FileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        FileSortMode.Modified -> compareBy<FileEntry> { it.modifiedAt }
        FileSortMode.Size -> compareBy<FileEntry> { it.size }
        FileSortMode.Type -> compareBy<FileEntry, String>(String.CASE_INSENSITIVE_ORDER) {
            it.mimeType ?: it.name.substringAfterLast('.', "")
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }.let { if (ascending) it else it.reversed() }

    return entries.sortedWith(
        compareByDescending<FileEntry> { it.isDirectory }.then(valueComparator)
    )
}

private fun FileSortMode.label(): String = when (this) {
    FileSortMode.Name -> "По имени"
    FileSortMode.Modified -> "По дате"
    FileSortMode.Size -> "По размеру"
    FileSortMode.Type -> "По типу"
}

private fun fileDetails(entry: FileEntry): String {
    if (entry.isDirectory) return "Папка"
    val size = formatBytes(entry.size)
    val date = if (entry.modifiedAt > 0L) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.modifiedAt))
    } else {
        "Дата неизвестна"
    }
    return "$size · $date"
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 Б"
    val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit += 1
    }
    val formatted = if (value >= 10 || unit == 0 || value % 1.0 == 0.0) {
        "%.0f".format(value)
    } else {
        "%.1f".format(value)
    }
    return "$formatted ${units[unit]}"
}
