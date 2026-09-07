package com.aurafiles.app.cloud.yandex

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI

interface YandexBinaryReadHandle : Closeable {
    val input: InputStream
}

interface YandexBinaryWriteHandle : Closeable {
    val output: OutputStream
    fun commit()
    fun abort()
}

interface YandexBinaryTransfer {
    fun openRead(link: YandexTransferLink): YandexBinaryReadHandle
    fun openWrite(link: YandexTransferLink, expectedSize: Long? = null): YandexBinaryWriteHandle
}

class UrlConnectionYandexBinaryTransfer : YandexBinaryTransfer {
    override fun openRead(link: YandexTransferLink): YandexBinaryReadHandle {
        val connection = openConnection(link)
        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                connection.errorStream?.close()
                connection.disconnect()
                throw transferError(status)
            }
            val stream = connection.inputStream
            object : YandexBinaryReadHandle {
                private var closed = false
                override val input: InputStream = stream
                override fun close() {
                    if (closed) return
                    closed = true
                    runCatching { input.close() }
                    connection.disconnect()
                }
            }
        } catch (error: IOException) {
            connection.disconnect()
            throw networkTransferError(error)
        }
    }

    override fun openWrite(link: YandexTransferLink, expectedSize: Long?): YandexBinaryWriteHandle {
        val connection = openConnection(link).apply {
            doOutput = true
            if (expectedSize != null && expectedSize >= 0L) {
                setFixedLengthStreamingMode(expectedSize)
            } else {
                setChunkedStreamingMode(CHUNK_SIZE)
            }
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        val stream = try {
            connection.outputStream
        } catch (error: IOException) {
            connection.disconnect()
            throw networkTransferError(error)
        }
        return object : YandexBinaryWriteHandle {
            private var finished = false
            override val output: OutputStream = stream

            override fun commit() {
                if (finished) return
                try {
                    output.flush()
                    output.close()
                    val status = connection.responseCode
                    if (status !in 200..299) {
                        connection.errorStream?.close()
                        throw transferError(status)
                    }
                    connection.inputStream?.close()
                    finished = true
                } catch (error: IOException) {
                    throw networkTransferError(error)
                } finally {
                    connection.disconnect()
                    finished = true
                }
            }

            override fun abort() {
                if (finished) return
                runCatching { output.close() }
                connection.disconnect()
                finished = true
            }

            override fun close() {
                if (!finished) abort()
            }
        }
    }

    private fun openConnection(link: YandexTransferLink): HttpURLConnection {
        val connection = try {
            URI.create(link.href).toURL().openConnection() as HttpURLConnection
        } catch (error: Exception) {
            throw YandexApiException(
                kind = YandexFailureKind.BAD_RESPONSE,
                message = "Яндекс вернул некорректную ссылку передачи файла.",
                cause = error,
            )
        }
        connection.requestMethod = link.method.uppercase()
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", YandexOAuthClient.USER_AGENT)
        return connection
    }

    private fun transferError(status: Int): YandexApiException {
        val kind = classifyYandexFailure(status)
        return YandexApiException(
            kind = kind,
            statusCode = status,
            message = userMessageForYandexFailure(kind, status),
        )
    }

    private fun networkTransferError(error: IOException) = YandexApiException(
        kind = YandexFailureKind.NETWORK,
        message = userMessageForYandexFailure(YandexFailureKind.NETWORK),
        cause = error,
    )

    companion object {
        private const val CHUNK_SIZE = 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 120_000
    }
}
