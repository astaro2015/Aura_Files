package com.aurafiles.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class ConflictResolverTest {
    @Test
    fun newerPolicyReplacesOnlyWhenSourceIsNewer() {
        assertEquals(
            TransferConflictPolicy.REPLACE,
            ConflictResolver.resolve(TransferConflictPolicy.REPLACE_IF_NEWER, 10, 200, 10, 100),
        )
        assertEquals(
            TransferConflictPolicy.SKIP,
            ConflictResolver.resolve(TransferConflictPolicy.REPLACE_IF_NEWER, 10, 100, 10, 200),
        )
    }

    @Test
    fun sizePolicySkipsEqualFilesAndReplacesDifferentOnes() {
        assertEquals(
            TransferConflictPolicy.SKIP,
            ConflictResolver.resolve(TransferConflictPolicy.REPLACE_IF_SIZE_DIFFERS, 10, 0, 10, 0),
        )
        assertEquals(
            TransferConflictPolicy.REPLACE,
            ConflictResolver.resolve(TransferConflictPolicy.REPLACE_IF_SIZE_DIFFERS, 11, 0, 10, 0),
        )
    }
}
