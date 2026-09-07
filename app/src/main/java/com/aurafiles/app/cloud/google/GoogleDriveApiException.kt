package com.aurafiles.app.cloud.google

enum class GoogleDriveFailureKind {
    NETWORK,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    RATE_LIMITED,
    SERVER,
    BAD_RESPONSE,
    REAUTHORIZATION_REQUIRED,
    ACCOUNT_MISMATCH,
    UNSUPPORTED_FILE,
    OTHER,
}

class GoogleDriveApiException(
    val kind: GoogleDriveFailureKind,
    val statusCode: Int? = null,
    val providerReason: String = "",
    val providerMessage: String = "",
    message: String = userMessageForGoogleDriveFailure(kind, statusCode, providerMessage),
    cause: Throwable? = null,
) : Exception(message, cause) {
    val retryable: Boolean
        get() = kind in setOf(
            GoogleDriveFailureKind.NETWORK,
            GoogleDriveFailureKind.RATE_LIMITED,
            GoogleDriveFailureKind.SERVER,
        )
}

fun classifyGoogleDriveFailure(
    statusCode: Int?,
    providerReason: String = "",
    providerMessage: String = "",
): GoogleDriveFailureKind {
    val reason = providerReason.trim().lowercase()
    val message = providerMessage.lowercase()
    return when {
        statusCode == 401 -> GoogleDriveFailureKind.UNAUTHORIZED
        statusCode == 403 && reason in setOf("ratelimitexceeded", "userratelimitexceeded", "sharingratelimitexceeded") ->
            GoogleDriveFailureKind.RATE_LIMITED
        statusCode == 403 && ("rate limit" in message || "quota" in message) -> GoogleDriveFailureKind.RATE_LIMITED
        statusCode == 403 -> GoogleDriveFailureKind.FORBIDDEN
        statusCode == 404 -> GoogleDriveFailureKind.NOT_FOUND
        statusCode == 409 -> GoogleDriveFailureKind.CONFLICT
        statusCode == 429 -> GoogleDriveFailureKind.RATE_LIMITED
        statusCode != null && statusCode >= 500 -> GoogleDriveFailureKind.SERVER
        else -> GoogleDriveFailureKind.OTHER
    }
}

fun userMessageForGoogleDriveFailure(
    kind: GoogleDriveFailureKind,
    statusCode: Int? = null,
    providerMessage: String = "",
): String = when (kind) {
    GoogleDriveFailureKind.NETWORK -> "Не удалось связаться с Google Drive. Проверь интернет и повтори попытку."
    GoogleDriveFailureKind.UNAUTHORIZED -> "Google отклонил access token. Требуется обновить авторизацию."
    GoogleDriveFailureKind.FORBIDDEN -> providerMessage.trim().takeIf(String::isNotBlank)?.let {
        "Google Drive запретил запрос: $it"
    } ?: "Недостаточно прав для Google Drive или операция запрещена владельцем файла. Проверь, что Google Drive API включён в Google Cloud и нужный Drive scope добавлен в OAuth consent screen."
    GoogleDriveFailureKind.NOT_FOUND -> "Файл или папка в Google Drive не найдены."
    GoogleDriveFailureKind.CONFLICT -> "Конфликт в Google Drive. Обнови папку и повтори операцию."
    GoogleDriveFailureKind.RATE_LIMITED -> "Google Drive временно ограничил частоту запросов. Повтори операцию позже."
    GoogleDriveFailureKind.SERVER -> "Google Drive временно недоступен. Повтори операцию позже."
    GoogleDriveFailureKind.BAD_RESPONSE -> "Google Drive вернул некорректный ответ."
    GoogleDriveFailureKind.REAUTHORIZATION_REQUIRED -> "Google требует подтверждения доступа. Войди в аккаунт Google Drive заново."
    GoogleDriveFailureKind.ACCOUNT_MISMATCH -> "Google вернул другой аккаунт. Выбери тот же аккаунт, который был подключён к Aura Files."
    GoogleDriveFailureKind.UNSUPPORTED_FILE -> providerMessage.trim().ifBlank { "Этот тип файла Google Drive нельзя скачать как обычный файл." }
    GoogleDriveFailureKind.OTHER -> providerMessage.trim().ifBlank {
        if (statusCode != null) "Ошибка Google Drive: HTTP $statusCode" else "Запрос к Google Drive не выполнен."
    }
}
