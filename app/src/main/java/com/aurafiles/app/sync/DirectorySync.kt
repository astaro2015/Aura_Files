package com.aurafiles.app.sync

import com.aurafiles.app.backend.BackendPath
import com.aurafiles.app.backend.StorageBackend
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class SyncDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }
enum class SyncActionType { CREATE_DIRECTORY, COPY_FILE, REPLACE_FILE, REPLACE_DIRECTORY, DELETE_FILE, DELETE_DIRECTORY }

data class SyncAction(
    val type: SyncActionType,
    val relativePath: String,
    val size: Long = 0L,
)

data class DirectorySyncPlan(
    val direction: SyncDirection,
    val actions: List<SyncAction>,
    val deleteExtraneous: Boolean,
) {
    val filesToCopy: Int get() = actions.count { it.type == SyncActionType.COPY_FILE }
    val filesToReplace: Int get() = actions.count { it.type == SyncActionType.REPLACE_FILE || it.type == SyncActionType.REPLACE_DIRECTORY }
    val objectsToDelete: Int get() = actions.count { it.type == SyncActionType.DELETE_FILE || it.type == SyncActionType.DELETE_DIRECTORY }
    val bytesToCopy: Long get() = actions.filter {
        it.type == SyncActionType.COPY_FILE || it.type == SyncActionType.REPLACE_FILE || it.type == SyncActionType.REPLACE_DIRECTORY
    }.sumOf { it.size }
}

object DirectorySyncPlanner {
    fun build(
        comparison: DirectoryCompareResult,
        direction: SyncDirection,
        deleteExtraneous: Boolean = false,
    ): DirectorySyncPlan {
        val actions = mutableListOf<SyncAction>()
        val replacedDirectoryPrefixes = comparison.entries.mapNotNull { entry ->
            val source = if (direction == SyncDirection.LEFT_TO_RIGHT) entry.left else entry.right
            val destination = if (direction == SyncDirection.LEFT_TO_RIGHT) entry.right else entry.left
            entry.relativePath.takeIf {
                source != null && destination != null && source.isDirectory != destination.isDirectory
            }
        }.sortedBy { it.count { ch -> ch == '/' } }

        comparison.entries.forEach { entry ->
            // A replaced source directory is copied recursively by TransferEngine, so
            // descendant compare rows must not schedule a second copy/delete pass.
            if (replacedDirectoryPrefixes.any { prefix ->
                    entry.relativePath != prefix && entry.relativePath.startsWith("$prefix/")
                }) return@forEach

            val source = if (direction == SyncDirection.LEFT_TO_RIGHT) entry.left else entry.right
            val destination = if (direction == SyncDirection.LEFT_TO_RIGHT) entry.right else entry.left
            when {
                source != null && destination == null -> {
                    actions += SyncAction(
                        if (source.isDirectory) SyncActionType.CREATE_DIRECTORY else SyncActionType.COPY_FILE,
                        entry.relativePath,
                        source.size,
                    )
                }
                source == null && destination != null && deleteExtraneous -> {
                    actions += SyncAction(
                        if (destination.isDirectory) SyncActionType.DELETE_DIRECTORY else SyncActionType.DELETE_FILE,
                        entry.relativePath,
                        destination.size,
                    )
                }
                source != null && destination != null && source.isDirectory != destination.isDirectory -> {
                    // Type collision is one atomic replacement through TransferEngine.
                    actions += SyncAction(
                        if (source.isDirectory) SyncActionType.REPLACE_DIRECTORY else SyncActionType.REPLACE_FILE,
                        entry.relativePath,
                        source.size,
                    )
                }
                source != null && destination != null && !source.isDirectory && !destination.isDirectory -> {
                    val sourceWins = when (entry.difference) {
                        DirectoryDifference.SIZE_DIFFERS -> true
                        DirectoryDifference.LEFT_NEWER -> direction == SyncDirection.LEFT_TO_RIGHT
                        DirectoryDifference.RIGHT_NEWER -> direction == SyncDirection.RIGHT_TO_LEFT
                        DirectoryDifference.TYPE_DIFFERS -> true
                        else -> false
                    }
                    if (sourceWins) actions += SyncAction(SyncActionType.REPLACE_FILE, entry.relativePath, source.size)
                }
            }
        }

        // Create directories shallow -> deep; delete directories deep -> shallow.
        val ordered = actions.sortedWith(
            compareBy<SyncAction> {
                when (it.type) {
                    SyncActionType.CREATE_DIRECTORY -> 0
                    SyncActionType.COPY_FILE, SyncActionType.REPLACE_FILE, SyncActionType.REPLACE_DIRECTORY -> 1
                    SyncActionType.DELETE_FILE -> 2
                    SyncActionType.DELETE_DIRECTORY -> 3
                }
            }.thenComparator { a, b ->
                when {
                    a.type == SyncActionType.CREATE_DIRECTORY && b.type == SyncActionType.CREATE_DIRECTORY -> depth(a.relativePath) - depth(b.relativePath)
                    a.type == SyncActionType.DELETE_DIRECTORY && b.type == SyncActionType.DELETE_DIRECTORY -> depth(b.relativePath) - depth(a.relativePath)
                    else -> a.relativePath.compareTo(b.relativePath, ignoreCase = true)
                }
            }
        )
        return DirectorySyncPlan(direction, ordered, deleteExtraneous)
    }

    private fun depth(path: String): Int = path.count { it == '/' }
}

class DirectorySyncExecutor {
    suspend fun execute(
        plan: DirectorySyncPlan,
        left: StorageBackend,
        leftRoot: String,
        right: StorageBackend,
        rightRoot: String,
        copyFile: suspend (
            sourceBackend: StorageBackend,
            sourcePath: String,
            destinationBackend: StorageBackend,
            destinationDirectory: String,
            replace: Boolean,
        ) -> Unit,
        onProgress: (Int, Int, SyncAction) -> Unit = { _, _, _ -> },
    ) {
        val sourceBackend = if (plan.direction == SyncDirection.LEFT_TO_RIGHT) left else right
        val sourceRoot = if (plan.direction == SyncDirection.LEFT_TO_RIGHT) leftRoot else rightRoot
        val destinationBackend = if (plan.direction == SyncDirection.LEFT_TO_RIGHT) right else left
        val destinationRoot = if (plan.direction == SyncDirection.LEFT_TO_RIGHT) rightRoot else leftRoot

        plan.actions.forEachIndexed { index, action ->
            currentCoroutineContext().ensureActive()
            val sourcePath = resolve(sourceRoot, action.relativePath)
            val destinationPath = resolve(destinationRoot, action.relativePath)
            when (action.type) {
                SyncActionType.CREATE_DIRECTORY -> destinationBackend.mkdir(destinationPath)
                SyncActionType.COPY_FILE, SyncActionType.REPLACE_FILE, SyncActionType.REPLACE_DIRECTORY -> {
                    copyFile(
                        sourceBackend,
                        sourcePath,
                        destinationBackend,
                        BackendPath.parent(destinationPath),
                        action.type == SyncActionType.REPLACE_FILE || action.type == SyncActionType.REPLACE_DIRECTORY,
                    )
                }
                SyncActionType.DELETE_FILE -> destinationBackend.delete(destinationPath, false)
                SyncActionType.DELETE_DIRECTORY -> destinationBackend.delete(destinationPath, true)
            }
            onProgress(index + 1, plan.actions.size, action)
        }
    }

    private fun resolve(root: String, relative: String): String {
        var current = BackendPath.normalize(root)
        relative.split('/').filter(String::isNotBlank).forEach { current = BackendPath.child(current, it) }
        return current
    }
}
