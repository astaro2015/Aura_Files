package com.aurafiles.app.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Shared guards for recursive local/SAF traversal. */
object DocumentTreeSafety {
    const val MAX_DEPTH = 256

    fun identityKey(document: DocumentFile): String = identityKey(document.uri)

    fun identityKey(uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: return "file:${uri.normalizeScheme()}"
            val file = File(path)
            val canonical = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
            return "file:$canonical"
        }
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val authority = uri.authority.orEmpty()
            val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            if (!documentId.isNullOrBlank()) return "content:$authority:$documentId"
        }
        return uri.normalizeScheme().toString()
    }

    fun sameIdentity(left: Uri, right: Uri): Boolean = identityKey(left) == identityKey(right)

    fun isFilesystemSymlink(document: DocumentFile): Boolean {
        if (document.uri.scheme != ContentResolver.SCHEME_FILE) return false
        val path = document.uri.path ?: return false
        return runCatching { Files.isSymbolicLink(File(path).toPath()) }.getOrDefault(false)
    }

    fun requireNotFilesystemSymlink(document: DocumentFile, operation: String) {
        if (isFilesystemSymlink(document)) {
            throw IOException("$operation: символическая ссылка ${document.name ?: document.uri} не обходится рекурсивно")
        }
    }

    fun requireDepth(depth: Int, operation: String) {
        if (depth > MAX_DEPTH) {
            throw IOException("$operation: превышена безопасная глубина каталогов $MAX_DEPTH")
        }
    }

    fun requireUniqueDirectoryVisit(
        directory: DocumentFile,
        depth: Int,
        visited: MutableSet<String>,
        operation: String,
    ): String {
        requireDepth(depth, operation)
        requireNotFilesystemSymlink(directory, operation)
        val key = identityKey(directory)
        if (!visited.add(key)) {
            throw IOException("$operation: обнаружен цикл или повторный каталог ${directory.name ?: directory.uri}")
        }
        return key
    }

    /** Returns null when path ancestry cannot be proven from direct filesystem paths. */
    fun isSameOrDescendantFile(candidate: DocumentFile, ancestor: DocumentFile): Boolean? {
        if (candidate.uri.scheme != ContentResolver.SCHEME_FILE || ancestor.uri.scheme != ContentResolver.SCHEME_FILE) {
            return null
        }
        val candidatePath = candidate.uri.path ?: return null
        val ancestorPath = ancestor.uri.path ?: return null
        val candidateCanonical = runCatching { File(candidatePath).canonicalFile }.getOrNull() ?: return null
        val ancestorCanonical = runCatching { File(ancestorPath).canonicalFile }.getOrNull() ?: return null
        return candidateCanonical.path == ancestorCanonical.path ||
            candidateCanonical.path.startsWith(ancestorCanonical.path + File.separator)
    }
}
