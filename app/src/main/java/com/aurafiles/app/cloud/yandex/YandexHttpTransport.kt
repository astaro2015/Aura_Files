package com.aurafiles.app.cloud.yandex

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.net.URLEncoder

internal data class YandexHttpRequest(
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
        return "YandexHttpRequest(method=$method, url=$url, headers=$safeHeaders, bodyBytes=${body?.size ?: 0}, connectTimeoutMs=$connectTimeoutMs, readTimeoutMs=$readTimeoutMs)"
    }
}

internal data class YandexHttpResponse(
    val statusCode: Int,
    val body: String,
) {
    override fun toString(): String =
        "YandexHttpResponse(statusCode=$statusCode, bodyChars=${body.length})"
}

internal fun interface YandexHttpTransport {
    @Throws(YandexApiException::class)
    fun execute(request: YandexHttpRequest): YandexHttpResponse
}

internal class UrlConnectionYandexHttpTransport : YandexHttpTransport {
    override fun execute(request: YandexHttpRequest): YandexHttpResponse {
        val attempts = candidateRequests(request)
        var lastError: IOException? = null
        attempts.forEachIndexed { index, candidate ->
            try {
                val response = executeOnce(candidate)
                // A provider-side 5xx can be host/edge specific. Try the other official
                // Yandex OAuth hostname once, but never hide 4xx OAuth/account errors.
                if (response.statusCode >= 500 && index < attempts.lastIndex) return@forEachIndexed
                return response
            } catch (error: IOException) {
                lastError = error
                if (index == attempts.lastIndex) throw networkError(error, candidate.url)
            }
        }
        throw networkError(lastError ?: IOException("Неизвестная сетевая ошибка"), request.url)
    }

    private fun executeOnce(request: YandexHttpRequest): YandexHttpResponse {
        val connection = URI.create(request.url).toURL().openConnection() as HttpURLConnection
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
            YandexHttpResponse(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * oauth.yandex.ru and oauth.yandex.com are both official OAuth hosts. Some mobile
     * DNS/VPN/operator combinations temporarily fail one hostname while the other works.
     * Retry only on transport failure; HTTP provider errors are never hidden by fallback.
     */
    private fun candidateRequests(request: YandexHttpRequest): List<YandexHttpRequest> = when {
        request.url.startsWith(YANDEX_COM_PREFIX) -> listOf(
            request,
            request.copy(url = YANDEX_RU_PREFIX + request.url.removePrefix(YANDEX_COM_PREFIX)),
        )
        request.url.startsWith(YANDEX_RU_PREFIX) -> listOf(
            request,
            request.copy(url = YANDEX_COM_PREFIX + request.url.removePrefix(YANDEX_RU_PREFIX)),
        )
        else -> listOf(request)
    }

    private fun networkError(error: IOException, url: String): YandexApiException {
        val detail = when (error) {
            is UnknownHostException -> "DNS: ${error.message.orEmpty()}"
            is SocketTimeoutException -> "тайм-аут: ${error.message.orEmpty()}"
            is SSLException -> "TLS: ${error.message.orEmpty()}"
            else -> "${error::class.java.simpleName}: ${error.message.orEmpty()}"
        }.trim().trimEnd(':')
        val host = runCatching { URI.create(url).host }.getOrNull().orEmpty()
        return YandexApiException(
            kind = YandexFailureKind.NETWORK,
            providerDescription = detail,
            message = buildString {
                append("Не удалось связаться с Яндексом")
                if (host.isNotBlank()) append(" (").append(host).append(')')
                append('.')
                if (detail.isNotBlank()) append(" ").append(detail)
                append(". Aura продолжит повторять запрос, пока код входа не истечёт.")
            },
            cause = error,
        )
    }

    private companion object {
        const val YANDEX_RU_PREFIX = "https://oauth.yandex.ru"
        const val YANDEX_COM_PREFIX = "https://oauth.yandex.com"
    }
}

internal fun formBody(values: Map<String, String>): ByteArray = values
    .filterValues { it.isNotBlank() }
    .entries
    .joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }
    .toByteArray(Charsets.UTF_8)

internal fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
