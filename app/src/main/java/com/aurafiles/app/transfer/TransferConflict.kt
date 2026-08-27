package com.aurafiles.app.transfer

data class TransferConflict(
    val operationId: String,
    val sourceName: String,
    val sourceSize: Long,
    val sourceModifiedAt: Long,
    val existingName: String,
    val existingSize: Long,
    val existingModifiedAt: Long,
)

data class TransferConflictDecision(
    val policy: TransferConflictPolicy,
    val applyToAll: Boolean = false,
)

object ConflictResolver {
    fun resolve(
        policy: TransferConflictPolicy,
        sourceSize: Long,
        sourceModifiedAt: Long,
        existingSize: Long,
        existingModifiedAt: Long,
    ): TransferConflictPolicy = when (policy) {
        TransferConflictPolicy.REPLACE_IF_NEWER -> {
            if (sourceModifiedAt > existingModifiedAt) TransferConflictPolicy.REPLACE else TransferConflictPolicy.SKIP
        }
        TransferConflictPolicy.REPLACE_IF_SIZE_DIFFERS -> {
            if (sourceSize != existingSize) TransferConflictPolicy.REPLACE else TransferConflictPolicy.SKIP
        }
        else -> policy
    }
}
