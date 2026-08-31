package com.aurafiles.app.ui

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
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
import com.aurafiles.app.data.FileRepository
import com.aurafiles.app.backend.StorageItem
import com.aurafiles.app.model.SftpProfile
import com.aurafiles.app.sync.DirectoryDifference
import com.aurafiles.app.sync.SyncDirection
import com.aurafiles.app.transfer.TransferConflictPolicy
import com.aurafiles.app.transfer.TransferState
import com.aurafiles.app.ui.theme.AuraFilesTheme
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BackendWorkspaceActivity : ComponentActivity() {
    private val viewModel: BackendWorkspaceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialBackendId = intent.getStringExtra(EXTRA_INITIAL_BACKEND_ID)
        setContent {
            AuraFilesTheme {
                val state by viewModel.state.collectAsState()
                BackendWorkspaceScreen(
                    state = state,
                    viewModel = viewModel,
                    initialBackendId = initialBackendId,
                    onClose = ::finish,
                )
            }
        }
    }

    companion object {
        const val EXTRA_INITIAL_BACKEND_ID = "com.aurafiles.app.extra.INITIAL_BACKEND_ID"
    }
}

@Composable
private fun BackendWorkspaceScreen(
    state: WorkspaceState,
    viewModel: BackendWorkspaceViewModel,
    initialBackendId: String?,
    onClose: () -> Unit,
) {
    var sftpDialog by remember { mutableStateOf(false) }
    var initialBackendApplied by remember(initialBackendId) { mutableStateOf(false) }
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(10_000L)
            viewModel.dismissMessage()
        }
    }
    LaunchedEffect(initialBackendId, state.backends, initialBackendApplied) {
        val backendId = initialBackendId ?: return@LaunchedEffect
        if (!initialBackendApplied && state.backends.any { it.id == backendId }) {
            viewModel.openBackendFromNetwork(backendId)
            initialBackendApplied = true
        }
    }
    var toolsOpen by remember { mutableStateOf(false) }
    var dragMove by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Закрыть") }
            Column(Modifier.weight(1f)) {
                val openedForSftp = initialBackendId?.startsWith("sftp:") == true
                Text(if (openedForSftp) "SFTP" else "Универсальные панели", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (openedForSftp) "SFTP-сервер · локальное хранилище" else "Local · SMB · FTP · SFTP",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { sftpDialog = true }) { Icon(Icons.Rounded.Add, contentDescription = "Добавить SFTP") }
            Box {
                IconButton(onClick = { toolsOpen = true }) { Icon(Icons.Rounded.MoreHoriz, contentDescription = "Инструменты") }
                DropdownMenu(expanded = toolsOpen, onDismissRequest = { toolsOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Похожие фотографии") },
                        onClick = { toolsOpen = false; context.startActivity(Intent(context, SimilarPhotosActivity::class.java)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Drag & Drop: ${if (dragMove) "перемещать" else "копировать"}") },
                        onClick = { dragMove = !dragMove },
                    )
                    DropdownMenuItem(
                        text = { Text("Настройки 0.14") },
                        onClick = { toolsOpen = false; context.startActivity(Intent(context, AdvancedSettingsActivity::class.java)) },
                    )
                }
            }
            IconButton(onClick = viewModel::comparePanels, enabled = state.busyLabel == null) {
                Icon(Icons.Rounded.CompareArrows, contentDescription = "Сравнить панели")
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
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
    val backendTitle = descriptors.firstOrNull { it.id == pane.backendId }?.title ?: pane.backendId.orEmpty()
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
    Surface(
        modifier = modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { event: DragAndDropEvent ->
                val payload = event.toAndroidDragEvent().localState as? BackendDragPayload
                if (busy) false else payload != null && payload.fromLeft != isLeft
            },
            target = dropTarget,
        ),
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
                    TextButton(onClick = onCopy, enabled = !busy) { Text("Копировать →") }
                    TextButton(onClick = onMove, enabled = !busy) { Text("Переместить →") }
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
                            onOpen = { if (item.isDirectory) onOpen(item) else onToggle(item) },
                            onToggle = { onToggle(item) },
                            fromLeft = isLeft,
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
            text = { Text("В универсальных панелях удаление выполняется напрямую без общей корзины. Папки удаляются вместе с содержимым.") },
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
) {
    Row(
        Modifier
            .fillMaxWidth()
            .auraDeleteEffect(dissolving, deleteAnimationMode, item.path.hashCode())
            .clickable(enabled = !busy && !dissolving, onClick = onOpen)
            .dragAndDropSource(transferData = { _ ->
                if (busy) null else DragAndDropTransferData(
                    clipData = ClipData.newPlainText("Aura Files", item.name),
                    localState = BackendDragPayload(fromLeft, item),
                )
            })
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = !busy)
        Icon(if (item.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Storage, contentDescription = null)
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
