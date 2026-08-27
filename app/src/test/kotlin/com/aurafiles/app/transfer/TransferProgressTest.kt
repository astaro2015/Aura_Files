package com.aurafiles.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferProgressTest {
    @Test
    fun byteProgressIsClamped() {
        assertEquals(0.5f, TransferProgress(processedBytes = 50, totalBytes = 100).fraction, 0.0001f)
        assertEquals(1f, TransferProgress(processedBytes = 150, totalBytes = 100).fraction, 0.0001f)
    }

    @Test
    fun itemProgressIsUsedWhenByteTotalIsUnknown() {
        assertEquals(0.25f, TransferProgress(currentItem = 1, totalItems = 4).fraction, 0.0001f)
    }
}
