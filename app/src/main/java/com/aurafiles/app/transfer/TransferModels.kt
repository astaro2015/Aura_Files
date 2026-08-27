package com.aurafiles.app.transfer

import android.net.Uri
import java.util.UUID

enum class TransferType {
    COPY,
    MOVE,
    DELETE,
    TRASH,
    RESTORE,
    UPLOAD,
    DOWNLOAD,
}

enum class TransferConflictPolicy {
    ASK,
    REPLACE,
    SKIP,
    KEEP_BOTH,
    REPLACE_IF_NEWER,
    REPLACE_IF_SIZE_DIFFERS,
    CANCEL,
}

sealed interface TransferSource {
    val name: String
    val size: Long
    val modifiedAt: Long

    data class Local(
        val uri: Uri,
        val parentUri: Uri?,
        override val name: String,
        override val size: Long,
        override val modifiedAt: Long,
        val isDirectory: Boolean,
        val mimeType: String?,
    ) : TransferSource

    data class Smb(
        val path: String,
        override val name: String,
        override val size: Long,
        override val modifiedAt: Long,
        val isDirectory: Boolean,
    ) : TransferSource
}

sealed interface TransferDestination {
    data class Local(val directoryUri: Uri) : TransferDestination
    data class Smb(val directoryPath: String) : TransferDestination
}

data class TransferRequest(
    val id: String = UUID.randomUUID().toString(),
    val type: TransferType,
    val sources: List<TransferSource>,
    val destination: TransferDestination?,
    val conflictPolicy: TransferConflictPolicy = TransferConflictPolicy.ASK,
    val preserveModifiedTime: Boolean = true,
)

data class TransferResult(
    val operationId: String,
    val completedItems: Int,
    val skippedItems: Int,
    val processedBytes: Long,
)

