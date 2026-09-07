package com.aurafiles.app.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.SavedStateViewModelFactory
import androidx.core.content.FileProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Launch
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Splitscreen
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurafiles.app.backend.StorageBackendDescriptor
import com.aurafiles.app.backend.StorageBackendKind
import com.aurafiles.app.data.FileRepository
import com.aurafiles.app.backend.StorageItem
import com.aurafiles.app.model.FileSortMode
import com.aurafiles.app.model.FileViewMode
import com.aurafiles.app.model.SftpProfile
import com.aurafiles.app.sync.DirectoryDifference
import com.aurafiles.app.sync.SyncDirection
import com.aurafiles.app.transfer.TransferConflictPolicy
import com.aurafiles.app.transfer.TransferState
import com.aurafiles.app.ui.theme.AuraFilesTheme
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BackendWorkspaceActivity : ComponentActivity() {
    private val viewModel: BackendWorkspaceViewModel by viewModels {
        SavedStateViewModelFactory(application, this)
    }
    private val googleAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.completeGoogleAuthorization(
            data = result.data,
            resultCode = result.resultCode,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialBackendId = intent.getStringExtra(EXTRA_INITIAL_BACKEND_ID)
        val addYandexOnOpen = intent.getBooleanExtra(EXTRA_ADD_YANDEX, false)
        val addGoogleOnOpen = intent.getBooleanExtra(EXTRA_ADD_GOOGLE, false)
        val singlePane = intent.getBooleanExtra(EXTRA_SINGLE_PANE, false)
        setContent {
            AuraFilesTheme {
                val state by viewModel.state.collectAsState()
                BackendWorkspaceScreen(
                    state = state,
                    viewModel = viewModel,
                    initialBackendId = initialBackendId,
                    addYandexOnOpen = addYandexOnOpen,
                    addGoogleOnOpen = addGoogleOnOpen,
                    singlePane = singlePane,
                    onLaunchGoogleResolution = { pending ->
                        viewModel.markGoogleResolutionLaunched()
                        googleAuthorizationLauncher.launch(
                            IntentSenderRequest.Builder(pending.intentSender).build()
                        )
                    },
                    onReopenBackend = { backendId ->
                        viewModel.consumeReopenBackendRequest()
                        startActivity(
                            Intent(this, BackendWorkspaceActivity::class.java)
                                .putExtra(EXTRA_INITIAL_BACKEND_ID, backendId)
                                .putExtra(EXTRA_SINGLE_PANE, singlePane)
                        )
                        finish()
                    },
                    onSwitchToDualPane = { backendId ->
                        startActivity(
                            Intent(this, BackendWorkspaceActivity::class.java)
                                .putExtra(EXTRA_INITIAL_BACKEND_ID, backendId)
                                .putExtra(EXTRA_SINGLE_PANE, false)
                        )
                        finish()
                    },
                    onClose = ::finish,
                )
            }
        }
    }

    companion object {
        const val EXTRA_INITIAL_BACKEND_ID = "com.aurafiles.app.extra.INITIAL_BACKEND_ID"
        const val EXTRA_ADD_YANDEX = "com.aurafiles.app.extra.ADD_YANDEX"
        const val EXTRA_ADD_GOOGLE = "com.aurafiles.app.extra.ADD_GOOGLE"
        const val EXTRA_SINGLE_PANE = "com.aurafiles.app.extra.SINGLE_PANE"
    }
}

@Composable
private fun BackendWorkspaceScreen(
    state: WorkspaceState,
    viewModel: BackendWorkspaceViewModel,
    initialBackendId: String?,
    addYandexOnOpen: Boolean,
    addGoogleOnOpen: Boolean,
    singlePane: Boolean,
    onLaunchGoogleResolution: (PendingIntent) -> Unit,
    onReopenBackend: (String) -> Unit,
    onSwitchToDualPane: (String) -> Unit,
    onClose: () -> Unit,
) {
    var sftpDialog by remember { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var addYandexApplied by rememberSaveable(addYandexOnOpen) { mutableStateOf(false) }
    var addGoogleApplied by rememberSaveable(addGoogleOnOpen) { mutableStateOf(false) }
    var initialBackendApplied by rememberSaveable(initialBackendId) { mutableStateOf(false) }
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(10_000L)
            viewModel.dismissMessage()
        }
    }
    LaunchedEffect(initialBackendId, state.backends, state.cloudProfiles, initialBackendApplied) {
        val backendId = initialBackendId ?: return@LaunchedEffect
        if (!initialBackendApplied && (state.backends.isNotEmpty() || state.cloudProfiles.isNotEmpty())) {
            viewModel.openBackendFromNetwork(backendId)
            initialBackendApplied = true
        }
    }
    LaunchedEffect(addYandexOnOpen, addYandexApplied) {
        if (addYandexOnOpen && !addYandexApplied) {
            addYandexApplied = true
            viewModel.showAddYandexDialog()
        }
    }
    LaunchedEffect(addGoogleOnOpen, addGoogleApplied) {
        if (addGoogleOnOpen && !addGoogleApplied) {
            addGoogleApplied = true
            viewModel.showAddGoogleDialog()
        }
    }
    val googleResolution = (state.googleAuth as? GoogleAuthUiState.NeedsResolution)?.pendingIntent
    LaunchedEffect(googleResolution) {
        if (googleResolution != null) onLaunchGoogleResolution(googleResolution)
    }
    LaunchedEffect(state.reopenBackendId) {
        state.reopenBackendId?.let(onReopenBackend)
    }
    var toolsOpen by remember { mutableStateOf(false) }
    var dragMove by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(state.openFileRequest) {
        val request = state.openFileRequest ?: return@LaunchedEffect
        viewModel.consumeOpenFileRequest()
        runCatching {
            val file = File(request.absolutePath)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            if (request.displayName.substringAfterLast('.', "").equals("apks", ignoreCase = true)) {
                SplitPackageInstallerActivity.start(context, uri, request.displayName)
            } else {
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, request.mimeType ?: "application/octet-stream")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(intent)
            }
        }.onFailure { error ->
            Toast.makeText(context, error.message ?: "Не удалось открыть ${request.displayName}", Toast.LENGTH_LONG).show()
        }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        if (!singlePane) Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Закрыть") }
            Column(Modifier.weight(1f)) {
                val openedForSftp = initialBackendId?.startsWith("sftp:") == true
                val openedForYandex = initialBackendId?.startsWith("yandex:") == true
                val openedForGoogle = initialBackendId?.startsWith("google:") == true
                Text(
                    when {
                        openedForYandex -> "Яндекс.Диск"
                        openedForGoogle -> "Google Drive"
                        openedForSftp -> "SFTP"
                        else -> "Универсальные панели"
                    },
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        openedForYandex -> if (singlePane) "Файлы в Яндекс.Диске" else "Яндекс.Диск · локальное хранилище"
                        openedForGoogle -> if (singlePane) "Файлы в Google Drive" else "Google Drive · локальное хранилище"
                        openedForSftp -> "SFTP-сервер · локальное хранилище"
                        else -> "Local · SMB · FTP · SFTP · Яндекс.Диск · Google Drive"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!singlePane) {
                Box {
                    IconButton(onClick = { addMenuOpen = true }) { Icon(Icons.Rounded.Add, contentDescription = "Добавить подключение") }
                    DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Rounded.Cloud, contentDescription = null) },
                            text = { Text("Яндекс.Диск") },
                            onClick = { addMenuOpen = false; viewModel.showAddYandexDialog() },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Rounded.Cloud, contentDescription = null) },
                            text = { Text("Google Drive") },
                            onClick = { addMenuOpen = false; viewModel.showAddGoogleDialog() },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Rounded.Storage, contentDescription = null) },
                            text = { Text("SFTP") },
                            onClick = { addMenuOpen = false; sftpDialog = true },
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { toolsOpen = true }) { Icon(Icons.Rounded.MoreHoriz, contentDescription = "Инструменты") }
                DropdownMenu(expanded = toolsOpen, onDismissRequest = { toolsOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Похожие фотографии") },
                        onClick = { toolsOpen = false; context.startActivity(Intent(context, SimilarPhotosActivity::class.java)) },
                    )
                    if (!singlePane) {
                        DropdownMenuItem(
                            text = { Text("Drag & Drop: ${if (dragMove) "перемещать" else "копировать"}") },
                            onClick = { dragMove = !dragMove },
                        )
                    }
                    if (singlePane) {
                        DropdownMenuItem(
                            text = { Text("Двухпанельный режим") },
                            onClick = {
                                toolsOpen = false
                                state.left.backendId?.let(onSwitchToDualPane)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Расширенные настройки") },
                        onClick = { toolsOpen = false; context.startActivity(Intent(context, AdvancedSettingsActivity::class.java)) },
                    )
                }
            }
            if (!singlePane) {
                IconButton(onClick = viewModel::comparePanels, enabled = state.busyLabel == null) {
                    Icon(Icons.AutoMirrored.Rounded.CompareArrows, contentDescription = "Сравнить панели")
                }
            }
        }
        if (singlePane) {
            StandardBackendBrowser(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                pane = state.left,
                descriptor = state.backends.firstOrNull { it.id == state.left.backendId },
                clipboard = state.clipboard,
                busy = state.busyLabel != null,
                onClose = onClose,
                onOpen = { viewModel.openOrPreview(true, it) },
                onToggle = { viewModel.toggleSelection(true, it) },
                onClearSelection = { viewModel.clearSelection(true) },
                onBack = { viewModel.back(true) },
                onRefresh = { viewModel.refresh(true) },
                onSwitchToDualPane = { state.left.backendId?.let(onSwitchToDualPane) },
                onCreateFolder = { viewModel.createFolder(true, it) },
                onRename = { item, name -> viewModel.renameItem(true, item, name) },
                onDelete = { items -> viewModel.deleteItems(true, items) },
                onCopy = { item -> viewModel.setClipboardItem(true, item, move = false) },
                onMove = { item -> viewModel.setClipboardItem(true, item, move = true) },
                onCopySelected = { viewModel.setClipboard(true, move = false) },
                onMoveSelected = { viewModel.setClipboard(true, move = true) },
                onPaste = { viewModel.pasteClipboard(true) },
                onClearClipboard = viewModel::clearClipboard,
            )
        } else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
            val landscapeLayout = maxWidth >= 700.dp
            if (landscapeLayout) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackendPane(
                        modifier = Modifier.weight(1f),
                        title = "Левая",
                        pane = state.left,
                        descriptors = state.backends,
                        busy = state.busyLabel != null,
                        onBackend = { viewModel.selectBackend(true, it) },
                        onOpen = { viewModel.open(true, it) },
                        onToggle = { viewModel.toggleSelection(true, it) },
                        onBack = { viewModel.back(true) },
                        onForward = { viewModel.forward(true) },
                        onRefresh = { viewModel.refresh(true) },
                        onRecent = { viewModel.goRecent(true, it) },
                        onCopy = { viewModel.copySelected(true, move = false) },
                        onMove = { viewModel.copySelected(true, move = true) },
                        onCreateFolder = { viewModel.createFolder(true, it) },
                        onRename = { viewModel.renameSelected(true, it) },
                        onDelete = { viewModel.deleteSelected(true) },
                        isLeft = true,
                        onDrop = { payload -> viewModel.transferSingle(payload.fromLeft, payload.item, move = dragMove) },
                    )
                    BackendPane(
                        modifier = Modifier.weight(1f),
                        title = "Правая",
                        pane = state.right,
                        descriptors = state.backends,
                        busy = state.busyLabel != null,
                        onBackend = { viewModel.selectBackend(false, it) },
                        onOpen = { viewModel.open(false, it) },
                        onToggle = { viewModel.toggleSelection(false, it) },
                        onBack = { viewModel.back(false) },
                        onForward = { viewModel.forward(false) },
                        onRefresh = { viewModel.refresh(false) },
                        onRecent = { viewModel.goRecent(false, it) },
                        onCopy = { viewModel.copySelected(false, move = false) },
                        onMove = { viewModel.copySelected(false, move = true) },
                        onCreateFolder = { viewModel.createFolder(false, it) },
                        onRename = { viewModel.renameSelected(false, it) },
                        onDelete = { viewModel.deleteSelected(false) },
                        isLeft = false,
                        onDrop = { payload -> viewModel.transferSingle(payload.fromLeft, payload.item, move = dragMove) },
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackendPane(
                        modifier = Modifier.weight(1f), title = "Верхняя", pane = state.left, descriptors = state.backends,
                        busy = state.busyLabel != null, onBackend = { viewModel.selectBackend(true, it) },
                        onOpen = { viewModel.open(true, it) }, onToggle = { viewModel.toggleSelection(true, it) },
                        onBack = { viewModel.back(true) }, onForward = { viewModel.forward(true) }, onRefresh = { viewModel.refresh(true) },
                        onRecent = { viewModel.goRecent(true, it) }, onCopy = { viewModel.copySelected(true) },
                        onMove = { viewModel.copySelected(true, true) },
                        onCreateFolder = { viewModel.createFolder(true, it) }, onRename = { viewModel.renameSelected(true, it) },
                        onDelete = { viewModel.deleteSelected(true) }, isLeft = true,
                        onDrop = { payload -> viewModel.transferSingle(payload.fromLeft, payload.item, move = dragMove) },
                    )
                    BackendPane(
                        modifier = Modifier.weight(1f), title = "Нижняя", pane = state.right, descriptors = state.backends,
                        busy = state.busyLabel != null, onBackend = { viewModel.selectBackend(false, it) },
                        onOpen = { viewModel.open(false, it) }, onToggle = { viewModel.toggleSelection(false, it) },
                        onBack = { viewModel.back(false) }, onForward = { viewModel.forward(false) }, onRefresh = { viewModel.refresh(false) },
                        onRecent = { viewModel.goRecent(false, it) }, onCopy = { viewModel.copySelected(false) },
                        onMove = { viewModel.copySelected(false, true) },
                        onCreateFolder = { viewModel.createFolder(false, it) }, onRename = { viewModel.renameSelected(false, it) },
                        onDelete = { viewModel.deleteSelected(false) }, isLeft = false,
                        onDrop = { payload -> viewModel.transferSingle(payload.fromLeft, payload.item, move = dragMove) },
                    )
                }
            }
        }
        state.busyLabel?.let { label ->
            Surface(tonalElevation = 4.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, fontWeight = FontWeight.Medium)
                        state.transfer?.let { p ->
                            Text(
                                "${p.currentItem}/${p.totalItems} · ${p.currentName}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    val paused = state.transfer?.state == TransferState.PAUSED
                    IconButton(onClick = { if (paused) viewModel.resume() else viewModel.pause(); Unit }) {
                        Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, contentDescription = if (paused) "Продолжить" else "Пауза")
                    }
                    IconButton(onClick = viewModel::cancel) { Icon(Icons.Rounded.Stop, contentDescription = "Остановить") }
                }
            }
        }
        }
    }

    YandexAuthDialog(
        state = state.yandexAuth,
        onStart = viewModel::startYandexAuthorization,
        onVerificationOpened = viewModel::markYandexVerificationOpened,
        onDismiss = viewModel::dismissYandexDialog,
    )
    GoogleAuthDialog(
        state = state.googleAuth,
        onRetry = viewModel::showAddGoogleDialog,
        onDismiss = viewModel::cancelGoogleAuthorization,
    )

    state.conflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Файл уже существует") },
            text = { Text(conflict.sourceName) },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveConflict(TransferConflictPolicy.REPLACE, false) }) { Text("Заменить") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.resolveConflict(TransferConflictPolicy.KEEP_BOTH, false) }) { Text("Оставить оба") }
                    TextButton(onClick = { viewModel.resolveConflict(TransferConflictPolicy.SKIP, false) }) { Text("Пропустить") }
                }
            },
        )
    }

    state.comparison?.let { comparison ->
        ComparisonDialog(
            comparison = comparison,
            onDismiss = viewModel::dismissComparison,
            onSync = viewModel::prepareSync,
        )
    }
    state.syncPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSyncPlan,
            title = { Text("План синхронизации") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Будет скопировано: ${plan.filesToCopy} файлов")
                    Text("Будет заменено: ${plan.filesToReplace} файлов")
                    Text("Будет удалено: ${plan.objectsToDelete}")
                    Text("Объём записи: ${formatBackendBytes(plan.bytesToCopy)}")
                    if (!plan.deleteExtraneous) Text("Удаление лишних файлов выключено.", color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = { Button(onClick = viewModel::executeSync) { Text("Выполнить") } },
            dismissButton = { TextButton(onClick = viewModel::dismissSyncPlan) { Text("Отмена") } },
        )
    }

    state.pendingHostKey?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::rejectHostKey,
            title = { Text(if (pending.previous == null) "Новый SFTP-сервер" else "Ключ SFTP изменился") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${pending.host}\n${pending.fingerprint}")
                    pending.previous?.let { Text("Ранее: $it", color = MaterialTheme.colorScheme.error) }
                    Text(if (pending.previous == null) "Сверьте fingerprint сервера перед доверием." else "Не принимайте новый ключ, пока не проверите причину изменения.")
                }
            },
            confirmButton = { Button(onClick = viewModel::acceptHostKey) { Text("Доверять этому ключу") } },
            dismissButton = { TextButton(onClick = viewModel::rejectHostKey) { Text("Отмена") } },
        )
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            title = { Text("Aura Files") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
        )
    }
    if (sftpDialog) {
        SftpConnectionDialog(
            onDismiss = { sftpDialog = false },
            onSave = { profile -> viewModel.saveSftp(profile); sftpDialog = false },
        )
    }
}

@Composable
private fun StandardBackendBrowser(
    modifier: Modifier,
    pane: BackendPaneState,
    descriptor: StorageBackendDescriptor?,
    clipboard: BackendClipboard?,
    busy: Boolean,
    onClose: () -> Unit,
    onOpen: (StorageItem) -> Unit,
    onToggle: (StorageItem) -> Unit,
    onClearSelection: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSwitchToDualPane: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onRename: (StorageItem, String) -> Unit,
    onDelete: (List<StorageItem>) -> Unit,
    onCopy: (StorageItem) -> Unit,
    onMove: (StorageItem) -> Unit,
    onCopySelected: () -> Unit,
    onMoveSelected: () -> Unit,
    onPaste: () -> Unit,
    onClearClipboard: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(FileSortMode.Name) }
    var sortAscending by remember { mutableStateOf(true) }
    var viewMode by remember { mutableStateOf(FileViewMode.List) }
    var createFolderOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<StorageItem?>(null) }
    var deleteTargets by remember { mutableStateOf<List<StorageItem>>(emptyList()) }
    var propertiesTarget by remember { mutableStateOf<StorageItem?>(null) }

    val selectedItems = remember(pane.items, pane.selected) {
        pane.items.filter { it.path in pane.selected }
    }
    val shownItems = remember(pane.items, query, sortMode, sortAscending) {
        sortBackendItems(
            pane.items.filter { it.name.contains(query, ignoreCase = true) },
            sortMode,
            sortAscending,
        )
    }
    val backendTitle = descriptor?.title ?: "Хранилище"
    val backendKind = descriptor?.kind
    val providerTitle = when (backendKind) {
        StorageBackendKind.YANDEX_DISK -> "Яндекс.Диск"
        StorageBackendKind.GOOGLE_DRIVE -> "Google Drive"
        else -> backendTitle
    }
    val folderTitle = when {
        pane.path == "/" -> providerTitle
        backendKind == StorageBackendKind.GOOGLE_DRIVE -> providerTitle
        else -> pane.path.substringAfterLast('/').ifBlank { providerTitle }
    }

    LaunchedEffect(pane.path) {
        query = ""
        searchVisible = false
    }

    BackHandler {
        if (!busy) {
            when {
                selectedItems.isNotEmpty() -> onClearSelection()
                pane.back.isNotEmpty() -> onBack()
                else -> onClose()
            }
        }
    }

    Column(modifier) {
        if (selectedItems.isEmpty()) {
            BackendBrowserHeader(
                title = folderTitle,
                onBack = { if (pane.back.isNotEmpty()) onBack() else onClose() },
                backEnabled = !busy,
            )
        } else {
            BackendSelectionHeader(
                count = selectedItems.size,
                propertiesEnabled = selectedItems.size == 1,
                onProperties = { propertiesTarget = selectedItems.singleOrNull() },
                onClear = onClearSelection,
            )
        }

        BackendBreadcrumbs(
            pane = pane,
            descriptor = descriptor,
            busy = busy,
            onRefresh = onRefresh,
            onDualPane = onSwitchToDualPane,
            onCreateFolder = { if (!busy) createFolderOpen = true },
        )

        BrowserControls(
            sortMode = sortMode,
            ascending = sortAscending,
            viewMode = viewMode,
            searchVisible = searchVisible,
            onSort = { requested ->
                if (requested == sortMode) sortAscending = !sortAscending
                else {
                    sortMode = requested
                    sortAscending = true
                }
            },
            onViewMode = { viewMode = it },
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

        AnimatedVisibility(visible = clipboard != null, enter = fadeIn(), exit = fadeOut()) {
            clipboard?.let { clip ->
                BackendClipboardBar(
                    clipboard = clip,
                    busy = busy,
                    onPaste = onPaste,
                    onClear = onClearClipboard,
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                pane.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                shownItems.isEmpty() -> EmptyFolder(Modifier.align(Alignment.Center))
                viewMode == FileViewMode.List -> BackendStandardList(
                    items = shownItems,
                    selectedPaths = pane.selected,
                    selectionMode = selectedItems.isNotEmpty(),
                    busy = busy,
                    onOpen = onOpen,
                    onToggle = onToggle,
                    onCopy = onCopy,
                    onMove = onMove,
                    onRename = { renameTarget = it },
                    onDelete = { deleteTargets = listOf(it) },
                    onProperties = { propertiesTarget = it },
                )
                else -> BackendStandardGrid(
                    items = shownItems,
                    selectedPaths = pane.selected,
                    selectionMode = selectedItems.isNotEmpty(),
                    busy = busy,
                    onOpen = onOpen,
                    onToggle = onToggle,
                    onCopy = onCopy,
                    onMove = onMove,
                    onRename = { renameTarget = it },
                    onDelete = { deleteTargets = listOf(it) },
                    onProperties = { propertiesTarget = it },
                )
            }
        }

        if (selectedItems.isNotEmpty()) {
            BackendSelectionBottomBar(
                copyEnabled = selectedItems.none(StorageItem::isLink) && !busy,
                moveEnabled = selectedItems.none(StorageItem::isLink) && !busy,
                renameEnabled = selectedItems.size == 1 && !busy,
                deleteEnabled = !busy,
                onCopy = onCopySelected,
                onMove = onMoveSelected,
                onRename = { renameTarget = selectedItems.singleOrNull() },
                onDelete = { deleteTargets = selectedItems },
            )
        }
    }

    if (createFolderOpen) {
        NameDialog(
            title = "Новая папка",
            initialValue = "",
            confirmLabel = "Создать",
            onDismiss = { createFolderOpen = false },
            onConfirm = {
                createFolderOpen = false
                onCreateFolder(it)
            },
        )
    }

    renameTarget?.let { item ->
        NameDialog(
            title = "Переименовать",
            initialValue = item.name,
            confirmLabel = "Готово",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                onRename(item, name)
                onClearSelection()
            },
        )
    }

    propertiesTarget?.let { item ->
        BackendPropertiesDialog(
            item = item,
            backendTitle = backendTitle,
            onDismiss = { propertiesTarget = null },
        )
    }

    if (deleteTargets.isNotEmpty()) {
        BackendDeleteDialog(
            items = deleteTargets,
            backendKind = backendKind,
            onDismiss = { deleteTargets = emptyList() },
            onConfirm = {
                val targets = deleteTargets
                deleteTargets = emptyList()
                onDelete(targets)
                onClearSelection()
            },
        )
    }
}

@Composable
private fun BackendBrowserHeader(
    title: String,
    onBack: () -> Unit,
    backEnabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, enabled = backEnabled) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Назад",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BackendBreadcrumbs(
    pane: BackendPaneState,
    descriptor: StorageBackendDescriptor?,
    busy: Boolean,
    onRefresh: () -> Unit,
    onDualPane: () -> Unit,
    onCreateFolder: () -> Unit,
) {
    val title = descriptor?.title ?: "Хранилище"
    val segments = if (descriptor?.kind == StorageBackendKind.GOOGLE_DRIVE) {
        emptyList()
    } else {
        pane.path.removePrefix("/").split('/').filter(String::isNotBlank)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 2.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            segments.forEach { segment ->
                Text("  ›  ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(segment, fontSize = 12.sp)
            }
        }
        IconButton(onClick = onRefresh, enabled = !busy) {
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = "Обновить",
                tint = if (busy) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                else MaterialTheme.colorScheme.onSurface,
            )
        }
        TextButton(onClick = onDualPane, enabled = !busy) {
            val actionColor = if (busy) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            else MaterialTheme.colorScheme.onSurface
            Icon(
                Icons.Rounded.Splitscreen,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = actionColor,
            )
            Spacer(Modifier.width(4.dp))
            Text("2 панели", maxLines = 1, fontSize = 12.sp, color = actionColor)
        }
        IconButton(onClick = onCreateFolder, enabled = !busy) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = "Создать папку",
                tint = if (busy) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun BackendSelectionHeader(
    count: Int,
    propertiesEnabled: Boolean,
    onProperties: () -> Unit,
    onClear: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, contentDescription = "Снять выделение") }
        Text("$count выбрано", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreHoriz, contentDescription = "Ещё действия") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Свойства") },
                    leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                    enabled = propertiesEnabled,
                    onClick = { menuOpen = false; onProperties() },
                )
            }
        }
    }
}

@Composable
private fun BackendClipboardBar(
    clipboard: BackendClipboard,
    busy: Boolean,
    onPaste: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (clipboard.move) Icons.Rounded.ContentCut else Icons.Rounded.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (clipboard.items.size == 1) clipboard.items.first().name else "Выбрано объектов: ${clipboard.items.size}",
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
            )
            TextButton(onClick = onClear) { Text("Отмена") }
            Button(onClick = onPaste, enabled = !busy) { Text("Вставить") }
        }
    }
}

@Composable
private fun BackendStandardList(
    items: List<StorageItem>,
    selectedPaths: Set<String>,
    selectionMode: Boolean,
    busy: Boolean,
    onOpen: (StorageItem) -> Unit,
    onToggle: (StorageItem) -> Unit,
    onCopy: (StorageItem) -> Unit,
    onMove: (StorageItem) -> Unit,
    onRename: (StorageItem) -> Unit,
    onDelete: (StorageItem) -> Unit,
    onProperties: (StorageItem) -> Unit,
) {
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp)) {
        itemsIndexed(items, key = { _, item -> item.path }) { index, item ->
            val shape = when {
                items.size == 1 -> RoundedCornerShape(22.dp)
                index == 0 -> RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                index == items.lastIndex -> RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
                else -> RoundedCornerShape(0.dp)
            }
            Surface(shape = shape, color = MaterialTheme.colorScheme.surface) {
                Column {
                    BackendStandardRow(
                        item = item,
                        selected = item.path in selectedPaths,
                        selectionMode = selectionMode,
                        busy = busy,
                        onOpen = { onOpen(item) },
                        onToggle = { onToggle(item) },
                        onCopy = { onCopy(item) },
                        onMove = { onMove(item) },
                        onRename = { onRename(item) },
                        onDelete = { onDelete(item) },
                        onProperties = { onProperties(item) },
                    )
                    if (index != items.lastIndex) {
                        HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    }
                }
            }
        }
    }
}

@Composable
private fun BackendStandardRow(
    item: StorageItem,
    selected: Boolean,
    selectionMode: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f) else MaterialTheme.colorScheme.surface)
            .combinedClickable(
                enabled = !busy,
                onClick = { if (selectionMode) onToggle() else if (!item.isLink) onOpen() },
                onLongClick = onToggle,
            )
            .padding(start = 14.dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackendItemIcon(item, Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(backendItemDetails(item), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
        }
        if (selectionMode) {
            IconButton(onClick = onToggle, enabled = !busy) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Box {
            IconButton(onClick = { menuOpen = true }, enabled = !busy) { Icon(Icons.Rounded.MoreHoriz, contentDescription = "Действия") }
            BackendItemMenu(
                expanded = menuOpen,
                item = item,
                onDismiss = { menuOpen = false },
                onOpen = onOpen,
                onCopy = onCopy,
                onMove = onMove,
                onRename = onRename,
                onDelete = onDelete,
                onProperties = onProperties,
            )
        }
    }
}

@Composable
private fun BackendStandardGrid(
    items: List<StorageItem>,
    selectedPaths: Set<String>,
    selectionMode: Boolean,
    busy: Boolean,
    onOpen: (StorageItem) -> Unit,
    onToggle: (StorageItem) -> Unit,
    onCopy: (StorageItem) -> Unit,
    onMove: (StorageItem) -> Unit,
    onRename: (StorageItem) -> Unit,
    onDelete: (StorageItem) -> Unit,
    onProperties: (StorageItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        gridItems(items, key = { it.path }) { item ->
            BackendStandardTile(
                item = item,
                selected = item.path in selectedPaths,
                selectionMode = selectionMode,
                busy = busy,
                onOpen = { onOpen(item) },
                onToggle = { onToggle(item) },
                onCopy = { onCopy(item) },
                onMove = { onMove(item) },
                onRename = { onRename(item) },
                onDelete = { onDelete(item) },
                onProperties = { onProperties(item) },
            )
        }
    }
}

@Composable
private fun BackendStandardTile(
    item: StorageItem,
    selected: Boolean,
    selectionMode: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .height(190.dp)
            .combinedClickable(
                enabled = !busy,
                onClick = { if (selectionMode) onToggle() else if (!item.isLink) onOpen() },
                onLongClick = onToggle,
            ),
        shape = RoundedCornerShape(22.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (selected) 0.75f else 0.18f)),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().height(112.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)),
                contentAlignment = Alignment.Center,
            ) {
                BackendItemIcon(item, Modifier.size(52.dp))
                Box(Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { if (selectionMode) onToggle() else menuOpen = true }, enabled = !busy) {
                        Icon(if (selectionMode) Icons.Rounded.CheckCircle else Icons.Rounded.MoreHoriz, contentDescription = null)
                    }
                    BackendItemMenu(
                        expanded = menuOpen,
                        item = item,
                        onDismiss = { menuOpen = false },
                        onOpen = onOpen,
                        onCopy = onCopy,
                        onMove = onMove,
                        onRename = onRename,
                        onDelete = onDelete,
                        onProperties = onProperties,
                    )
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f).padding(11.dp, 8.dp, 11.dp, 9.dp)) {
                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(if (item.isDirectory) "Папка" else formatBytes(item.size), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun BackendItemMenu(
    expanded: Boolean,
    item: StorageItem,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!item.isDirectory) {
            DropdownMenuItem(
                text = { Text("Открыть в…") },
                leadingIcon = { Icon(Icons.Rounded.Launch, contentDescription = null) },
                enabled = !item.isLink,
                onClick = { onDismiss(); onOpen() },
            )
        }
        DropdownMenuItem(
            text = { Text("Копировать") },
            leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
            enabled = !item.isLink,
            onClick = { onDismiss(); onCopy() },
        )
        DropdownMenuItem(
            text = { Text("Переместить") },
            leadingIcon = { Icon(Icons.Rounded.ContentCut, contentDescription = null) },
            enabled = !item.isLink,
            onClick = { onDismiss(); onMove() },
        )
        DropdownMenuItem(
            text = { Text("Свойства") },
            leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
            onClick = { onDismiss(); onProperties() },
        )
        DropdownMenuItem(
            text = { Text("Переименовать") },
            leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null) },
            onClick = { onDismiss(); onRename() },
        )
        DropdownMenuItem(
            text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); onDelete() },
        )
    }
}

@Composable
private fun BackendItemIcon(item: StorageItem, modifier: Modifier = Modifier) {
    val icon = when {
        item.isDirectory -> Icons.Rounded.Folder
        item.mimeType?.startsWith("image/") == true -> Icons.Rounded.Image
        item.mimeType?.startsWith("video/") == true -> Icons.Rounded.Movie
        item.mimeType?.startsWith("audio/") == true -> Icons.Rounded.MusicNote
        else -> Icons.Rounded.Description
    }
    Icon(icon, contentDescription = null, modifier = modifier, tint = MaterialTheme.colorScheme.primary)
}

@Composable
private fun BackendSelectionBottomBar(
    copyEnabled: Boolean,
    moveEnabled: Boolean,
    renameEnabled: Boolean,
    deleteEnabled: Boolean,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f), tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BackendSelectionAction(Icons.Rounded.ContentCopy, "Копировать", copyEnabled, onCopy)
            BackendSelectionAction(Icons.Rounded.ContentCut, "Переместить", moveEnabled, onMove)
            BackendSelectionAction(Icons.Rounded.DriveFileRenameOutline, "Переименовать", renameEnabled, onRename)
            BackendSelectionAction(Icons.Rounded.Delete, "Удалить", deleteEnabled, onDelete, error = true)
        }
    }
}

@Composable
private fun BackendSelectionAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    error: Boolean = false,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(label, fontSize = 10.sp, color = tint)
    }
}

@Composable
private fun BackendPropertiesDialog(
    item: StorageItem,
    backendTitle: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Хранилище: $backendTitle")
                Text("Путь: ${item.path}")
                Text("Тип: ${if (item.isDirectory) "Папка" else item.mimeType ?: "Файл"}")
                if (!item.isDirectory) Text("Размер: ${formatBytes(item.size)}")
                if (item.modifiedAt > 0L) {
                    Text("Изменён: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.modifiedAt))}")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

@Composable
private fun BackendDeleteDialog(
    items: List<StorageItem>,
    backendKind: StorageBackendKind?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val destination = backendDeleteExplanation(backendKind)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (items.size == 1) "Удалить ${items.first().name}?" else "Удалить объектов: ${items.size}?") },
        text = { Text(destination) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun backendDeleteExplanation(kind: StorageBackendKind?): String = when (kind) {
    StorageBackendKind.YANDEX_DISK -> "Объекты будут перемещены в корзину Яндекс.Диска, откуда их можно восстановить."
    StorageBackendKind.GOOGLE_DRIVE -> "Объекты будут перемещены в корзину Google Drive, откуда их можно восстановить."
    else -> "Для этого сетевого хранилища общей корзины Aura нет. Удаление может быть необратимым."
}

private fun sortBackendItems(
    items: List<StorageItem>,
    mode: FileSortMode,
    ascending: Boolean,
): List<StorageItem> {
    val valueComparator = when (mode) {
        FileSortMode.Name -> compareBy<StorageItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        FileSortMode.Modified -> compareBy<StorageItem> { it.modifiedAt }
        FileSortMode.Size -> compareBy<StorageItem> { it.size }
        FileSortMode.Type -> compareBy<StorageItem, String>(String.CASE_INSENSITIVE_ORDER) {
            it.mimeType ?: it.name.substringAfterLast('.', "")
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }.let { if (ascending) it else it.reversed() }
    return items.sortedWith(compareByDescending<StorageItem> { it.isDirectory }.then(valueComparator))
}

private fun backendItemDetails(item: StorageItem): String {
    if (item.isDirectory) return "Папка"
    val size = formatBytes(item.size)
    val date = if (item.modifiedAt > 0L) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.modifiedAt))
    } else "Дата неизвестна"
    return "$size · $date"
}

@Composable
private fun GoogleAuthDialog(
    state: GoogleAuthUiState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    when (state) {
        GoogleAuthUiState.Idle -> Unit
        GoogleAuthUiState.Requesting -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Google Drive") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Проверяю разрешение Google Drive…")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        )
        is GoogleAuthUiState.NeedsResolution,
        GoogleAuthUiState.AwaitingUser -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Google Drive") },
            text = { Text("Выберите Google-аккаунт и разрешите Aura Files доступ к Google Drive в системном окне Google.") },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        )
        GoogleAuthUiState.Finalizing -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Google Drive") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Проверяю аккаунт и подключаю Drive…")
                }
            },
            confirmButton = {},
        )
        is GoogleAuthUiState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Не удалось подключить Google Drive") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.message)
                    if (state.message.contains("UNREGISTERED_ON_API_CONSOLE", ignoreCase = true)) {
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://console.cloud.google.com/apis/credentials"))
                                )
                            }
                        }) {
                            Icon(Icons.Rounded.Launch, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Открыть Google Cloud Credentials")
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = onRetry) { Text("Повторить") } },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                            ClipData.newPlainText("Google Drive OAuth error", state.message)
                        )
                    }) { Text("Копировать") }
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
            },
        )
    }
}

@Composable
private fun YandexAuthDialog(
    state: YandexAuthUiState,
    onStart: (String, String) -> Unit,
    onVerificationOpened: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    when (state) {
        YandexAuthUiState.Idle -> Unit
        is YandexAuthUiState.Configuring,
        is YandexAuthUiState.Error -> {
            val initialClientId = when (state) {
                is YandexAuthUiState.Configuring -> state.clientId
                is YandexAuthUiState.Error -> state.clientId
                else -> ""
            }
            var clientId by remember(initialClientId) { mutableStateOf(initialClientId) }
            var clientSecret by remember { mutableStateOf("") }
            val error = (state as? YandexAuthUiState.Error)?.message
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Добавить Яндекс.Диск") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Вход выполняется через официальный device-code OAuth. Пароль Яндекса Aura не получает.")
                        OutlinedTextField(
                            value = clientId,
                            onValueChange = { clientId = it },
                            label = { Text("Yandex OAuth Client ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = clientSecret,
                            onValueChange = { clientSecret = it },
                            label = { Text("Yandex OAuth Client Secret") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Нужны Client ID и Client Secret (пароль приложения) из созданного Yandex OAuth-приложения. Secret сохраняется только в зашифрованном хранилище Android Keystore. В OAuth-приложении нужны права Yandex Disk REST API на чтение и запись.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://oauth.yandex.ru/client/new/")))
                            }
                        }) { Text("Создать OAuth-приложение Яндекса") }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = { Button(onClick = { onStart(clientId, clientSecret) }) { Text("Войти через Яндекс") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
            )
        }
        is YandexAuthUiState.Requesting -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Яндекс.Диск") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Получаю код входа…")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        )
        is YandexAuthUiState.AwaitingApproval -> {
            LaunchedEffect(state.userCode, state.verificationUrl, state.autoOpenVerification) {
                if (state.autoOpenVerification) {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                        ClipData.newPlainText("Yandex OAuth code", state.userCode)
                    )
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.verificationUrl)))
                    }
                    onVerificationOpened()
                }
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Подтвердите вход в Яндекс") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Код уже скопирован в буфер обмена:")
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(
                                state.userCode,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Text("Введите его на странице Яндекс OAuth, разрешите Aura Files доступ к Диску и вернитесь в Aura. Приложение само завершит вход — отдельной ссылки возврата у device-code OAuth нет.")
                        Text(state.status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                    ClipData.newPlainText("Yandex OAuth code", state.userCode)
                                )
                            }) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Копировать код")
                            }
                            OutlinedButton(onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.verificationUrl))) }
                            }) {
                                Icon(Icons.Rounded.Launch, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Открыть Яндекс")
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
            )
        }
    }
}

private data class BackendDragPayload(val fromLeft: Boolean, val item: StorageItem)

@Composable
private fun BackendPane(
    modifier: Modifier,
    title: String,
    pane: BackendPaneState,
    descriptors: List<StorageBackendDescriptor>,
    busy: Boolean,
    onBackend: (String) -> Unit,
    onOpen: (StorageItem) -> Unit,
    onToggle: (StorageItem) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onRecent: (String) -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    isLeft: Boolean,
    onDrop: (BackendDragPayload) -> Unit,
    allowCrossPaneActions: Boolean = true,
    allowBackendSwitch: Boolean = true,
    openFilesOnClick: Boolean = false,
) {
    var backendMenu by remember { mutableStateOf(false) }
    var recentMenu by remember { mutableStateOf(false) }
    var createFolderOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var selectionMenu by remember { mutableStateOf(false) }
    var propertiesOpen by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    val selectedItems = pane.items.filter { it.path in pane.selected }
    var dissolvingPaths by remember(pane.backendId) { mutableStateOf<Set<String>>(emptySet()) }
    val context = LocalContext.current
    val deleteAnimationMode = remember(context) { FileRepository(context.applicationContext).deleteAnimationMode() }
    val scope = rememberCoroutineScope()
    val backendDescriptor = descriptors.firstOrNull { it.id == pane.backendId }
    val backendTitle = backendDescriptor?.title ?: pane.backendId.orEmpty()
    val backendKind = backendDescriptor?.kind
    LaunchedEffect(busy) {
        if (!busy) dissolvingPaths = emptySet()
    }
    val dropTarget = remember(isLeft, busy, onDrop) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                if (busy) return false
                val payload = event.toAndroidDragEvent().localState as? BackendDragPayload ?: return false
                if (payload.fromLeft == isLeft) return false
                onDrop(payload)
                return true
            }
        }
    }
    val paneModifier = if (allowCrossPaneActions) {
        modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { event: DragAndDropEvent ->
                val payload = event.toAndroidDragEvent().localState as? BackendDragPayload
                if (busy) false else payload != null && payload.fromLeft != isLeft
            },
            target = dropTarget,
        )
    } else modifier
    Surface(
        modifier = paneModifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = pane.back.isNotEmpty() && !busy) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                }
                IconButton(onClick = onForward, enabled = pane.forward.isNotEmpty() && !busy) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Вперёд")
                }
                if (allowBackendSwitch) {
                    Box {
                        TextButton(onClick = { backendMenu = true }, enabled = !busy) {
                            Text(descriptors.firstOrNull { it.id == pane.backendId }?.title ?: "Источник", maxLines = 1)
                        }
                        DropdownMenu(expanded = backendMenu, onDismissRequest = { backendMenu = false }) {
                            descriptors.forEach { backend ->
                                DropdownMenuItem(
                                    text = { Text(backend.title) },
                                    onClick = { backendMenu = false; onBackend(backend.id) },
                                )
                            }
                        }
                    }
                } else {
                    Text(backendTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { recentMenu = true }, enabled = pane.recent.isNotEmpty()) {
                        Icon(Icons.Rounded.History, contentDescription = "История")
                    }
                    DropdownMenu(expanded = recentMenu, onDismissRequest = { recentMenu = false }) {
                        pane.recent.forEach { path ->
                            DropdownMenuItem(text = { Text(path, maxLines = 1) }, onClick = { recentMenu = false; onRecent(path) })
                        }
                    }
                }
                IconButton(onClick = { createFolderOpen = true; editName = "" }, enabled = !busy) {
                    Icon(Icons.Rounded.Add, contentDescription = "Создать папку")
                }
                IconButton(onClick = onRefresh, enabled = !busy) { Icon(Icons.Rounded.Refresh, contentDescription = "Обновить") }
            }
            Text(
                "$title · ${pane.path}",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pane.selected.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Выбрано: ${pane.selected.size}", modifier = Modifier.weight(1f), fontSize = 12.sp)
                    if (allowCrossPaneActions) {
                        TextButton(onClick = onCopy, enabled = !busy) { Text("Копировать →") }
                        TextButton(onClick = onMove, enabled = !busy) { Text("Переместить →") }
                    }
                    Box {
                        IconButton(onClick = { selectionMenu = true }, enabled = !busy) {
                            Icon(Icons.Rounded.MoreHoriz, contentDescription = "Действия с выбранными")
                        }
                        DropdownMenu(expanded = selectionMenu, onDismissRequest = { selectionMenu = false }) {
                            if (selectedItems.size == 1) {
                                DropdownMenuItem(
                                    text = { Text("Свойства") },
                                    onClick = { selectionMenu = false; propertiesOpen = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Переименовать") },
                                    onClick = {
                                        selectionMenu = false
                                        editName = selectedItems.single().name
                                        renameOpen = true
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                                onClick = { selectionMenu = false; deleteOpen = true },
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
            when {
                pane.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                pane.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Пусто") }
                else -> LazyColumn(Modifier.weight(1f)) {
                    items(pane.items, key = { "${it.backendId}|${it.path}" }) { item ->
                        BackendItemRow(
                            item = item,
                            selected = item.path in pane.selected,
                            busy = busy,
                            dissolving = item.path in dissolvingPaths,
                            deleteAnimationMode = deleteAnimationMode,
                            onOpen = {
                                if (item.isDirectory || openFilesOnClick) onOpen(item) else onToggle(item)
                            },
                            onToggle = { onToggle(item) },
                            fromLeft = isLeft,
                            enableDrag = allowCrossPaneActions,
                            showSelectionCheckbox = allowCrossPaneActions || pane.selected.isNotEmpty(),
                        )
                    }
                }
            }
        }
    }

    if (createFolderOpen) {
        AlertDialog(
            onDismissRequest = { createFolderOpen = false },
            title = { Text("Новая папка") },
            text = { OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("Название") }, singleLine = true) },
            confirmButton = {
                Button(
                    onClick = { val name = editName; createFolderOpen = false; onCreateFolder(name) },
                    enabled = editName.isNotBlank(),
                ) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { createFolderOpen = false }) { Text("Отмена") } },
        )
    }
    if (renameOpen && selectedItems.size == 1) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Переименовать") },
            text = { OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("Новое имя") }, singleLine = true) },
            confirmButton = {
                Button(
                    onClick = { val name = editName; renameOpen = false; onRename(name) },
                    enabled = editName.isNotBlank(),
                ) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Отмена") } },
        )
    }
    if (propertiesOpen && selectedItems.size == 1) {
        val item = selectedItems.single()
        AlertDialog(
            onDismissRequest = { propertiesOpen = false },
            title = { Text(item.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Backend: $backendTitle")
                    Text("Путь: ${item.path}")
                    Text("Тип: ${if (item.isDirectory) "Папка" else item.mimeType ?: "Файл"}")
                    if (!item.isDirectory) Text("Размер: ${formatBackendBytes(item.size)}")
                    if (item.modifiedAt > 0L) {
                        Text("Изменён: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.modifiedAt))}")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { propertiesOpen = false }) { Text("Готово") } },
        )
    }
    if (deleteOpen && selectedItems.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text(if (selectedItems.size == 1) "Удалить ${selectedItems.single().name}?" else "Удалить объектов: ${selectedItems.size}?") },
            text = { Text(backendDeleteExplanation(backendKind)) },
            confirmButton = {
                Button(onClick = {
                    deleteOpen = false
                    dissolvingPaths = selectedItems.map { it.path }.toSet()
                    scope.launch {
                        val wait = deleteAnimationMode.preDeleteDelayMillis()
                        if (wait > 0L) delay(wait)
                        onDelete()
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun BackendItemRow(
    item: StorageItem,
    selected: Boolean,
    busy: Boolean,
    dissolving: Boolean,
    deleteAnimationMode: com.aurafiles.app.model.DeleteAnimationMode,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    fromLeft: Boolean,
    enableDrag: Boolean,
    showSelectionCheckbox: Boolean,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .auraDeleteEffect(dissolving, deleteAnimationMode, item.path.hashCode())
        .combinedClickable(
            enabled = !busy && !dissolving,
            onClick = onOpen,
            onLongClick = onToggle,
        )
    val draggableModifier = if (enableDrag) {
        rowModifier.dragAndDropSource(transferData = { _ ->
            if (busy) null else DragAndDropTransferData(
                clipData = ClipData.newPlainText("Aura Files", item.name),
                localState = BackendDragPayload(fromLeft, item),
            )
        })
    } else rowModifier
    Row(
        draggableModifier
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSelectionCheckbox) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = !busy)
        } else {
            Spacer(Modifier.width(10.dp))
        }
        val itemIcon = when {
            item.isDirectory -> Icons.Rounded.Folder
            item.mimeType?.startsWith("image/") == true -> Icons.Rounded.Image
            item.mimeType?.startsWith("video/") == true -> Icons.Rounded.Movie
            item.mimeType?.startsWith("audio/") == true -> Icons.Rounded.MusicNote
            else -> Icons.Rounded.Description
        }
        Icon(itemIcon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            Text(
                if (item.isDirectory) "Папка" else formatBackendBytes(item.size),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ComparisonDialog(
    comparison: com.aurafiles.app.sync.DirectoryCompareResult,
    onDismiss: () -> Unit,
    onSync: (SyncDirection, Boolean) -> Unit,
) {
    var deleteExtraneous by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("DIFF") }
    val filtered = comparison.entries.filter { entry ->
        when (filter) {
            "SAME" -> entry.difference == DirectoryDifference.SAME
            "LEFT" -> entry.difference == DirectoryDifference.ONLY_LEFT
            "RIGHT" -> entry.difference == DirectoryDifference.ONLY_RIGHT
            "DIFF" -> entry.difference !in setOf(DirectoryDifference.SAME, DirectoryDifference.ONLY_LEFT, DirectoryDifference.ONLY_RIGHT)
            else -> true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сравнение панелей") },
        text = {
            Column {
                Text("Различий: ${comparison.changed.size} · всего: ${comparison.entries.size}")
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(
                        "ALL" to "Все",
                        "SAME" to "Одинаковые",
                        "LEFT" to "Только слева",
                        "RIGHT" to "Только справа",
                        "DIFF" to "Различающиеся",
                    ).forEach { (key, label) ->
                        TextButton(onClick = { filter = key }) {
                            Text(if (filter == key) "✓ $label" else label)
                        }
                    }
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(filtered.take(500), key = { it.relativePath }) { entry ->
                        val marker = when (entry.difference) {
                            DirectoryDifference.SAME -> "✓"
                            DirectoryDifference.ONLY_LEFT -> "←"
                            DirectoryDifference.ONLY_RIGHT -> "→"
                            else -> "≠"
                        }
                        val tint = when (entry.difference) {
                            DirectoryDifference.SAME -> MaterialTheme.colorScheme.onSurfaceVariant
                            DirectoryDifference.ONLY_LEFT, DirectoryDifference.ONLY_RIGHT -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        }
                        Text(
                            "$marker ${differenceLabel(entry.difference)} · ${entry.relativePath}",
                            fontSize = 12.sp,
                            color = tint,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
                if (filtered.size > 500) Text("Показаны первые 500 из ${filtered.size}", fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Удалять лишние при синхронизации", modifier = Modifier.weight(1f), fontSize = 12.sp)
                    Switch(checked = deleteExtraneous, onCheckedChange = { deleteExtraneous = it })
                }
            }
        },
        confirmButton = {
            Row {
                Button(onClick = { onSync(SyncDirection.LEFT_TO_RIGHT, deleteExtraneous) }) { Text("Синхронизировать →") }
                Spacer(Modifier.width(4.dp))
                Button(onClick = { onSync(SyncDirection.RIGHT_TO_LEFT, deleteExtraneous) }) { Text("← Синхронизировать") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun SftpConnectionDialog(
    onDismiss: () -> Unit,
    onSave: (SftpProfile) -> Unit,
) {
    var name by remember { mutableStateOf("SFTP") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var initialPath by remember { mutableStateOf("/") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новое SFTP-подключение") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(host, { host = it }, label = { Text("Сервер / IP") }, singleLine = true)
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Порт") }, singleLine = true)
                OutlinedTextField(username, { username = it }, label = { Text("Логин") }, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("Пароль (если без ключа)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                OutlinedTextField(
                    privateKey,
                    { privateKey = it },
                    label = { Text("Приватный ключ (необязательно)") },
                    minLines = 3,
                    maxLines = 7,
                    supportingText = { Text("Ключ хранится зашифрованно через CredentialStore") },
                )
                OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Passphrase ключа") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                OutlinedTextField(initialPath, { initialPath = it }, label = { Text("Начальный путь") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val p = port.toIntOrNull()
                when {
                    host.isBlank() -> error = "Введите сервер"
                    username.isBlank() -> error = "Введите логин"
                    p == null || p !in 1..65535 -> error = "Некорректный порт"
                    password.isBlank() && privateKey.isBlank() -> error = "Введите пароль или приватный ключ"
                    else -> onSave(
                        SftpProfile(
                            id = UUID.randomUUID().toString(),
                            name = name.ifBlank { host }, host = host.trim(), port = p, username = username,
                            password = password, privateKey = privateKey, privateKeyPassphrase = passphrase,
                            initialPath = initialPath.ifBlank { "/" },
                        )
                    )
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun differenceLabel(difference: DirectoryDifference): String = when (difference) {
    DirectoryDifference.ONLY_LEFT -> "Только слева"
    DirectoryDifference.ONLY_RIGHT -> "Только справа"
    DirectoryDifference.SIZE_DIFFERS -> "Размер отличается"
    DirectoryDifference.LEFT_NEWER -> "Слева новее"
    DirectoryDifference.RIGHT_NEWER -> "Справа новее"
    DirectoryDifference.SAME -> "Одинаковые"
    DirectoryDifference.TYPE_DIFFERS -> "Тип отличается"
}

private fun formatBackendBytes(bytes: Long): String {
    val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit += 1 }
    return if (unit == 0) "$bytes ${units[unit]}" else "%.1f %s".format(value, units[unit])
}
