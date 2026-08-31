package com.aurafiles.app.ui

import com.aurafiles.app.backend.StorageItem

/** Immutable identity of one pane listing request. */
internal data class PaneRefreshRequest(
    val backendId: String,
    val path: String,
    val generation: Long,
)

/**
 * A backend call may ignore coroutine cancellation while blocked in a network library.
 * Its result is safe to publish only while both the pane location and request generation
 * still match the original request.
 */
internal fun PaneRefreshRequest.isCurrentFor(
    pane: BackendPaneState,
    activeGeneration: Long,
): Boolean = generation == activeGeneration &&
    backendId == pane.backendId &&
    path == pane.path

/** Drop selections for objects which disappeared or no longer belong to this listing. */
internal fun reconcilePaneSelection(
    selected: Set<String>,
    items: List<StorageItem>,
): Set<String> {
    if (selected.isEmpty()) return emptySet()
    val listedPaths = items.asSequence().map(StorageItem::path).toHashSet()
    return selected.filterTo(linkedSetOf()) { it in listedPaths }
}
