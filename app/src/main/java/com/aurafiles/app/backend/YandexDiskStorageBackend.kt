package com.aurafiles.app.backend

import com.aurafiles.app.cloud.CloudProfile
import com.aurafiles.app.cloud.CloudProvider
import com.aurafiles.app.cloud.yandex.UrlConnectionYandexBinaryTransfer
import com.aurafiles.app.cloud.yandex.YandexAccessTokenProvider
import com.aurafiles.app.cloud.yandex.YandexApiException
import com.aurafiles.app.cloud.yandex.YandexBinaryTransfer
import com.aurafiles.app.cloud.yandex.YandexDiskApi
import com.aurafiles.app.cloud.yandex.YandexDiskApiClient
import com.aurafiles.app.cloud.yandex.YandexDiskResource
import com.aurafiles.app.cloud.yandex.YandexFailureKind
import com.aurafiles.app.cloud.yandex.YandexOperationLink
import com.aurafiles.app.cloud.yandex.YandexOperationState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

class YandexDiskStorageBackend(
    private val profile: CloudProfile,
    private val tokenProvider: YandexAccessTokenProvider,
    private val apiFactory: (String) -> YandexDiskApi = { token -> YandexDiskApiClient(token) },
    private val binaryTransfer: YandexBinaryTransfer = UrlConnectionYandexBinaryTransfer(),
    override val descriptor: StorageBackendDescriptor = StorageBackendDescriptor(
        id = "yandex:${profile.id}",
        title = profile.name.ifBlank { "Яндекс.Диск" },
        kind = StorageBackendKind.YANDEX_DISK,
    ),
) : StorageBackend {
    init {
        require(profile.provider == CloudProvider.YANDEX_DISK) { "Cloud-профиль относится не к Яндекс.Диску" }
    }

    override suspend fun list(path: String): List<StorageItem> {
        val normalized = normalize(path)
        return authorizedApi { list(normalized) }.map(::toStorageItem)
    }

    override suspend fun stat(path: String): StorageItem? {
        val normalized = normalize(path)
        if (normalized == "/") {
            return StorageItem(
                backendId = descriptor.id,
                path = "/",
                name = descriptor.title,
                isDirectory = true,
            )
        }
        return authorizedApi { resource(normalized) }?.let(::toStorageItem)
    }

    override suspend fun openRead(path: String): StorageReadHandle {
        val normalized = normalize(path)
        val item = stat(normalized) ?: throw IOException("Файл на Яндекс.Диске не найден")
        if (item.isDirectory) throw IOException("Папку нельзя открыть как файл")
        val link = authorizedApi { downloadLink(normalized) }
        val handle = binaryTransfer.openRead(link)
        return object : StorageReadHandle {
            override val input = handle.input
            override fun close() = handle.close()
        }
    }

    override suspend fun openWrite(path: String, replace: Boolean): StorageWriteHandle =
        openWrite(path, replace, expectedSize = null)

    override suspend fun openWrite(path: String, replace: Boolean, expectedSize: Long?): StorageWriteHandle {
        val normalized = normalize(path)
        require(normalized != "/") { "Нельзя записать файл в корень без имени" }
        if (!replace && stat(normalized) != null) throw IOException("${BackendPath.name(normalized)} уже существует")
        val link = authorizedApi { uploadLink(normalized, overwrite = replace) }
        val handle = binaryTransfer.openWrite(link, expectedSize)
        return object : StorageWriteHandle {
            private var finished = false
            override val output = handle.output

            override fun commit() {
                if (finished) return
                try {
                    handle.commit()
                    finished = true
                } catch (error: Throwable) {
                    runCatching { handle.abort() }
                    finished = true
                    throw error
                }
            }

            override fun abort() {
                if (finished) return
                handle.abort()
                finished = true
            }

            override fun close() {
                if (!finished) abort()
            }
        }
    }

    override suspend fun mkdir(path: String): StorageItem {
        val normalized = normalize(path)
        require(normalized != "/") { "Корень Яндекс.Диска уже существует" }
        stat(normalized)?.let { existing ->
            if (existing.isDirectory) return existing
            throw IOException("${existing.name} уже существует и не является папкой")
        }
        try {
            authorizedApi { mkdir(normalized) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val probe = probeStat(normalized)
            if (probe.known && probe.item?.isDirectory == true) return requireNotNull(probe.item)
            throw ambiguousMutation(
                "Яндекс не подтвердил создание папки ${BackendPath.name(normalized)}. " +
                    "Aura не повторяет команду автоматически, чтобы не создать дубль.",
                error,
            )
        }
        return stat(normalized) ?: StorageItem(
            backendId = descriptor.id,
            path = normalized,
            name = BackendPath.name(normalized),
            isDirectory = true,
        )
    }

    override suspend fun rename(path: String, newName: String): StorageItem {
        val normalized = normalize(path)
        require(normalized != "/") { "Нельзя переименовать корень Яндекс.Диска" }
        val destination = child(parent(normalized), newName)
        require(stat(destination) == null) { "$newName уже существует" }
        try {
            val operation = authorizedApi { move(normalized, destination, overwrite = false) }
            waitForOperation(operation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            reconcileMoveAfterFailure(normalized, destination, "переименование", error)?.let { return it }
            throw error
        }
        return stat(destination) ?: throw IOException("Яндекс не подтвердил переименование")
    }

    override suspend fun move(path: String, destinationDirectory: String): StorageItem {
        val normalized = normalize(path)
        require(normalized != "/") { "Нельзя перемещать корень Яндекс.Диска" }
        val destinationDir = normalize(destinationDirectory)
        val target = child(destinationDir, BackendPath.name(normalized))
        require(stat(target) == null) { "${BackendPath.name(normalized)} уже существует в папке назначения" }
        try {
            val operation = authorizedApi { move(normalized, target, overwrite = false) }
            waitForOperation(operation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            reconcileMoveAfterFailure(normalized, target, "перемещение", error)?.let { return it }
            throw error
        }
        return stat(target) ?: throw IOException("Яндекс не подтвердил перемещение")
    }

    override suspend fun delete(path: String, recursive: Boolean) {
        val normalized = normalize(path)
        require(normalized != "/") { "Нельзя удалить корень Яндекс.Диска" }
        val item = stat(normalized) ?: return
        if (item.isDirectory && !recursive && list(normalized).isNotEmpty()) {
            throw IOException("Папка не пуста")
        }
        try {
            val operation = authorizedApi { delete(normalized, permanently = false) }
            waitForOperation(operation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val probe = probeStat(normalized)
            if (probe.known && probe.item == null) return
            throw ambiguousMutation(
                "Яндекс не подтвердил удаление ${item.name}. " +
                    "Текущее состояние нельзя считать окончательным; Aura не повторяет удаление автоматически.",
                error,
            )
        }
    }

    override suspend fun ping(): Boolean = runCatching {
        authorizedApi { diskInfo() }
        true
    }.getOrDefault(false)

    override fun close() = Unit

    private fun toStorageItem(resource: YandexDiskResource): StorageItem = StorageItem(
        backendId = descriptor.id,
        path = normalize(resource.path),
        name = resource.name.ifBlank { BackendPath.name(resource.path) },
        isDirectory = resource.isDirectory,
        size = if (resource.isDirectory) 0L else resource.size.coerceAtLeast(0L),
        modifiedAt = resource.modifiedAtEpochMs.coerceAtLeast(0L),
        mimeType = if (resource.isDirectory) null else resource.mimeType ?: BackendPath.guessMime(resource.name),
    )

    /**
     * Every REST call gets at most one authoritative refresh/retry after HTTP 401.
     * Other failures are returned unchanged so rate limiting, access denial and conflicts are not hidden.
     */
    private fun <T> authorizedApi(block: YandexDiskApi.() -> T): T {
        val firstToken = tokenProvider.accessToken(false)
        return try {
            apiFactory(firstToken).block()
        } catch (error: YandexApiException) {
            if (error.kind != YandexFailureKind.UNAUTHORIZED) throw error
            val refreshedToken = tokenProvider.accessToken(true)
            apiFactory(refreshedToken).block()
        }
    }

    private suspend fun waitForOperation(operation: YandexOperationLink) {
        val href = operation.href ?: return
        val deadlineNanos = System.nanoTime() + OPERATION_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadlineNanos) {
            currentCoroutineContext().ensureActive()
            when (authorizedApi { operationState(href) }) {
                YandexOperationState.SUCCESS -> return
                YandexOperationState.FAILED -> throw IOException("Операция на Яндекс.Диске завершилась ошибкой")
                YandexOperationState.IN_PROGRESS -> Unit
            }
            currentCoroutineContext().ensureActive()
            val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
            if (remainingMs <= 0L) break
            delay(minOf(OPERATION_POLL_INTERVAL_MS, remainingMs))
        }
        throw IOException("Операция на Яндекс.Диске не завершилась за ${OPERATION_TIMEOUT_MS / 1_000L} секунд")
    }

    private suspend fun reconcileMoveAfterFailure(
        sourcePath: String,
        targetPath: String,
        operationLabel: String,
        cause: Throwable,
    ): StorageItem? {
        val source = probeStat(sourcePath)
        val target = probeStat(targetPath)
        if (source.known && source.item == null && target.known && target.item != null) {
            return target.item
        }
        throw ambiguousMutation(
            "Яндекс не подтвердил $operationLabel. Текущее состояние source/target не доказывает, " +
                "что сервер не завершит асинхронную операцию позже; Aura не повторяет её автоматически.",
            cause,
        )
    }

    private suspend fun probeStat(path: String): StatProbe = try {
        StatProbe(known = true, item = stat(path))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        StatProbe(known = false, item = null)
    }

    private fun ambiguousMutation(message: String, cause: Throwable): IOException = IOException(message, cause)

    private data class StatProbe(val known: Boolean, val item: StorageItem?)

    companion object {
        private const val OPERATION_POLL_INTERVAL_MS = 500L
        private const val OPERATION_TIMEOUT_MS = 180_000L
    }
}
