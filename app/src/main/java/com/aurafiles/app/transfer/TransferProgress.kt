package com.aurafiles.app.transfer

enum class TransferState {
    PREPARING,
    RUNNING,
    PAUSED,
    CANCELLING,
    COMPLETED,
    FAILED,
}

data class TransferProgress(
    val operationId: String,
    val currentName: String = "",
    val currentItem: Int = 0,
    val totalItems: Int = 0,
    val currentItemBytes: Long = 0L,
    val currentItemTotalBytes: Long = 0L,
    val processedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val etaMillis: Long? = null,
    val state: TransferState = TransferState.PREPARING,
    val error: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes > 0L) {
            (processedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else if (totalItems > 0) {
            (currentItem.toFloat() / totalItems.toFloat()).coerceIn(0f, 1f)
        } else 0f
}

