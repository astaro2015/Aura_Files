package com.aurafiles.app.ui

import com.aurafiles.app.backend.StorageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendPaneRefreshPolicyTest {
    @Test fun staleGenerationCannotReplaceCurrentPane() {
        val pane = BackendPaneState(backendId = "ftp:server", path = "/new")

        assertFalse(PaneRefreshRequest("ftp:server", "/new", 4).isCurrentFor(pane, activeGeneration = 5))
        assertFalse(PaneRefreshRequest("ftp:server", "/old", 5).isCurrentFor(pane, activeGeneration = 5))
        assertFalse(PaneRefreshRequest("smb:server", "/new", 5).isCurrentFor(pane, activeGeneration = 5))
        assertTrue(PaneRefreshRequest("ftp:server", "/new", 5).isCurrentFor(pane, activeGeneration = 5))
    }

    @Test fun selectionIsIntersectedWithLatestListing() {
        val items = listOf(
            StorageItem("ftp:server", "/a", "a", false),
            StorageItem("ftp:server", "/c", "c", false),
        )

        assertEquals(linkedSetOf("/a"), reconcilePaneSelection(linkedSetOf("/a", "/deleted"), items))
        assertEquals(emptySet<String>(), reconcilePaneSelection(emptySet(), items))
    }
}
