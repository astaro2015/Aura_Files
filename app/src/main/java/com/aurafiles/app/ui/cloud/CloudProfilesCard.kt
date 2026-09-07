package com.aurafiles.app.ui.cloud

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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteOutline
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurafiles.app.cloud.CloudProfile
import com.aurafiles.app.cloud.CloudProvider

@Composable
internal fun CloudProfilesCard(
    profiles: List<CloudProfile>,
    onOpen: (CloudProfile) -> Unit,
    onAddYandex: () -> Unit,
    onAddGoogle: () -> Unit,
    onDelete: (CloudProfile) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<CloudProfile?>(null) }
    val ordered = profiles.sortedWith(compareBy<CloudProfile> { it.provider.displayName }.thenBy { it.name.lowercase() })
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Подключённые облака", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Открываются как обычное файловое хранилище; двухпанельный режим доступен отдельно",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ordered.forEachIndexed { index, profile ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 62.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(profile) }
                        .padding(start = 14.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(38.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(
                            Icons.Rounded.Cloud,
                            contentDescription = null,
                            modifier = Modifier.padding(9.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(
                            profile.accountLabel.ifBlank { profile.provider.displayName },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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

            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextButton(onClick = onAddYandex, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Яндекс.Диск")
                }
                TextButton(onClick = onAddGoogle, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Google Drive")
                }
            }
        }
    }

    pendingDelete?.let { profile ->
        val provider = profile.provider.displayName
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить $provider?") },
            text = {
                val providerNote = if (profile.provider == CloudProvider.GOOGLE_DRIVE) {
                    "Aura также попробует отозвать выданный ей доступ Google. Файлы в Google Drive останутся."
                } else {
                    "Профиль и OAuth-токены будут удалены с этого устройства. Файлы на Диске останутся."
                }
                Text("${profile.name}\n$providerNote")
            },
            confirmButton = {
                Button(onClick = {
                    pendingDelete = null
                    onDelete(profile)
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }
}
