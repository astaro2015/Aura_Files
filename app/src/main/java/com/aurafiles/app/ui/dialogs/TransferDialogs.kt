package com.aurafiles.app.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.aurafiles.app.transfer.TransferConflict
import com.aurafiles.app.transfer.TransferConflictPolicy
import com.aurafiles.app.transfer.TransferProgress
import com.aurafiles.app.transfer.TransferState
import java.util.Locale

@Composable
internal fun TransferStatusOverlay(
    progress: TransferProgress?,
    label: String?,
    fallbackProgress: Float,
    cancelable: Boolean,
    paused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (progress?.state == TransferState.PREPARING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label ?: "Выполняется операция…",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                progress?.let { current ->
                    if (current.totalBytes > 0L) {
                        Text(
                            "${bytes(current.processedBytes)} / ${bytes(current.totalBytes)} · ${(current.fraction * 100).toInt()} %",
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.78f),
                            fontSize = 11.sp,
                        )
                    }
                    if (current.bytesPerSecond > 0L) {
                        Text(
                            "${bytes(current.bytesPerSecond)}/с" +
                                (current.etaMillis?.let { " · осталось ~${eta(it)}" } ?: ""),
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.78f),
                            fontSize = 11.sp,
                        )
                    }
                    if (current.totalItems > 0) {
                        Text(
                            "Объект ${current.currentItem.coerceAtLeast(1)} из ${current.totalItems}",
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                        )
                    }
                }
                if (fallbackProgress > 0f || progress?.totalBytes == 0L) {
                    LinearProgressIndicator(
                        progress = { progress?.fraction ?: fallbackProgress },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        trackColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.2f),
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                }
            }
            if (cancelable) {
                TextButton(onClick = if (paused) onResume else onPause) {
                    Text(if (paused) "Продолжить" else "Пауза")
                }
                TextButton(onClick = onCancel) { Text("Стоп") }
            }
        }
    }
}

@Composable
internal fun TransferConflictDialog(
    conflict: TransferConflict,
    onResolve: (TransferConflictPolicy, Boolean) -> Unit,
) {
    var applyToAll by remember(conflict.operationId, conflict.sourceName) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onResolve(TransferConflictPolicy.CANCEL, false) },
        title = { Text("Файл уже существует") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(conflict.sourceName, fontWeight = FontWeight.SemiBold)
                Text(
                    "Источник: ${bytes(conflict.sourceSize)}\nСуществующий: ${bytes(conflict.existingSize)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyToAll, onCheckedChange = { applyToAll = it })
                    Text("Применить ко всем конфликтам")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onResolve(TransferConflictPolicy.REPLACE, applyToAll) }) { Text("Заменить") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onResolve(TransferConflictPolicy.SKIP, applyToAll) }) { Text("Пропустить") }
                TextButton(onClick = { onResolve(TransferConflictPolicy.KEEP_BOTH, applyToAll) }) { Text("Сохранить оба") }
            }
        },
    )
}

private fun bytes(value: Long): String {
    if (value < 1024L) return "$value Б"
    val units = arrayOf("КБ", "МБ", "ГБ", "ТБ")
    var scaled = value.toDouble()
    var unit = -1
    while (scaled >= 1024.0 && unit < units.lastIndex) {
        scaled /= 1024.0
        unit += 1
    }
    return String.format(Locale.getDefault(), "%.1f %s", scaled, units[unit.coerceAtLeast(0)])
}

private fun eta(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000L).coerceAtLeast(1L)
    return when {
        seconds < 60L -> "$seconds сек"
        seconds < 3_600L -> "${seconds / 60} мин ${seconds % 60} сек"
        else -> "${seconds / 3_600} ч ${(seconds % 3_600) / 60} мин"
    }
}
