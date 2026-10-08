package com.aurafiles.app.cloud.yandex

import java.time.Instant
import java.time.OffsetDateTime

interface YandexDiskApi {
    fun diskInfo(): YandexDiskInfo
    fun resource(path: String): YandexDiskResource?
    fun list(path: String): List<YandexDiskResource>
    fun uploadLink(path: String, overwrite: Boolean): YandexTransferLink
    fun downloadLink(path: String): YandexTransferLink
    fun mkdir(path: String)
    fun move(sourcePath: String, destinationPath: String, overwrite: Boolean = false): YandexOperationLink
    fun delete(path: String, permanently: Boolean = false): YandexOperationLink
    fun operationState(href: String): YandexOperationState
}

class YandexDiskApiClient internal constructor(
    private val accessToken: String,
    private val transport: YandexHttpTransport = UrlConnectionYandexHttpTransport(),
) : YandexDiskApi {
    init {
        require(accessToken.isNotBlank()) { "Пустой Yandex access token" }
    }

    override fun diskInfo(): YandexDiskInfo {
        val response = request("GET", "")
        requireSuccess(response, setOf(200))
        val json = parseJsonObject(response)
        val userJson = json.optJSONObject("user")
        return YandexDiskInfo(
            totalSpace = json.nullableLong("total_space"),
            usedSpace = json.nullableLong("used_space"),
            trashSize = json.nullableLong("trash_size"),
            maxFileSize = json.nullableLong("max_file_size"),
            paid = if (json.has("is_paid") && !json.isNull("is_paid")) json.optBoolean("is_paid") else null,
            revision = json.nullableLong("revision"),
            user = YandexDiskUser(
                uid = userJson?.optString("uid").orEmpty(),
                login = userJson?.optString("login").orEmpty(),
                displayName = userJson?.optString("display_name").orEmpty(),
            ),
        )
    }

    override fun resource(path: String): YandexDiskResource? {
        val normalized = normalizeDiskPath(path)
        val response = request("GET", "resources", mapOf("path" to normalized))
        if (response.statusCode == 404) return null
        requireSuccess(response, setOf(200))
        return parseResource(parseJsonObject(response), normalized)
    }

    override fun list(path: String): List<YandexDiskResource> {
        val normalized = normalizeDiskPath(path)
        val result = mutableListOf<YandexDiskResource>()
        var offset = 0
        val limit = PAGE_SIZE
        while (true) {
            val response = request(
                "GET",
                "resources",
                mapOf(
                    "path" to normalized,
                    "limit" to limit.toString(),
                    "offset" to offset.toString(),
                ),
            )
            requireSuccess(response, setOf(200))
            val embedded = parseJsonObject(response).optJSONObject("_embedded")
                ?: return result.sortedResources()
            val items = embedded.optJSONArray("items")
            val count = items?.length() ?: 0
            for (index in 0 until count) {
                val item = items?.optJSONObject(index) ?: continue
                result += parseResource(item, normalized)
            }
            val total = embedded.optLong("total", result.size.toLong()).coerceAtLeast(result.size.toLong())
            if (count == 0 || result.size.toLong() >= total) break
            offset += count
        }
        return result.sortedResources()
    }

    override fun uploadLink(path: String, overwrite: Boolean): YandexTransferLink {
        val response = request(
            "GET",
            "resources/upload",
            mapOf("path" to normalizeDiskPath(path), "overwrite" to overwrite.toString()),
        )
        requireSuccess(response, setOf(200))
        return parseTransferLink(response, defaultMethod = "PUT")
    }

    override fun downloadLink(path: String): YandexTransferLink {
        val response = request(
            "GET",
            "resources/download",
            mapOf("path" to normalizeDiskPath(path)),
        )
        requireSuccess(response, setOf(200))
        return parseTransferLink(response, defaultMethod = "GET")
    }

    override fun mkdir(path: String) {
        val response = request("PUT", "resources", mapOf("path" to normalizeDiskPath(path)))
        requireSuccess(response, setOf(201))
    }

    override fun move(sourcePath: String, destinationPath: String, overwrite: Boolean): YandexOperationLink {
        val response = request(
            "POST",
            "resources/move",
            mapOf(
                "from" to normalizeDiskPath(sourcePath),
                "path" to normalizeDiskPath(destinationPath),
                "overwrite" to overwrite.toString(),
            ),
        )
        requireSuccess(response, setOf(201, 202))
        return parseOperationLink(response)
    }

    override fun delete(path: String, permanently: Boolean): YandexOperationLink {
        val response = request(
            "DELETE",
            "resources",
            mapOf(
                "path" to normalizeDiskPath(path),
                "permanently" to permanently.toString(),
            ),
        )
        if (response.statusCode == 404) return YandexOperationLink()
        requireSuccess(response, setOf(202, 204))
        return parseOperationLink(response)
    }

    override fun operationState(href: String): YandexOperationState {
        require(href.startsWith("https://")) { "Некорректная ссылка операции Яндекс.Диска" }
        val response = transport.execute(
            YandexHttpRequest(
                method = "GET",
                url = href,
                headers = authHeaders(),
                connectTimeoutMs = OPERATION_STATUS_TIMEOUT_MS,
                readTimeoutMs = OPERATION_STATUS_TIMEOUT_MS,
            ),
        )
        requireSuccess(response, setOf(200))
        return when (parseJsonObject(response).optString("status").lowercase()) {
            "success" -> YandexOperationState.SUCCESS
            "failed" -> YandexOperationState.FAILED
            else -> YandexOperationState.IN_PROGRESS
        }
    }

    private fun request(
        method: String,
        endpoint: String,
        query: Map<String, String> = emptyMap(),
    ): YandexHttpResponse {
        val base = API_BASE + endpoint.trimStart('/')
        val url = if (query.isEmpty()) base else {
            base + "?" + query.entries.joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }
        }
        return transport.execute(
            YandexHttpRequest(
                method = method,
                url = url,
                headers = authHeaders(),
            ),
        )
    }

    private fun authHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Authorization" to "OAuth ${accessToken.trim()}",
        "User-Agent" to YandexOAuthClient.USER_AGENT,
    )

    private fun requireSuccess(response: YandexHttpResponse, expected: Set<Int>) {
        if (response.statusCode !in expected) throwYandexError(response)
    }

    private fun parseTransferLink(response: YandexHttpResponse, defaultMethod: String): YandexTransferLink {
        val json = parseJsonObject(response)
        val href = json.optString("href")
        if (href.isBlank()) {
            throw YandexApiException(
                kind = YandexFailureKind.BAD_RESPONSE,
                statusCode = response.statusCode,
                message = "Яндекс не вернул ссылку передачи файла.",
            )
        }
        return YandexTransferLink(
            href = href,
            method = json.optString("method").ifBlank { defaultMethod }.uppercase(),
        )
    }

    private fun parseOperationLink(response: YandexHttpResponse): YandexOperationLink {
        if (response.body.isBlank()) return YandexOperationLink()
        return YandexOperationLink(parseJsonObject(response).optString("href").takeIf(String::isNotBlank))
    }

    private fun parseResource(json: org.json.JSONObject, fallbackParent: String): YandexDiskResource {
        val name = json.optString("name").ifBlank {
            normalizeDiskPath(json.optString("path")).substringAfterLast('/').ifBlank { "/" }
        }
        val providerPath = normalizeProviderPath(json.optString("path"))
        val path = when {
            providerPath != "/" -> providerPath
            name == "/" -> "/"
            fallbackParent == "/" -> "/$name"
            else -> "$fallbackParent/$name"
        }
        val directory = json.optString("type").equals("dir", ignoreCase = true)
        return YandexDiskResource(
            path = normalizeDiskPath(path),
            name = name,
            isDirectory = directory,
            size = if (directory) 0L else json.optLong("size", 0L).coerceAtLeast(0L),
            modifiedAtEpochMs = parseYandexTime(json.optString("modified")),
            mimeType = if (directory) null else json.nullableString("mime_type"),
        )
    }

    private fun normalizeProviderPath(raw: String): String {
        val value = raw.trim().removePrefix("disk:")
        return if (value.isBlank()) "/" else normalizeDiskPath(value)
    }

    private fun normalizeDiskPath(raw: String): String {
        if (raw.isBlank()) return "/"
        val segments = raw.replace('\\', '/').split('/')
            .filter(String::isNotBlank)
            .filter { it != "." }
        require(segments.none { it == ".." }) { "Выход за корень Яндекс.Диска запрещён" }
        return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
    }

    private fun parseYandexTime(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(value).toEpochMilli() }
            .getOrDefault(0L)
    }

    private fun List<YandexDiskResource>.sortedResources(): List<YandexDiskResource> =
        sortedWith(compareByDescending<YandexDiskResource> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    companion object {
        const val API_BASE = "https://cloud-api.yandex.net/v1/disk/"
        private const val PAGE_SIZE = 200
        private const val OPERATION_STATUS_TIMEOUT_MS = 10_000
    }
}
