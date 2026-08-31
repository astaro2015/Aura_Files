package com.aurafiles.app.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

/**
 * Lists directory metadata with one provider query when possible.
 * DocumentFile's getters each perform their own provider query; using them in a loop
 * can turn one directory listing into thousands of IPC calls.
 */
data class FastDocumentInfo(
    val document: DocumentFile,
    val name: String,
    val uri: Uri,
    val isDirectory: Boolean,
    val mimeType: String?,
    val size: Long,
    val modifiedAt: Long,
)

object FastDocumentListing {
    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    fun list(context: Context, directory: DocumentFile): List<FastDocumentInfo> {
        val uri = directory.uri
        if (uri.scheme == ContentResolver.SCHEME_FILE) return listFileDirectory(uri)
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            queryDocumentProvider(context, uri)?.let { return it }
        }
        return fallback(directory)
    }

    fun resolve(context: Context, uri: Uri): DocumentFile? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: return null
            return runCatching { DocumentFile.fromFile(File(path)) }.getOrNull()
        }
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        return when {
            runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false) ->
                runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
                    ?: runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            uri.path?.contains("/tree/") == true ->
                runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
                    ?: runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
            else -> runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
        }
    }

    private fun listFileDirectory(uri: Uri): List<FastDocumentInfo> {
        val directory = uri.path?.let(::File)
            ?: throw IOException("Не удалось определить путь папки")
        val children = directory.listFiles()
            ?: throw IOException("Не удалось прочитать ${directory.name}: доступ отозван или накопитель отключён")
        return children.map { file ->
            val childUri = Uri.fromFile(file)
            FastDocumentInfo(
                document = DocumentFile.fromFile(file),
                name = file.name,
                uri = childUri,
                isDirectory = file.isDirectory,
                mimeType = if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else
                    java.net.URLConnection.guessContentTypeFromName(file.name),
                size = if (file.isFile) file.length().coerceAtLeast(0L) else 0L,
                modifiedAt = file.lastModified().coerceAtLeast(0L),
            )
        }
    }

    private fun queryDocumentProvider(context: Context, directoryUri: Uri): List<FastDocumentInfo>? {
        val documentId = runCatching {
            if (DocumentsContract.isDocumentUri(context, directoryUri)) {
                DocumentsContract.getDocumentId(directoryUri)
            } else {
                DocumentsContract.getTreeDocumentId(directoryUri)
            }
        }.getOrNull() ?: return null
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri, documentId)
        }.getOrNull() ?: return null
        val resolver = context.contentResolver
        val result = ArrayList<FastDocumentInfo>()
        // A provider that does not expose the child-document query may return null and can
        // still be handled by DocumentFile below. Security/IO failures, however, must reach
        // the UI: silently converting them to an empty directory creates dangerous phantom
        // state after a USB drive is removed or a persisted SAF grant is revoked.
        val cursor = resolver.query(childrenUri, projection, null, null, null) ?: return null
        cursor.use { c ->
            val idColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) return null
            while (c.moveToNext()) {
                val childId = c.getString(idColumn) ?: continue
                val name = c.getString(nameColumn) ?: continue
                val mime = c.getString(mimeColumn)
                val childUri = runCatching {
                    DocumentsContract.buildDocumentUriUsingTree(directoryUri, childId)
                }.getOrNull() ?: continue
                val document = DocumentFile.fromSingleUri(context, childUri) ?: continue
                val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (!isDirectory && sizeColumn >= 0 && !c.isNull(sizeColumn)) c.getLong(sizeColumn).coerceAtLeast(0L) else 0L
                val modified = if (modifiedColumn >= 0 && !c.isNull(modifiedColumn)) c.getLong(modifiedColumn).coerceAtLeast(0L) else 0L
                result += FastDocumentInfo(document, name, childUri, isDirectory, mime, size, modified)
            }
        }
        return result
    }

    private fun fallback(directory: DocumentFile): List<FastDocumentInfo> =
        directory.listFiles().mapNotNull { child ->
            val name = child.name ?: return@mapNotNull null
            val isDirectory = child.isDirectory
            FastDocumentInfo(
                document = child,
                name = name,
                uri = child.uri,
                isDirectory = isDirectory,
                mimeType = child.type,
                size = if (isDirectory) 0L else child.length().coerceAtLeast(0L),
                modifiedAt = child.lastModified().coerceAtLeast(0L),
            )
        }
}
