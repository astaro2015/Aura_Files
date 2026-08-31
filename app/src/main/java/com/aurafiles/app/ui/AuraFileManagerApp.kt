package com.aurafiles.app.ui

import android.content.ActivityNotFoundException
import android.Manifest
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.hardware.usb.UsbManager
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.automirrored.rounded.MenuBook
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
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Usb
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
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.aurafiles.app.model.ClipboardMode
import com.aurafiles.app.model.DeleteAnimationMode
import com.aurafiles.app.model.FileEntry
import com.aurafiles.app.model.FileCategory
import com.aurafiles.app.model.FileCollectionGroup
import com.aurafiles.app.model.FileSortMode
import com.aurafiles.app.model.FileViewMode
import com.aurafiles.app.model.FtpEntry
import com.aurafiles.app.model.FtpProfile
import com.aurafiles.app.model.FtpServerConfig
import com.aurafiles.app.model.FtpServerStatus
import com.aurafiles.app.model.SftpServerConfig
import com.aurafiles.app.model.SftpServerStatus
import com.aurafiles.app.model.LanDevice
import com.aurafiles.app.model.LanService
import com.aurafiles.app.model.SmbEntry
import com.aurafiles.app.model.SmbProfile
import com.aurafiles.app.model.SftpProfile
import com.aurafiles.app.data.FtpServerService
import com.aurafiles.app.data.SftpServerService
import com.aurafiles.app.model.MainSection
import com.aurafiles.app.model.StorageSnapshot
import com.aurafiles.app.model.StorageAnalysis
import com.aurafiles.app.model.StorageAccessMode
import com.aurafiles.app.model.StorageVolumeInfo
import com.aurafiles.app.model.SystemSoundType
import com.aurafiles.app.network.NetworkProfile
import com.aurafiles.app.network.NetworkProtocol
import com.aurafiles.app.transfer.TransferConflictPolicy
import com.aurafiles.app.ui.dialogs.TransferConflictDialog
import com.aurafiles.app.ui.dialogs.TransferStatusOverlay
import com.aurafiles.app.ui.network.NetworkProfilesCard
import com.aurafiles.app.model.TrashRecord
import com.aurafiles.app.model.displayLocation
import com.aurafiles.app.model.isTemporaryCandidate
import com.aurafiles.app.model.isThumbnailCache
import com.aurafiles.app.model.isReaderSupported
import com.aurafiles.app.model.isAudioFile
import com.aurafiles.app.model.matchesCategory
import com.aurafiles.app.model.sourceLabel
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
    val sftpServerStatus by SftpServerService.status.collectAsState()
    val context = LocalContext.current
    val openSftpWorkspace: (NetworkProfile) -> Unit = { profile ->
        context.startActivity(
            Intent(context, BackendWorkspaceActivity::class.java)
                .putExtra(BackendWorkspaceActivity.EXTRA_INITIAL_BACKEND_ID, "sftp:${profile.id}")
        )
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var previewEntry by remember { mutableStateOf<FileEntry?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var pendingFtpServerConfig by remember { mutableStateOf<FtpServerConfig?>(null) }
    var pendingSftpServerConfig by remember { mutableStateOf<SftpServerConfig?>(null) }
    var pendingSystemSound by remember { mutableStateOf<Pair<FileEntry, SystemSoundType>?>(null) }
    var selectionModeActive by remember { mutableStateOf(false) }
    val openLocalFile: (FileEntry) -> Unit = { entry ->
        when {
            entry.isReaderSupported() -> runCatching { openBookReader(context, entry) }
                .onFailure { previewEntry = entry }
            ArchiveBrowserActivity.isBrowsableArchiveName(entry.name) ->
                runCatching { ArchiveBrowserActivity.start(context, entry) }.onFailure { previewEntry = entry }
            openEnhancedPreview(context, entry, uiState.items) -> Unit
            else -> previewEntry = entry
        }
    }
    val treeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri -> if (uri != null) viewModel.attachRoot(uri) },
    )
    val volumeTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result -> result.data?.data?.let(viewModel::attachRoot) },
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
    val smbUploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = viewModel::uploadToSmb,
    )
    val smbFolderUploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri -> uri?.let { viewModel.uploadToSmb(listOf(it)) } },
    )
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {
            pendingFtpServerConfig?.let { config -> startFtpServer(context, uiState, config) }
            pendingSftpServerConfig?.let { config -> startSftpServer(context, uiState, config) }
            pendingFtpServerConfig = null
            pendingSftpServerConfig = null
        },
    )
    val systemSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = {
            pendingSystemSound?.let { (entry, type) ->
                if (Settings.System.canWrite(context)) viewModel.assignSystemSound(entry, type)
                else Toast.makeText(context, "Разрешение на изменение системных звуков не выдано", Toast.LENGTH_LONG).show()
            }
            pendingSystemSound = null
        },
    )
    val assignSystemSound: (FileEntry, SystemSoundType) -> Unit = { entry, type ->
        if (Settings.System.canWrite(context)) {
            viewModel.assignSystemSound(entry, type)
        } else {
            pendingSystemSound = entry to type
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
            runCatching { systemSettingsLauncher.launch(intent) }
                .onFailure {
                    pendingSystemSound = null
                    Toast.makeText(context, "Android не открыл настройку системных разрешений", Toast.LENGTH_LONG).show()
                }
        }
    }
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val externallyDeleted = ExternalFileChanges.consumeDeleted()
                if (externallyDeleted.isNotEmpty()) {
                    viewModel.refreshStorageVolumes()
                    viewModel.applyExternalDeletions(externallyDeleted)
                } else {
                    // Files may have been renamed or deleted by another application while
                    // Aura was in the background. Re-read the visible local destination
                    // instead of keeping a stale folder/category snapshot indefinitely.
                    viewModel.onAppResumed()
                }
            }
        }
        val storageManager = context.getSystemService(StorageManager::class.java)
        val storageCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) {
                    viewModel.refreshStorageVolumes()
                }
            }
        } else null
        val usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                viewModel.refreshStorageVolumes()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && storageCallback != null) {
            storageManager.registerStorageVolumeCallback(ContextCompat.getMainExecutor(context), storageCallback)
        }
        ContextCompat.registerReceiver(
            context,
            usbReceiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && storageCallback != null) {
                storageManager.unregisterStorageVolumeCallback(storageCallback)
            }
            runCatching { context.unregisterReceiver(usbReceiver) }
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
        enabled = !uiState.browserOpen && uiState.activeSection == MainSection.Network &&
            (uiState.smbPath != "/" ||
                (uiState.smbProfile?.share?.isNotBlank() == true && uiState.smbShares.isNotEmpty()) ||
                uiState.ftpPath != "/"),
    ) {
        val smbCanGoBack = uiState.smbPath != "/" ||
            (uiState.smbProfile?.share?.isNotBlank() == true && uiState.smbShares.isNotEmpty())
        if (smbCanGoBack) viewModel.navigateSmbBack() else viewModel.navigateFtpBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!selectionModeActive) {
                AuraBottomNavigation(
                    selected = uiState.activeSection,
                    onSelect = viewModel::selectSection,
                )
            }
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
                    onOpenFile = openLocalFile,
                    onOpenExternal = { openFile(context, it) },
                    onSetSystemSound = assignSystemSound,
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
                    onCancelOperation = viewModel::cancelOperation,
                    onPauseOperation = viewModel::pauseOperation,
                    onResumeOperation = viewModel::resumeOperation,
                    onResolveConflict = viewModel::resolveTransferConflict,
                    onToggleDualPane = viewModel::toggleDualPane,
                    onOpenSecondary = viewModel::openSecondaryEntry,
                    onSecondaryBack = viewModel::navigateSecondaryBack,
                    onRefreshSecondary = viewModel::refreshSecondaryFolder,
                    onCopyToOtherPane = viewModel::copyToOtherPane,
                    onSelectionModeChanged = { selectionModeActive = it },
                )
            } else {
                when (uiState.activeSection) {
                    MainSection.Browse -> HomeScreen(
                        state = uiState,
                        onChooseRoot = { treeLauncher.launch(null) },
                        onOpenFavorites = viewModel::openFavorites,
                        onOpenVolume = { volume ->
                            val mountedVolume = volume.volume
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mountedVolume != null) {
                                volumeTreeLauncher.launch(mountedVolume.createOpenDocumentTreeIntent())
                            } else {
                                treeLauncher.launch(null)
                            }
                        },
                        onStorageSettings = {
                            val intent = Intent(Settings.ACTION_MEMORY_CARD_SETTINGS)
                            runCatching { context.startActivity(intent) }
                                .onFailure { context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
                        },
                        onAnalyze = viewModel::analyzeStorage,
                        onOpenCategory = viewModel::openCategory,
                        onFullAccess = {
                            if (uiState.fullAccessGranted) viewModel.activateFullAccess() else requestFullAccess()
                        },
                        onOpenSettings = { settingsOpen = true },
                    )
                    MainSection.Recent -> RecentScreen(
                        state = uiState,
                        onOpenSettings = { settingsOpen = true },
                        onOpenFile = openLocalFile,
                        onOpenExternal = { openFile(context, it) },
                        onSetSystemSound = assignSystemSound,
                        onRename = viewModel::rename,
                        onDeleteMany = viewModel::delete,
                        onClipboardMany = viewModel::putOnClipboard,
                        onSetLastModified = viewModel::setLastModified,
                        onCreateArchive = viewModel::createArchive,
                        onExtractArchive = viewModel::extractArchive,
                        onToggleFavorites = viewModel::toggleFavorites,
                        onBatchRename = viewModel::batchRename,
                        onCalculateHash = viewModel::calculateHash,
                        onViewMode = viewModel::setViewMode,
                        onShare = { entries ->
                            viewModel.prepareShare(entries) { intent -> shareFiles(context, intent) }
                        },
                        onSelectionModeChanged = { selectionModeActive = it },
                    )
                    MainSection.Network -> FtpScreen(
                        state = uiState,
                        serverStatus = ftpServerStatus,
                        sftpServerStatus = sftpServerStatus,
                        onOpenSettings = { settingsOpen = true },
                        onScanLan = viewModel::scanLan,
                        onConnectProfile = { profile ->
                            if (profile.protocol == NetworkProtocol.SFTP) openSftpWorkspace(profile)
                            else viewModel.connectNetworkProfile(profile)
                        },
                        onTestProfile = { profile ->
                            if (profile.protocol == NetworkProtocol.SFTP) openSftpWorkspace(profile)
                            else viewModel.testNetworkProfile(profile)
                        },
                        onDuplicateProfile = viewModel::duplicateNetworkProfile,
                        onDeleteProfile = viewModel::deleteNetworkProfile,
                        onLoadSftpProfile = viewModel::sftpProfile,
                        onSaveSftpProfile = viewModel::saveSftpProfile,
                        onOpenSftpProfile = openSftpWorkspace,
                        onConnectSmb = viewModel::connectSmb,
                        onSelectSmbShare = viewModel::selectSmbShare,
                        onDisconnectSmb = viewModel::disconnectSmb,
                        onRefreshSmb = viewModel::refreshSmb,
                        onBackSmb = viewModel::navigateSmbBack,
                        onOpenSmb = viewModel::openSmbEntry,
                        onDownloadSmb = viewModel::downloadFromSmb,
                        onUploadSmb = { smbUploadLauncher.launch(arrayOf("*/*")) },
                        onUploadSmbFolder = { smbFolderUploadLauncher.launch(null) },
                        onCreateSmbFolder = viewModel::createSmbFolder,
                        onRenameSmb = viewModel::renameSmb,
                        onDeleteSmb = viewModel::deleteSmb,
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
                                pendingFtpServerConfig = config
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                startFtpServer(context, uiState, config)
                            }
                        },
                        onStopServer = { FtpServerService.stop(context) },
                        onStartSftpServer = { config ->
                            if (
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                pendingSftpServerConfig = config
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                startSftpServer(context, uiState, config)
                            }
                        },
                        onStopSftpServer = { SftpServerService.stop(context) },
                    )
                    MainSection.Cleanup -> CleanupScreen(
                        state = uiState,
                        onOpenSettings = { settingsOpen = true },
                        onAnalyze = viewModel::analyzeStorage,
                        onOpenLargeFiles = viewModel::openLargeFiles,
                        onOpenDuplicates = viewModel::openDuplicates,
                        onOpenTemporary = viewModel::openTemporaryFiles,
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
    if (settingsOpen) {
        SettingsPage(
            state = uiState,
            onDismiss = { settingsOpen = false },
            onShowHidden = viewModel::setShowHiddenFiles,
            onShowThumbnailFiles = viewModel::setShowThumbnailFiles,
            onGridThumbnails = viewModel::setShowGridThumbnails,
            onFavoritesHome = viewModel::setShowFavoritesOnHome,
            onDeleteAnimationMode = viewModel::setDeleteAnimationMode,
            onChooseFolder = {
                settingsOpen = false
                treeLauncher.launch(null)
            },
            onFullAccess = {
                settingsOpen = false
                if (uiState.fullAccessGranted) viewModel.activateFullAccess() else requestFullAccess()
            },
        )
    }
}

@Composable
private fun HomeScreen(
    state: FileManagerUiState,
    onChooseRoot: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenVolume: (StorageVolumeInfo) -> Unit,
    onStorageSettings: () -> Unit,
    onAnalyze: () -> Unit,
    onOpenCategory: (FileCategory) -> Unit,
    onFullAccess: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            LargeTitleRow(title = "Файлы", onSettings = onOpenSettings)
        }
        if (state.showFavoritesOnHome) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenFavorites),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier.padding(15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconBubble(Icons.Rounded.Star, AuraOrange)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Избранное", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (state.favoriteItems.isEmpty()) "Добавляйте файлы через меню «…»" else "Объектов: ${state.favoriteItems.size}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                    }
                }
            }
        }
        if (!state.rootConnected) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LocationCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Smartphone,
                        title = "На устройстве",
                        subtitle = "Выбрать папку",
                        tint = AuraBlue,
                        onClick = onChooseRoot,
                    )
                    LocationCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Storage,
                        title = "Весь накопитель",
                        subtitle = if (state.fullAccessGranted) "Использовать доступ" else "Настроить доступ",
                        tint = AuraOrange,
                        onClick = onFullAccess,
                    )
                }
            }
        }
        if (state.storageVolumes.isNotEmpty()) {
            item { SectionHeader("Подключённые накопители") }
            items(state.storageVolumes, key = { "volume-${it.id}" }) { volume ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenVolume(volume) },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(Icons.Rounded.Usb, AuraOrange)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(volume.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (volume.state == android.os.Environment.MEDIA_MOUNTED) "Подключён · нажмите для доступа" else volume.state,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                        IconButton(onClick = onStorageSettings) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Системные настройки накопителя")
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                    }
                }
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
            StorageCard(storage = state.storage)
        }
    }
}

@Composable
private fun FtpScreen(
    state: FileManagerUiState,
    serverStatus: FtpServerStatus,
    sftpServerStatus: SftpServerStatus,
    onOpenSettings: () -> Unit,
    onScanLan: () -> Unit,
    onConnectProfile: (NetworkProfile) -> Unit,
    onTestProfile: (NetworkProfile) -> Unit,
    onDuplicateProfile: (NetworkProfile) -> Unit,
    onDeleteProfile: (NetworkProfile) -> Unit,
    onLoadSftpProfile: (NetworkProfile?) -> SftpProfile?,
    onSaveSftpProfile: (SftpProfile) -> NetworkProfile,
    onOpenSftpProfile: (NetworkProfile) -> Unit,
    onConnectSmb: (SmbProfile) -> Unit,
    onSelectSmbShare: (String) -> Unit,
    onDisconnectSmb: () -> Unit,
    onRefreshSmb: () -> Unit,
    onBackSmb: () -> Boolean,
    onOpenSmb: (SmbEntry) -> Unit,
    onDownloadSmb: (SmbEntry) -> Unit,
    onUploadSmb: () -> Unit,
    onUploadSmbFolder: () -> Unit,
    onCreateSmbFolder: (String) -> Unit,
    onRenameSmb: (SmbEntry, String) -> Unit,
    onDeleteSmb: (SmbEntry, Boolean) -> Unit,
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
    onStartSftpServer: (SftpServerConfig) -> Unit,
    onStopSftpServer: () -> Unit,
) {
    var settingsOpen by remember { mutableStateOf(false) }
    var ftpDialogInitial by remember { mutableStateOf<FtpProfile?>(null) }
    var smbSettingsOpen by remember { mutableStateOf(false) }
    var smbDialogInitial by remember { mutableStateOf<SmbProfile?>(null) }
    var smbCreateFolderOpen by remember { mutableStateOf(false) }
    var sftpSettingsOpen by remember { mutableStateOf(false) }
    var sftpDialogInitial by remember { mutableStateOf<SftpProfile?>(null) }
    var createFolderOpen by remember { mutableStateOf(false) }
    var serverSettingsOpen by remember { mutableStateOf(false) }
    var sftpServerSettingsOpen by remember { mutableStateOf(false) }
    var connectSectionExpanded by remember { mutableStateOf(false) }
    var serverSectionExpanded by remember { mutableStateOf(serverStatus.running || sftpServerStatus.running) }
    val savedSftpProfile = state.networkProfiles.firstOrNull { it.protocol == NetworkProtocol.SFTP }

    LaunchedEffect(serverStatus.running, sftpServerStatus.running) {
        if (serverStatus.running || sftpServerStatus.running) serverSectionExpanded = true
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { LargeTitleRow("Сеть", onSettings = onOpenSettings) }
        item {
            LanDevicesCard(
                devices = state.lanDevices,
                scanning = state.lanScanning,
                onScan = onScanLan,
                onSmb = { device ->
                    onConnectSmb(
                        SmbProfile(
                            name = device.name,
                            host = device.address,
                            share = "",
                            username = "",
                            password = "",
                        )
                    )
                },
                onFtp = { device ->
                    ftpDialogInitial = FtpProfile(name = device.name, host = device.address, username = "", password = "")
                    settingsOpen = true
                },
                onSftp = { device ->
                    sftpDialogInitial = SftpProfile(
                        name = device.name,
                        host = device.address,
                        port = 22,
                        username = "",
                    )
                    sftpSettingsOpen = true
                },
            )
        }
        item {
            ExpandableNetworkSection(
                title = "Подключиться к",
                subtitle = "SMB, FTP, SFTP и сохранённые подключения",
                icon = Icons.Rounded.Language,
                accent = AuraPurple,
                expanded = connectSectionExpanded,
                onToggle = { connectSectionExpanded = !connectSectionExpanded },
            ) {
                if (state.networkProfiles.isNotEmpty()) {
                    NetworkProfilesCard(
                        profiles = state.networkProfiles,
                        onConnect = onConnectProfile,
                        onTest = onTestProfile,
                        onDuplicate = onDuplicateProfile,
                        onDelete = onDeleteProfile,
                        deleteAnimationMode = state.deleteAnimationMode,
                    )
                }
                SmbConnectionCard(
                    state = state,
                    onConfigure = {
                        smbDialogInitial = state.smbProfile
                        smbSettingsOpen = true
                    },
                    onConnect = {
                        val profile = state.smbProfile
                        if (profile == null) {
                            smbDialogInitial = null
                            smbSettingsOpen = true
                        } else onConnectSmb(profile)
                    },
                    onDisconnect = onDisconnectSmb,
                )
                FtpConnectionCard(
                    state = state,
                    onConfigure = {
                        ftpDialogInitial = state.ftpProfile
                        settingsOpen = true
                    },
                    onConnect = { profile -> onConnect(profile) },
                )
                SftpConnectionCard(
                    profile = savedSftpProfile,
                    onConfigure = {
                        sftpDialogInitial = onLoadSftpProfile(savedSftpProfile)
                        sftpSettingsOpen = true
                    },
                    onConnect = {
                        if (savedSftpProfile == null) {
                            sftpDialogInitial = null
                            sftpSettingsOpen = true
                        } else {
                            onOpenSftpProfile(savedSftpProfile)
                        }
                    },
                )
            }
        }
        item {
            ExpandableNetworkSection(
                title = "Создать сервер",
                subtitle = when {
                    serverStatus.running && sftpServerStatus.running ->
                        "FTP и SFTP работают · подключений: ${serverStatus.clients + sftpServerStatus.clients}"
                    serverStatus.running -> "FTP работает · подключений: ${serverStatus.clients}"
                    sftpServerStatus.running -> "SFTP работает · подключений: ${sftpServerStatus.clients}"
                    else -> "FTP или защищённый SFTP-сервер на телефоне"
                },
                icon = Icons.Rounded.Smartphone,
                accent = if (serverStatus.running || sftpServerStatus.running) AuraGreen else AuraBlue,
                expanded = serverSectionExpanded,
                onToggle = { serverSectionExpanded = !serverSectionExpanded },
            ) {
                FtpServerCard(
                    status = serverStatus,
                    rootConnected = state.rootConnected,
                    onConfigure = { serverSettingsOpen = true },
                    onStop = onStopServer,
                )
                SftpHostServerCard(
                    status = sftpServerStatus,
                    rootConnected = state.rootConnected,
                    fullAccessGranted = state.fullAccessGranted,
                    onConfigure = { sftpServerSettingsOpen = true },
                    onStop = onStopSftpServer,
                )
            }
        }
        if (state.smbConnected) {
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val canGoBack = state.smbPath != "/" ||
                            (state.smbProfile?.share?.isNotBlank() == true && state.smbShares.isNotEmpty())
                        IconButton(onClick = { onBackSmb() }, enabled = canGoBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "На уровень выше")
                        }
                        Text(
                            buildString {
                                append("\\\\")
                                append(state.smbProfile?.host.orEmpty())
                                state.smbProfile?.share?.takeIf(String::isNotBlank)?.let { append("\\").append(it) }
                                if (state.smbPath != "/") append(state.smbPath.replace('/', '\\'))
                            },
                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            maxLines = 1,
                            fontWeight = FontWeight.Medium,
                        )
                        IconButton(onClick = onRefreshSmb, enabled = !state.smbLoading) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Обновить SMB")
                        }
                    }
                }
            }
            if (state.smbProfile?.share?.isNotBlank() == true) {
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            TextButton(onClick = onUploadSmb, enabled = state.smbTransferLabel == null) {
                                Icon(Icons.Rounded.UploadFile, contentDescription = null)
                                Spacer(Modifier.width(5.dp))
                                Text("Файлы")
                            }
                            TextButton(onClick = onUploadSmbFolder, enabled = state.smbTransferLabel == null) {
                                Icon(Icons.Rounded.Folder, contentDescription = null)
                                Spacer(Modifier.width(5.dp))
                                Text("Папку")
                            }
                            TextButton(onClick = { smbCreateFolderOpen = true }, enabled = state.smbTransferLabel == null) {
                                Icon(Icons.Rounded.Add, contentDescription = null)
                                Spacer(Modifier.width(5.dp))
                                Text("Папка")
                            }
                            TextButton(onClick = onDisconnectSmb) { Text("Отключить") }
                        }
                    }
                }
            }
            state.smbTransferLabel?.let { label ->
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
                state.smbLoading -> item {
                    Box(Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                state.smbProfile?.share.isNullOrBlank() && state.smbShares.isNotEmpty() -> item {
                    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                        Column {
                            state.smbShares.forEachIndexed { index, shareName ->
                                SmbShareRow(
                                    name = shareName,
                                    host = state.smbProfile?.host.orEmpty(),
                                    onOpen = { onSelectSmbShare(shareName) },
                                )
                                if (index != state.smbShares.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 66.dp),
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    )
                                }
                            }
                        }
                    }
                }
                state.smbProfile?.share.isNullOrBlank() -> item {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                        Text(
                            "Доступные общие папки не найдены. Проверьте учётные данные или укажите имя шары в расширенных настройках.",
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.smbItems.isEmpty() -> item {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                        Text("Общая папка пуста", modifier = Modifier.fillMaxWidth().padding(30.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> itemsIndexed(
                    items = state.smbItems,
                    key = { _, entry -> "smb:${entry.path}" },
                ) { index, entry ->
                    val shape = networkListRowShape(index, state.smbItems.lastIndex)
                    Surface(shape = shape, color = MaterialTheme.colorScheme.surface) {
                        Column {
                            SmbFileRow(
                                entry = entry,
                                busy = state.smbTransferLabel != null,
                                onOpen = { onOpenSmb(entry) },
                                onDownload = { onDownloadSmb(entry) },
                                onRename = { name -> onRenameSmb(entry, name) },
                                onDelete = { recursive -> onDeleteSmb(entry, recursive) },
                            )
                            if (index != state.smbItems.lastIndex) {
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
                else -> itemsIndexed(
                    items = state.ftpItems,
                    key = { _, entry -> "ftp:${entry.path}" },
                ) { index, entry ->
                    val shape = networkListRowShape(index, state.ftpItems.lastIndex)
                    Surface(shape = shape, color = MaterialTheme.colorScheme.surface) {
                        Column {
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

    if (smbCreateFolderOpen) {
        NameDialog(
            title = "Новая папка SMB",
            initialValue = "",
            confirmLabel = "Создать",
            onDismiss = { smbCreateFolderOpen = false },
            onConfirm = { name -> smbCreateFolderOpen = false; onCreateSmbFolder(name) },
        )
    }
    if (settingsOpen) {
        FtpConnectionDialog(
            initial = ftpDialogInitial ?: state.ftpProfile,
            onDismiss = { settingsOpen = false },
            onConfirm = { profile -> settingsOpen = false; onConnect(profile) },
        )
    }
    if (smbSettingsOpen) {
        SmbConnectionDialog(
            initial = smbDialogInitial ?: state.smbProfile,
            onDismiss = { smbSettingsOpen = false },
            onConfirm = { profile -> smbSettingsOpen = false; onConnectSmb(profile) },
        )
    }
    if (sftpSettingsOpen) {
        SftpConnectionDialog(
            initial = sftpDialogInitial,
            onDismiss = { sftpSettingsOpen = false },
            onConfirm = { profile ->
                sftpSettingsOpen = false
                val saved = onSaveSftpProfile(profile)
                onOpenSftpProfile(saved)
            },
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
    if (sftpServerSettingsOpen) {
        SftpServerConfigDialog(
            onDismiss = { sftpServerSettingsOpen = false },
            onConfirm = { config ->
                sftpServerSettingsOpen = false
                onStartSftpServer(config)
            },
        )
    }
}

private fun networkListRowShape(index: Int, lastIndex: Int): RoundedCornerShape = when {
    lastIndex <= 0 -> RoundedCornerShape(22.dp)
    index == 0 -> RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    index == lastIndex -> RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
    else -> RoundedCornerShape(0.dp)
}

@Composable
private fun LanDevicesCard(
    devices: List<LanDevice>,
    scanning: Boolean,
    onScan: () -> Unit,
    onSmb: (LanDevice) -> Unit,
    onFtp: (LanDevice) -> Unit,
    onSftp: (LanDevice) -> Unit,
) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(Icons.Rounded.Storage, AuraPurple)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Устройства рядом", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (scanning) "Поиск устройств и сетевых служб…" else "Компьютеры, NAS, ТВ и роутеры в вашей сети",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                IconButton(onClick = onScan, enabled = !scanning) {
                    if (scanning) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Refresh, contentDescription = "Искать устройства")
                }
            }
            devices.forEach { device ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(
                        enabled = device.services.any { it == LanService.Smb || it == LanService.Ssh || it == LanService.Ftp },
                        onClick = {
                            when {
                                LanService.Smb in device.services -> onSmb(device)
                                LanService.Ssh in device.services -> onSftp(device)
                                LanService.Ftp in device.services -> onFtp(device)
                            }
                        },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val serviceNames = device.services.map { service ->
                            when (service) {
                                LanService.Smb -> "SMB"
                                LanService.Ftp -> "FTP"
                                LanService.Web -> "WEB"
                                LanService.Ssh -> "SSH"
                                LanService.Media -> "медиа"
                            }
                        }.sorted().joinToString(" · ")
                        Text(
                            if (serviceNames.isBlank()) device.address else "${device.address} · $serviceNames",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (LanService.Smb in device.services) TextButton(onClick = { onSmb(device) }) { Text("SMB") }
                    if (LanService.Ftp in device.services) TextButton(onClick = { onFtp(device) }) { Text("FTP") }
                    if (LanService.Ssh in device.services) TextButton(onClick = { onSftp(device) }) { Text("SFTP") }
                }
            }
            if (!scanning && devices.isEmpty()) {
                Text(
                    "Пока ничего не найдено. Проверьте одну Wi‑Fi-сеть и отключите в роутере изоляцию клиентов. Для ручного ввода откройте «Подключиться к» ниже.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun ExpandableNetworkSection(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBubble(icon, accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(
                        subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = if (expanded) "Свернуть" else "Развернуть",
                    modifier = Modifier.graphicsLayer(rotationZ = if (expanded) 90f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun FtpConnectionCard(
    state: FileManagerUiState,
    onConfigure: () -> Unit,
    onConnect: (FtpProfile) -> Unit,
) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(Icons.Rounded.Language, if (state.ftpConnected) AuraGreen else AuraOrange)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("FTP-сервер", fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            state.ftpLoading -> "Подключение…"
                            state.ftpConnected -> "Подключено · keep-alive 25 с"
                            state.ftpProfile != null -> "${state.ftpProfile?.name.orEmpty()} · соединение закрыто"
                            else -> "Удалённый FTP-сервер"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                TextButton(onClick = onConfigure) { Text("Параметры") }
            }
            if (!state.ftpConnected) {
                Button(
                    onClick = {
                        val profile = state.ftpProfile
                        if (profile == null) onConfigure() else onConnect(profile)
                    },
                    enabled = !state.ftpLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.ftpProfile == null) "Настроить и подключиться" else "Подключиться")
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

@Composable
private fun SmbConnectionCard(
    state: FileManagerUiState,
    onConfigure: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(Icons.Rounded.Storage, if (state.smbConnected) AuraGreen else AuraBlue)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("SMB-сервер", fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            state.smbLoading -> "Подключение…"
                            state.smbConnected -> "SMB2/3 · ${state.smbProfile?.host.orEmpty()}"
                            state.smbProfile != null -> "${state.smbProfile?.name.orEmpty()} · ${state.smbProfile?.host.orEmpty()}"
                            else -> "Windows, macOS, NAS и роутеры"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                TextButton(onClick = onConfigure) { Text("Параметры") }
            }
            if (state.smbConnected) {
                TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Отключить SMB") }
            } else {
                Button(onClick = onConnect, enabled = !state.smbLoading, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.smbProfile == null) "Настроить и подключиться" else "Подключиться")
                }
            }
        }
    }
}

@Composable
private fun SftpConnectionCard(
    profile: NetworkProfile?,
    onConfigure: () -> Unit,
    onConnect: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(Icons.Rounded.Lock, AuraPurple)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("SFTP-сервер", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (profile == null) {
                            "SFTP через SSH · обычно порт 22"
                        } else {
                            "${profile.name} · ${profile.host}:${profile.port}"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                TextButton(onClick = onConfigure) { Text("Параметры") }
            }
            Text(
                "Шифрованное подключение к Windows OpenSSH, Linux, NAS и серверам.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Text(if (profile == null) "Настроить и подключиться" else "Открыть SFTP")
            }
        }
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
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(Icons.Rounded.Smartphone, if (status.running) AuraGreen else AuraBlue)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("FTP-сервер на телефоне", fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            status.starting -> "Запуск…"
                            status.running -> "Работает · подключений: ${status.clients}"
                            status.error != null -> "Не запущен"
                            else -> "Доступ к файлам телефона с компьютера"
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
                ) { Text(if (rootConnected) "Настроить и запустить FTP-сервер" else "Сначала подключите папку") }
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
private fun SftpHostServerCard(
    status: SftpServerStatus,
    rootConnected: Boolean,
    fullAccessGranted: Boolean,
    onConfigure: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(Icons.Rounded.Lock, if (status.running) AuraGreen else AuraPurple)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("SFTP-сервер на телефоне", fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            status.starting -> "Запуск…"
                            status.running -> "Работает · подключений: ${status.clients}"
                            else -> "SSH/SFTP · шифрованная передача файлов"
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
                            clipboard.setPrimaryClip(ClipData.newPlainText("Aura SFTP", details))
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
                    "SFTP шифрует логин, пароль и файлы. Aura поднимает только файловый SFTP-подсервис — командной SSH-оболочки нет.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            } else {
                Button(
                    onClick = onConfigure,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = rootConnected && !status.starting,
                ) {
                    Text(if (rootConnected) "Настроить и запустить SFTP-сервер" else "Сначала подключите папку")
                }
                Text(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !fullAccessGranted) {
                        "Для SFTP-сервера нужен прямой доступ Android к локальной памяти. В Aura включите «Весь накопитель». FTP при этом продолжает работать и с обычной выбранной папкой."
                    } else {
                        "Публикуется подключённая локальная память/SD-карта. Облачные SAF-папки как корень SFTP-сервера не поддерживаются."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun SftpServerConfigDialog(
    onDismiss: () -> Unit,
    onConfirm: (SftpServerConfig) -> Unit,
) {
    var port by remember { mutableStateOf("2222") }
    var username by remember { mutableStateOf("aura") }
    var password by remember { mutableStateOf(generateFtpPassword()) }
    var readOnly by remember { mutableStateOf(true) }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
        title = { Text("Настройка SFTP-сервера на телефоне") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Корнем станет подключённая локальная память. По умолчанию используется порт 2222; соединение шифруется SSH.",
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
                    supportingText = { Text("Показывается открыто, чтобы ввести на другом устройстве") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = if (readOnly) AuraGreen else AuraOrange)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Только чтение", fontWeight = FontWeight.Medium)
                        Text(
                            if (readOnly) "Клиент сможет только читать и скачивать" else "Разрешены загрузка, удаление и переименование",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                    Switch(checked = readOnly, onCheckedChange = { readOnly = it })
                }
                Text(
                    "Подключения принимаются только из локальной/частной сети. Командная SSH-оболочка намеренно отключена — работает только SFTP.",
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
                    else -> onConfirm(SftpServerConfig(parsedPort, username, password, readOnly))
                }
            }) { Text("Запустить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
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
        title = { Text("Настройка FTP-сервера на телефоне") },
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
private fun SmbShareRow(
    name: String,
    host: String,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppleFolderGlyph()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                "\\\\$host\\$name",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onOpen) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = "Открыть $name")
        }
    }
}

@Composable
private fun SmbFileRow(
    entry: SmbEntry,
    busy: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (Boolean) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var dissolving by remember(entry.path) { mutableStateOf(false) }
    val context = LocalContext.current
    val deleteAnimationMode = remember(context) { com.aurafiles.app.data.FileRepository(context.applicationContext).deleteAnimationMode() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(busy) {
        if (!busy) dissolving = false
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .auraDeleteEffect(dissolving, deleteAnimationMode, entry.path.hashCode())
            .clickable(enabled = entry.isDirectory && !busy && !dissolving, onClick = onOpen)
            .padding(start = 14.dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.isDirectory) AppleFolderGlyph()
        else AppleFileGlyph(Icons.AutoMirrored.Rounded.InsertDriveFile, AuraPurple)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                if (entry.isDirectory) "Папка SMB" else formatBytes(entry.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = !busy) {
                Icon(Icons.Rounded.MoreHoriz, contentDescription = "Действия с ${entry.name}")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (entry.isDirectory) "Скачать папку" else "Скачать на устройство") },
                    leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                    onClick = { menuOpen = false; onDownload() },
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
            title = "Переименовать на SMB",
            initialValue = entry.name,
            confirmLabel = "Готово",
            onDismiss = { renameOpen = false },
            onConfirm = { name -> renameOpen = false; onRename(name) },
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text(if (entry.isDirectory) "Удалить папку SMB?" else "Удалить файл SMB?") },
            text = {
                Text(
                    if (entry.isDirectory) {
                        "${entry.name}\nПапка и всё её содержимое будут удалены без корзины."
                    } else "${entry.name}\nФайл будет удалён без корзины."
                )
            },
            confirmButton = {
                Button(onClick = {
                    deleteOpen = false
                    dissolving = true
                    scope.launch {
                        val wait = deleteAnimationMode.preDeleteDelayMillis()
                        if (wait > 0L) delay(wait)
                        onDelete(entry.isDirectory)
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SftpConnectionDialog(
    initial: SftpProfile?,
    onDismiss: () -> Unit,
    onConfirm: (SftpProfile) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "SFTP") }
    var host by remember(initial) { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember(initial) { mutableStateOf((initial?.port ?: 22).toString()) }
    var username by remember(initial) { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember(initial) { mutableStateOf(initial?.password.orEmpty()) }
    var privateKey by remember(initial) { mutableStateOf(initial?.privateKey.orEmpty()) }
    var passphrase by remember(initial) { mutableStateOf(initial?.privateKeyPassphrase.orEmpty()) }
    var initialPath by remember(initial) { mutableStateOf(initial?.initialPath ?: "/") }
    var advanced by remember(initial) {
        mutableStateOf(initial?.usesKey == true || initial?.initialPath?.let { it != "/" } == true)
    }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
        title = { Text("SFTP-подключение") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    "SFTP работает поверх SSH. На Windows нужен включённый OpenSSH Server; стандартный порт — 22.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    label = { Text(if (privateKey.isBlank()) "Пароль" else "Пароль (не используется при ключе)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (advanced) "Скрыть дополнительные настройки" else "Ключ SSH и начальная папка")
                }
                AnimatedVisibility(visible = advanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        OutlinedTextField(
                            value = privateKey,
                            onValueChange = { privateKey = it },
                            label = { Text("Приватный ключ (необязательно)") },
                            minLines = 3,
                            maxLines = 7,
                            supportingText = { Text("Если ключ указан, Aura использует его вместо пароля") },
                        )
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text("Passphrase ключа") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = initialPath,
                            onValueChange = { initialPath = it },
                            label = { Text("Начальная папка") },
                            supportingText = { Text("Например /home/onda или /C:/Users/Onda") },
                            singleLine = true,
                        )
                    }
                }
                Text(
                    "При первом подключении Aura покажет SHA-256 fingerprint ключа сервера и попросит подтвердить его. Пароль и ключ сохраняются в защищённом хранилище.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val targetPort = port.toIntOrNull()
                val targetHost = host.trim()
                    .removePrefix("sftp://")
                    .removePrefix("ssh://")
                    .substringBefore('/')
                    .substringBefore(':')
                when {
                    targetHost.isBlank() -> localError = "Введите адрес сервера"
                    targetPort == null || targetPort !in 1..65535 -> localError = "Некорректный порт"
                    username.isBlank() -> localError = "Введите логин"
                    password.isBlank() && privateKey.isBlank() -> localError = "Введите пароль или приватный ключ"
                    else -> onConfirm(
                        SftpProfile(
                            id = initial?.id.orEmpty(),
                            name = name.trim().ifBlank { targetHost },
                            host = targetHost,
                            port = targetPort,
                            username = username.trim(),
                            password = password,
                            privateKey = privateKey,
                            privateKeyPassphrase = passphrase,
                            trustedFingerprint = initial?.trustedFingerprint.orEmpty(),
                            initialPath = initialPath.trim().ifBlank { "/" },
                        )
                    )
                }
            }) { Text("Подключиться") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun SmbConnectionDialog(
    initial: SmbProfile?,
    onDismiss: () -> Unit,
    onConfirm: (SmbProfile) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "Сетевой компьютер") }
    var host by remember(initial) { mutableStateOf(initial?.host.orEmpty()) }
    var share by remember(initial) { mutableStateOf(initial?.share.orEmpty()) }
    var username by remember(initial) { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember(initial) { mutableStateOf(initial?.password.orEmpty()) }
    var domain by remember(initial) { mutableStateOf(initial?.domain.orEmpty()) }
    var advanced by remember(initial) { mutableStateOf(initial?.share?.isNotBlank() == true || initial?.domain?.isNotBlank() == true) }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Storage, contentDescription = null) },
        title = { Text("SMB-подключение") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    "Укажите только компьютер или IP. Aura попробует гостевой вход, затем введённую учётную запись и покажет доступные общие папки.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Компьютер или IP") }, singleLine = true)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Логин (пусто — гостевой вход)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (advanced) "Скрыть расширенные настройки" else "Расширенные настройки")
                }
                AnimatedVisibility(visible = advanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        OutlinedTextField(
                            value = share,
                            onValueChange = { share = it },
                            label = { Text("Открыть шару напрямую (необязательно)") },
                            supportingText = { Text("Например Movies. Пусто — показать список общих папок") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = domain,
                            onValueChange = { domain = it },
                            label = { Text("Домен (необязательно)") },
                            singleLine = true,
                        )
                    }
                }
                Text("Поддерживается безопасный SMB2/SMB3; устаревший SMB1 отключён.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val rawTarget = host.trim().removePrefix("\\\\").removePrefix("smb://")
                val separator = rawTarget.indexOfAny(charArrayOf('\\', '/'))
                val targetHost = if (separator < 0) rawTarget else rawTarget.substring(0, separator)
                val pastedShare = if (separator < 0) "" else rawTarget.substring(separator + 1)
                    .substringBefore('\\').substringBefore('/')
                val targetShare = share.trim().trim('/', '\\').ifBlank { pastedShare }
                when {
                    targetHost.isBlank() -> localError = "Введите адрес устройства"
                    else -> onConfirm(SmbProfile(name.ifBlank { targetHost }, targetHost, targetShare, username, password, domain))
                }
            }) { Text("Подключиться") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
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
    var dissolving by remember(entry.path) { mutableStateOf(false) }
    val context = LocalContext.current
    val deleteAnimationMode = remember(context) { com.aurafiles.app.data.FileRepository(context.applicationContext).deleteAnimationMode() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(busy) {
        if (!busy) dissolving = false
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .auraDeleteEffect(dissolving, deleteAnimationMode, entry.path.hashCode())
            .clickable(enabled = entry.isDirectory && !busy && !dissolving, onClick = onOpen)
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
                TextButton(onClick = {
                    deleteOpen = false
                    dissolving = true
                    scope.launch {
                        val wait = deleteAnimationMode.preDeleteDelayMillis()
                        if (wait > 0L) delay(wait)
                        onDelete()
                    }
                }) {
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
    onOpenExternal: (FileEntry) -> Unit,
    onSetSystemSound: (FileEntry, SystemSoundType) -> Unit,
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
    onCancelOperation: () -> Unit,
    onPauseOperation: () -> Unit,
    onResumeOperation: () -> Unit,
    onResolveConflict: (TransferConflictPolicy, Boolean) -> Unit,
    onToggleDualPane: () -> Unit,
    onOpenSecondary: (FileEntry) -> Unit,
    onSecondaryBack: () -> Boolean,
    onRefreshSecondary: () -> Unit,
    onCopyToOtherPane: (FileEntry, Boolean) -> Unit,
    onSelectionModeChanged: (Boolean) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var createDialog by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var dateDialogEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var propertiesEntry by remember { mutableStateOf<FileEntry?>(null) }
    var deleteEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var archiveEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var batchRenameEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    val context = LocalContext.current
    val hasCollectionGroups = state.collectionGroups.isNotEmpty()
    val shownItems = remember(
        state.items, state.showHidden, state.showThumbnailFiles, query, state.sortMode, state.sortAscending, hasCollectionGroups
    ) {
        // Collection screens render their grouped data, so sorting the same 5,000-file list here
        // as well is pure duplicate work. Skip the flat sort when groups are available.
        if (hasCollectionGroups) emptyList() else sortEntries(
            entries = state.items.filter {
                (state.showHidden || !it.name.startsWith('.')) &&
                    (state.showThumbnailFiles || !it.isThumbnailCache()) &&
                    it.name.contains(query, ignoreCase = true)
            },
            mode = state.sortMode,
            ascending = state.sortAscending,
        )
    }
    val shownGroups = remember(
        state.collectionGroups, state.showHidden, state.showThumbnailFiles, query, state.sortMode, state.sortAscending
    ) {
        state.collectionGroups.mapNotNull { group ->
            val sorted = sortEntries(
                group.entries.filter {
                    (state.showHidden || !it.name.startsWith('.')) &&
                        (state.showThumbnailFiles || !it.isThumbnailCache()) &&
                        it.name.contains(query, ignoreCase = true)
                },
                state.sortMode,
                state.sortAscending,
            )
            val entries = if (state.duplicateOriginalUris.isEmpty()) sorted else
                sorted.sortedByDescending { it.uri in state.duplicateOriginalUris }
            if (entries.isEmpty()) null else group.copy(entries = entries)
        }
    }
    val selectedEntries = remember(state.items, selectedUris) { state.items.filter { it.uri in selectedUris } }

    LaunchedEffect(selectedEntries.isNotEmpty()) {
        onSelectionModeChanged(selectedEntries.isNotEmpty())
    }
    DisposableEffect(Unit) {
        onDispose { onSelectionModeChanged(false) }
    }

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

    LaunchedEffect(state.folderStack.lastOrNull()?.document?.uri, state.collectionTitle) {
        selectedUris = emptySet()
        query = ""
        searchVisible = false
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
                shareEnabled = true,
                dateEnabled = selectedEntries.none(FileEntry::isDirectory),
                allFavorited = selectedEntries.all { it.uri in state.favoriteUris },
                onFavorite = {
                    onToggleFavorites(selectedEntries)
                    selectedUris = emptySet()
                },
                onShare = {
                    onShare(selectedEntries)
                    selectedUris = emptySet()
                },
                onChangeDate = { dateDialogEntries = selectedEntries },
                onClear = { selectedUris = emptySet() },
            )
        }
        if (state.collectionTitle == null) Breadcrumbs(state = state)
        BrowserControls(
            sortMode = state.sortMode,
            ascending = state.sortAscending,
            viewMode = state.viewMode,
            searchVisible = searchVisible,
            onSort = onSort,
            onViewMode = onViewMode,
            onToggleSearch = {
                searchVisible = !searchVisible
                if (!searchVisible) query = ""
            },
        )
        AnimatedVisibility(visible = searchVisible, enter = fadeIn(), exit = fadeOut()) {
            AuraSearchField(
                query = query,
                onQueryChange = { query = it },
                onClose = {
                    query = ""
                    searchVisible = false
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
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

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                shownItems.isEmpty() && shownGroups.isEmpty() -> EmptyFolder(modifier = Modifier.align(Alignment.Center))
                state.viewMode == FileViewMode.List -> BrowserListContent(
                    entries = shownItems,
                    groups = shownGroups,
                    selectedUris = selectedUris,
                    selectionMode = selectedEntries.isNotEmpty(),
                    deletingUris = state.deletingUris,
                    deleteAnimationMode = state.deleteAnimationMode,
                    duplicateOriginalUris = state.duplicateOriginalUris,
                    onOpen = { entry -> if (entry.isDirectory) onOpen(entry) else onOpenFile(entry) },
                    onToggleSelection = { entry ->
                        selectedUris = toggleSelection(selectedUris, entry.uri)
                    },
                    onRename = onRename,
                    onDelete = onDelete,
                    onCopy = { onClipboard(it, ClipboardMode.Copy) },
                    onMove = { onClipboard(it, ClipboardMode.Move) },
                    onShare = { onShare(listOf(it)) },
                    onOpenExternal = onOpenExternal,
                    onSetSystemSound = onSetSystemSound,
                    onProperties = { propertiesEntry = it },
                )
                else -> BrowserGridContent(
                    entries = shownItems,
                    groups = shownGroups,
                    showThumbnails = state.showGridThumbnails,
                    selectedUris = selectedUris,
                    selectionMode = selectedEntries.isNotEmpty(),
                    deletingUris = state.deletingUris,
                    deleteAnimationMode = state.deleteAnimationMode,
                    duplicateOriginalUris = state.duplicateOriginalUris,
                    onOpen = { entry -> if (entry.isDirectory) onOpen(entry) else onOpenFile(entry) },
                    onToggleSelection = { entry ->
                        selectedUris = toggleSelection(selectedUris, entry.uri)
                    },
                    onRename = onRename,
                    onDelete = onDelete,
                    onCopy = { onClipboard(it, ClipboardMode.Copy) },
                    onMove = { onClipboard(it, ClipboardMode.Move) },
                    onShare = { onShare(listOf(it)) },
                    onOpenExternal = onOpenExternal,
                    onSetSystemSound = onSetSystemSound,
                    onProperties = { propertiesEntry = it },
                )
            }

            if (state.operationInProgress) {
                TransferStatusOverlay(
                    progress = state.transferProgress,
                    label = state.operationLabel,
                    fallbackProgress = state.operationProgress,
                    cancelable = state.operationCancelable,
                    paused = state.transferPaused,
                    onPause = onPauseOperation,
                    onResume = onResumeOperation,
                    onCancel = onCancelOperation,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(20.dp),
                )
            }
        }
        if (selectedEntries.isNotEmpty()) {
            SelectionBottomBar(
                extractEnabled = selectedEntries.size == 1 && isExtractableArchiveName(selectedEntries.first().name),
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
                onBatchRename = { batchRenameEntries = selectedEntries },
                onDelete = { deleteEntries = selectedEntries },
            )
        }
    }

    state.transferConflict?.let { conflict ->
        TransferConflictDialog(conflict = conflict, onResolve = onResolveConflict)
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
            title = "Создать архив · ZIP / TAR / GZ / BZ2 / XZ",
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
    onOpenSettings: () -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
    onSetSystemSound: (FileEntry, SystemSoundType) -> Unit,
    onRename: (FileEntry, String) -> Unit,
    onDeleteMany: (List<FileEntry>) -> Unit,
    onClipboardMany: (List<FileEntry>, ClipboardMode) -> Unit,
    onSetLastModified: (List<FileEntry>, Long) -> Unit,
    onCreateArchive: (List<FileEntry>, String) -> Unit,
    onExtractArchive: (FileEntry) -> Unit,
    onToggleFavorites: (List<FileEntry>) -> Unit,
    onBatchRename: (List<FileEntry>, List<String>) -> Unit,
    onCalculateHash: (FileEntry) -> Unit,
    onViewMode: (FileViewMode) -> Unit,
    onShare: (List<FileEntry>) -> Unit,
    onSelectionModeChanged: (Boolean) -> Unit,
) {
    val items = state.recentItems
    var selectedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var propertiesEntry by remember { mutableStateOf<FileEntry?>(null) }
    var deleteEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var dateEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var archiveEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var batchRenameEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    val selectedEntries = items.filter { it.uri in selectedUris }

    LaunchedEffect(selectedEntries.isNotEmpty()) {
        onSelectionModeChanged(selectedEntries.isNotEmpty())
    }
    DisposableEffect(Unit) {
        onDispose { onSelectionModeChanged(false) }
    }

    BackHandler(enabled = selectedEntries.isNotEmpty()) { selectedUris = emptySet() }

    Column(Modifier.fillMaxSize()) {
        if (selectedEntries.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Недавние", modifier = Modifier.weight(1f), fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                FilledIconButton(onClick = {
                    onViewMode(if (state.viewMode == FileViewMode.List) FileViewMode.Grid else FileViewMode.List)
                }) {
                    Icon(
                        if (state.viewMode == FileViewMode.List) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList,
                        contentDescription = if (state.viewMode == FileViewMode.List) "Показать плиткой" else "Показать списком",
                    )
                }
                Spacer(Modifier.width(6.dp))
                FilledIconButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.MoreHoriz, contentDescription = "Настройки")
                }
            }
        } else {
            SelectionHeader(
                count = selectedEntries.size,
                shareEnabled = true,
                dateEnabled = true,
                allFavorited = selectedEntries.all { it.uri in state.favoriteUris },
                onFavorite = {
                    onToggleFavorites(selectedEntries)
                    selectedUris = emptySet()
                },
                onShare = {
                    onShare(selectedEntries)
                    selectedUris = emptySet()
                },
                onChangeDate = { dateEntries = selectedEntries },
                onClear = { selectedUris = emptySet() },
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
            Box(Modifier.weight(1f)) {
                if (state.viewMode == FileViewMode.List) {
                    BrowserListContent(
                        entries = items,
                        groups = emptyList(),
                        selectedUris = selectedUris,
                        selectionMode = selectedEntries.isNotEmpty(),
                        deletingUris = state.deletingUris,
                        deleteAnimationMode = state.deleteAnimationMode,
                        onOpen = onOpenFile,
                        onToggleSelection = { selectedUris = toggleSelection(selectedUris, it.uri) },
                        onRename = onRename,
                        onDelete = { deleteEntries = listOf(it) },
                        onCopy = { onClipboardMany(listOf(it), ClipboardMode.Copy) },
                        onMove = { onClipboardMany(listOf(it), ClipboardMode.Move) },
                        onShare = { onShare(listOf(it)) },
                        onOpenExternal = onOpenExternal,
                        onSetSystemSound = onSetSystemSound,
                        onProperties = { propertiesEntry = it },
                    )
                } else {
                    BrowserGridContent(
                        entries = items,
                        groups = emptyList(),
                        showThumbnails = state.showGridThumbnails,
                        selectedUris = selectedUris,
                        selectionMode = selectedEntries.isNotEmpty(),
                        deletingUris = state.deletingUris,
                        deleteAnimationMode = state.deleteAnimationMode,
                        onOpen = onOpenFile,
                        onToggleSelection = { selectedUris = toggleSelection(selectedUris, it.uri) },
                        onRename = onRename,
                        onDelete = { deleteEntries = listOf(it) },
                        onCopy = { onClipboardMany(listOf(it), ClipboardMode.Copy) },
                        onMove = { onClipboardMany(listOf(it), ClipboardMode.Move) },
                        onShare = { onShare(listOf(it)) },
                        onOpenExternal = onOpenExternal,
                        onSetSystemSound = onSetSystemSound,
                        onProperties = { propertiesEntry = it },
                    )
                }
            }
        }
        if (selectedEntries.isNotEmpty()) {
            SelectionBottomBar(
                extractEnabled = selectedEntries.size == 1 && isExtractableArchiveName(selectedEntries.first().name),
                onCopy = { onClipboardMany(selectedEntries, ClipboardMode.Copy); selectedUris = emptySet() },
                onMove = { onClipboardMany(selectedEntries, ClipboardMode.Move); selectedUris = emptySet() },
                onArchive = { archiveEntries = selectedEntries },
                onExtract = { onExtractArchive(selectedEntries.first()); selectedUris = emptySet() },
                onBatchRename = { batchRenameEntries = selectedEntries },
                onDelete = { deleteEntries = selectedEntries },
            )
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
            title = "Создать архив · ZIP / TAR / GZ / BZ2 / XZ",
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
    onOpenSettings: () -> Unit,
    onAnalyze: () -> Unit,
    onOpenLargeFiles: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onOpenTemporary: () -> Unit,
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
        item { LargeTitleRow("Хранилище", onSettings = onOpenSettings) }
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
                            analysis.largeFileCount == 0 -> "Крупных файлов не найдено"
                            else -> "${analysis.largeFileCount} файлов размером от 50 МБ"
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
                    val temporaryCount = analysis?.temporaryFileCount ?: 0
                    val temporaryBytes = analysis?.temporaryBytes ?: 0L
                    RecommendationRow(
                        icon = Icons.Rounded.DeleteForever,
                        tint = AuraOrange,
                        title = "Временные файлы",
                        subtitle = when {
                            analysis == null -> "Кэш, миниатюры и временные файлы приложений"
                            temporaryCount == 0 -> "Кандидатов не найдено"
                            else -> "$temporaryCount · ${formatBytes(temporaryBytes)}"
                        },
                        onClick = if (analysis == null) onAnalyze else onOpenTemporary,
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
                    "В этом представлении показана часть индекса. Категории и поиск продолжают работать по всей базе.",
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
            deletingUris = state.deletingUris,
            deleteAnimationMode = state.deleteAnimationMode,
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
            label = "Сеть",
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
private fun LargeTitleRow(title: String, onSettings: (() -> Unit)? = null) {
    var aboutOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
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
        Box {
            FilledIconButton(
                onClick = { menuOpen = true },
                colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Icon(Icons.Rounded.MoreHoriz, contentDescription = "Дополнительные действия")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (onSettings != null) {
                    DropdownMenuItem(
                        text = { Text("Настройки") },
                        leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        onClick = { menuOpen = false; onSettings() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("О приложении") },
                    leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                    onClick = { menuOpen = false; aboutOpen = true },
                )
            }
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
                    PropertyRow("Версия", com.aurafiles.app.BuildConfig.VERSION_NAME)
                }
            },
            confirmButton = {
                TextButton(onClick = { aboutOpen = false }) { Text("Готово") }
            },
        )
    }
}

@Composable
private fun SettingsPage(
    state: FileManagerUiState,
    onDismiss: () -> Unit,
    onShowHidden: (Boolean) -> Unit,
    onShowThumbnailFiles: (Boolean) -> Unit,
    onGridThumbnails: (Boolean) -> Unit,
    onFavoritesHome: (Boolean) -> Unit,
    onDeleteAnimationMode: (DeleteAnimationMode) -> Unit,
    onChooseFolder: () -> Unit,
    onFullAccess: () -> Unit,
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp, 12.dp, 12.dp, 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                    Text("Настройки", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                }
                LazyColumn(
                    contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item { SectionHeader("Отображение") }
                    item {
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                            Column {
                                SettingsSwitchRow(
                                    title = "Скрытые файлы",
                                    subtitle = "Показывать имена, начинающиеся с точки",
                                    checked = state.showHidden,
                                    onCheckedChange = onShowHidden,
                                )
                                HorizontalDivider(Modifier.padding(start = 16.dp))
                                SettingsSwitchRow(
                                    title = "Служебные миниатюры",
                                    subtitle = "Показывать файлы из .thumbnails и thumbnail",
                                    checked = state.showThumbnailFiles,
                                    onCheckedChange = onShowThumbnailFiles,
                                )
                                HorizontalDivider(Modifier.padding(start = 16.dp))
                                SettingsSwitchRow(
                                    title = "Превью в плитках",
                                    subtitle = "Загружать фото и кадры видео в сетке",
                                    checked = state.showGridThumbnails,
                                    onCheckedChange = onGridThumbnails,
                                )
                                HorizontalDivider(Modifier.padding(start = 16.dp))
                                SettingsSwitchRow(
                                    title = "Избранное на главной",
                                    subtitle = "Показывать быстрый вход на экране «Обзор»",
                                    checked = state.showFavoritesOnHome,
                                    onCheckedChange = onFavoritesHome,
                                )
                            }
                        }
                    }
                    item { SectionHeader("Анимации") }
                    item {
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                            Column {
                                DeleteAnimationChoice(
                                    title = "Распад на пиксели",
                                    subtitle = "Основной эффект Aura · срабатывает перед удалением",
                                    selected = state.deleteAnimationMode == DeleteAnimationMode.Dissolve,
                                    onClick = { onDeleteAnimationMode(DeleteAnimationMode.Dissolve) },
                                )
                                HorizontalDivider(Modifier.padding(start = 16.dp))
                                DeleteAnimationChoice(
                                    title = "Песчинки",
                                    subtitle = "Будто мелкие частицы сдувает ветром",
                                    selected = state.deleteAnimationMode == DeleteAnimationMode.Smoke,
                                    onClick = { onDeleteAnimationMode(DeleteAnimationMode.Smoke) },
                                )
                                HorizontalDivider(Modifier.padding(start = 16.dp))
                                DeleteAnimationChoice(
                                    title = "Сгорание",
                                    subtitle = "Рваная кромка тлеет, вспыхивает и прогорает внутрь",
                                    selected = state.deleteAnimationMode == DeleteAnimationMode.Burn,
                                    onClick = { onDeleteAnimationMode(DeleteAnimationMode.Burn) },
                                )
                                HorizontalDivider(Modifier.padding(start = 16.dp))
                                DeleteAnimationChoice(
                                    title = "Без анимации",
                                    subtitle = "Удалять сразу",
                                    selected = state.deleteAnimationMode == DeleteAnimationMode.Off,
                                    onClick = { onDeleteAnimationMode(DeleteAnimationMode.Off) },
                                )
                            }
                        }
                    }
                    item {
                        Button(
                            onClick = { AdvancedSettingsActivity.start(context) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Расширенные возможности 0.14") }
                    }
                    item { SectionHeader("Область работы") }
                    item {
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    if (state.accessMode == StorageAccessMode.Full) "Сейчас: весь накопитель" else "Сейчас: выбранная папка",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    state.folderStack.firstOrNull()?.label ?: "Папка не подключена",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                                Button(onClick = onChooseFolder, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Выбрать папку")
                                }
                                TextButton(onClick = onFullAccess, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Rounded.Storage, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Использовать весь накопитель")
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            "Избранное хранит ссылки на исходные файлы и не скрывает их от других приложений. Для настоящего тайного раздела потребуется отдельный зашифрованный сейф.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteAnimationChoice(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(16.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
    onClose: (() -> Unit)? = null,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("Поиск") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = when {
            onClose != null -> {
                {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, contentDescription = "Скрыть поиск")
                    }
                }
            }
            onAdvanced != null -> {
                {
                    val action = onAdvanced
                IconButton(onClick = action) {
                    Icon(
                        Icons.Rounded.Tune,
                        contentDescription = "Фильтры поиска",
                        tint = if (advancedActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            }
            else -> null
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
        Triple(FileCategory.Books, Icons.AutoMirrored.Rounded.MenuBook, AuraPurple),
        Triple(FileCategory.Apk, Icons.Rounded.Apps, AuraGreen),
        Triple(FileCategory.Downloads, Icons.Rounded.Download, AuraBlue),
        Triple(FileCategory.Camera, Icons.Rounded.PhotoCamera, AuraPink),
        Triple(FileCategory.Other, Icons.AutoMirrored.Rounded.InsertDriveFile, MaterialTheme.colorScheme.onSurfaceVariant),
    )
    val categorySummaries = remember(state.analysis) {
        state.analysis?.categories?.associateBy { it.category }.orEmpty()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowTiles.forEach { (category, icon, tint) ->
                    val summary = categorySummaries[category]
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

private fun isExtractableArchiveName(name: String): Boolean {
    return ArchiveBrowserActivity.isBrowsableArchiveName(name)
}

private fun categoryLabel(category: FileCategory): String = when (category) {
    FileCategory.Images -> "Изображения"
    FileCategory.Video -> "Видео"
    FileCategory.Audio -> "Аудио"
    FileCategory.Documents -> "Документы"
    FileCategory.Archives -> "Архивы"
    FileCategory.Books -> "Книги"
    FileCategory.Apk -> "APK"
    FileCategory.Downloads -> "Загрузки"
    FileCategory.Camera -> "Камера"
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
    searchVisible: Boolean,
    onSort: (FileSortMode) -> Unit,
    onViewMode: (FileViewMode) -> Unit,
    onToggleSearch: () -> Unit,
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
        IconButton(onClick = onToggleSearch) {
            Icon(
                if (searchVisible) Icons.Rounded.Close else Icons.Rounded.Search,
                contentDescription = if (searchVisible) "Скрыть поиск" else "Показать поиск",
                tint = if (searchVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            IconButton(
                onClick = {
                    onViewMode(if (viewMode == FileViewMode.List) FileViewMode.Grid else FileViewMode.List)
                },
            ) {
                Icon(
                    if (viewMode == FileViewMode.List) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList,
                    contentDescription = if (viewMode == FileViewMode.List) "Показать плиткой" else "Показать списком",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun BrowserListContent(
    entries: List<FileEntry>,
    groups: List<FileCollectionGroup>,
    selectedUris: Set<Uri>,
    selectionMode: Boolean,
    deletingUris: Set<Uri> = emptySet(),
    deleteAnimationMode: DeleteAnimationMode = DeleteAnimationMode.Dissolve,
    duplicateOriginalUris: Set<Uri> = emptySet(),
    onOpen: (FileEntry) -> Unit,
    onToggleSelection: (FileEntry) -> Unit,
    onRename: (FileEntry, String) -> Unit,
    onDelete: (FileEntry) -> Unit,
    onCopy: (FileEntry) -> Unit,
    onMove: (FileEntry) -> Unit,
    onShare: (FileEntry) -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
    onSetSystemSound: (FileEntry, SystemSoundType) -> Unit,
    onProperties: (FileEntry) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
    ) {
        val renderedGroups = if (groups.isEmpty()) listOf(FileCollectionGroup("", entries)) else groups
        renderedGroups.forEach { group ->
            if (group.title.isNotBlank()) {
                item(key = "header-${group.title}") {
                    Text(
                        group.title,
                        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 7.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            itemsIndexed(group.entries, key = { _, entry -> entry.uri.toString() }) { index, entry ->
                val shape = when {
                    group.entries.size == 1 -> RoundedCornerShape(22.dp)
                    index == 0 -> RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                    index == group.entries.lastIndex -> RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
                    else -> RoundedCornerShape(0.dp)
                }
                Surface(
                    modifier = Modifier.auraDeleteEffect(
                        active = entry.uri in deletingUris,
                        mode = deleteAnimationMode,
                        seed = entry.uri.toString().hashCode(),
                    ),
                    shape = shape,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column {
                        BrowserFileRow(
                            entry = entry,
                            selected = entry.uri in selectedUris,
                            selectionMode = selectionMode,
                            showLocation = groups.isNotEmpty(),
                            duplicateOriginal = entry.uri in duplicateOriginalUris,
                            onClick = { onOpen(entry) },
                            onToggleSelection = { onToggleSelection(entry) },
                            onRename = { onRename(entry, it) },
                            onDelete = { onDelete(entry) },
                            onCopy = { onCopy(entry) },
                            onMove = { onMove(entry) },
                            onShare = { onShare(entry) },
                                    onOpenExternal = { onOpenExternal(entry) },
                                    onSetSystemSound = { type -> onSetSystemSound(entry, type) },
                                    onProperties = { onProperties(entry) },
                        )
                        if (index != group.entries.lastIndex) {
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

@Composable
private fun BrowserGridContent(
    entries: List<FileEntry>,
    groups: List<FileCollectionGroup>,
    showThumbnails: Boolean,
    selectedUris: Set<Uri>,
    selectionMode: Boolean,
    deletingUris: Set<Uri> = emptySet(),
    deleteAnimationMode: DeleteAnimationMode = DeleteAnimationMode.Dissolve,
    duplicateOriginalUris: Set<Uri> = emptySet(),
    onOpen: (FileEntry) -> Unit,
    onToggleSelection: (FileEntry) -> Unit,
    onRename: (FileEntry, String) -> Unit,
    onDelete: (FileEntry) -> Unit,
    onCopy: (FileEntry) -> Unit,
    onMove: (FileEntry) -> Unit,
    onShare: (FileEntry) -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
    onSetSystemSound: (FileEntry, SystemSoundType) -> Unit,
    onProperties: (FileEntry) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val renderedGroups = if (groups.isEmpty()) listOf(FileCollectionGroup("", entries)) else groups
        renderedGroups.forEach { group ->
            if (group.title.isNotBlank()) {
                item(
                    key = "grid-header-${group.title}",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    Text(
                        group.title,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            items(group.entries, key = { it.uri.toString() }) { entry ->
                Box(
                    Modifier.auraDeleteEffect(
                        active = entry.uri in deletingUris,
                        mode = deleteAnimationMode,
                        seed = entry.uri.toString().hashCode(),
                    )
                ) {
                    GridFileTile(
                    entry = entry,
                    selected = entry.uri in selectedUris,
                    selectionMode = selectionMode,
                    showThumbnail = showThumbnails,
                    duplicateOriginal = entry.uri in duplicateOriginalUris,
                    onClick = { onOpen(entry) },
                    onToggleSelection = { onToggleSelection(entry) },
                    onRename = { onRename(entry, it) },
                    onDelete = { onDelete(entry) },
                    onCopy = { onCopy(entry) },
                    onMove = { onMove(entry) },
                    onShare = { onShare(entry) },
                    onOpenExternal = { onOpenExternal(entry) },
                    onSetSystemSound = { type -> onSetSystemSound(entry, type) },
                    onProperties = { onProperties(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GridFileTile(
    entry: FileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    showThumbnail: Boolean,
    duplicateOriginal: Boolean = false,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    onSetSystemSound: (SystemSoundType) -> Unit,
    onProperties: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var systemSoundOpen by remember { mutableStateOf(false) }

    val accent = fileAccent(entry)
    Surface(
        modifier = Modifier
            .height(210.dp)
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelection() else onClick() },
                onLongClick = onToggleSelection,
        ),
        shape = RoundedCornerShape(22.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, accent.copy(alpha = if (selected) 0.75f else 0.28f)),
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().height(128.dp).background(accent.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                if (entry.isDirectory) {
                    AppleFolderGlyph()
                } else if (showThumbnail) {
                    FileThumbnail(
                        entry = entry,
                        modifier = Modifier.fillMaxSize(),
                        fallback = { FileIcon(entry) },
                    )
                } else {
                    FileIcon(entry)
                }
                if (entry.isTemporaryCandidate()) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = AuraOrange.copy(alpha = 0.90f),
                    ) {
                        Text("временный", modifier = Modifier.padding(6.dp, 3.dp), color = Color.White, fontSize = 9.sp)
                    }
                }
                if (duplicateOriginal) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = AuraGreen.copy(alpha = 0.94f),
                    ) {
                        Text("ОРИГИНАЛ?", modifier = Modifier.padding(7.dp, 3.dp), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { if (selectionMode) onToggleSelection() else menuOpen = true }) {
                        Icon(
                            if (selectionMode) Icons.Rounded.CheckCircle else Icons.Rounded.MoreHoriz,
                            contentDescription = if (selectionMode) "Изменить выделение" else "Действия",
                            tint = if (selected) MaterialTheme.colorScheme.primary else accent,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (!entry.isDirectory) {
                            DropdownMenuItem(
                                text = { Text("Открыть в…") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Launch, contentDescription = null) },
                                onClick = { menuOpen = false; onOpenExternal() },
                            )
                        }
                        if (entry.isAudioFile()) {
                            DropdownMenuItem(
                                text = { Text("Назначить системным звуком") },
                                leadingIcon = { Icon(Icons.Rounded.MusicNote, contentDescription = null) },
                                onClick = { menuOpen = false; systemSoundOpen = true },
                            )
                        }
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
            Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(11.dp, 8.dp, 11.dp, 9.dp)) {
                Text(
                    entry.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (entry.isDirectory) "Папка" else formatBytes(entry.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
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
        DeleteEntriesDialog(
            entries = listOf(entry),
            onDismiss = { deleteOpen = false },
            onConfirm = { onDelete(); deleteOpen = false },
        )
    }
    if (systemSoundOpen) {
        SystemSoundDialog(
            entry = entry,
            onDismiss = { systemSoundOpen = false },
            onSelect = { type -> systemSoundOpen = false; onSetSystemSound(type) },
        )
    }
}

@Composable
private fun BrowserFileRow(
    entry: FileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    showLocation: Boolean = false,
    duplicateOriginal: Boolean = false,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit = {},
    onSetSystemSound: (SystemSoundType) -> Unit,
    onProperties: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var systemSoundOpen by remember { mutableStateOf(false) }

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
        Column(modifier = Modifier.weight(1f)) {
            FileCopy(
                entry = entry,
                modifier = Modifier.fillMaxWidth(),
                location = if (showLocation) entry.displayLocation() else null,
                temporaryCandidate = showLocation && entry.isTemporaryCandidate(),
            )
            if (duplicateOriginal) {
                Surface(
                    modifier = Modifier.padding(top = 3.dp),
                    shape = RoundedCornerShape(7.dp),
                    color = AuraGreen.copy(alpha = 0.16f),
                ) {
                    Text(
                        "★ Оригинал? · оставить",
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        color = AuraGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
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
                if (!entry.isDirectory) {
                    DropdownMenuItem(
                        text = { Text("Открыть в…") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Launch, contentDescription = null) },
                        onClick = { menuOpen = false; onOpenExternal() },
                    )
                }
                if (entry.isAudioFile()) {
                    DropdownMenuItem(
                        text = { Text("Назначить системным звуком") },
                        leadingIcon = { Icon(Icons.Rounded.MusicNote, contentDescription = null) },
                        onClick = { menuOpen = false; systemSoundOpen = true },
                    )
                }
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
    if (systemSoundOpen) {
        SystemSoundDialog(
            entry = entry,
            onDismiss = { systemSoundOpen = false },
            onSelect = { type -> systemSoundOpen = false; onSetSystemSound(type) },
        )
    }
}

@Composable
private fun SystemSoundDialog(
    entry: FileEntry,
    onDismiss: () -> Unit,
    onSelect: (SystemSoundType) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.MusicNote, contentDescription = null) },
        title = { Text("Назначить системным звуком") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "При первом использовании Android попросит разрешить Aura изменение системных настроек.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(SystemSoundType.Ringtone) }) { Text("Звонок") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSelect(SystemSoundType.Alarm) }) { Text("Будильник") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

@Composable
private fun SelectionHeader(
    count: Int,
    shareEnabled: Boolean,
    dateEnabled: Boolean,
    allFavorited: Boolean,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onChangeDate: () -> Unit,
    onClear: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 4.dp),
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
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreHoriz, contentDescription = "Ещё действия")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (allFavorited) "Убрать из избранного" else "В избранное") },
                    leadingIcon = {
                        Icon(
                            if (allFavorited) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onFavorite()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Поделиться") },
                    leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                    enabled = shareEnabled,
                    onClick = {
                        menuOpen = false
                        onShare()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Изменить дату") },
                    leadingIcon = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) },
                    enabled = dateEnabled,
                    onClick = {
                        menuOpen = false
                        onChangeDate()
                    },
                )
            }
        }
    }
}

@Composable
private fun SelectionBottomBar(
    extractEnabled: Boolean,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onArchive: () -> Unit,
    onExtract: () -> Unit,
    onBatchRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SelectionAction(Icons.Rounded.ContentCopy, "Копировать", onCopy)
            SelectionAction(Icons.Rounded.ContentCut, "Переместить", onMove)
            SelectionAction(Icons.Rounded.Archive, "Сжать", onArchive)
            SelectionAction(
                Icons.Rounded.Delete,
                "Удалить",
                onDelete,
                tint = MaterialTheme.colorScheme.error,
            )
            if (extractEnabled) SelectionAction(Icons.Rounded.FolderOpen, "Распаковать", onExtract)
            SelectionAction(Icons.Rounded.DriveFileRenameOutline, "Переименовать", onBatchRename)
        }
    }
}

@Composable
private fun SelectionAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val actualTint = if (enabled) tint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = actualTint, modifier = Modifier.size(23.dp))
        Text(label, fontSize = 10.sp, color = actualTint, maxLines = 1)
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
    val context = LocalContext.current
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
                TextButton(
                    onClick = { EnhancedPropertiesActivity.start(context, entry); onDismiss() },
                ) {
                    Text(if (entry.isDirectory) "Рассчитать размер папки" else "MD5 / SHA-1 / SHA-256")
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
        mime == "application/pdf" || extension in setOf("pdf", "djvu", "djv") -> Icons.Rounded.Description to AuraRed
        mime == "application/vnd.android.package-archive" || extension == "apk" -> Icons.Rounded.Apps to AuraGreen
        mime.contains("zip") || mime.contains("archive") || extension in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "tbz2", "txz") ->
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
private fun fileAccent(entry: FileEntry): Color {
    if (entry.isDirectory) return AuraBlue
    val mime = entry.mimeType.orEmpty().lowercase()
    val extension = entry.name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("image/") || extension in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "svg") -> AuraPink
        mime.startsWith("video/") || extension in setOf("mp4", "mkv", "avi", "mov", "webm", "3gp") -> AuraPurple
        mime.startsWith("audio/") || extension in setOf("mp3", "m4a", "wav", "flac", "ogg", "aac") -> AuraGreen
        extension in setOf("pdf", "djvu", "djv") -> AuraRed
        extension == "apk" -> AuraGreen
        extension in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "tbz2", "txz") -> AuraOrange
        extension in setOf("txt", "md", "doc", "docx", "rtf", "odt") -> AuraBlue
        else -> MaterialTheme.colorScheme.onSurfaceVariant
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
private fun FileCopy(
    entry: FileEntry,
    modifier: Modifier = Modifier,
    location: String? = null,
    temporaryCandidate: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(
            entry.name,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            when {
                temporaryCandidate -> "Кандидат на удаление · ${entry.sourceLabel()}"
                location != null -> "${entry.sourceLabel()} · $location"
                else -> fileDetails(entry)
            },
            color = if (temporaryCandidate) AuraOrange else MaterialTheme.colorScheme.onSurfaceVariant,
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
    if (entries.size == 1) {
        NameDialog(
            title = "Переименовать",
            initialValue = entries.first().name,
            confirmLabel = "Готово",
            onDismiss = onDismiss,
            onConfirm = { onConfirm(listOf(it)) },
        )
        return
    }

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
    val summaries = analysis.categories.filter {
        it.bytes > 0L && it.category !in setOf(FileCategory.Downloads, FileCategory.Camera)
    }
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
    FileCategory.Books -> AuraPurple
    FileCategory.Apk -> AuraGreen
    FileCategory.Downloads -> AuraBlue
    FileCategory.Camera -> AuraPink
    FileCategory.Other -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun TrashDialog(
    records: List<TrashRecord>,
    deletingUris: Set<Uri>,
    deleteAnimationMode: DeleteAnimationMode,
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .auraDeleteEffect(
                                    active = record.entry.uri in deletingUris,
                                    mode = deleteAnimationMode,
                                    seed = record.entry.uri.toString().hashCode(),
                                )
                                .padding(vertical = 6.dp),
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

private fun startSftpServer(context: Context, state: FileManagerUiState, config: SftpServerConfig) {
    val root = state.folderStack.firstOrNull()
    if (root == null) {
        Toast.makeText(context, "Сначала подключите локальную папку", Toast.LENGTH_LONG).show()
        return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
        Toast.makeText(
            context,
            "Для SFTP-сервера включите «Весь накопитель» в настройках Aura",
            Toast.LENGTH_LONG,
        ).show()
        return
    }
    runCatching {
        SftpServerService.start(context, root.document.uri, root.label, config)
    }.onFailure {
        Toast.makeText(context, it.message ?: "Не удалось запустить SFTP-сервер", Toast.LENGTH_LONG).show()
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
