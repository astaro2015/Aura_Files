package com.aurafiles.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

/**
 * Aura-owned read-only content provider for temporary file sharing.
 *
 * This deliberately does not extend AndroidX FileProvider. Real devices have shown that
 * FileProvider can still re-enter its FILE_PROVIDER_PATHS meta-data parser while a recipient (or
 * Aura's own preflight) opens a content URI. Aura instead owns both URI generation and URI -> File
 * resolution, so no manifest meta-data/XML lookup participates in the data path.
 */
class AuraFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = fileForUri(uri)
        val requested = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val columns = mutableListOf<String>()
        val values = mutableListOf<Any>()
        requested.forEach { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> {
                    columns += OpenableColumns.DISPLAY_NAME
                    values += file.name
                }
                OpenableColumns.SIZE -> {
                    columns += OpenableColumns.SIZE
                    values += file.length()
                }
            }
        }
        return MatrixCursor(columns.toTypedArray(), 1).apply {
            addRow(values.toTypedArray())
        }
    }

    override fun getType(uri: Uri): String {
        val file = fileForUri(uri)
        val extension = file.extension.lowercase(Locale.ROOT)
        return when (extension) {
            "apk" -> "application/octet-stream"
            "apks" -> "application/vnd.aurafiles.apks+zip"
            else -> extension.takeIf { it.isNotEmpty() }
                ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
                ?: "application/octet-stream"
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("AuraFileProvider is read-only")
        val file = fileForUri(uri)
        if (!file.isFile) throw FileNotFoundException("File not found: ${file.name}")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("AuraFileProvider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("AuraFileProvider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("AuraFileProvider is read-only")

    private fun fileForUri(uri: Uri): File {
        val ctx = context ?: throw IllegalStateException("AuraFileProvider has no Context")
        val expectedAuthority = authority(ctx)
        require(uri.scheme == "content" && uri.authority == expectedAuthority) {
            "Unexpected AuraFileProvider URI: $uri"
        }

        val segments = uri.pathSegments
        require(segments.size >= 2) { "Malformed AuraFileProvider URI: $uri" }
        val rootName = segments.first()
        val relativePath = segments.drop(1).joinToString("/")
        require(relativePath.isNotBlank()) { "Empty AuraFileProvider relative path" }

        val canonicalPath = AuraFileProviderPathPolicy.resolveIncoming(
            rootName = rootName,
            relativePath = relativePath,
            roots = roots(ctx),
        )
        return File(canonicalPath)
    }

    companion object {
        fun authority(context: Context): String = "${context.packageName}.fileprovider"

        @Suppress("DEPRECATION")
        private fun roots(context: Context): List<AuraFileProviderPathPolicy.Root> = listOf(
            AuraFileProviderPathPolicy.Root(
                "shared_storage",
                Environment.getExternalStorageDirectory().canonicalPath,
            ),
            AuraFileProviderPathPolicy.Root(
                "temporary_shares",
                File(context.cacheDir, "shares").canonicalPath,
            ),
            AuraFileProviderPathPolicy.Root(
                "archive_preview",
                File(context.cacheDir, "archive-preview").canonicalPath,
            ),
            AuraFileProviderPathPolicy.Root(
                "backend_open",
                File(context.cacheDir, "backend-open").canonicalPath,
            ),
            AuraFileProviderPathPolicy.Root(
                "vault_open",
                File(context.cacheDir, "vault-open").canonicalPath,
            ),
        )

        fun uriForFile(context: Context, file: File): Uri {
            val canonicalFile = file.canonicalFile
            val match = AuraFileProviderPathPolicy.resolve(canonicalFile.path, roots(context))
            return Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(match.rootName)
                .apply {
                    match.relativePath.split('/').forEach(::appendPath)
                }
                .build()
        }
    }
}
