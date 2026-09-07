package com.aurafiles.app.network

import com.aurafiles.app.model.SmbProfile
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal data class SmbTarget(
    val host: String,
    val share: String,
)

internal data class SmbIdentity(
    val username: String,
    val domain: String,
)

/**
 * Accepts the forms users commonly paste into an SMB dialog:
 *   server, server:445, \\server\share, smb://server/share, [IPv6]:445/share.
 * Aura intentionally supports direct SMB2/SMB3 on the standard TCP port 445 only.
 */
internal fun parseSmbTarget(rawHost: String, rawShare: String = ""): SmbTarget {
    var raw = rawHost.trim()
    if (raw.startsWith("smb://", ignoreCase = true)) raw = raw.substring(6)
    while (raw.startsWith("\\\\") || raw.startsWith("//")) raw = raw.substring(2)

    require(raw.isNotBlank()) { "Введите адрес SMB-устройства" }

    val authority: String
    val remainder: String
    if (raw.startsWith('[')) {
        val close = raw.indexOf(']')
        require(close > 1) { "Некорректный IPv6-адрес SMB" }
        authority = raw.substring(1, close)
        var tail = raw.substring(close + 1)
        if (tail.startsWith(':')) {
            val portText = tail.substring(1).takeWhile(Char::isDigit)
            require(portText.isNotBlank()) { "Некорректный порт SMB" }
            require(portText.toIntOrNull() == 445) { "Aura SMB использует порт 445; указан порт $portText" }
            tail = tail.substring(1 + portText.length)
        }
        require(tail.isEmpty() || tail.startsWith('/') || tail.startsWith('\\')) {
            "Некорректный адрес SMB после IPv6/порта"
        }
        remainder = tail.trimStart('/', '\\')
    } else {
        val separator = raw.indexOfAny(charArrayOf('/', '\\'))
        val rawAuthority = if (separator < 0) raw else raw.substring(0, separator)
        remainder = if (separator < 0) "" else raw.substring(separator + 1)
        val colonCount = rawAuthority.count { it == ':' }
        if (colonCount == 1) {
            val split = rawAuthority.lastIndexOf(':')
            val hostPart = rawAuthority.substring(0, split)
            val portText = rawAuthority.substring(split + 1)
            require(hostPart.isNotBlank() && portText.isNotBlank() && portText.all(Char::isDigit)) {
                "Некорректный адрес или порт SMB"
            }
            require(portText.toIntOrNull() == 445) { "Aura SMB использует порт 445; указан порт $portText" }
            authority = hostPart
        } else {
            // Multiple ':' characters are treated as an unbracketed IPv6 literal. If a port
            // is needed with IPv6, the user must use the unambiguous [IPv6]:445 form.
            authority = rawAuthority
        }
    }

    val host = authority.trim()
    require(host.isNotBlank()) { "Введите адрес SMB-устройства" }
    require(host.none { it == '/' || it == '\\' || it.isWhitespace() || it in "@?#" }) {
        "Некорректный адрес SMB-устройства"
    }
    require(host.none(Char::isISOControl)) { "Некорректный адрес SMB-устройства" }

    fun shareFrom(value: String, pasted: Boolean): String {
        val parts = value.trim().trim('/', '\\')
            .split('/', '\\')
            .map(String::trim)
            .filter(String::isNotBlank)
        if (parts.isEmpty()) return ""
        require(parts.size == 1) {
            if (pasted) "Укажите только сервер и имя общей папки SMB, без подпапки" else
                "В поле шары укажите только её имя, без подпапки"
        }
        val share = parts.single()
        require(share != "." && share != ".." && share.none(Char::isISOControl)) {
            "Некорректное имя общей папки SMB"
        }
        return share
    }

    val pastedShare = shareFrom(remainder, pasted = true)
    val explicitShare = shareFrom(rawShare, pasted = false)
    require(pastedShare.isBlank() || explicitShare.isBlank() || pastedShare.equals(explicitShare, ignoreCase = true)) {
        "Имя шары в адресе и отдельном поле не совпадает"
    }
    return SmbTarget(host = host, share = explicitShare.ifBlank { pastedShare })
}

/** Supports DOMAIN\\user and UPN user@domain while feeding SMBJ separate user/domain fields. */
internal fun parseSmbIdentity(rawUsername: String, rawDomain: String = ""): SmbIdentity {
    var username = rawUsername.trim()
    var domain = rawDomain.trim()
    val slashSeparators = username.count { it == '\\' }
    if (slashSeparators > 0) {
        require(slashSeparators == 1) { "Некорректное имя пользователя SMB" }
        val separator = username.indexOf('\\')
        val embeddedDomain = username.substring(0, separator).trim()
        val embeddedUser = username.substring(separator + 1).trim()
        require(embeddedDomain.isNotBlank() && embeddedUser.isNotBlank()) {
            "Используйте формат DOMAIN\\user"
        }
        if (domain.isNotBlank()) {
            require(domain.equals(embeddedDomain, ignoreCase = true)) {
                "Домен в поле и в имени DOMAIN\\user не совпадает"
            }
        } else {
            domain = embeddedDomain
        }
        username = embeddedUser
    } else if ('@' in username) {
        // SMBJ expects username and domain as separate AuthenticationContext fields. jCIFS also
        // parses UPNs this way internally, so normalize once and keep both clients consistent.
        require(username.count { it == '@' } == 1) { "Некорректное имя пользователя SMB" }
        val at = username.indexOf('@')
        val embeddedUser = username.substring(0, at).trim()
        val embeddedDomain = username.substring(at + 1).trim()
        require(embeddedUser.isNotBlank() && embeddedDomain.isNotBlank()) {
            "Используйте формат user@domain"
        }
        if (domain.isNotBlank()) {
            require(domain.equals(embeddedDomain, ignoreCase = true)) {
                "Домен в поле и в имени user@domain не совпадает"
            }
        } else {
            domain = embeddedDomain
        }
        username = embeddedUser
    }
    require(username.none { it == '/' || it.isISOControl() }) { "Некорректное имя пользователя SMB" }
    require(domain.none { it == '/' || it == '\\' || it.isISOControl() }) { "Некорректный домен SMB" }
    if (username.isBlank()) domain = ""
    return SmbIdentity(username = username, domain = domain)
}

/** Canonical representation used by UI, persistence, SMBJ and jCIFS. */
internal fun SmbProfile.normalizedSmbProfile(): SmbProfile {
    val target = parseSmbTarget(host, share)
    val identity = parseSmbIdentity(username, domain)
    return copy(
        name = name.trim().ifBlank { target.host },
        host = target.host,
        share = target.share,
        username = identity.username,
        // Guest/anonymous authentication does not use a password or a domain. Never retain
        // stale credentials behind a profile which visually says "guest".
        password = if (identity.username.isBlank()) "" else password,
        domain = identity.domain,
    )
}

/** Host form suitable for an smb:// URL; raw SMBJ connections keep the unbracketed host. */
internal fun smbUrlHost(host: String): String {
    val escaped = host.replace("%", "%25")
    return if (':' in escaped && !escaped.startsWith('[')) "[$escaped]" else escaped
}

/** Relative path inside one already-selected SMB share. Preserve server-visible spaces exactly. */
internal fun normalizeSmbRelativePath(raw: String): String {
    val segments = raw.replace('/', '\\')
        .split('\\')
        .filter(String::isNotEmpty)
        .filterNot { it == "." }
    require(segments.none { it == ".." }) { "Выход за корень SMB запрещён" }
    return segments.joinToString("\\")
}

internal fun smbIOException(
    error: Throwable,
    host: String? = null,
    share: String? = null,
    guestMode: Boolean? = null,
): IOException {
    if (error is IOException && error.message?.startsWith("SMB:") == true) return error
    return IOException(smbUserMessage(error, host, share, guestMode), error)
}

/**
 * Turns SMBJ/jCIFS transport/status strings into messages suitable for the UI and strips
 * implementation details such as jCIFS' `0.0.0.0<00>/10.0.0.2` address representation.
 */
internal fun smbUserMessage(
    error: Throwable,
    host: String? = null,
    share: String? = null,
    guestMode: Boolean? = null,
): String {
    val causes = generateSequence(error) { it.cause }.take(10).toList()
    val messages = causes.mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
    messages.firstOrNull { it.startsWith("SMB:") }?.let { return it }

    val details = messages.joinToString(" | ")
    val endpoint = buildString {
        host?.trim()?.takeIf(String::isNotBlank)?.let(::append)
        share?.trim()?.trim('/', '\\')?.takeIf(String::isNotBlank)?.let { value ->
            if (isNotEmpty()) append('\\')
            append(value)
        }
    }

    return when {
        causes.any { it is UnknownHostException } || details.contains("name or service not known", true) ->
            if (endpoint.isNotBlank()) "SMB: устройство $endpoint не найдено" else "SMB: устройство не найдено"
        details.contains("STATUS_LOGON_FAILURE", true) || details.contains("logon failure", true) ->
            if (guestMode == true) {
                "SMB: гостевой вход запрещён сервером. Укажите пользователя и пароль SMB"
            } else {
                "SMB: неверный логин или пароль"
            }
        details.contains("STATUS_ACCOUNT_DISABLED", true) ->
            "SMB: учётная запись отключена на сервере"
        details.contains("STATUS_ACCOUNT_LOCKED_OUT", true) ->
            "SMB: учётная запись заблокирована на сервере"
        details.contains("STATUS_ACCOUNT_RESTRICTION", true) || details.contains("STATUS_LOGON_TYPE_NOT_GRANTED", true) ->
            "SMB: сервер ограничил вход для этой учётной записи"
        details.contains("STATUS_ACCOUNT_EXPIRED", true) ->
            "SMB: срок действия учётной записи истёк"
        details.contains("STATUS_WRONG_PASSWORD", true) ->
            "SMB: неверный пароль"
        details.contains("STATUS_PASSWORD_EXPIRED", true) ->
            "SMB: пароль учётной записи истёк"
        details.contains("STATUS_PASSWORD_MUST_CHANGE", true) ->
            "SMB: сервер требует сменить пароль учётной записи"
        details.contains("STATUS_NO_LOGON_SERVERS", true) ->
            "SMB: сервер не может связаться с контроллером домена"
        details.contains("STATUS_BAD_NETWORK_NAME", true) ||
            details.contains("STATUS_BAD_NETWORK_PATH", true) ||
            details.contains("network name cannot be found", true) ->
            "SMB: общая папка или сетевой путь не найдены"
        details.contains("STATUS_ACCESS_DENIED", true) || details.contains("access is denied", true) ->
            "SMB: доступ запрещён. Проверьте права на общую папку и учётные данные SMB"
        details.contains("STATUS_OBJECT_NAME_COLLISION", true) || details.contains("already exists", true) ->
            "SMB: файл или папка с таким именем уже существует"
        details.contains("STATUS_OBJECT_NAME_NOT_FOUND", true) ||
            details.contains("STATUS_OBJECT_PATH_NOT_FOUND", true) ||
            details.contains("STATUS_NO_SUCH_FILE", true) ->
            "SMB: файл или папка больше не существует"
        details.contains("STATUS_SHARING_VIOLATION", true) ->
            "SMB: файл занят другим приложением или устройством"
        details.contains("STATUS_DISK_FULL", true) || details.contains("disk full", true) ->
            "SMB: на устройстве недостаточно свободного места"
        details.contains("STATUS_MEDIA_WRITE_PROTECTED", true) || details.contains("write protected", true) ->
            "SMB: общая папка доступна только для чтения"
        details.contains("STATUS_NETWORK_NAME_DELETED", true) ||
            details.contains("STATUS_CONNECTION_DISCONNECTED", true) ||
            details.contains("STATUS_USER_SESSION_DELETED", true) ||
            details.contains("connection closed", true) ->
            "SMB: соединение с устройством потеряно"
        causes.any { it is SocketTimeoutException } || details.contains("timed out", true) || details.contains("timeout", true) ->
            "SMB: устройство не ответило вовремя"
        causes.any { it is ConnectException } || details.contains("connection refused", true) || details.contains("ECONNREFUSED", true) ->
            "SMB: устройство отклонило подключение к порту 445"
        causes.any { it is NoRouteToHostException } ||
            details.contains("STATUS_NETWORK_UNREACHABLE", true) ||
            details.contains("STATUS_HOST_UNREACHABLE", true) ->
            "SMB: устройство недоступно из текущей сети"
        details.contains("connection reset", true) || details.contains("STATUS_CONNECTION_RESET", true) ->
            "SMB: соединение было сброшено устройством"
        details.contains("STATUS_IO_TIMEOUT", true) ->
            "SMB: устройство не ответило вовремя"
        details.contains("transport closed in negotiate", true) ||
            details.contains("negotiate", true) && details.contains("closed", true) ->
            "SMB: сервер не завершил согласование SMB2/SMB3"
        (details.contains("SMB1", true) && details.contains("not supported", true)) ||
            (details.contains("dialect", true) && details.contains("not supported", true)) ->
            "SMB: сервер не поддерживает SMB2/SMB3; SMB1 в Aura отключён"
        details.contains("Failed to connect", true) ->
            if (endpoint.isNotBlank()) {
                "SMB: не удалось подключиться к $endpoint. Проверьте SMB2/SMB3, порт 445 и настройки общего доступа"
            } else {
                "SMB: не удалось подключиться к устройству. Проверьте SMB2/SMB3 и порт 445"
            }
        else -> {
            val first = messages.firstOrNull()?.let(::sanitizeSmbDetail)
            when {
                first.isNullOrBlank() -> "SMB: операция не выполнена"
                // Keep our own/local validation messages intact instead of turning every local
                // file-picker failure into a misleading network error.
                first.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' } -> first
                else -> "SMB: ${first.take(220)}"
            }
        }
    }
}

private fun sanitizeSmbDetail(raw: String): String = raw
    .replace(Regex("(?:0\\.0\\.0\\.0|[A-Za-z0-9_.-]+)<[0-9A-Fa-f]{2}>/"), "")
    .replace(Regex("\\s+"), " ")
    .trim()

internal fun isLikelySmbTransportError(error: Throwable): Boolean {
    val causes = generateSequence(error) { it.cause }.take(10).toList()
    if (causes.any { it is SocketTimeoutException || it is ConnectException || it is NoRouteToHostException || it is UnknownHostException }) {
        return true
    }
    val details = causes.mapNotNull { it.message }.joinToString(" | ")
    return details.contains("timed out", true) ||
        details.contains("timeout", true) ||
        details.contains("connection reset", true) ||
        details.contains("connection closed", true) ||
        details.contains("connection disconnected", true) ||
        details.contains("STATUS_NETWORK_NAME_DELETED", true) ||
        details.contains("STATUS_CONNECTION_DISCONNECTED", true) ||
        details.contains("STATUS_CONNECTION_RESET", true) ||
        details.contains("STATUS_CONNECTION_ABORTED", true) ||
        details.contains("STATUS_NETWORK_UNREACHABLE", true) ||
        details.contains("STATUS_HOST_UNREACHABLE", true) ||
        details.contains("STATUS_IO_TIMEOUT", true) ||
        details.contains("STATUS_USER_SESSION_DELETED", true) ||
        details.contains("transport closed", true) ||
        details.contains("Failed to connect", true) ||
        details.contains("broken pipe", true) ||
        details.contains("socket closed", true) ||
        causes.any { it is SocketException && it.message?.contains("network", true) == true }
}
