package com.aurafiles.app.tools

import android.content.Context
import java.io.File

data class AuraCacheStats(
    val totalBytes: Long,
    val pdfReflowBytes: Long,
    val thumbnailsAndSharesBytes: Long,
)

class CacheInspector(private val context: Context) {
    fun stats(): AuraCacheStats {
        val total = size(context.cacheDir)
        val pdf = context.cacheDir.walkTopDown()
            .filter { it.isFile && (it.path.contains("pdf", true) || it.name.startsWith("reflow-v", true)) }
            .sumOf(File::length)
        return AuraCacheStats(total, pdf, (total - pdf).coerceAtLeast(0L))
    }

    fun clearAll() {
        context.cacheDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    fun clearPdfReflow() {
        context.cacheDir.walkBottomUp().forEach { file ->
            if (file.path.contains("pdf", true) || file.name.startsWith("reflow-v", true)) runCatching { file.deleteRecursively() }
        }
    }

    private fun size(root: File): Long = root.walkTopDown().filter(File::isFile).sumOf(File::length)
}
