package com.aurafiles.app.index

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageIndexerPathPolicyTest {
    @Test
    fun androidPrivateExternalTreesAreSkipped() {
        assertTrue(isProtectedAndroidSharedPath("/storage/emulated/0/Android/data"))
        assertTrue(isProtectedAndroidSharedPath("/storage/emulated/0/Android/data/com.example/files"))
        assertTrue(isProtectedAndroidSharedPath("/storage/emulated/0/Android/obb"))
        assertTrue(isProtectedAndroidSharedPath("C:\\storage\\Android\\data\\com.example"))
    }

    @Test
    fun ordinarySharedFoldersRemainScannable() {
        assertFalse(isProtectedAndroidSharedPath("/storage/emulated/0/DCIM"))
        assertFalse(isProtectedAndroidSharedPath("/storage/emulated/0/Android/media"))
        assertFalse(isProtectedAndroidSharedPath("/storage/emulated/0/data"))
        assertFalse(isProtectedAndroidSharedPath(""))
    }
}
