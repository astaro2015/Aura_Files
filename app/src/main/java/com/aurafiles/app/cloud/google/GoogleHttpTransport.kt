package com.aurafiles.app.cloud.google

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

internal data class GoogleHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val connectTimeoutMs: Int = 20_000,
    val readTimeoutMs: Int = 60_000,
) {
    override fun toString(): String {
        val safeHeaders = headers.mapValues { (name, value) ->
            if (name.equals("Authorization", ignoreCase = true)) "<redacted>" else value
        }
        return "GoogleHttpRequest(method=$method, url=$url, headers=$safeHeaders, bodyBytes=${body?.size ?: 0}, connectTimeoutMs=$connectTimeoutMs, readTimeoutMs=$readTimeoutMs)"
    }
}

internal data class GoogleHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value?.firstOrNull()

    override fun toString(): String = "GoogleHttpResponse(statusCode=$statusCode, bodyChars=${body.length})"
}

internal fun interface GoogleHttpTransport {
    @Throws(GoogleDriveApiException::class)
    fun execute(request: GoogleHttpRequest): GoogleHttpResponse
}

internal class UrlConnectionGoogleHttpTransport : GoogleHttpTransport {
    override fun execute(request: GoogleHttpRequest): GoogleHttpResponse {
        val connection = try {
            URI.create(request.url).toURL().openConnection() as HttpURLConnection
        } catch (error: Exception) {
            throw networkError(error)
        }
        return try {
            connection.requestMethod = request.method.uppercase()
            connection.connectTimeout = request.connectTimeoutMs
            connection.readTimeout = request.readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            request.headers.forEach(connection::setRequestProperty)
            request.body?.let { body ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            @Suppress("UNCHECKED_CAST")
            val responseHeaders = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key ?: "" }
                .mapValues { it.value ?: emptyList() }
            GoogleHttpResponse(status, responseBody, responseHeaders)
        } catch (error: IOException) {
            throw networkError(error)
        } finally {
            connection.disconnect()
        }
    }

    private fun networkError(error: Throwable) = GoogleDriveApiException(
        kind = GoogleDriveFailureKind.NETWORK,
        message = userMessageForGoogleDriveFailure(GoogleDriveFailureKind.NETWORK),
        cause = error,
    )
}

internal fun googleUrlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
