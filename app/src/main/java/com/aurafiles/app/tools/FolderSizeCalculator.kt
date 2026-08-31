package com.aurafiles.app.tools

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.data.FastDocumentListing
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

        suspend fun walk(directory: DocumentFile) {
            currentCoroutineContext().ensureActive()
            // FastDocumentListing performs one provider query for the whole directory when
            // possible. DocumentFile getters in a loop can otherwise trigger several IPC
            // calls per child and make folder-size calculation painfully slow on SAF roots.
            for (child in FastDocumentListing.list(appContext, directory)) {
                currentCoroutineContext().ensureActive()
                if (child.isDirectory) {
                    directories += 1
                    publish(child.name)
                    walk(child.document)
                } else {
                    files += 1
                    bytes += child.size.coerceAtLeast(0L)
                    publish(child.name)
                }
            }
        }

        walk(root)
        publish(root.name.orEmpty(), force = true)
        return FolderSizeResult(bytes, files, directories)
    }

    private companion object {
        const val PROGRESS_INTERVAL_NANOS = 100_000_000L
    }
}
