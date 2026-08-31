package com.aurafiles.app.sync

import com.aurafiles.app.backend.StorageItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorySyncTest {
    private val dir = StorageItem("l", "/x", "x", true)
    private val file = StorageItem("r", "/x", "x", false, 3)

    @Test fun typeCollisionIsReplacementEvenWhenExtraneousDeletionIsOff() {
        val comparison = DirectoryCompareResult(
            listOf(DirectoryCompareEntry("x", dir, file, DirectoryDifference.TYPE_DIFFERS)), "/", "/"
        )
        val plan = DirectorySyncPlanner.build(comparison, SyncDirection.LEFT_TO_RIGHT, deleteExtraneous = false)
        assertTrue(plan.actions.any { it.type == SyncActionType.REPLACE_DIRECTORY })
        assertFalse(plan.actions.any { it.type == SyncActionType.DELETE_FILE || it.type == SyncActionType.DELETE_DIRECTORY })
    }

    @Test fun extraneousObjectsAreNotDeletedByDefault() {
        val comparison = DirectoryCompareResult(
            listOf(DirectoryCompareEntry("extra.txt", null, file, DirectoryDifference.ONLY_RIGHT)), "/", "/"
        )
        val plan = DirectorySyncPlanner.build(comparison, SyncDirection.LEFT_TO_RIGHT)
        assertTrue(plan.actions.isEmpty())
    }
    @Test fun fileReplacingDirectorySuppressesDescendantDeletes() {
        val sourceFile = StorageItem("l", "/x", "x", false, 3)
        val destinationDir = StorageItem("r", "/x", "x", true)
        val destinationChild = StorageItem("r", "/x/child.txt", "child.txt", false, 1)
        val comparison = DirectoryCompareResult(
            listOf(
                DirectoryCompareEntry("x", sourceFile, destinationDir, DirectoryDifference.TYPE_DIFFERS),
                DirectoryCompareEntry("x/child.txt", null, destinationChild, DirectoryDifference.ONLY_RIGHT),
            ), "/", "/"
        )
        val plan = DirectorySyncPlanner.build(comparison, SyncDirection.LEFT_TO_RIGHT, deleteExtraneous = true)
        assertTrue(plan.actions.size == 1 && plan.actions.single().type == SyncActionType.REPLACE_FILE)
    }

}
