package com.aurafiles.app.cloud.yandex

enum class YandexFailureKind {
    NETWORK,
    AUTH_PENDING,
    AUTH_DENIED,
    AUTH_EXPIRED,
    INVALID_CLIENT,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    RATE_LIMITED,
    SERVER,
    BAD_RESPONSE,
    OTHER,
}

class YandexApiException(
    val kind: YandexFailureKind,
    val statusCode: Int? = null,
    val providerCode: String = "",
    val providerDescription: String = "",
    message: String = userMessageForYandexFailure(kind, statusCode, providerDescription),
    cause: Throwable? = null,
) : Exception(message, cause) {
    val retryable: Boolean
        get() = kind in setOf(
            YandexFailureKind.NETWORK,
            YandexFailureKind.AUTH_PENDING,
            YandexFailureKind.RATE_LIMITED,
            YandexFailureKind.SERVER,
        )
}

fun classifyYandexFailure(
    statusCode: Int?,
    providerCode: String = "",
    providerDescription: String = "",
): YandexFailureKind {
    val code = providerCode.trim().lowercase()
    val description = providerDescription.lowercase()
    return when {
        code in setOf("authorization_pending", "bad_verification_code") -> YandexFailureKind.AUTH_PENDING
        code in setOf("authorization_declined", "access_denied") -> YandexFailureKind.AUTH_DENIED
        code in setOf("expired_token", "invalid_grant") || "expired" in description -> YandexFailureKind.AUTH_EXPIRED
        code == "invalid_client" || code == "unauthorized_client" -> YandexFailureKind.INVALID_CLIENT
        code == "slow_down" -> YandexFailureKind.RATE_LIMITED
        statusCode == 401 -> YandexFailureKind.UNAUTHORIZED
        statusCode == 403 -> YandexFailureKind.FORBIDDEN
        statusCode == 404 -> YandexFailureKind.NOT_FOUND
        statusCode == 409 -> YandexFailureKind.CONFLICT
        statusCode == 429 -> YandexFailureKind.RATE_LIMITED
        statusCode != null && statusCode >= 500 -> YandexFailureKind.SERVER
        else -> YandexFailureKind.OTHER
    }
}

fun userMessageForYandexFailure(
    kind: YandexFailureKind,
    statusCode: Int? = null,
    providerDescription: String = "",
): String = when (kind) {
    YandexFailureKind.NETWORK -> "Не удалось связаться с Яндексом. Проверь интернет и повтори попытку."
    YandexFailureKind.AUTH_PENDING -> "Жду подтверждения входа через Яндекс."
    YandexFailureKind.AUTH_DENIED -> "Вход через Яндекс отменён пользователем."
    YandexFailureKind.AUTH_EXPIRED -> "Код или refresh token устарел. Нужно войти через Яндекс заново."
    YandexFailureKind.INVALID_CLIENT -> "Яндекс не принял OAuth Client ID или Client Secret приложения. Проверь оба значения в Yandex OAuth."
    YandexFailureKind.UNAUTHORIZED -> "Яндекс не принял OAuth-токен. Требуется повторный вход или обновление токена."
    YandexFailureKind.FORBIDDEN -> "Недостаточно прав для Яндекс.Диска. Проверь разрешения OAuth-приложения."
    YandexFailureKind.NOT_FOUND -> "Файл или папка на Яндекс.Диске не найдены."
    YandexFailureKind.CONFLICT -> "Конфликт на Яндекс.Диске: имя занято или операция несовместима с текущим состоянием."
    YandexFailureKind.RATE_LIMITED -> "Яндекс временно ограничил частоту запросов. Повтори операцию позже."
    YandexFailureKind.SERVER -> "Сервис Яндекса временно недоступен. Повтори операцию позже."
    YandexFailureKind.BAD_RESPONSE -> "Яндекс вернул некорректный ответ."
    YandexFailureKind.OTHER -> providerDescription.trim().ifBlank {
        if (statusCode != null) "Ошибка Яндекса: HTTP $statusCode" else "Запрос к Яндексу не выполнен."
    }
}
