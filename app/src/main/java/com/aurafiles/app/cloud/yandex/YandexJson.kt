package com.aurafiles.app.cloud.yandex

import org.json.JSONObject

internal fun parseJsonObject(response: YandexHttpResponse): JSONObject {
    if (response.body.isBlank()) return JSONObject()
    return try {
        JSONObject(response.body)
    } catch (error: Exception) {
        throw YandexApiException(
            kind = YandexFailureKind.BAD_RESPONSE,
            statusCode = response.statusCode,
            message = userMessageForYandexFailure(YandexFailureKind.BAD_RESPONSE),
            cause = error,
        )
    }
}

internal fun throwYandexError(response: YandexHttpResponse): Nothing {
    val json = runCatching { JSONObject(response.body) }.getOrNull()
    val providerCode = json?.optString("error").orEmpty()
    val description = sequenceOf(
        json?.optString("error_description"),
        json?.optString("message"),
        json?.optString("description"),
    ).filterNotNull().firstOrNull { it.isNotBlank() }.orEmpty()
    val kind = classifyYandexFailure(response.statusCode, providerCode, description)
    throw YandexApiException(
        kind = kind,
        statusCode = response.statusCode,
        providerCode = providerCode,
        providerDescription = description,
        message = userMessageForYandexFailure(kind, response.statusCode, description),
    )
}

internal fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)

internal fun JSONObject.nullableLong(name: String): Long? =
    if (!has(name) || isNull(name)) null else runCatching { getLong(name) }.getOrNull()
