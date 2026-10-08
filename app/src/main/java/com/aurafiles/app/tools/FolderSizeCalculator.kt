package com.aurafiles.app.tools

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.data.DocumentTreeSafety
import com.aurafiles.app.data.FastDocumentListing
import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class FolderSizeResult(
    val bytes: Long,
    val files: Long,
    val directories: Long,
)

data class FolderSizeProgress(
    val currentName: String,
    val bytes: Long,
    val files: Long,
    val directories: Long,
)

class FolderSizeCalculator(context: Context) {
    private val appContext = context.applicationContext

    suspend fun calculate(
        root: DocumentFile,
        onProgress: (FolderSizeProgress) -> Unit = {},
    ): FolderSizeResult {
        require(root.isDirectory) { "Выбранный объект не является папкой" }
        DocumentTreeSafety.requireNotFilesystemSymlink(root, "Подсчёт размера папки")
        var bytes = 0L
        var files = 0L
        var directories = 0L
        var lastProgressAt = 0L

        fun publish(currentName: String, force: Boolean = false) {
            val now = System.nanoTime()
            if (!force && now - lastProgressAt < PROGRESS_INTERVAL_NANOS) return
            lastProgressAt = now
            onProgress(FolderSizeProgress(currentName, bytes, files, directories))
        }

        data class PendingDirectory(val directory: DocumentFile, val depth: Int)
        val visited = mutableSetOf<String>()
        DocumentTreeSafety.requireUniqueDirectoryVisit(root, 0, visited, "Подсчёт размера папки")
        val pending = ArrayDeque<PendingDirectory>()
        pending.addLast(PendingDirectory(root, 0))

        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val current = pending.removeLast()
            // FastDocumentListing performs one provider query for the whole directory when
            // possible. DocumentFile getters in a loop can otherwise trigger several IPC
            // calls per child and make folder-size calculation painfully slow on SAF roots.
            val children = FastDocumentListing.listStrict(appContext, current.directory)
            for (child in children) {
                currentCoroutineContext().ensureActive()
                if (DocumentTreeSafety.isFilesystemSymlink(child.document)) {
                    // A filesystem link can leave the selected tree or form a cycle. Do not
                    // follow it while calculating recursive size.
                    continue
                }
                if (child.isDirectory) {
                    val depth = current.depth + 1
                    DocumentTreeSafety.requireUniqueDirectoryVisit(
                        child.document,
                        depth,
                        visited,
                        "Подсчёт размера папки",
                    )
                    directories = try {
                        Math.addExact(directories, 1L)
                    } catch (_: ArithmeticException) {
                        throw IOException("Подсчёт размера папки: переполнение счётчика каталогов")
                    }
                    publish(child.name)
                    pending.addLast(PendingDirectory(child.document, depth))
                } else {
                    files = try {
                        Math.addExact(files, 1L)
                    } catch (_: ArithmeticException) {
                        throw IOException("Подсчёт размера папки: переполнение счётчика файлов")
                    }
                    bytes = try {
                        Math.addExact(bytes, child.size.coerceAtLeast(0L))
                    } catch (_: ArithmeticException) {
                        throw IOException("Подсчёт размера папки: размер слишком велик")
                    }
                    publish(child.name)
                }
            }
        }

        publish(root.name.orEmpty(), force = true)
        return FolderSizeResult(bytes, files, directories)
    }

    private companion object {
        const val PROGRESS_INTERVAL_NANOS = 100_000_000L
    }
}
