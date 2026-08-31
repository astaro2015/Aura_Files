package com.aurafiles.app.transfer

interface SmbTransferGateway {
    suspend fun upload(
        source: TransferSource.Local,
        destination: TransferDestination.Smb,
        controller: TransferController,
        onBytes: (name: String, delta: Long, total: Long, started: Boolean) -> Unit,
    )

    suspend fun download(
        source: TransferSource.Smb,
        destination: TransferDestination.Local,
        controller: TransferController,
        onBytes: (name: String, delta: Long, total: Long, started: Boolean) -> Unit,
    )
}
