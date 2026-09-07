package com.aurafiles.app.cloud.google

import org.json.JSONObject

internal fun parseGoogleJsonObject(response: GoogleHttpResponse): JSONObject = try {
    JSONObject(response.body.ifBlank { "{}" })
} catch (error: Exception) {
    throw GoogleDriveApiException(
        kind = GoogleDriveFailureKind.BAD_RESPONSE,
        statusCode = response.statusCode,
        message = userMessageForGoogleDriveFailure(GoogleDriveFailureKind.BAD_RESPONSE),
        cause = error,
    )
}

internal fun parseGoogleError(response: GoogleHttpResponse): GoogleDriveApiException {
    val json = runCatching { JSONObject(response.body) }.getOrNull()
    val error = json?.optJSONObject("error")
    val message = error?.optString("message").orEmpty()
    val errors = error?.optJSONArray("errors")
    val reason = errors?.optJSONObject(0)?.optString("reason").orEmpty()
    val kind = classifyGoogleDriveFailure(response.statusCode, reason, message)
    return GoogleDriveApiException(
        kind = kind,
        statusCode = response.statusCode,
        providerReason = reason,
        providerMessage = message,
    )
}
