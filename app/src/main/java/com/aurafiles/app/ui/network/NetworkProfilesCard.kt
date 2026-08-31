package com.aurafiles.app.ui.network

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurafiles.app.model.DeleteAnimationMode
import com.aurafiles.app.network.NetworkProfile
import com.aurafiles.app.network.NetworkProtocol
import com.aurafiles.app.ui.auraDeleteEffect
import com.aurafiles.app.ui.preDeleteDelayMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun NetworkProfilesCard(
    profiles: List<NetworkProfile>,
    onConnect: (NetworkProfile) -> Unit,
    onTest: (NetworkProfile) -> Unit,
    onDuplicate: (NetworkProfile) -> Unit,
    onDelete: (NetworkProfile) -> Unit,
    deleteAnimationMode: DeleteAnimationMode,
) {
    var pendingDelete by remember { mutableStateOf<NetworkProfile?>(null) }
    var dissolvingProfileId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                "Сохранённые подключения",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                fontWeight = FontWeight.SemiBold,
            )
            profiles.forEachIndexed { index, profile ->
                if (index > 0) {
                    HorizontalDivider(
                        Modifier.padding(start = 62.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .auraDeleteEffect(
                            active = dissolvingProfileId == profile.id,
                            mode = deleteAnimationMode,
                            seed = profile.id.hashCode(),
                        )
                        .clickable(enabled = dissolvingProfileId != profile.id) { onConnect(profile) }
                        .padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProfileIcon(
                        when (profile.protocol) {
                            NetworkProtocol.SMB -> Icons.Rounded.Storage
                            NetworkProtocol.SFTP -> Icons.Rounded.Lock
                            else -> Icons.Rounded.Language
                        },
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(
                            "${profile.protocol.name} · ${profile.host}" +
                                if (profile.protocol == NetworkProtocol.SMB && profile.smbShare.isNotBlank()) {
                                    "\\${profile.smbShare}"
                                } else ":${profile.port}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { onTest(profile) }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Проверить ${profile.name}")
                    }
                    IconButton(onClick = { onDuplicate(profile) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Дублировать ${profile.name}")
                    }
                    IconButton(onClick = { pendingDelete = profile }) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "Удалить ${profile.name}",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить подключение?") },
            text = { Text("${profile.name}\nПароль также будет удалён из защищённого хранилища.") },
            confirmButton = {
                Button(onClick = {
                    pendingDelete = null
                    dissolvingProfileId = profile.id
                    scope.launch {
                        val wait = deleteAnimationMode.preDeleteDelayMillis()
                        if (wait > 0L) delay(wait)
                        onDelete(profile)
                        // If deletion fails and the row stays on screen, restore it instead of
                        // leaving a permanently invisible profile. A successful delete removes it first.
                        delay(1200L)
                        if (dissolvingProfileId == profile.id) dissolvingProfileId = null
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ProfileIcon(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(38.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.padding(9.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
