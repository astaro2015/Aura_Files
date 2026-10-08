package com.aurafiles.app.ui

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.aurafiles.app.backend.BackendFactory
import com.aurafiles.app.backend.BackendPath
import com.aurafiles.app.backend.StorageBackend
import com.aurafiles.app.backend.StorageBackendDescriptor
import com.aurafiles.app.backend.StorageBackendKind
import com.aurafiles.app.backend.StorageBackendRegistry
import com.aurafiles.app.backend.StorageItem
import com.aurafiles.app.cloud.CloudProfile
import com.aurafiles.app.cloud.CloudProfileRepository
import com.aurafiles.app.cloud.CloudProvider
import com.aurafiles.app.cloud.google.GoogleAuthorizationStep
import com.aurafiles.app.cloud.google.GoogleDriveApiClient
import com.aurafiles.app.cloud.google.GoogleDriveApiException
import com.aurafiles.app.cloud.google.GoogleDriveFailureKind
import com.aurafiles.app.cloud.google.GoogleIdentityAuthorization
import com.aurafiles.app.cloud.google.userMessageForGoogleDriveFailure
import com.aurafiles.app.cloud.yandex.YandexAuthService
import com.aurafiles.app.cloud.yandex.YandexDeviceCode
import com.aurafiles.app.cloud.yandex.YandexDeviceTokenPoll
import com.aurafiles.app.cloud.yandex.YandexOAuthSettingsStore
import com.aurafiles.app.cloud.yandex.userMessageForYandexFailure
import com.aurafiles.app.cloud.yandex.YandexApiException
import com.aurafiles.app.data.FileRepository
import com.aurafiles.app.model.SftpHostKeyException
import com.aurafiles.app.model.SftpProfile
import com.aurafiles.app.network.NetworkProfileRepository
import com.aurafiles.app.network.NetworkProtocol
import com.aurafiles.app.sync.DirectoryComparator
import com.aurafiles.app.sync.DirectoryCompareResult
import com.aurafiles.app.sync.DirectorySyncExecutor
import com.aurafiles.app.sync.DirectorySyncPlan
import com.aurafiles.app.sync.DirectorySyncPlanner
import com.aurafiles.app.sync.SyncDirection
import com.aurafiles.app.transfer.TransferConflict
import com.aurafiles.app.transfer.TransferConflictDecision
import com.aurafiles.app.transfer.TransferConflictPolicy
import com.aurafiles.app.transfer.TransferController
import com.aurafiles.app.transfer.TransferDestination
import com.aurafiles.app.transfer.TransferEngine
import com.aurafiles.app.transfer.TransferProgress
import com.aurafiles.app.transfer.TransferRequest
import com.aurafiles.app.transfer.TransferSource
import com.aurafiles.app.transfer.TransferType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BackendPaneState(
    val backendId: String? = null,
    val path: String = "/",
    val items: List<StorageItem> = emptyList(),
    val loading: Boolean = false,
    val back: List<String> = emptyList(),
    val forward: List<String> = emptyList(),
    val recent: List<String> = emptyList(),
    val selected: Set<String> = emptySet(),
)

internal data class PendingHostKey(
    val profileId: String,
    val host: String,
    val fingerprint: String,
    val previous: String?,
    val paneLeft: Boolean,
)

internal data class BackendOpenFileRequest(
    val absolutePath: String,
    val displayName: String,
    val mimeType: String?,
)

internal data class BackendClipboard(
    val sourceBackendId: String,
    val items: List<StorageItem>,
    val move: Boolean,
)

internal sealed interface YandexAuthUiState {
    data object Idle : YandexAuthUiState
    data class Configuring(val clientId: String) : YandexAuthUiState
    data class Requesting(val clientId: String) : YandexAuthUiState
    data class AwaitingApproval(
        val clientId: String,
        val userCode: String,
        val verificationUrl: String,
        val expiresAtEpochMs: Long,
        val status: String = "Жду подтверждения входа в браузере…",
        val autoOpenVerification: Boolean = false,
    ) : YandexAuthUiState
    data class Error(val clientId: String, val message: String) : YandexAuthUiState
}

internal sealed interface GoogleAuthUiState {
    data object Idle : GoogleAuthUiState
    data object Requesting : GoogleAuthUiState
    data class NeedsResolution(val pendingIntent: PendingIntent) : GoogleAuthUiState
    data object AwaitingUser : GoogleAuthUiState
    data object Finalizing : GoogleAuthUiState
    data class Error(val message: String) : GoogleAuthUiState
}

internal data class WorkspaceState(
    val backends: List<StorageBackendDescriptor> = emptyList(),
    val cloudProfiles: List<CloudProfile> = emptyList(),
    val yandexAuth: YandexAuthUiState = YandexAuthUiState.Idle,
    val googleAuth: GoogleAuthUiState = GoogleAuthUiState.Idle,
    val left: BackendPaneState = BackendPaneState(),
    val right: BackendPaneState = BackendPaneState(),
    val transfer: TransferProgress? = null,
    val conflict: TransferConflict? = null,
    val comparison: DirectoryCompareResult? = null,
    val syncPlan: DirectorySyncPlan? = null,
    val pendingHostKey: PendingHostKey? = null,
    val busyLabel: String? = null,
    val message: String? = null,
    val reopenBackendId: String? = null,
    val openFileRequest: BackendOpenFileRequest? = null,
    val clipboard: BackendClipboard? = null,
)

internal class BackendWorkspaceViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val fileRepository = FileRepository(application)
    private val profileRepository = NetworkProfileRepository(application)
    private val cloudProfileRepository = CloudProfileRepository(application)
    private val yandexSettings = YandexOAuthSettingsStore(application)
    private val yandexAuthService = YandexAuthService(cloudProfileRepository)
    private val googleIdentity = GoogleIdentityAuthorization(application)
    private val registry = StorageBackendRegistry()
    private val factory = BackendFactory(application, profileRepository, cloudProfileRepository, yandexSettings)
    private val transferEngine = TransferEngine(application, smbGateway = null, backendRegistry = registry)
    private val comparator = DirectoryComparator()
    private val syncExecutor = DirectorySyncExecutor()
    private val _state = MutableStateFlow(WorkspaceState())
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()
    private var operation: Job? = null
    private var controller: TransferController? = null
    private var leftRefreshJob: Job? = null
    private var rightRefreshJob: Job? = null
    private var leftRefreshGeneration = 0L
    private var rightRefreshGeneration = 0L
    private var yandexAuthJob: Job? = null
    private var googleAuthJob: Job? = null
    // Persist interactive Google authorization state so Activity/process recreation cannot discard a valid result.
    private var googleAuthorizationActive: Boolean = savedStateHandle[KEY_GOOGLE_AUTH_ACTIVE] ?: false

    init {
        rebuildBackends()
        viewModelScope.launch {
            transferEngine.progress.collect { progress -> _state.update { it.copy(transfer = progress) } }
        }
        viewModelScope.launch {
            transferEngine.conflict.collect { conflict -> _state.update { it.copy(conflict = conflict) } }
        }
        restorePendingYandexAuthorization()
        if (googleAuthorizationActive) {
            _state.update { it.copy(googleAuth = GoogleAuthUiState.AwaitingUser) }
        }
    }

    override fun onCleared() {
        operation?.cancel()
        leftRefreshJob?.cancel()
        rightRefreshJob?.cancel()
        yandexAuthJob?.cancel()
        googleAuthJob?.cancel()
        registry.close()
        super.onCleared()
    }

    fun rebuildBackends(preferredBackendId: String? = null) {
        invalidateRefresh(true)
        invalidateRefresh(false)
        val oldIds = registry.descriptors().map { it.id }
        oldIds.forEach(registry::remove)
        fileRepository.restoreRoot()?.let { root ->
            registry.register(factory.local(root.uri, root.name ?: "Локальная память"))
        }
        profileRepository.profiles().forEach { profile ->
            if (profile.protocol == NetworkProtocol.SMB && profile.smbShare.isBlank()) return@forEach
            runCatching { registry.register(factory.network(profile)) }
        }
        val cloudProfiles = cloudProfileRepository.profiles()
        val cloudRegistrationErrors = mutableListOf<String>()
        cloudProfiles
            .filter { profile ->
                when (profile.provider) {
                    CloudProvider.YANDEX_DISK -> cloudProfileRepository.hasStoredTokens(profile.id)
                    CloudProvider.GOOGLE_DRIVE -> profile.accountLabel.isNotBlank()
                }
            }
            .forEach { profile ->
                runCatching { registry.register(factory.cloud(profile)) }
                    .onFailure { error ->
                        cloudRegistrationErrors += "${profile.provider.displayName}: ${error.message ?: error::class.java.simpleName}"
                    }
            }
        val descriptors = registry.descriptors()
        val validBackendIds = descriptors.mapTo(hashSetOf(), StorageBackendDescriptor::id)
        _state.update { state ->
            val local = descriptors.firstOrNull { it.kind == StorageBackendKind.LOCAL }
            val first = local ?: descriptors.firstOrNull()
            val second = descriptors.firstOrNull { it.id != first?.id } ?: first
            val preferred = preferredBackendId?.let { id -> descriptors.firstOrNull { it.id == id } }
            val preferredPeer = local?.takeIf { it.id != preferred?.id }
                ?: descriptors.firstOrNull { it.id != preferred?.id }
            state.copy(
                backends = descriptors,
                cloudProfiles = cloudProfiles,
                left = if (preferred != null) {
                    BackendPaneState(backendId = preferred.id, path = preferred.rootPath)
                } else {
                    state.left.takeIf { pane -> pane.backendId in validBackendIds }
                        ?.copy(items = emptyList(), loading = false, selected = emptySet())
                        ?: BackendPaneState(backendId = first?.id, path = first?.rootPath ?: "/")
                },
                right = if (preferred != null && preferredPeer != null) {
                    BackendPaneState(backendId = preferredPeer.id, path = preferredPeer.rootPath)
                } else {
                    state.right.takeIf { pane -> pane.backendId in validBackendIds }
                        ?.copy(items = emptyList(), loading = false, selected = emptySet())
                        ?: BackendPaneState(backendId = second?.id, path = second?.rootPath ?: "/")
                },
                comparison = null,
                syncPlan = null,
                message = when {
                    preferredBackendId != null && preferred == null ->
                        "Файловый backend $preferredBackendId не зарегистрирован. ${cloudRegistrationErrors.joinToString("; ")}".trim()
                    cloudRegistrationErrors.isNotEmpty() && state.message.isNullOrBlank() ->
                        "Не удалось зарегистрировать облачное подключение: ${cloudRegistrationErrors.joinToString("; ")}"
                    else -> state.message
                },
            )
        }
        refresh(true)
        refresh(false)
    }

    fun showAddYandexDialog() {
        if (yandexAuthJob?.isActive == true) return
        val clientId = yandexSettings.configuredClientId()
        val clientSecret = yandexSettings.configuredClientSecret(clientId)
        if (clientId.isBlank() || clientSecret.isBlank()) {
            _state.update { it.copy(yandexAuth = YandexAuthUiState.Configuring(clientId)) }
        } else {
            startYandexAuthorization(clientId, clientSecret)
        }
    }

    fun dismissYandexDialog() {
        yandexAuthJob?.cancel()
        yandexAuthJob = null
        clearPendingYandexAuthorization()
        _state.update { it.copy(yandexAuth = YandexAuthUiState.Idle) }
    }

    fun startYandexAuthorization(clientId: String, clientSecret: String) {
        val normalizedClientId = clientId.trim()
        if (normalizedClientId.isBlank()) {
            _state.update { it.copy(yandexAuth = YandexAuthUiState.Error(clientId, "Укажите Yandex OAuth Client ID приложения.")) }
            return
        }
        // The password field intentionally stays visually empty after an error. Reuse the
        // encrypted Keystore copy when the user simply presses Retry/Login again.
        val normalizedClientSecret = clientSecret.trim().ifBlank {
            yandexSettings.configuredClientSecret(normalizedClientId)
        }
        if (normalizedClientSecret.isBlank()) {
            _state.update { it.copy(yandexAuth = YandexAuthUiState.Error(normalizedClientId, "Укажите Yandex OAuth Client Secret (пароль приложения).")) }
            return
        }
        yandexAuthJob?.cancel()
        clearPendingYandexAuthorization()
        yandexAuthJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                yandexSettings.rememberClientId(normalizedClientId)
                yandexSettings.rememberClientSecret(normalizedClientId, normalizedClientSecret)
                val config = yandexSettings.config(normalizedClientId, normalizedClientSecret)
                _state.update { it.copy(yandexAuth = YandexAuthUiState.Requesting(normalizedClientId)) }
                val code = yandexAuthService.startDeviceAuthorization(config)
                persistPendingYandexAuthorization(normalizedClientId, code)
                _state.update {
                    it.copy(
                        yandexAuth = YandexAuthUiState.AwaitingApproval(
                            clientId = normalizedClientId,
                            userCode = code.userCode,
                            verificationUrl = code.verificationUrl,
                            expiresAtEpochMs = code.expiresAtEpochMs,
                            autoOpenVerification = true,
                        )
                    )
                }
                pollYandexAuthorization(config, code)
            } catch (_: CancellationException) {
                // User closed the dialog.
            } catch (error: Throwable) {
                clearPendingYandexAuthorization()
                val message = if (error is YandexApiException) error.message ?: userMessageForYandexFailure(error.kind)
                    else error.message ?: "Не удалось войти через Яндекс"
                _state.update { it.copy(yandexAuth = YandexAuthUiState.Error(normalizedClientId, message)) }
            } finally {
                yandexAuthJob = null
            }
        }
    }

    private suspend fun pollYandexAuthorization(
        config: com.aurafiles.app.cloud.yandex.YandexOAuthConfig,
        code: YandexDeviceCode,
    ) {
        var intervalSeconds = code.intervalSeconds.coerceAtLeast(1)
        while (kotlin.coroutines.coroutineContext.isActive) {
            val remaining = code.expiresAtEpochMs - System.currentTimeMillis()
            if (remaining <= 0L) throw YandexApiException(com.aurafiles.app.cloud.yandex.YandexFailureKind.AUTH_EXPIRED)
            delay((intervalSeconds * 1000L).coerceAtMost(remaining))
            val pollResult = try {
                yandexAuthService.pollDeviceAuthorization(config, code)
            } catch (error: YandexApiException) {
                if (error.kind == com.aurafiles.app.cloud.yandex.YandexFailureKind.NETWORK ||
                    error.kind == com.aurafiles.app.cloud.yandex.YandexFailureKind.SERVER
                ) {
                    _state.update { state ->
                        val current = state.yandexAuth as? YandexAuthUiState.AwaitingApproval
                        if (current == null) state else state.copy(
                            yandexAuth = current.copy(
                                status = (error.message ?: userMessageForYandexFailure(error.kind)) +
                                    "\nПовторю автоматически…"
                            )
                        )
                    }
                    intervalSeconds = maxOf(intervalSeconds, 5)
                    continue
                }
                throw error
            }
            val (poll, account) = pollResult
            when (poll) {
                YandexDeviceTokenPoll.Pending -> _state.update { state ->
                    val current = state.yandexAuth as? YandexAuthUiState.AwaitingApproval
                    if (current == null) state else state.copy(yandexAuth = current.copy(status = "Жду подтверждения входа в браузере…"))
                }
                is YandexDeviceTokenPoll.SlowDown -> {
                    intervalSeconds += poll.extraDelaySeconds.coerceAtLeast(1)
                    _state.update { state ->
                        val current = state.yandexAuth as? YandexAuthUiState.AwaitingApproval
                        if (current == null) state else state.copy(yandexAuth = current.copy(status = "Яндекс попросил снизить частоту запросов. Жду…"))
                    }
                }
                is YandexDeviceTokenPoll.Granted -> {
                    val authenticated = requireNotNull(account)
                    clearPendingYandexAuthorization()
                    val backendId = "yandex:${authenticated.profileId}"
                    rebuildBackends(preferredBackendId = backendId)
                    ensurePreferredBackendSelected(backendId, "Яндекс.Диск")
                    _state.update {
                        it.copy(
                            yandexAuth = YandexAuthUiState.Idle,
                            message = "Яндекс.Диск подключён${authenticated.accountLabel.takeIf(String::isNotBlank)?.let { label -> ": $label" }.orEmpty()}",
                            reopenBackendId = backendId,
                        )
                    }
                    return
                }
            }
        }
    }

    fun markYandexVerificationOpened() {
        _state.update { state ->
            val current = state.yandexAuth as? YandexAuthUiState.AwaitingApproval
            if (current == null || !current.autoOpenVerification) state
            else state.copy(yandexAuth = current.copy(autoOpenVerification = false))
        }
    }

    private fun persistPendingYandexAuthorization(clientId: String, code: YandexDeviceCode) {
        savedStateHandle[KEY_YANDEX_CLIENT_ID] = clientId
        savedStateHandle[KEY_YANDEX_DEVICE_CODE] = code.deviceCode
        savedStateHandle[KEY_YANDEX_USER_CODE] = code.userCode
        savedStateHandle[KEY_YANDEX_VERIFICATION_URL] = code.verificationUrl
        savedStateHandle[KEY_YANDEX_INTERVAL_SECONDS] = code.intervalSeconds
        savedStateHandle[KEY_YANDEX_EXPIRES_AT] = code.expiresAtEpochMs
    }

    private fun clearPendingYandexAuthorization() {
        savedStateHandle.remove<String>(KEY_YANDEX_CLIENT_ID)
        savedStateHandle.remove<String>(KEY_YANDEX_DEVICE_CODE)
        savedStateHandle.remove<String>(KEY_YANDEX_USER_CODE)
        savedStateHandle.remove<String>(KEY_YANDEX_VERIFICATION_URL)
        savedStateHandle.remove<Int>(KEY_YANDEX_INTERVAL_SECONDS)
        savedStateHandle.remove<Long>(KEY_YANDEX_EXPIRES_AT)
    }

    private fun restorePendingYandexAuthorization() {
        val clientId = savedStateHandle.get<String>(KEY_YANDEX_CLIENT_ID)?.trim().orEmpty()
        val deviceCode = savedStateHandle.get<String>(KEY_YANDEX_DEVICE_CODE).orEmpty()
        val userCode = savedStateHandle.get<String>(KEY_YANDEX_USER_CODE).orEmpty()
        val verificationUrl = savedStateHandle.get<String>(KEY_YANDEX_VERIFICATION_URL).orEmpty()
        val intervalSeconds = savedStateHandle.get<Int>(KEY_YANDEX_INTERVAL_SECONDS) ?: return
        val expiresAt = savedStateHandle.get<Long>(KEY_YANDEX_EXPIRES_AT) ?: return
        if (clientId.isBlank() || deviceCode.isBlank() || userCode.isBlank() || verificationUrl.isBlank()) {
            clearPendingYandexAuthorization()
            return
        }
        val code = runCatching {
            YandexDeviceCode(deviceCode, userCode, verificationUrl, intervalSeconds, expiresAt)
        }.getOrElse {
            clearPendingYandexAuthorization()
            return
        }
        if (code.isExpired()) {
            clearPendingYandexAuthorization()
            return
        }
        val config = runCatching { yandexSettings.config(clientId) }.getOrElse {
            clearPendingYandexAuthorization()
            return
        }
        _state.update {
            it.copy(
                yandexAuth = YandexAuthUiState.AwaitingApproval(
                    clientId = clientId,
                    userCode = code.userCode,
                    verificationUrl = code.verificationUrl,
                    expiresAtEpochMs = code.expiresAtEpochMs,
                    status = "Продолжаю ожидать подтверждение Яндекса…",
                    autoOpenVerification = false,
                )
            )
        }
        yandexAuthJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                pollYandexAuthorization(config, code)
            } catch (_: CancellationException) {
                // SavedStateHandle retains the pending code across Activity/process recreation.
            } catch (error: Throwable) {
                clearPendingYandexAuthorization()
                val message = if (error is YandexApiException) error.message ?: userMessageForYandexFailure(error.kind)
                    else error.message ?: "Не удалось завершить вход через Яндекс"
                _state.update { it.copy(yandexAuth = YandexAuthUiState.Error(clientId, message)) }
            } finally {
                yandexAuthJob = null
            }
        }
    }

    fun showAddGoogleDialog() {
        if (googleAuthorizationActive || googleAuthJob?.isActive == true) return
        setGoogleAuthorizationActive(true)
        googleAuthJob = viewModelScope.launch {
            _state.update { it.copy(googleAuth = GoogleAuthUiState.Requesting) }
            try {
                when (val step = googleIdentity.authorize(selectAccount = true)) {
                    is GoogleAuthorizationStep.Granted -> finishGoogleAuthorization(step.accessToken)
                    is GoogleAuthorizationStep.NeedsResolution -> {
                        _state.update { it.copy(googleAuth = GoogleAuthUiState.NeedsResolution(step.pendingIntent)) }
                    }
                }
            } catch (_: CancellationException) {
                setGoogleAuthorizationActive(false)
                _state.update { it.copy(googleAuth = GoogleAuthUiState.Idle) }
            } catch (error: Throwable) {
                setGoogleAuthError(error)
            } finally {
                googleAuthJob = null
            }
        }
    }

    fun markGoogleResolutionLaunched() {
        _state.update { state ->
            if (state.googleAuth is GoogleAuthUiState.NeedsResolution) {
                state.copy(googleAuth = GoogleAuthUiState.AwaitingUser)
            } else state
        }
    }

    fun completeGoogleAuthorization(data: Intent?, resultCode: Int) {
        if (!googleAuthorizationActive) setGoogleAuthorizationActive(true)
        googleAuthJob?.cancel()
        googleAuthJob = viewModelScope.launch {
            _state.update { it.copy(googleAuth = GoogleAuthUiState.Finalizing) }
            try {
                val resultWasOk = resultCode == Activity.RESULT_OK
                // Prefer the AuthorizationResult returned by Google Play services when it exists.
                // Some vendor/Play-services combinations return RESULT_CANCELED or a null/empty
                // Intent even after the user granted the requested scope. Do not treat resultCode
                // as the source of truth: AuthorizationClient can immediately verify an already
                // granted scope without opening another window.
                val returnedResult = data?.let { intent ->
                    runCatching { googleIdentity.authorizationResultFromIntent(intent) }
                }
                val granted = returnedResult?.getOrNull()
                if (granted != null) {
                    finishGoogleAuthorization(granted.accessToken)
                } else {
                    when (val verified = googleIdentity.authorize(selectAccount = false)) {
                        is GoogleAuthorizationStep.Granted -> finishGoogleAuthorization(verified.accessToken)
                        is GoogleAuthorizationStep.NeedsResolution -> {
                            val returnedError = returnedResult?.exceptionOrNull()?.message?.takeIf(String::isNotBlank)
                            val message = when {
                                returnedError != null -> "Google не подтвердил результат системного окна: $returnedError"
                                resultWasOk -> "Google закрыл окно авторизации, но доступ к Drive не подтвердился."
                                else -> "Подключение Google Drive не подтверждено (resultCode=$resultCode). Если ты разрешил доступ, проверь настройку Android OAuth client и Drive API."
                            }
                            throw GoogleDriveApiException(
                                kind = GoogleDriveFailureKind.REAUTHORIZATION_REQUIRED,
                                message = message,
                            )
                        }
                    }
                }
            } catch (_: CancellationException) {
                setGoogleAuthorizationActive(false)
                _state.update { it.copy(googleAuth = GoogleAuthUiState.Idle) }
            } catch (error: Throwable) {
                setGoogleAuthError(error)
            } finally {
                googleAuthJob = null
            }
        }
    }

    fun cancelGoogleAuthorization() {
        setGoogleAuthorizationActive(false)
        googleAuthJob?.cancel()
        googleAuthJob = null
        _state.update { it.copy(googleAuth = GoogleAuthUiState.Idle) }
    }

    private suspend fun finishGoogleAuthorization(accessToken: String) {
        val about = withContext(Dispatchers.IO) { GoogleDriveApiClient(accessToken).about() }
        val email = about.user.emailAddress.trim()
        if (email.isBlank()) {
            runCatching { googleIdentity.clearToken(accessToken) }
            throw GoogleDriveApiException(
                kind = GoogleDriveFailureKind.BAD_RESPONSE,
                message = "Google Drive не вернул e-mail аккаунта. Подключение не сохранено.",
            )
        }
        val accountId = about.user.permissionId.trim()
        val existing = cloudProfileRepository.profiles().firstOrNull { profile ->
            profile.provider == CloudProvider.GOOGLE_DRIVE && (
                accountId.isNotBlank() && profile.accountId == accountId ||
                    profile.accountLabel.equals(email, ignoreCase = true)
                )
        }
        val now = System.currentTimeMillis()
        val saved = cloudProfileRepository.save(
            CloudProfile(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = existing?.name?.takeIf(String::isNotBlank) ?: "Google Drive — $email",
                provider = CloudProvider.GOOGLE_DRIVE,
                accountId = accountId.ifBlank { existing?.accountId.orEmpty() },
                accountLabel = email,
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                updatedAtEpochMs = now,
            )
        )
        com.aurafiles.app.cloud.google.GoogleAccessTokenMemoryCache.put(email, accessToken)
        val backendId = "google:${saved.id}"
        rebuildBackends(preferredBackendId = backendId)
        ensurePreferredBackendSelected(backendId, "Google Drive")
        setGoogleAuthorizationActive(false)
        _state.update {
            it.copy(
                googleAuth = GoogleAuthUiState.Idle,
                message = "Google Drive подключён: $email",
                reopenBackendId = backendId,
            )
        }
    }

    private fun setGoogleAuthError(error: Throwable) {
        setGoogleAuthorizationActive(false)
        val message = if (error is GoogleDriveApiException) {
            error.message ?: userMessageForGoogleDriveFailure(error.kind)
        } else error.message ?: "Не удалось подключить Google Drive"
        _state.update { it.copy(googleAuth = GoogleAuthUiState.Error(message)) }
    }

    private fun ensurePreferredBackendSelected(backendId: String, providerName: String) {
        val state = _state.value
        if (state.backends.none { it.id == backendId }) {
            throw IllegalStateException("$providerName авторизован, но backend не зарегистрирован. Закройте и снова откройте раздел «Сеть».")
        }
        if (state.left.backendId != backendId) {
            throw IllegalStateException("$providerName зарегистрирован, но облачная панель не была выбрана.")
        }
    }

    private fun setGoogleAuthorizationActive(active: Boolean) {
        googleAuthorizationActive = active
        if (active) savedStateHandle[KEY_GOOGLE_AUTH_ACTIVE] = true
        else savedStateHandle.remove<Boolean>(KEY_GOOGLE_AUTH_ACTIVE)
    }

    fun consumeReopenBackendRequest() {
        _state.update { state ->
            if (state.reopenBackendId == null) state else state.copy(reopenBackendId = null)
        }
    }

    fun deleteCloudProfile(profile: CloudProfile) {
        yandexAuthJob?.cancel()
        googleAuthJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            val revokeWarning = if (profile.provider == CloudProvider.GOOGLE_DRIVE && profile.accountLabel.isNotBlank()) {
                runCatching { googleIdentity.revoke(profile.accountLabel) }.exceptionOrNull()?.message
            } else null
            runCatching { cloudProfileRepository.delete(profile.id) }
                .onSuccess {
                    rebuildBackends()
                    val suffix = revokeWarning?.let { " (локально удалено; отзыв доступа Google не подтверждён: $it)" }.orEmpty()
                    _state.update { it.copy(message = "Облачное подключение «${profile.name}» удалено$suffix") }
                }
                .onFailure { error ->
                    _state.update { it.copy(message = error.message ?: "Не удалось удалить облачное подключение") }
                }
        }
    }

    fun selectBackend(left: Boolean, backendId: String) {
        val descriptor = _state.value.backends.firstOrNull { it.id == backendId } ?: return
        updatePane(left) { BackendPaneState(backendId = backendId, path = descriptor.rootPath) }
        refresh(left)
    }

    fun openBackendFromNetwork(backendId: String) {
        val descriptor = _state.value.backends.firstOrNull { it.id == backendId }
        if (descriptor == null) {
            _state.update { it.copy(message = "Подключение $backendId сохранено, но файловый backend не зарегистрирован. Переподключите источник; если ошибка повторится, пришлите build.log или скрин сообщения Aura.") }
            return
        }
        val local = _state.value.backends.firstOrNull { it.kind == StorageBackendKind.LOCAL && it.id != backendId }
        updatePane(true) { BackendPaneState(backendId = backendId, path = descriptor.rootPath) }
        if (local != null) {
            updatePane(false) { BackendPaneState(backendId = local.id, path = local.rootPath) }
        }
        refresh(true)
        if (local != null) refresh(false)
    }

    fun open(left: Boolean, item: StorageItem) {
        if (!item.isDirectory) return
        updatePane(left) { pane ->
            pane.copy(
                path = item.path,
                back = (pane.back + pane.path).takeLast(HISTORY_LIMIT),
                forward = emptyList(),
                recent = (listOf(item.path) + pane.recent.filterNot { it == item.path }).take(RECENT_LIMIT),
                selected = emptySet(),
            )
        }
        refresh(left)
    }

    fun openOrPreview(left: Boolean, item: StorageItem) {
        if (item.isDirectory) {
            open(left, item)
            return
        }
        if (operation?.isActive == true) return
        val backendId = pane(left).backendId ?: return
        val backend = registry.get(backendId) ?: run {
            _state.update { it.copy(message = "Источник недоступен") }
            return
        }
        operation = viewModelScope.launch {
            _state.update { it.copy(busyLabel = "Открываю ${item.name}") }
            try {
                val target = withContext(Dispatchers.IO) {
                    val directory = File(getApplication<Application>().cacheDir, "backend-open").apply { mkdirs() }
                    directory.listFiles()?.filter { file ->
                        file.isFile && file.lastModified() > 0L &&
                            System.currentTimeMillis() - file.lastModified() > OPEN_CACHE_MAX_AGE_MS
                    }?.forEach { file -> runCatching { file.delete() } }
                    requireCacheSpace(directory, item.size, "открытия ${item.name}")
                    val safeName = item.name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "file" }
                    val file = File(directory, "${UUID.randomUUID()}-$safeName")
                    val partial = File(directory, ".${file.name}.part")
                    try {
                        backend.openRead(item.path).use { handle ->
                            partial.outputStream().buffered(DEFAULT_COPY_BUFFER).use { output ->
                                val buffer = ByteArray(DEFAULT_COPY_BUFFER)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = handle.input.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    require(directory.usableSpace > OPEN_CACHE_SPACE_RESERVE_BYTES) {
                                        "Недостаточно свободного места для открытия ${item.name}"
                                    }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                        require(partial.renameTo(file)) { "Не удалось завершить подготовку ${item.name}" }
                        file
                    } catch (error: Throwable) {
                        partial.delete()
                        file.delete()
                        throw error
                    }
                }
                _state.update {
                    it.copy(
                        openFileRequest = BackendOpenFileRequest(target.absolutePath, item.name, item.mimeType),
                        message = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update { it.copy(message = error.message ?: "Не удалось открыть ${item.name}") }
            } finally {
                operation = null
                _state.update { it.copy(busyLabel = null) }
            }
        }
    }

    fun consumeOpenFileRequest() {
        _state.update { it.copy(openFileRequest = null) }
    }

    private fun requireCacheSpace(directory: File, expectedBytes: Long, purpose: String) {
        val usable = directory.usableSpace
        require(usable > OPEN_CACHE_SPACE_RESERVE_BYTES) { "Недостаточно свободного места для $purpose" }
        if (expectedBytes > 0L) {
            require(expectedBytes <= usable - OPEN_CACHE_SPACE_RESERVE_BYTES) {
                "Недостаточно свободного места для $purpose"
            }
        }
    }

    fun back(left: Boolean) {
        val pane = pane(left)
        val target = pane.back.lastOrNull() ?: return
        updatePane(left) {
            it.copy(path = target, back = it.back.dropLast(1), forward = (listOf(it.path) + it.forward).take(HISTORY_LIMIT), selected = emptySet())
        }
        refresh(left)
    }

    fun forward(left: Boolean) {
        val pane = pane(left)
        val target = pane.forward.firstOrNull() ?: return
        updatePane(left) {
            it.copy(path = target, back = (it.back + it.path).takeLast(HISTORY_LIMIT), forward = it.forward.drop(1), selected = emptySet())
        }
        refresh(left)
    }

    fun goRecent(left: Boolean, path: String) {
        val pane = pane(left)
        if (pane.path == path) return
        updatePane(left) { it.copy(path = path, back = (it.back + it.path).takeLast(HISTORY_LIMIT), forward = emptyList(), selected = emptySet()) }
        refresh(left)
    }

    fun toggleSelection(left: Boolean, item: StorageItem) {
        updatePane(left) { pane ->
            if (pane.items.none { listed -> listed.path == item.path }) pane
            else pane.copy(selected = pane.selected.toMutableSet().apply { if (!add(item.path)) remove(item.path) })
        }
    }

    fun clearSelection(left: Boolean) = updatePane(left) { it.copy(selected = emptySet()) }

    fun setClipboard(left: Boolean, move: Boolean) {
        val pane = pane(left)
        val backendId = pane.backendId ?: return
        val selected = pane.items.filter { it.path in pane.selected }
        setClipboardItems(left, selected, move)
    }

    fun setClipboardItem(left: Boolean, item: StorageItem, move: Boolean) {
        setClipboardItems(left, listOf(item), move)
    }

    private fun setClipboardItems(left: Boolean, selected: List<StorageItem>, move: Boolean) {
        val pane = pane(left)
        val backendId = pane.backendId ?: return
        if (selected.isEmpty()) {
            _state.update { it.copy(message = "Выберите файлы или папки") }
            return
        }
        _state.update {
            it.copy(
                clipboard = BackendClipboard(
                    sourceBackendId = backendId,
                    items = selected,
                    move = move,
                )
            )
        }
        clearSelection(left)
    }

    fun clearClipboard() {
        _state.update { it.copy(clipboard = null) }
    }

    fun pasteClipboard(left: Boolean) {
        if (operation?.isActive == true) return
        val clipboard = _state.value.clipboard ?: return
        val destinationPane = pane(left)
        val destinationBackend = destinationPane.backendId ?: return
        val request = TransferRequest(
            type = if (clipboard.move) TransferType.MOVE else TransferType.COPY,
            sources = clipboard.items.map { item ->
                TransferSource.Backend(
                    backendId = clipboard.sourceBackendId,
                    path = item.path,
                    name = item.name,
                    size = item.size,
                    modifiedAt = item.modifiedAt,
                    isDirectory = item.isDirectory,
                    mimeType = item.mimeType,
                )
            },
            destination = TransferDestination.Backend(destinationBackend, destinationPane.path),
            conflictPolicy = TransferConflictPolicy.ASK,
            preserveModifiedTime = true,
        )
        controller = TransferController()
        val activeController = requireNotNull(controller)
        operation = viewModelScope.launch {
            _state.update {
                it.copy(busyLabel = if (clipboard.move) "Перемещение" else "Копирование")
            }
            try {
                val result = withContext(Dispatchers.IO) { transferEngine.execute(request, activeController) }
                _state.update {
                    it.copy(
                        clipboard = if (clipboard.move) null else it.clipboard,
                        message = (if (clipboard.move) "Перемещение завершено" else "Копирование завершено") + result.warningSuffix(),
                    )
                }
            } catch (_: CancellationException) {
                _state.update { it.copy(message = "Передача остановлена") }
            } catch (error: Throwable) {
                _state.update { it.copy(message = error.message ?: "Ошибка передачи") }
            } finally {
                controller = null
                operation = null
                _state.update { it.copy(busyLabel = null) }
                refresh(left, preserveMessage = true)
            }
        }
    }

    fun createFolder(left: Boolean, requestedName: String) {
        val name = requestedName.trim()
        if (name.isBlank()) {
            _state.update { it.copy(message = "Введите название папки") }
            return
        }
        mutatePane(left, "Создание папки") { backend, pane ->
            backend.mkdir(BackendPath.child(pane.path, name))
            "Папка «$name» создана"
        }
    }

    fun renameSelected(left: Boolean, requestedName: String) {
        val pane = pane(left)
        val selected = pane.items.filter { it.path in pane.selected }
        if (selected.size != 1) {
            _state.update { it.copy(message = "Для переименования выберите один объект") }
            return
        }
        val name = requestedName.trim()
        if (name.isBlank()) {
            _state.update { it.copy(message = "Введите новое имя") }
            return
        }
        renameItem(left, selected.single(), name)
    }

    fun renameItem(left: Boolean, item: StorageItem, requestedName: String) {
        val name = requestedName.trim()
        if (name.isBlank()) {
            _state.update { it.copy(message = "Введите новое имя") }
            return
        }
        mutatePane(left, "Переименование") { backend, _ ->
            backend.rename(item.path, name)
            "${item.name} переименован в $name"
        }
    }

    fun deleteSelected(left: Boolean) {
        val pane = pane(left)
        val selected = pane.items.filter { it.path in pane.selected }
        deleteItems(left, selected)
    }

    fun deleteItems(left: Boolean, selected: List<StorageItem>) {
        if (selected.isEmpty()) {
            _state.update { it.copy(message = "Выберите объекты для удаления") }
            return
        }
        mutatePane(left, "Удаление объектов") { backend, _ ->
            // Trash semantics are owned by each backend: Yandex/Google move to provider trash,
            // while protocols without trash support may delete permanently. UI explains this
            // before calling here. Recursive deletion is only used after that confirmation.
            selected.forEach { backend.delete(it.path, recursive = it.isDirectory) }
            "Удалено объектов: ${selected.size}"
        }
    }

    fun refresh(left: Boolean, preserveMessage: Boolean = false) {
        val snapshot = pane(left)
        val backendId = snapshot.backendId
        val backend = backendId?.let(registry::get)
        if (backendId == null || backend == null) {
            invalidateRefresh(left)
            updatePane(left) { current ->
                if (current.backendId == backendId) {
                    current.copy(items = emptyList(), loading = false, selected = emptySet())
                } else current
            }
            return
        }

        val generation = beginRefresh(left)
        val request = PaneRefreshRequest(backendId, snapshot.path, generation)
        updatePane(left) { current ->
            if (request.isCurrentFor(current, activeRefreshGeneration(left))) current.copy(loading = true)
            else current
        }
        val job = viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { backend.list(request.path) }
                updatePane(left) { current ->
                    if (request.isCurrentFor(current, activeRefreshGeneration(left))) {
                        current.copy(
                            items = items,
                            loading = false,
                            selected = reconcilePaneSelection(current.selected, items),
                        )
                    } else current
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val isCurrent = request.isCurrentFor(pane(left), activeRefreshGeneration(left))
                if (isCurrent) {
                    updatePane(left) { current ->
                        if (request.isCurrentFor(current, activeRefreshGeneration(left))) current.copy(loading = false)
                        else current
                    }
                    handleBackendError(left, backendId, error, preserveMessage)
                }
            }
        }
        setRefreshJob(left, job)
    }

    fun copySelected(fromLeft: Boolean, move: Boolean = false) {
        val sourcePane = pane(fromLeft)
        val selected = if (sourcePane.selected.isEmpty()) emptyList() else sourcePane.items.filter { it.path in sourcePane.selected }
        if (selected.isEmpty()) {
            _state.update { it.copy(message = "Выберите файлы или папки") }
            return
        }
        transfer(fromLeft, selected, move)
    }

    fun transferSingle(fromLeft: Boolean, item: StorageItem, move: Boolean = false) = transfer(fromLeft, listOf(item), move)

    private fun transfer(fromLeft: Boolean, items: List<StorageItem>, move: Boolean) {
        if (operation?.isActive == true) return
        val sourcePane = pane(fromLeft)
        val destinationPane = pane(!fromLeft)
        val sourceBackend = sourcePane.backendId ?: return
        val destinationBackend = destinationPane.backendId ?: return
        val request = TransferRequest(
            type = if (move) TransferType.MOVE else TransferType.COPY,
            sources = items.map { item ->
                TransferSource.Backend(
                    backendId = sourceBackend,
                    path = item.path,
                    name = item.name,
                    size = item.size,
                    modifiedAt = item.modifiedAt,
                    isDirectory = item.isDirectory,
                    mimeType = item.mimeType,
                )
            },
            destination = TransferDestination.Backend(destinationBackend, destinationPane.path),
            conflictPolicy = TransferConflictPolicy.ASK,
            preserveModifiedTime = true,
        )
        controller = TransferController()
        val activeController = requireNotNull(controller)
        operation = viewModelScope.launch {
            _state.update { it.copy(busyLabel = if (move) "Перемещение между панелями" else "Копирование между панелями") }
            try {
                val result = withContext(Dispatchers.IO) { transferEngine.execute(request, activeController) }
                clearSelectionIfCurrent(fromLeft, sourcePane)
                val completed = if (move) "Перемещение завершено" else "Копирование завершено"
                _state.update { it.copy(message = completed + result.warningSuffix()) }
            } catch (_: CancellationException) {
                _state.update { it.copy(message = "Передача остановлена") }
            } catch (error: Throwable) {
                _state.update { it.copy(message = error.message ?: "Ошибка передачи") }
            } finally {
                controller = null
                _state.update { it.copy(busyLabel = null, comparison = null, syncPlan = null) }
                refresh(true, preserveMessage = true)
                refresh(false, preserveMessage = true)
            }
        }
    }

    fun pause() = controller?.pause()
    fun resume() = controller?.resume()
    fun cancel() { controller?.cancel(); operation?.cancel() }
    fun resolveConflict(policy: TransferConflictPolicy, applyToAll: Boolean) =
        transferEngine.resolveConflict(TransferConflictDecision(policy, applyToAll))

    fun comparePanels() {
        if (operation?.isActive == true) return
        val left = pane(true); val right = pane(false)
        val lb = left.backendId?.let(registry::get) ?: return
        val rb = right.backendId?.let(registry::get) ?: return
        operation = viewModelScope.launch {
            _state.update { it.copy(busyLabel = "Сравнение каталогов", comparison = null) }
            try {
                val comparison = withContext(Dispatchers.IO) {
                    comparator.compare(lb, left.path, rb, right.path, recursive = true)
                }
                if (paneLocationMatches(true, left) && paneLocationMatches(false, right)) {
                    _state.update { it.copy(comparison = comparison) }
                }
            } catch (_: CancellationException) {
                _state.update { it.copy(message = "Сравнение остановлено") }
            } catch (error: Throwable) {
                _state.update { state -> state.copy(message = error.message ?: "Ошибка сравнения") }
            } finally {
                _state.update { it.copy(busyLabel = null) }
            }
        }
    }

    fun prepareSync(direction: SyncDirection, deleteExtraneous: Boolean = false) {
        val comparison = _state.value.comparison ?: return
        _state.update { it.copy(syncPlan = DirectorySyncPlanner.build(comparison, direction, deleteExtraneous)) }
    }

    fun executeSync() {
        val plan = _state.value.syncPlan ?: return
        if (operation?.isActive == true) return
        val leftPane = pane(true); val rightPane = pane(false)
        val left = leftPane.backendId?.let(registry::get) ?: return
        val right = rightPane.backendId?.let(registry::get) ?: return
        val activeController = TransferController().also { controller = it }
        operation = viewModelScope.launch {
            _state.update { it.copy(busyLabel = "Синхронизация каталогов") }
            val cleanupWarnings = mutableListOf<String>()
            try {
                withContext(Dispatchers.IO) {
                    syncExecutor.execute(
                        plan, left, leftPane.path, right, rightPane.path,
                        copyFile = { sourceBackend, sourcePath, destinationBackend, destinationDirectory, replace ->
                            val source = sourceBackend.stat(sourcePath) ?: error("Источник исчез: $sourcePath")
                            val request = TransferRequest(
                                type = TransferType.COPY,
                                sources = listOf(
                                    TransferSource.Backend(
                                        backendId = sourceBackend.descriptor.id,
                                        path = source.path,
                                        name = source.name,
                                        size = source.size,
                                        modifiedAt = source.modifiedAt,
                                        isDirectory = source.isDirectory,
                                        mimeType = source.mimeType,
                                    )
                                ),
                                destination = TransferDestination.Backend(destinationBackend.descriptor.id, destinationDirectory),
                                conflictPolicy = if (replace) TransferConflictPolicy.REPLACE else TransferConflictPolicy.SKIP,
                            )
                            cleanupWarnings += transferEngine.execute(request, activeController).warnings
                        },
                    )
                }
                _state.update {
                    it.copy(
                        syncPlan = null,
                        comparison = null,
                        message = "Синхронизация завершена" + cleanupWarnings.warningSuffix(),
                    )
                }
            } catch (_: CancellationException) {
                _state.update { it.copy(message = "Синхронизация остановлена") }
            } catch (error: Throwable) {
                _state.update { it.copy(message = error.message ?: "Ошибка синхронизации") }
            } finally {
                controller = null
                _state.update { it.copy(busyLabel = null, comparison = null, syncPlan = null) }
                refresh(true, preserveMessage = true)
                refresh(false, preserveMessage = true)
            }
        }
    }

    fun dismissComparison() = _state.update { it.copy(comparison = null) }
    fun dismissSyncPlan() = _state.update { it.copy(syncPlan = null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun acceptHostKey() {
        val pending = _state.value.pendingHostKey ?: return
        profileRepository.trustSftpFingerprint(pending.profileId, pending.fingerprint)
        _state.update { it.copy(pendingHostKey = null, message = "Ключ сервера сохранён") }
        rebuildBackends()
        refresh(pending.paneLeft)
    }

    fun rejectHostKey() = _state.update { it.copy(pendingHostKey = null) }

    fun saveSftp(profile: SftpProfile) {
        profileRepository.save(profile)
        rebuildBackends()
    }

    fun sftpProfiles(): List<SftpProfile> = profileRepository.profiles()
        .filter { it.protocol == NetworkProtocol.SFTP }
        .map(profileRepository::sftp)

    private fun mutatePane(
        left: Boolean,
        label: String,
        block: suspend (StorageBackend, BackendPaneState) -> String,
    ) {
        if (operation?.isActive == true) {
            _state.update { it.copy(message = "Сначала дождитесь текущей операции") }
            return
        }
        val snapshot = pane(left)
        val backend = snapshot.backendId?.let(registry::get) ?: return
        operation = viewModelScope.launch {
            _state.update { it.copy(busyLabel = label) }
            var succeeded = false
            try {
                val message = withContext(Dispatchers.IO) { block(backend, snapshot) }
                succeeded = true
                _state.update { it.copy(message = message) }
            } catch (_: CancellationException) {
                _state.update { it.copy(message = "$label отменено") }
            } catch (error: Throwable) {
                handleBackendError(left, backend.descriptor.id, error)
            } finally {
                if (succeeded) clearSelectionIfCurrent(left, snapshot)
                _state.update { it.copy(busyLabel = null, comparison = null, syncPlan = null) }
                // A batch may have changed several objects before a later item failed.
                // Always reconcile the pane with the backend, including cancellation/error.
                refresh(left, preserveMessage = true)
            }
        }
    }

    private fun handleBackendError(
        left: Boolean,
        backendId: String,
        error: Throwable,
        preserveMessage: Boolean = false,
    ) {
        val hostKey = generateSequence(error) { it.cause }.filterIsInstance<SftpHostKeyException>().firstOrNull()
        if (hostKey != null) {
            val profileId = profileRepository.profiles().firstOrNull { profile ->
                profile.protocol == NetworkProtocol.SFTP && backendId == "sftp:${profile.id}"
            }?.id.orEmpty()
            _state.update {
                it.copy(
                    pendingHostKey = PendingHostKey(profileId, hostKey.host, hostKey.fingerprint, hostKey.previousFingerprint, left),
                    message = if (preserveMessage) it.message else null,
                )
            }
        } else {
            val failure = error.message ?: "Ошибка backend"
            _state.update { current ->
                val message = if (preserveMessage && !current.message.isNullOrBlank()) {
                    val side = if (left) "левая" else "правая"
                    "${current.message}\nНе обновлена $side панель: $failure"
                } else failure
                current.copy(message = message)
            }
        }
    }

    private fun pane(left: Boolean): BackendPaneState = if (left) _state.value.left else _state.value.right
    private fun updatePane(left: Boolean, transform: (BackendPaneState) -> BackendPaneState) {
        _state.update { state ->
            val before = if (left) state.left else state.right
            val transformed = transform(before)
            val locationChanged = before.backendId != transformed.backendId || before.path != transformed.path
            val after = if (locationChanged) {
                transformed.copy(items = emptyList(), loading = false, selected = emptySet())
            } else transformed
            val updated = if (left) state.copy(left = after) else state.copy(right = after)
            if (locationChanged) updated.copy(comparison = null, syncPlan = null) else updated
        }
    }

    private fun beginRefresh(left: Boolean): Long {
        if (left) {
            leftRefreshJob?.cancel()
            leftRefreshGeneration += 1
            return leftRefreshGeneration
        }
        rightRefreshJob?.cancel()
        rightRefreshGeneration += 1
        return rightRefreshGeneration
    }

    private fun invalidateRefresh(left: Boolean) {
        if (left) {
            leftRefreshJob?.cancel()
            leftRefreshJob = null
            leftRefreshGeneration += 1
        } else {
            rightRefreshJob?.cancel()
            rightRefreshJob = null
            rightRefreshGeneration += 1
        }
    }

    private fun activeRefreshGeneration(left: Boolean): Long =
        if (left) leftRefreshGeneration else rightRefreshGeneration

    private fun setRefreshJob(left: Boolean, job: Job) {
        if (left) leftRefreshJob = job else rightRefreshJob = job
    }

    private fun paneLocationMatches(left: Boolean, snapshot: BackendPaneState): Boolean {
        val current = pane(left)
        return current.backendId == snapshot.backendId && current.path == snapshot.path
    }

    private fun clearSelectionIfCurrent(left: Boolean, snapshot: BackendPaneState) {
        updatePane(left) { current ->
            if (current.backendId == snapshot.backendId && current.path == snapshot.path) {
                current.copy(selected = emptySet())
            } else current
        }
    }

    private fun com.aurafiles.app.transfer.TransferResult.warningSuffix(): String = warnings.warningSuffix()

    private fun List<String>.warningSuffix(): String =
        if (isEmpty()) "" else "\nВнимание: ${distinct().joinToString("; ")}"

    companion object {
        private const val KEY_YANDEX_CLIENT_ID = "yandex_auth_client_id"
        private const val KEY_YANDEX_DEVICE_CODE = "yandex_auth_device_code"
        private const val KEY_YANDEX_USER_CODE = "yandex_auth_user_code"
        private const val KEY_YANDEX_VERIFICATION_URL = "yandex_auth_verification_url"
        private const val KEY_YANDEX_INTERVAL_SECONDS = "yandex_auth_interval_seconds"
        private const val KEY_YANDEX_EXPIRES_AT = "yandex_auth_expires_at"
        private const val KEY_GOOGLE_AUTH_ACTIVE = "google_auth_active"
        private const val HISTORY_LIMIT = 80
        private const val OPEN_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val OPEN_CACHE_SPACE_RESERVE_BYTES = 256L * 1024L * 1024L
        private const val DEFAULT_COPY_BUFFER = 1024 * 1024
        private const val RECENT_LIMIT = 20
    }
}
