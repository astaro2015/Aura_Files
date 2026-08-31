package com.aurafiles.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurafiles.app.data.FileRepository
import com.aurafiles.app.index.StorageIndexer
import com.aurafiles.app.model.FileEntry
import com.aurafiles.app.tools.SimilarPhotoFinder
import com.aurafiles.app.ui.theme.AuraFilesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SimilarPhotosActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AuraFilesTheme { SimilarPhotosScreen(onClose = ::finish) } }
    }
}

private val MAIN_HANDLER = Handler(Looper.getMainLooper())
private inline fun runOnMain(crossinline block: () -> Unit) = MAIN_HANDLER.post { block() }

@Composable
private fun SimilarPhotosScreen(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var result by remember { mutableStateOf<SimilarPhotoFinder.Result?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("Подготовка…") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var dissolvingUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    val deleteAnimationMode = remember(context) { FileRepository(context.applicationContext).deleteAnimationMode() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                val repository = FileRepository(context)
                val root = repository.restoreRoot() ?: error("Сначала подключите локальное хранилище")
                val indexer = StorageIndexer(context)
                indexer.load(root) ?: error("Сначала выполните анализ хранилища в Aura")
                // Pull the image category directly from Room instead of the small recent-analysis cache.
                // Fetch one extra row so the finder can report that the candidate
                // set was truncated instead of silently presenting a partial scan.
                val images = indexer.categoryEntries(
                    root,
                    com.aurafiles.app.model.FileCategory.Images,
                    SimilarPhotoFinder.DEFAULT_MAX_IMAGES + 1,
                )
                SimilarPhotoFinder(context).find(images) { done, total, name ->
                    if (done == total || done % 16 == 0) {
                        runOnMain { progress = "$done / $total · $name" }
                    }
                }
            }
        }.onSuccess { result = it }
            .onFailure { error = it.message ?: "Не удалось найти похожие фотографии" }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Похожие фотографии", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Закрыть") }
        }
        Text("Экспериментальный perceptual hash. Это не точные дубликаты.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        val currentResult = result
        when {
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error.orEmpty()) }
            currentResult == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(); Text(progress)
                }
            }
            currentResult.groups.isEmpty() -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text("Похожих фотографий не найдено") }
            else -> {
                if (currentResult.limited) {
                    val limitation = when {
                        currentResult.comparisonLimitReached -> "Достигнут безопасный лимит сравнений; результат может быть неполным."
                        currentResult.candidateLimitReached -> "Проверены первые ${SimilarPhotoFinder.DEFAULT_MAX_IMAGES} изображений — безопасный лимит анализа."
                        currentResult.pairLimitReached -> "Совпадений очень много: показаны сильнейшие, но группы сохранены полностью."
                        else -> "Анализ выполнен с безопасными ограничениями."
                    }
                    Text(limitation, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val bestSimilarityByGroup = remember(currentResult) {
                    val groupByUri = HashMap<String, Int>()
                    currentResult.groups.forEachIndexed { groupIndex, group ->
                        group.forEach { entry -> groupByUri[entry.uri.toString()] = groupIndex }
                    }
                    DoubleArray(currentResult.groups.size) { Double.NaN }.also { best ->
                        currentResult.pairs.forEach { pair ->
                            val groupIndex = groupByUri[pair.left.uri.toString()] ?: return@forEach
                            if (groupByUri[pair.right.uri.toString()] == groupIndex &&
                                (best[groupIndex].isNaN() || pair.similarity > best[groupIndex])) {
                                best[groupIndex] = pair.similarity
                            }
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(currentResult.groups) { index, group ->
                        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
                            Column(Modifier.padding(10.dp)) {
                                val best = bestSimilarityByGroup.getOrNull(index)?.takeUnless(Double::isNaN)
                                Text(
                                    "Группа ${index + 1} · ${group.size} фото" +
                                        (best?.let { " · до ${"%.0f".format(it * 100)} %" } ?: ""),
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                )
                                group.forEach { entry ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .auraDeleteEffect(
                                                active = entry.uri.toString() in dissolvingUris,
                                                mode = deleteAnimationMode,
                                                seed = entry.uri.toString().hashCode(),
                                            )
                                            .padding(vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = entry.uri.toString() in selected,
                                            onCheckedChange = { checked ->
                                                selected = selected.toMutableSet().apply {
                                                    if (checked) add(entry.uri.toString()) else remove(entry.uri.toString())
                                                }
                                            }
                                        )
                                        FileThumbnail(
                                            entry = entry,
                                            modifier = Modifier.size(46.dp),
                                            fallback = { Box(Modifier.size(46.dp)) },
                                        )
                                        Text(entry.name, Modifier.weight(1f).padding(start = 8.dp), maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                }
                Button(onClick = { confirmDelete = true }, enabled = selected.isNotEmpty() && !deleting, modifier = Modifier.fillMaxWidth()) {
                    Text(if (deleting) "Перемещение…" else "В корзину выбранные вручную (${selected.size})")
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Переместить выбранные фотографии в корзину?") },
            text = { Text("Aura не удаляет похожие фотографии автоматически. В корзину попадут только отмеченные вручную: ${selected.size}.") },
            confirmButton = {
                Button(onClick = {
                    val entries = result?.groups.orEmpty().flatten().filter { it.uri.toString() in selected }
                    confirmDelete = false
                    deleting = true
                    dissolvingUris = entries.map { it.uri.toString() }.toSet()
                    scope.launch {
                        val delayMillis = deleteAnimationMode.preDeleteDelayMillis()
                        if (delayMillis > 0L) delay(delayMillis)
                        val deletedUris = withContext(Dispatchers.IO) {
                            val repository = FileRepository(context)
                            val root = repository.restoreRoot()
                            if (root == null) emptySet() else {
                                val removed = entries.mapNotNull { entry ->
                                    runCatching { repository.moveToTrash(root, entry); entry.uri }.getOrNull()
                                }.toSet()
                                if (removed.isNotEmpty()) {
                                    ExternalFileChanges.recordDeleted(removed)
                                    runCatching { StorageIndexer(context.applicationContext).removeUris(root, removed) }
                                    runCatching { repository.clearAnalysisCache() }
                                }
                                removed
                            }
                        }
                        selected = emptySet()
                        result = result?.let { current ->
                            current.copy(
                                groups = current.groups.map { group -> group.filterNot { it.uri in deletedUris } }.filter { it.size > 1 },
                                pairs = current.pairs.filterNot { it.left.uri in deletedUris || it.right.uri in deletedUris },
                            )
                        }
                        dissolvingUris = emptySet()
                        deleting = false
                    }
                }) { Text("В корзину") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}
