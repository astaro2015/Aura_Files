package com.aurafiles.app.cloud.google

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI

interface GoogleBinaryReadHandle : AutoCloseable {
    val input: InputStream
}

interface GoogleBinaryWriteHandle : AutoCloseable {
    val output: OutputStream
    fun commit()
    fun abort()
}

interface GoogleBinaryTransfer {
    fun openRead(accessToken: String, file: GoogleDriveFile, exportSpec: GoogleExportSpec? = null): GoogleBinaryReadHandle
    fun openWrite(sessionUrl: String, expectedSize: Long?, mimeType: String): GoogleBinaryWriteHandle
}

/**
 * Binary Drive transfers over HTTPS.
 *
 * For a known source size the upload streams directly to the resumable-session URL using a
 * fixed Content-Length, which is the documented Drive single-request resumable-upload form.
 * If the backend cannot report a trustworthy size, data is first spooled to an app-private temp
 * file and then uploaded with its measured fixed length instead of relying on an undocumented
 * chunked PUT. The temp file is always removed on commit/abort/close.
 */
class UrlConnectionGoogleBinaryTransfer(
    private val tempDirectory: File? = null,
) : GoogleBinaryTransfer {
    override fun openRead(
        accessToken: String,
        file: GoogleDriveFile,
        exportSpec: GoogleExportSpec?,
    ): GoogleBinaryReadHandle {
        val url = if (exportSpec != null) {
            "${GoogleDriveApiClient.API_BASE}files/${googleUrlEncode(file.id)}/export?mimeType=${googleUrlEncode(exportSpec.mimeType)}"
        } else {
            "${GoogleDriveApiClient.API_BASE}files/${googleUrlEncode(file.id)}?alt=media&supportsAllDrives=true"
        }
        val connection = open(url)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw parseGoogleError(GoogleHttpResponse(status, body))
            }
            val stream = object : FilterInputStream(connection.inputStream) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        connection.disconnect()
                    }
                }
            }
            return object : GoogleBinaryReadHandle {
                override val input: InputStream = stream
                override fun close() = input.close()
            }
        } catch (error: Throwable) {
            connection.disconnect()
            if (error is GoogleDriveApiException) throw error
            if (error is IOException) throw networkError(error)
            throw error
        }
    }

    override fun openWrite(sessionUrl: String, expectedSize: Long?, mimeType: String): GoogleBinaryWriteHandle {
        require(sessionUrl.startsWith("https://")) { "Google resumable upload URL должен использовать HTTPS" }
        return if (expectedSize != null && expectedSize >= 0L) {
            directFixedLengthWrite(sessionUrl, expectedSize, mimeType)
        } else {
            spooledFixedLengthWrite(sessionUrl, mimeType)
        }
    }

    private fun directFixedLengthWrite(
        sessionUrl: String,
        expectedSize: Long,
        mimeType: String,
    ): GoogleBinaryWriteHandle {
        val connection = uploadConnection(sessionUrl, expectedSize, mimeType)
        val raw = try {
            connection.outputStream
        } catch (error: IOException) {
            connection.disconnect()
            throw networkError(error)
        }
        var written = 0L
        val counting = object : FilterOutputStream(raw) {
            override fun write(b: Int) {
                if (written >= expectedSize) throw IOException("Записано больше ожидаемого размера Google Drive upload")
                out.write(b)
                written += 1L
            }

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (written + length > expectedSize) throw IOException("Записано больше ожидаемого размера Google Drive upload")
                out.write(buffer, offset, length)
                written += length.toLong()
            }
        }
        return object : GoogleBinaryWriteHandle {
            private var finished = false
            override val output: OutputStream = counting

            override fun commit() {
                if (finished) return
                try {
                    if (written != expectedSize) {
                        throw IOException("Google Drive upload: записано $written из $expectedSize байт")
                    }
                    finishUpload(connection, output)
                    finished = true
                } catch (error: Throwable) {
                    finished = true
                    if (error is GoogleDriveApiException) throw error
                    if (error is IOException) throw networkError(error)
                    throw error
                } finally {
                    connection.disconnect()
                }
            }

            override fun abort() {
                if (finished) return
                finished = true
                runCatching { output.close() }
                connection.disconnect()
            }

            override fun close() {
                if (!finished) abort()
            }
        }
    }

    private fun spooledFixedLengthWrite(sessionUrl: String, mimeType: String): GoogleBinaryWriteHandle {
        tempDirectory?.mkdirs()
        val temp = File.createTempFile("aura-google-upload-", ".part", tempDirectory)
        val fileOutput = try {
            BufferedOutputStream(FileOutputStream(temp), SPOOL_BUFFER_SIZE)
        } catch (error: IOException) {
            runCatching { temp.delete() }
            throw networkError(error)
        }
        return object : GoogleBinaryWriteHandle {
            private var finished = false
            override val output: OutputStream = fileOutput

            override fun commit() {
                if (finished) return
                var connection: HttpURLConnection? = null
                try {
                    output.flush()
                    output.close()
                    val measuredSize = temp.length()
                    connection = uploadConnection(sessionUrl, measuredSize, mimeType)
                    temp.inputStream().buffered(SPOOL_BUFFER_SIZE).use { input ->
                        connection.outputStream.buffered(SPOOL_BUFFER_SIZE).use { target ->
                            input.copyTo(target, SPOOL_BUFFER_SIZE)
                        }
                    }
                    finishUploadAfterOutputClosed(connection)
                    finished = true
                } catch (error: Throwable) {
                    finished = true
                    if (error is GoogleDriveApiException) throw error
                    if (error is IOException) throw networkError(error)
                    throw error
                } finally {
                    connection?.disconnect()
                    runCatching { output.close() }
                    runCatching { temp.delete() }
                }
            }

            override fun abort() {
                if (finished) return
                finished = true
                runCatching { output.close() }
                runCatching { temp.delete() }
            }

            override fun close() {
                if (!finished) abort()
            }
        }
    }

    private fun uploadConnection(sessionUrl: String, contentLength: Long, mimeType: String): HttpURLConnection {
        val connection = open(sessionUrl)
        try {
            connection.requestMethod = "PUT"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = UPLOAD_READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", mimeType.ifBlank { "application/octet-stream" })
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setFixedLengthStreamingMode(contentLength)
            return connection
        } catch (error: Throwable) {
            connection.disconnect()
            throw error
        }
    }

    private fun finishUpload(connection: HttpURLConnection, output: OutputStream) {
        output.flush()
        output.close()
        finishUploadAfterOutputClosed(connection)
    }

    private fun finishUploadAfterOutputClosed(connection: HttpURLConnection) {
        val status = connection.responseCode
        val responseStream = if (status >= 400) connection.errorStream else connection.inputStream
        val body = responseStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status !in setOf(200, 201)) {
            throw parseGoogleError(GoogleHttpResponse(status, body))
        }
    }

    private fun open(url: String): HttpURLConnection = try {
        URI.create(url).toURL().openConnection() as HttpURLConnection
    } catch (error: Exception) {
        throw networkError(error)
    }

    private fun networkError(error: Throwable) = GoogleDriveApiException(
        kind = GoogleDriveFailureKind.NETWORK,
        message = userMessageForGoogleDriveFailure(GoogleDriveFailureKind.NETWORK),
        cause = error,
    )

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val UPLOAD_READ_TIMEOUT_MS = 180_000
        private const val SPOOL_BUFFER_SIZE = 1024 * 1024
        private const val USER_AGENT = "AuraFiles/1.2.4"
    }
}
