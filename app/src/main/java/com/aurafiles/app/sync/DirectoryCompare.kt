package com.aurafiles.app.sync

import com.aurafiles.app.backend.BackendPath
import com.aurafiles.app.backend.StorageBackend
import com.aurafiles.app.backend.StorageBackendKind
import com.aurafiles.app.backend.StorageItem
import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class DirectoryDifference {
    ONLY_LEFT,
    ONLY_RIGHT,
    SIZE_DIFFERS,
    LEFT_NEWER,
    RIGHT_NEWER,
    SAME,
    TYPE_DIFFERS,
}

data class DirectoryCompareEntry(
    val relativePath: String,
    val left: StorageItem?,
    val right: StorageItem?,
    val difference: DirectoryDifference,
)

data class DirectoryCompareResult(
    val entries: List<DirectoryCompareEntry>,
    val leftRoot: String,
    val rightRoot: String,
) {
    val changed: List<DirectoryCompareEntry> get() = entries.filter { it.difference != DirectoryDifference.SAME }
}

class DirectoryComparator {
    suspend fun compare(
        leftBackend: StorageBackend,
        leftRoot: String,
        rightBackend: StorageBackend,
        rightRoot: String,
        recursive: Boolean = true,
        onProgress: (String) -> Unit = {},
    ): DirectoryCompareResult {
        val left = flatten(leftBackend, leftRoot, recursive, onProgress)
        val right = flatten(rightBackend, rightRoot, recursive, onProgress)
        val timestampToleranceMs = timestampToleranceMs(
            leftBackend.descriptor.kind,
            rightBackend.descriptor.kind,
        )
        val all = (left.keys + right.keys).distinct().sortedWith(
            compareBy<String> { it.lowercase() }.thenBy { it }
        )
        val entries = all.map { relative ->
            currentCoroutineContext().ensureActive()
            val l = left[relative]
            val r = right[relative]
            DirectoryCompareEntry(relative, l, r, classify(l, r, timestampToleranceMs))
        }
        return DirectoryCompareResult(entries, leftRoot, rightRoot)
    }

    private suspend fun flatten(
        backend: StorageBackend,
        root: String,
        recursive: Boolean,
        onProgress: (String) -> Unit,
    ): Map<String, StorageItem> {
        data class PendingDirectory(val path: String, val relative: String, val depth: Int)

        val result = linkedMapOf<String, StorageItem>()
        val visitedDirectories = mutableSetOf(backend.normalize(root))
        val pending = ArrayDeque<PendingDirectory>()
        pending.addLast(PendingDirectory(root, "", 0))

        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val current = pending.removeLast()
            if (current.depth > MAX_RECURSION_DEPTH) {
                throw IOException(
                    "Нельзя сравнить/синхронизировать каталог: превышена безопасная глубина $MAX_RECURSION_DEPTH"
                )
            }
            val children = backend.list(current.path)
            // Stack traversal is intentionally reversed to preserve the provider's visible order.
            for (item in children.asReversed()) {
                currentCoroutineContext().ensureActive()
                val relative = if (current.relative.isBlank()) item.name else "${current.relative}/${item.name}"
                if (result.containsKey(relative)) {
                    throw IOException(
                        "Нельзя сравнить/синхронизировать каталог: в хранилище «${backend.descriptor.title}» " +
                            "есть несколько объектов с одинаковым путём $relative"
                    )
                }
                result[relative] = item
                onProgress(relative)
                if (recursive && item.isDirectory && !item.isLink) {
                    val depth = current.depth + 1
                    if (depth > MAX_RECURSION_DEPTH) {
                        throw IOException(
                            "Нельзя сравнить/синхронизировать каталог: превышена безопасная глубина $MAX_RECURSION_DEPTH"
                        )
                    }
                    val key = backend.normalize(item.path)
                    if (!visitedDirectories.add(key)) {
                        throw IOException(
                            "Нельзя сравнить/синхронизировать каталог: обнаружен цикл $relative"
                        )
                    }
                    pending.addLast(PendingDirectory(item.path, relative, depth))
                }
            }
        }
        return result
    }

    companion object {
        fun classify(
            left: StorageItem?,
            right: StorageItem?,
            timestampToleranceMs: Long = PRECISE_TIMESTAMP_TOLERANCE_MS,
        ): DirectoryDifference = when {
            left == null -> DirectoryDifference.ONLY_RIGHT
            right == null -> DirectoryDifference.ONLY_LEFT
            left.isDirectory != right.isDirectory -> DirectoryDifference.TYPE_DIFFERS
            left.isDirectory && right.isDirectory -> DirectoryDifference.SAME
            left.size != right.size -> DirectoryDifference.SIZE_DIFFERS
            left.modifiedAt > 0L && right.modifiedAt > 0L && left.modifiedAt - right.modifiedAt > timestampToleranceMs -> DirectoryDifference.LEFT_NEWER
            left.modifiedAt > 0L && right.modifiedAt > 0L && right.modifiedAt - left.modifiedAt > timestampToleranceMs -> DirectoryDifference.RIGHT_NEWER
            else -> DirectoryDifference.SAME
        }

        /**
         * Traditional FTP LIST timestamps are minute-granular. Treat sub-minute
         * differences as equal whenever either side is FTP; precise backends keep
         * the former two-second tolerance (also suitable for FAT/SMB rounding).
         */
        fun timestampToleranceMs(left: StorageBackendKind, right: StorageBackendKind): Long =
            if (left == StorageBackendKind.FTP || right == StorageBackendKind.FTP) {
                FTP_LIST_TIMESTAMP_TOLERANCE_MS
            } else {
                PRECISE_TIMESTAMP_TOLERANCE_MS
            }

        private const val PRECISE_TIMESTAMP_TOLERANCE_MS = 2_000L
        private const val FTP_LIST_TIMESTAMP_TOLERANCE_MS = 60_000L
        private const val MAX_RECURSION_DEPTH = 256
    }
}
