package com.aurafiles.app.data

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.model.SmbEntry
import com.aurafiles.app.transfer.SmbTransferGateway
import com.aurafiles.app.transfer.TransferController
import com.aurafiles.app.transfer.TransferDestination
import com.aurafiles.app.transfer.TransferSource
import java.io.IOException

class SmbTransferGatewayAdapter(
    context: Context,
    private val repository: SmbRepository,
) : SmbTransferGateway {
    private val appContext = context.applicationContext

    override suspend fun upload(
        source: TransferSource.Local,
        destination: TransferDestination.Smb,
        controller: TransferController,
        onBytes: (String, Long, Long) -> Unit,
    ) {
        controller.checkpoint()
        var previous = 0L
        var previousName = ""
        repository.upload(listOf(source.uri)) { name, written, total ->
            if (name != previousName) {
                previousName = name
                previous = 0L
            }
            val delta = (written - previous).coerceAtLeast(0L)
            previous = written
            onBytes(name, delta, total)
        }
    }

    override suspend fun download(
        source: TransferSource.Smb,
        destination: TransferDestination.Local,
        controller: TransferController,
        onBytes: (String, Long, Long) -> Unit,
    ) {
        controller.checkpoint()
        val local = runCatching { DocumentFile.fromTreeUri(appContext, destination.directoryUri) }.getOrNull()
            ?: runCatching { DocumentFile.fromSingleUri(appContext, destination.directoryUri) }.getOrNull()
            ?: throw IOException("Локальная папка недоступна")
        var previous = 0L
        var previousName = ""
        repository.download(
            SmbEntry(
                name = source.name,
                path = source.path,
                isDirectory = source.isDirectory,
                size = source.size,
                modifiedAt = source.modifiedAt,
            ),
            local,
        ) { name, written, total ->
            if (name != previousName) {
                previousName = name
                previous = 0L
            }
            val delta = (written - previous).coerceAtLeast(0L)
            previous = written
            onBytes(name, delta, total)
        }
    }
}
