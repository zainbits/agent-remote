package dev.zain.agentremote

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zain.agentremote.agent.AgentEvent
import dev.zain.agentremote.agent.AgentModelOption
import dev.zain.agentremote.agent.AgentRequestCancelledException
import dev.zain.agentremote.agent.AgentUsage
import dev.zain.agentremote.agent.ChatMessage
import dev.zain.agentremote.agent.ChatRole
import dev.zain.agentremote.agent.CodexRateLimitWindow
import dev.zain.agentremote.agent.CodexStatusSnapshot
import dev.zain.agentremote.agent.ConnectionState
import dev.zain.agentremote.agent.DurableAgentClient
import dev.zain.agentremote.agent.ImageAttachment
import dev.zain.agentremote.agent.SessionSummary
import dev.zain.agentremote.agent.SessionCleanupReport
import dev.zain.agentremote.agent.SlashCommand
import dev.zain.agentremote.agent.SlashCommandSource
import dev.zain.agentremote.agent.isActive
import dev.zain.agentremote.data.AppSettings
import dev.zain.agentremote.data.BackendKind
import dev.zain.agentremote.data.NetworkProfile
import dev.zain.agentremote.data.SettingsRepository
import dev.zain.agentremote.data.displayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

enum class AppScreen {
    HOME,
    CHAT,
}

data class CommandOutputState(
    val command: String,
    val body: String = "",
    val status: String? = null,
    val running: Boolean = false,
    val isError: Boolean = false,
    val progress: Float? = null,
)

data class SessionCleanupUiState(
    val checking: Boolean = false,
    val deleting: Boolean = false,
    val preview: SessionCleanupReport? = null,
    val message: String? = null,
    val error: String? = null,
) {
    val busy: Boolean
        get() = checking || deleting
}

data class ChatUiState(
    val settings: AppSettings = AppSettings(),
    val screen: AppScreen = AppScreen.HOME,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val pendingImages: List<ImageAttachment> = emptyList(),
    val attachmentError: String? = null,
    val attachmentSelectionBusy: Boolean = false,
    val busy: Boolean = false,
    val requestInFlight: Boolean = false,
    val cancellationRequested: Boolean = false,
    val reconnecting: Boolean = false,
    val canReconnect: Boolean = false,
    val statusLine: String = "Disconnected",
    val slashCommands: List<SlashCommand> = emptyList(),
    val usage: AgentUsage = AgentUsage(),
    val modelOptions: List<AgentModelOption> = emptyList(),
    val modelSelectionBusy: Boolean = false,
    val commandOutput: CommandOutputState? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val sessionsLoading: Boolean = false,
    val sessionsError: String? = null,
    val sessionActionId: String? = null,
    val sessionCleanup: SessionCleanupUiState = SessionCleanupUiState(),
    val activeSessionTitle: String? = null,
    val historyLoading: Boolean = false,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepo = SettingsRepository(application)
    private val grokBackend = DurableAgentClient(
        BackendKind.GROK_BUILD,
        application.contentResolver,
    )
    private val codexBackend = DurableAgentClient(
        BackendKind.CODEX,
        application.contentResolver,
    )
    private val sessionsByBackend = mutableMapOf<BackendKind, List<SessionSummary>>()
    private val sessionErrorsByBackend = mutableMapOf<BackendKind, String?>()
    private var activeBackendKind: BackendKind = BackendKind.GROK_BUILD

    private val backend
        get() = when (activeBackendKind) {
            BackendKind.GROK_BUILD -> grokBackend
            BackendKind.CODEX -> codexBackend
        }

    private fun durableBackend(kind: BackendKind): DurableAgentClient = when (kind) {
        BackendKind.GROK_BUILD -> grokBackend
        BackendKind.CODEX -> codexBackend
    }

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private val eventsJobs = mutableListOf<Job>()
    private var streamingUserId: String? = null
    private var streamingAssistantId: String? = null
    private var streamingThoughtId: String? = null
    private var nextPromptGeneration: Long = 0
    private var activePromptGeneration: Long? = null
    private var activeSlashCommand: String? = null
    private val activeCommandMessages = linkedMapOf<String, String>()
    private var suppressLoadedSlashTurn: Boolean = false
    private var loadedPromptIndex: Long? = null
    private var reconnectJob: Job? = null
    private var connectionActionGeneration: Long = 0
    private var connectionDesired: Boolean = false
    private var hasConnectedSession: Boolean = false
    private var appInForeground: Boolean = true
    private var sessionListVisible: Boolean = false
    private var sessionStatusPollingJob: Job? = null
    private var activeSessionIdForReconnect: String? = null
    private var activeSessionCwdForReconnect: String = AppSettings.DEFAULT_CWD
    private var latestCodexStatus: CodexStatusSnapshot? = null
    /**
     * After a local [send], the agent echoes the prompt as `user_message_chunk`.
     * We already inserted an optimistic USER bubble, so ignore those deltas until
     * the turn ends. Session load still applies UserDelta (flag stays false).
     */
    private var suppressUserEcho: Boolean = false

    private val informationCommands = setOf("context", "usage", "session-info", "status")

    private val localSlashCommands = listOf(
        SlashCommand(
            name = "stop",
            description = "Stop the current agent request",
            source = SlashCommandSource.APP,
        ),
        SlashCommand(
            name = "cancel",
            description = "Stop the current agent request",
            source = SlashCommandSource.APP,
        ),
        SlashCommand(
            name = "new",
            description = "Start a new conversation",
            source = SlashCommandSource.APP,
        ),
        SlashCommand(
            name = "clear",
            description = "Start a new conversation",
            source = SlashCommandSource.APP,
        ),
        SlashCommand(
            name = "home",
            description = "Return to the session list",
            source = SlashCommandSource.APP,
        ),
        SlashCommand(
            name = "disconnect",
            description = "Disconnect from the agent",
            source = SlashCommandSource.APP,
        ),
        SlashCommand(
            name = "context",
            description = "Show context window usage and session stats",
            source = SlashCommandSource.APP,
        ),
        SlashCommand(
            name = "usage",
            description = "Show context window usage and session stats",
            source = SlashCommandSource.APP,
        ),
        SlashCommand(
            name = "session-info",
            description = "Show session details (model, turns, context usage)",
            source = SlashCommandSource.APP,
        ),
        SlashCommand(
            name = "help",
            description = "Show available slash commands",
            source = SlashCommandSource.APP,
        ),
    )

    private val codexStatusCommand = SlashCommand(
        name = "status",
        description = "Show current session configuration and usage",
        source = SlashCommandSource.APP,
    )

    private fun localSlashCommandsFor(kind: BackendKind = activeBackendKind): List<SlashCommand> =
        if (kind == BackendKind.CODEX) {
            localSlashCommands + codexStatusCommand
        } else {
            localSlashCommands
        }

    val settingsFlow = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    init {
        _ui.update { it.copy(slashCommands = localSlashCommandsFor()) }
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                if (_ui.value.screen == AppScreen.HOME) {
                    activeBackendKind = s.backendKind
                    _ui.update {
                        it.copy(
                            settings = s,
                            slashCommands = localSlashCommandsFor(s.backendKind),
                        )
                    }
                } else {
                    _ui.update { it.copy(settings = s) }
                }
            }
        }
        eventsJobs += viewModelScope.launch {
            grokBackend.events().collect { event ->
                if (activeBackendKind == BackendKind.GROK_BUILD) handleEvent(event)
            }
        }
        eventsJobs += viewModelScope.launch {
            codexBackend.events().collect { event ->
                if (activeBackendKind == BackendKind.CODEX) handleEvent(event)
            }
        }
        // Initial session list once settings load
        viewModelScope.launch {
            settingsRepo.settings.collect {
                // only auto-refresh when on home and we have a secret
                if (_ui.value.screen == AppScreen.HOME &&
                    _ui.value.settings.durableHostToken.isNotBlank() &&
                    _ui.value.sessions.isEmpty() &&
                    !_ui.value.sessionsLoading
                ) {
                    refreshSessions()
                    return@collect
                }
            }
        }
    }

    fun onDraftChange(value: String) {
        _ui.update { it.copy(draft = value) }
    }

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val current = _ui.value.pendingImages
        val existingUris = current.mapNotNull { it.localUri?.toString() }.toSet()
        val remaining = MAX_IMAGE_ATTACHMENTS - current.size
        if (remaining <= 0) {
            _ui.update { it.copy(attachmentError = "You can attach up to 4 images.") }
            return
        }
        val candidates = uris
            .distinctBy(Uri::toString)
            .filterNot { it.toString() in existingUris }
        val selected = candidates.take(remaining)
        val overLimit = candidates.size > selected.size
        if (selected.isEmpty()) {
            _ui.update { it.copy(attachmentError = null) }
            return
        }
        _ui.update { it.copy(attachmentSelectionBusy = true, attachmentError = null) }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                selected.map { uri -> runCatching { pickedImage(uri) } }
            }
            val images = loaded.mapNotNull(Result<ImageAttachment>::getOrNull)
            val firstError = loaded.firstNotNullOfOrNull { result ->
                result.exceptionOrNull()?.message
            }
            _ui.update { state ->
                state.copy(
                    pendingImages = (state.pendingImages + images)
                        .distinctBy { it.localUri?.toString() ?: it.id }
                        .take(MAX_IMAGE_ATTACHMENTS),
                    attachmentError = firstError ?: if (overLimit) {
                        "You can attach up to 4 images."
                    } else {
                        null
                    },
                    attachmentSelectionBusy = false,
                )
            }
        }
    }

    fun removeImage(id: String) {
        _ui.update { state ->
            state.copy(
                pendingImages = state.pendingImages.filterNot { it.id == id },
                attachmentError = null,
            )
        }
    }

    private fun pickedImage(uri: Uri): ImageAttachment {
        val resolver = getApplication<Application>().contentResolver
        val reportedMimeType = resolver.getType(uri)?.lowercase()
        val mimeType = when (reportedMimeType) {
            "image/jpg" -> "image/jpeg"
            else -> reportedMimeType
        } ?: throw IllegalArgumentException("The selected item has no image type.")
        require(mimeType in SUPPORTED_IMAGE_MIME_TYPES) {
            "Choose a PNG, JPEG, GIF, or WebP image."
        }
        var fileName: String? = null
        var sizeBytes: Long? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) fileName = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex)
            }
        }
        if (sizeBytes == null || sizeBytes < 0L) {
            sizeBytes = resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
            }
        }
        val length = sizeBytes ?: throw IllegalArgumentException("Couldn't determine image size.")
        require(length in 1..MAX_IMAGE_BYTES) { "Each image must be 20 MiB or smaller." }
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/gif" -> "gif"
            else -> "webp"
        }
        val thumbnail = runCatching {
            resolver.loadThumbnail(uri, Size(256, 256), null).asImageBitmap()
        }.getOrNull()
        return ImageAttachment(
            id = UUID.randomUUID().toString(),
            fileName = fileName?.takeIf { it.isNotBlank() } ?: "image.$extension",
            mimeType = mimeType,
            sizeBytes = length,
            localUri = uri,
            thumbnail = thumbnail,
        )
    }

    fun refreshSessions() {
        stopSessionStatusPolling()
        refreshSessions(_ui.value.settings.backendKind)
    }

    fun renameSession(session: SessionSummary, title: String) {
        val normalized = title.trim()
        if (normalized.isBlank() || normalized == session.title) return
        updateSessionMetadata(session, title = normalized)
    }

    fun toggleSessionPin(session: SessionSummary) {
        updateSessionMetadata(session, pinned = !session.pinned)
    }

    fun deleteSession(session: SessionSummary) {
        val state = _ui.value
        val kind = state.settings.backendKind
        val settings = state.settings
        if (state.sessionActionId != null || settings.durableHostToken.isBlank()) return
        if (session.status.isActive) {
            _ui.update { it.copy(sessionsError = "Stop the active turn before deleting this session") }
            return
        }
        _ui.update { it.copy(sessionActionId = session.sessionId, sessionsError = null) }
        viewModelScope.launch {
            runCatching {
                durableBackend(kind).deleteSession(
                    baseUrl = settings.activeDurableBaseUrl,
                    secret = settings.durableHostToken,
                    sessionId = session.sessionId,
                )
            }.onSuccess {
                val remaining = sessionsByBackend[kind]
                    .orEmpty()
                    .filterNot { it.sessionId == session.sessionId }
                sessionsByBackend[kind] = remaining
                sessionErrorsByBackend[kind] = null
                _ui.update { current ->
                    if (current.settings.backendKind == kind) {
                        current.copy(
                            sessions = remaining,
                            sessionActionId = null,
                            sessionsError = null,
                        )
                    } else {
                        current.copy(sessionActionId = null)
                    }
                }
            }.onFailure { error ->
                val message = error.message ?: "Couldn't delete session"
                sessionErrorsByBackend[kind] = message
                _ui.update { current ->
                    if (current.settings.backendKind == kind) {
                        current.copy(sessionActionId = null, sessionsError = message)
                    } else {
                        current.copy(sessionActionId = null)
                    }
                }
            }
        }
    }

    fun previewOldSessionCleanup(baseUrl: String, secret: String) {
        val state = _ui.value
        if (state.sessionCleanup.busy) return
        val normalizedBaseUrl = baseUrl.trim()
        val normalizedSecret = secret.trim()
        if (normalizedBaseUrl.isBlank() || normalizedSecret.isBlank()) {
            _ui.update {
                it.copy(
                    sessionCleanup = SessionCleanupUiState(
                        error = "Set the durable host URL and token first.",
                    ),
                )
            }
            return
        }
        _ui.update {
            it.copy(sessionCleanup = SessionCleanupUiState(checking = true))
        }
        viewModelScope.launch {
            runCatching {
                grokBackend.previewSessionCleanup(
                    baseUrl = normalizedBaseUrl,
                    secret = normalizedSecret,
                )
            }.onSuccess { preview ->
                _ui.update {
                    it.copy(
                        sessionCleanup = if (preview.eligible.total == 0) {
                            SessionCleanupUiState(
                                message = "No unpinned, idle Grok or Codex sessions are " +
                                    "older than ${preview.olderThanDays} days.",
                            )
                        } else {
                            SessionCleanupUiState(preview = preview)
                        },
                    )
                }
            }.onFailure { error ->
                _ui.update {
                    it.copy(
                        sessionCleanup = SessionCleanupUiState(
                            error = error.message ?: "Couldn't check old sessions.",
                        ),
                    )
                }
            }
        }
    }

    fun deleteOldSessions(baseUrl: String, secret: String) {
        val state = _ui.value
        if (state.sessionCleanup.busy || state.sessionCleanup.preview == null) return
        val normalizedBaseUrl = baseUrl.trim()
        val normalizedSecret = secret.trim()
        if (normalizedBaseUrl.isBlank() || normalizedSecret.isBlank()) return
        _ui.update {
            it.copy(
                sessionCleanup = it.sessionCleanup.copy(
                    checking = false,
                    deleting = true,
                    message = null,
                    error = null,
                ),
            )
        }
        viewModelScope.launch {
            runCatching {
                grokBackend.deleteOldSessions(
                    baseUrl = normalizedBaseUrl,
                    secret = normalizedSecret,
                )
            }.onSuccess { report ->
                sessionsByBackend.clear()
                sessionErrorsByBackend.clear()
                val deleted = report.deleted?.total ?: 0
                val failed = report.failed?.total ?: 0
                val resultMessage = buildString {
                    append("Deleted $deleted old session")
                    if (deleted != 1) append('s')
                    append(" (Grok ${report.deleted?.grok ?: 0}, ")
                    append("Codex ${report.deleted?.codex ?: 0}).")
                    if (failed > 0) {
                        append(" $failed session")
                        if (failed != 1) append('s')
                        append(" could not be deleted.")
                    }
                    val protected = report.skippedPinned + report.skippedActive
                    if (protected > 0) {
                        append(" Kept $protected pinned or active session")
                        if (protected != 1) append('s')
                        append('.')
                    }
                }
                _ui.update {
                    it.copy(
                        sessionCleanup = SessionCleanupUiState(message = resultMessage),
                    )
                }
                refreshSessions(_ui.value.settings.backendKind, showLoading = false)
            }.onFailure { error ->
                _ui.update {
                    it.copy(
                        sessionCleanup = SessionCleanupUiState(
                            error = error.message ?: "Couldn't delete old sessions.",
                        ),
                    )
                }
            }
        }
    }

    fun dismissOldSessionCleanup() {
        if (_ui.value.sessionCleanup.deleting) return
        _ui.update { it.copy(sessionCleanup = SessionCleanupUiState()) }
    }

    private fun updateSessionMetadata(
        session: SessionSummary,
        title: String? = null,
        pinned: Boolean? = null,
    ) {
        val state = _ui.value
        val kind = state.settings.backendKind
        val settings = state.settings
        if (state.sessionActionId != null || settings.durableHostToken.isBlank()) return
        _ui.update { it.copy(sessionActionId = session.sessionId, sessionsError = null) }
        viewModelScope.launch {
            runCatching {
                durableBackend(kind).updateSessionMetadata(
                    baseUrl = settings.activeDurableBaseUrl,
                    secret = settings.durableHostToken,
                    sessionId = session.sessionId,
                    title = title,
                    pinned = pinned,
                )
            }.onSuccess { updated ->
                mergeSessionSummary(kind, updated)
                _ui.update { current ->
                    if (current.sessionActionId == session.sessionId) {
                        current.copy(sessionActionId = null)
                    } else {
                        current
                    }
                }
            }.onFailure { error ->
                _ui.update { current ->
                    if (current.settings.backendKind == kind) {
                        current.copy(
                            sessionActionId = null,
                            sessionsError = error.message ?: "Couldn't update session",
                        )
                    } else {
                        current.copy(sessionActionId = null)
                    }
                }
            }
        }
    }

    private fun markSessionRead(sessionId: String, kind: BackendKind = activeBackendKind) {
        val settings = _ui.value.settings
        if (settings.durableHostToken.isBlank()) return
        sessionsByBackend[kind]
            ?.firstOrNull { it.sessionId == sessionId && it.unread }
            ?.let { mergeSessionSummary(kind, it.copy(unread = false)) }
        viewModelScope.launch {
            runCatching {
                durableBackend(kind).updateSessionMetadata(
                    baseUrl = settings.activeDurableBaseUrl,
                    secret = settings.durableHostToken,
                    sessionId = sessionId,
                    unread = false,
                )
            }.onSuccess { updated ->
                mergeSessionSummary(kind, updated)
            }
        }
    }

    private fun mergeSessionSummary(kind: BackendKind, updated: SessionSummary) {
        val existing = sessionsByBackend[kind].orEmpty()
        val merged = (if (existing.any { it.sessionId == updated.sessionId }) {
            existing.map { if (it.sessionId == updated.sessionId) updated else it }
        } else {
            existing + updated
        })
            .sortedWith(
                compareByDescending<SessionSummary> { it.pinned }
                    .thenByDescending { it.updatedAt ?: it.createdAt.orEmpty() },
            )
        sessionsByBackend[kind] = merged
        _ui.update { state ->
            if (state.settings.backendKind == kind) state.copy(sessions = merged) else state
        }
    }

    private fun refreshSessions(kind: BackendKind, showLoading: Boolean = true) {
        val s = _ui.value.settings
        val secret = s.durableHostToken
        if (secret.isBlank()) {
            val message = "Set the durable host token in Settings first."
            sessionsByBackend[kind] = emptyList()
            sessionErrorsByBackend[kind] = message
            _ui.update {
                if (it.settings.backendKind == kind) {
                    it.copy(sessionsError = message, sessions = emptyList())
                } else {
                    it
                }
            }
            return
        }
        viewModelScope.launch {
            _ui.update {
                if (showLoading && it.settings.backendKind == kind) {
                    it.copy(sessionsLoading = true, sessionsError = null)
                } else {
                    it
                }
            }
            runCatching {
                val cwd = s.workingDirectory.ifBlank { AppSettings.DEFAULT_CWD }
                durableBackend(kind).listSessions(
                    baseUrl = s.activeDurableBaseUrl,
                    secret = secret,
                    workingDirectory = cwd,
                )
            }.onSuccess { list ->
                sessionsByBackend[kind] = list
                sessionErrorsByBackend[kind] = null
                _ui.update {
                    if (it.settings.backendKind == kind) {
                        it.copy(sessions = list, sessionsLoading = false, sessionsError = null)
                    } else {
                        it
                    }
                }
                updateSessionStatusPolling(kind, list)
            }.onFailure { e ->
                val message = e.message ?: "Failed to load ${kind.displayName} sessions"
                if (showLoading) {
                    sessionErrorsByBackend[kind] = message
                    _ui.update {
                        if (it.settings.backendKind == kind) {
                            it.copy(sessionsLoading = false, sessionsError = message)
                        } else {
                            it
                        }
                    }
                }
                updateSessionStatusPolling(kind, sessionsByBackend[kind].orEmpty())
            }
        }
    }

    private fun updateSessionStatusPolling(
        kind: BackendKind,
        sessions: List<SessionSummary>,
    ) {
        val state = _ui.value
        // An older refresh for the other backend must not cancel the selected backend's monitor.
        if (state.settings.backendKind != kind) return
        val shouldPoll = appInForeground &&
            sessionListVisible &&
            state.screen == AppScreen.HOME &&
            !state.busy &&
            state.settings.durableHostToken.isNotBlank() &&
            sessions.any { it.status.isActive }
        if (!shouldPoll) {
            stopSessionStatusPolling()
            return
        }
        if (sessionStatusPollingJob?.isActive == true) return

        val pollingJob = viewModelScope.launch {
            while (true) {
                delay(SESSION_STATUS_POLL_INTERVAL_MILLIS)
                val current = _ui.value
                if (!appInForeground ||
                    !sessionListVisible ||
                    current.screen != AppScreen.HOME ||
                    current.busy ||
                    current.settings.backendKind != kind ||
                    current.settings.durableHostToken.isBlank() ||
                    current.sessions.none { it.status.isActive }
                ) {
                    break
                }

                val settings = current.settings
                runCatching {
                    val cwd = settings.workingDirectory.ifBlank { AppSettings.DEFAULT_CWD }
                    durableBackend(kind).listSessions(
                        baseUrl = settings.activeDurableBaseUrl,
                        secret = settings.durableHostToken,
                        workingDirectory = cwd,
                    )
                }.onSuccess { list ->
                    sessionsByBackend[kind] = list
                    sessionErrorsByBackend[kind] = null
                    _ui.update {
                        if (it.settings.backendKind == kind &&
                            it.screen == AppScreen.HOME &&
                            sessionListVisible
                        ) {
                            it.copy(sessions = list, sessionsError = null)
                        } else {
                            it
                        }
                    }
                }
                // A transient background refresh failure keeps the last good list and retries.
                // Manual refresh remains the path that surfaces a host/network error to the user.
            }
        }
        sessionStatusPollingJob = pollingJob
        pollingJob.invokeOnCompletion {
            if (sessionStatusPollingJob === pollingJob) sessionStatusPollingJob = null
        }
    }

    private fun stopSessionStatusPolling() {
        sessionStatusPollingJob?.cancel()
        sessionStatusPollingJob = null
    }

    fun selectBackend(kind: BackendKind) {
        val state = _ui.value
        if (state.screen != AppScreen.HOME || state.busy || state.settings.backendKind == kind) return
        stopSessionStatusPolling()
        activeBackendKind = kind
        _ui.update {
            it.copy(
                settings = it.settings.copy(backendKind = kind),
                sessions = sessionsByBackend[kind].orEmpty(),
                sessionsError = sessionErrorsByBackend[kind],
                sessionsLoading = false,
                slashCommands = localSlashCommandsFor(kind),
            )
        }
        viewModelScope.launch {
            settingsRepo.update { it.copy(backendKind = kind) }
            refreshSessions(kind)
        }
    }

    fun openNewSession() {
        val s = _ui.value.settings
        activeBackendKind = s.backendKind
        val secret = s.durableHostToken
        if (secret.isBlank()) {
            pushSystem("Set the durable host token in Settings first.")
            return
        }
        val cwd = s.workingDirectory.ifBlank { AppSettings.DEFAULT_CWD }
        val connectionAction = beginConnectionAction(sessionId = null, cwd = cwd)
        viewModelScope.launch {
            clearChatLocal()
            _ui.update {
                it.copy(
                    screen = AppScreen.CHAT,
                    busy = true,
                    requestInFlight = false,
                    cancellationRequested = false,
                    reconnecting = false,
                    canReconnect = false,
                    connection = ConnectionState.Connecting,
                    statusLine = "Connecting…",
                    activeSessionTitle = "New chat",
                    historyLoading = false,
                    messages = emptyList(),
                    draft = "",
                    pendingImages = emptyList(),
                    attachmentError = null,
                    attachmentSelectionBusy = false,
                    slashCommands = localSlashCommandsFor(),
                    usage = AgentUsage(),
                    modelOptions = emptyList(),
                    modelSelectionBusy = false,
                    commandOutput = null,
                )
            }
            runCatching {
                backend.connectNew(
                    baseUrl = s.activeDurableBaseUrl,
                    secret = secret,
                    workingDirectory = cwd,
                    codexFullAccess = s.codexFullAccess,
                )
            }.onFailure { e ->
                if (connectionAction != connectionActionGeneration) return@onFailure
                connectionDesired = false
                pushSystem("Connect failed: ${e.message ?: e::class.java.simpleName}")
                _ui.update {
                    it.copy(
                        busy = false,
                        reconnecting = false,
                        connection = ConnectionState.Error("Connection failed"),
                        statusLine = "Connection failed",
                    )
                }
            }.onSuccess {
                if (connectionAction != connectionActionGeneration) return@onSuccess
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun openSession(session: SessionSummary) {
        val s = _ui.value.settings
        activeBackendKind = s.backendKind
        val secret = s.durableHostToken
        if (secret.isBlank()) {
            _ui.update {
                it.copy(
                    sessionsError =
                        "Set the durable host token in Settings first.",
                )
            }
            return
        }
        val cwd = session.cwd.ifBlank {
            s.workingDirectory.ifBlank { AppSettings.DEFAULT_CWD }
        }
        val connectionAction = beginConnectionAction(sessionId = session.sessionId, cwd = cwd)
        markSessionRead(session.sessionId, activeBackendKind)
        viewModelScope.launch {
            clearChatLocal()
            _ui.update {
                it.copy(
                    screen = AppScreen.CHAT,
                    busy = true,
                    requestInFlight = false,
                    cancellationRequested = false,
                    reconnecting = false,
                    canReconnect = true,
                    connection = ConnectionState.Connecting,
                    statusLine = "Loading session…",
                    activeSessionTitle = session.title,
                    historyLoading = true,
                    messages = emptyList(),
                    draft = "",
                    pendingImages = emptyList(),
                    attachmentError = null,
                    attachmentSelectionBusy = false,
                    slashCommands = localSlashCommandsFor(),
                    usage = AgentUsage(),
                    modelOptions = emptyList(),
                    modelSelectionBusy = false,
                    commandOutput = null,
                )
            }
            runCatching {
                backend.connectLoad(
                    baseUrl = s.activeDurableBaseUrl,
                    secret = secret,
                    workingDirectory = cwd,
                    sessionId = session.sessionId,
                )
            }.onFailure { e ->
                if (connectionAction != connectionActionGeneration) return@onFailure
                connectionDesired = false
                pushSystem("Load failed: ${e.message ?: e::class.java.simpleName}")
                _ui.update {
                    it.copy(
                        busy = false,
                        reconnecting = false,
                        canReconnect = true,
                        connection = ConnectionState.Error("Connection failed"),
                        statusLine = "Connection failed · tap link to retry",
                        historyLoading = false,
                    )
                }
            }.onSuccess {
                if (connectionAction != connectionActionGeneration) return@onSuccess
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun goHome() {
        val departingBackend = backend
        stopConnectionIntent(clearSession = true)
        clearChatLocal()
        // Commit navigation immediately; transport cleanup continues off-screen.
        _ui.update { it.copy(screen = AppScreen.HOME, busy = true) }
        viewModelScope.launch {
            runCatching { departingBackend.disconnect() }
            _ui.update {
                it.copy(
                    screen = AppScreen.HOME,
                    busy = false,
                    requestInFlight = false,
                    cancellationRequested = false,
                    reconnecting = false,
                    canReconnect = false,
                    connection = ConnectionState.Disconnected,
                    statusLine = "Disconnected",
                    messages = emptyList(),
                    draft = "",
                    pendingImages = emptyList(),
                    attachmentError = null,
                    attachmentSelectionBusy = false,
                    slashCommands = localSlashCommandsFor(),
                    usage = AgentUsage(),
                    modelOptions = emptyList(),
                    modelSelectionBusy = false,
                    commandOutput = null,
                    activeSessionTitle = null,
                    historyLoading = false,
                )
            }
            refreshSessions()
        }
    }

    fun disconnect() {
        stopConnectionIntent(clearSession = false)
        val canReconnect = activeSessionIdForReconnect != null
        clearChatLocal()
        viewModelScope.launch {
            runCatching { backend.disconnect() }
            _ui.update {
                it.copy(
                    busy = false,
                    requestInFlight = false,
                    cancellationRequested = false,
                    reconnecting = false,
                    canReconnect = canReconnect,
                    connection = ConnectionState.Disconnected,
                    statusLine = if (canReconnect) {
                        "Disconnected · tap link to reconnect"
                    } else {
                        "Disconnected"
                    },
                    slashCommands = localSlashCommandsFor(),
                    usage = AgentUsage(),
                    modelOptions = emptyList(),
                    modelSelectionBusy = false,
                    commandOutput = null,
                    historyLoading = false,
                )
            }
        }
    }

    fun reconnect() {
        if (_ui.value.screen != AppScreen.CHAT || activeSessionIdForReconnect == null) return
        if (reconnectJob?.isActive == true) return
        connectionDesired = true
        hasConnectedSession = true
        val action = ++connectionActionGeneration
        startReconnect(action)
    }

    fun onAppForegrounded() {
        appInForeground = true
        val state = _ui.value
        if (state.screen == AppScreen.HOME) {
            if (sessionListVisible && state.settings.durableHostToken.isNotBlank()) {
                stopSessionStatusPolling()
                refreshSessions(state.settings.backendKind, showLoading = false)
            }
            return
        }
        activeSessionIdForReconnect?.let { markSessionRead(it, activeBackendKind) }
        val sessionId = activeSessionIdForReconnect
        val needsReconnect = state.reconnecting ||
            state.connection !is ConnectionState.Connected ||
            !backend.isConnected()
        if (state.screen != AppScreen.CHAT || sessionId == null || !needsReconnect) return

        // A reconnect started while Android was backgrounding may still be waiting
        // on a half-open socket. Replace it so returning to the app always gets a
        // fresh, foreground network attempt.
        reconnectJob?.cancel()
        reconnectJob = null
        connectionDesired = true
        hasConnectedSession = true
        startReconnect(++connectionActionGeneration)
    }

    fun onAppBackgrounded() {
        appInForeground = false
        stopSessionStatusPolling()
        // Keep a healthy session connected, but do not spend the bounded retry
        // budget while Android has stopped the activity. Foregrounding restarts
        // an interrupted recovery from a new socket generation.
        reconnectJob?.cancel()
        reconnectJob = null
    }

    fun onSessionListVisibilityChanged(visible: Boolean) {
        if (sessionListVisible == visible) return
        sessionListVisible = visible
        if (!visible) {
            stopSessionStatusPolling()
            return
        }
        val state = _ui.value
        if (appInForeground &&
            state.screen == AppScreen.HOME &&
            !state.busy &&
            state.sessions.any { it.status.isActive }
        ) {
            stopSessionStatusPolling()
            refreshSessions(state.settings.backendKind, showLoading = false)
        }
    }

    fun selectSlashCommand(command: SlashCommand) {
        _ui.update {
            it.copy(
                draft = buildString {
                    append('/')
                    append(command.name)
                    if (!command.argumentHint.isNullOrBlank()) append(' ')
                },
            )
        }
    }

    fun dismissCommandOutput() {
        _ui.update { it.copy(commandOutput = null) }
    }

    fun send() {
        val initialState = _ui.value
        val text = initialState.draft.trim()
        val images = initialState.pendingImages
        if (initialState.attachmentSelectionBusy) return
        if (text.isEmpty() && images.isEmpty()) return
        if (images.isEmpty() && runLocalSlashCommand(text)) return
        val commandName = slashCommandName(text).takeIf { images.isEmpty() }
        if (!backend.isConnected()) {
            if (commandName != null) {
                showCommandOutput(
                    command = commandName,
                    body = "Not connected.",
                    isError = true,
                )
                _ui.update { it.copy(draft = "") }
            } else {
                pushSystem("Not connected.")
            }
            return
        }
        if (_ui.value.requestInFlight) return

        // Grok echoes prompt text as user_message_chunk. Normal messages get an
        // optimistic bubble; slash commands render only in the command panel.
        val generation = ++nextPromptGeneration
        activePromptGeneration = generation
        activeSlashCommand = commandName
        activeCommandMessages.clear()
        suppressUserEcho = true
        streamingUserId = null
        streamingAssistantId = null
        streamingThoughtId = null
        _ui.update { state ->
            state.copy(
                draft = "",
                pendingImages = emptyList(),
                attachmentError = null,
                attachmentSelectionBusy = false,
                busy = true,
                requestInFlight = true,
                cancellationRequested = false,
                commandOutput = commandName?.let {
                    CommandOutputState(
                        command = it,
                        status = "Running…",
                        running = true,
                    )
                } ?: state.commandOutput,
                messages = if (commandName == null) {
                    state.messages + ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatRole.USER,
                        text = text,
                        attachments = images,
                    )
                } else {
                    state.messages
                },
            )
        }
        viewModelScope.launch {
            runCatching { backend.sendPrompt(text, images) }
                .onFailure { e ->
                    // A disconnected/replaced session can finish an old request later.
                    if (activePromptGeneration != generation) return@onFailure
                    val wasCancelling = _ui.value.cancellationRequested
                    val command = activeSlashCommand
                    val connectionEnded = !backend.isConnected() && hasConnectedSession
                    finishActiveRequest()
                    if (connectionEnded) {
                        if (command != null) {
                            finishCommandOutput(
                                command = command,
                                status = "Interrupted",
                                fallbackBody = if (connectionDesired) {
                                    "The connection was interrupted. Reconnecting…"
                                } else {
                                    "The command stopped when the session was disconnected."
                                },
                                isError = connectionDesired,
                            )
                        }
                        return@onFailure
                    }
                    val message = when {
                        e is AgentRequestCancelledException && wasCancelling -> "Request stopped."
                        e is AgentRequestCancelledException -> "Request was cancelled by the agent."
                        wasCancelling ->
                            "Couldn't confirm the stop request: ${e.message ?: e::class.java.simpleName}"
                        else -> "Send failed: ${e.message ?: e::class.java.simpleName}"
                    }
                    val isError = e !is AgentRequestCancelledException || !wasCancelling
                    if (command != null) {
                        finishCommandOutput(
                            command = command,
                            status = if (isError) "Failed" else "Stopped",
                            fallbackBody = message,
                            isError = isError,
                        )
                    } else {
                        reportRequestOutcome(
                            message = message,
                            isError = isError,
                            useStopPanel = wasCancelling,
                        )
                    }
                }
        }
    }

    fun cancelCurrentRequest() = cancelCurrentRequest(command = null)

    private fun cancelCurrentRequest(command: String?) {
        if (command != null) {
            showCommandOutput(
                command = command,
                status = "Stopping…",
                running = true,
            )
        }
        val state = _ui.value
        if (!state.requestInFlight) {
            if (command != null) {
                finishCommandOutput(command, status = null, fallbackBody = "No request is running.")
            } else {
                pushSystem("No request is running.")
            }
            return
        }
        if (state.cancellationRequested) return
        if (!backend.isConnected()) {
            finishActiveRequest()
            if (command != null) {
                finishCommandOutput(
                    command = command,
                    status = "Failed",
                    fallbackBody = "Connection is no longer active.",
                    isError = true,
                )
            } else {
                pushSystem("Connection is no longer active.")
            }
            return
        }

        _ui.update { it.copy(cancellationRequested = true) }
        viewModelScope.launch {
            runCatching { backend.cancelCurrentRequest() }
                .onSuccess { sent ->
                    if (!sent && _ui.value.requestInFlight && _ui.value.cancellationRequested) {
                        _ui.update { it.copy(cancellationRequested = false) }
                        val message = "Couldn't stop request: no active agent session."
                        if (command != null) {
                            finishCommandOutput(
                                command = command,
                                status = "Failed",
                                fallbackBody = message,
                                isError = true,
                            )
                        } else {
                            pushSystem(message)
                        }
                    } else if (sent && _ui.value.requestInFlight && _ui.value.cancellationRequested) {
                        markInFlightToolsCancelled()
                    }
                }
                .onFailure { e ->
                    if (_ui.value.requestInFlight && _ui.value.cancellationRequested) {
                        _ui.update { it.copy(cancellationRequested = false) }
                        val message = "Couldn't stop request: ${e.message ?: e::class.java.simpleName}"
                        if (command != null) {
                            finishCommandOutput(
                                command = command,
                                status = "Failed",
                                fallbackBody = message,
                                isError = true,
                            )
                        } else {
                            pushSystem(message)
                        }
                    }
                }
        }
    }

    fun saveSettings(
        networkProfile: NetworkProfile,
        durableLanBaseUrl: String,
        durableTailnetBaseUrl: String,
        durableHostToken: String,
        workingDirectory: String,
        codexFullAccess: Boolean,
    ) {
        viewModelScope.launch {
            settingsRepo.update {
                it.copy(
                    networkProfile = networkProfile,
                    durableLanBaseUrl = durableLanBaseUrl.trim(),
                    durableTailnetBaseUrl = durableTailnetBaseUrl.trim(),
                    durableHostToken = durableHostToken,
                    workingDirectory = workingDirectory.trim()
                        .ifBlank { AppSettings.DEFAULT_CWD },
                    codexFullAccess = codexFullAccess,
                )
            }
            // Refresh list with new cwd/secret/url
            if (_ui.value.screen == AppScreen.HOME) {
                refreshSessions()
            }
        }
    }

    fun setNetworkProfile(profile: NetworkProfile) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(networkProfile = profile) }
        }
    }

    fun selectModel(model: AgentModelOption) {
        val state = _ui.value
        if (state.connection !is ConnectionState.Connected ||
            state.busy ||
            state.requestInFlight ||
            state.modelSelectionBusy ||
            model.id == state.usage.modelId
        ) {
            return
        }
        val selectedBackend = activeBackendKind
        _ui.update { it.copy(modelSelectionBusy = true) }
        viewModelScope.launch {
            runCatching { durableBackend(selectedBackend).selectModel(model.id) }
                .onSuccess { usage ->
                    if (selectedBackend != activeBackendKind) return@onSuccess
                    _ui.update { current ->
                        current.copy(
                            modelSelectionBusy = false,
                            usage = current.usage.copy(
                                modelId = usage.modelId,
                                modelName = usage.modelName,
                                reasoningEffort = usage.reasoningEffort,
                                contextWindowTokens = usage.contextWindowTokens,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    if (selectedBackend != activeBackendKind) return@onFailure
                    _ui.update { it.copy(modelSelectionBusy = false) }
                    pushSystem(
                        "Couldn't change model: ${error.message ?: error::class.java.simpleName}",
                    )
                }
        }
    }

    private fun refreshModelOptions() {
        val selectedBackend = activeBackendKind
        viewModelScope.launch {
            runCatching { durableBackend(selectedBackend).fetchModels() }
                .onSuccess { models ->
                    if (selectedBackend == activeBackendKind &&
                        _ui.value.screen == AppScreen.CHAT
                    ) {
                        _ui.update { it.copy(modelOptions = models) }
                    }
                }
        }
    }

    private fun beginConnectionAction(sessionId: String?, cwd: String): Long {
        stopSessionStatusPolling()
        reconnectJob?.cancel()
        reconnectJob = null
        connectionDesired = true
        hasConnectedSession = false
        activeSessionIdForReconnect = sessionId
        activeSessionCwdForReconnect = cwd
        return ++connectionActionGeneration
    }

    private fun stopConnectionIntent(clearSession: Boolean) {
        connectionActionGeneration += 1
        connectionDesired = false
        reconnectJob?.cancel()
        reconnectJob = null
        if (clearSession) {
            hasConnectedSession = false
            activeSessionIdForReconnect = null
            activeSessionCwdForReconnect = AppSettings.DEFAULT_CWD
        }
    }

    private fun startReconnect(connectionAction: Long) {
        val sessionId = activeSessionIdForReconnect ?: return
        if (reconnectJob?.isActive == true) return

        val retainedMessages = _ui.value.messages
        val retainedUsage = _ui.value.usage
        val retainedCommands = _ui.value.slashCommands
        _ui.update {
            it.copy(
                busy = false,
                reconnecting = true,
                canReconnect = true,
                connection = ConnectionState.Connecting,
                statusLine = "Connection lost · reconnecting…",
            )
        }

        reconnectJob = viewModelScope.launch {
            val retryDelaysMillis = longArrayOf(0, 1_000, 3_000)

            for ((attemptIndex, retryDelay) in retryDelaysMillis.withIndex()) {
                if (retryDelay > 0) delay(retryDelay)
                if (connectionAction != connectionActionGeneration ||
                    !connectionDesired ||
                    !appInForeground
                ) {
                    return@launch
                }

                clearChatLocal()
                _ui.update {
                    it.copy(
                        messages = emptyList(),
                        historyLoading = true,
                        usage = AgentUsage(),
                        modelOptions = emptyList(),
                        modelSelectionBusy = false,
                        slashCommands = localSlashCommandsFor(),
                        busy = false,
                        reconnecting = true,
                        canReconnect = true,
                        connection = ConnectionState.Connecting,
                        statusLine = if (attemptIndex == 0) {
                            "Reconnecting…"
                        } else {
                            "Reconnecting · attempt ${attemptIndex + 1}"
                        },
                    )
                }

                try {
                    val settings = _ui.value.settings
                    val completed = withTimeoutOrNull(RECONNECT_ATTEMPT_TIMEOUT_MILLIS) {
                        backend.connectLoad(
                            baseUrl = settings.activeDurableBaseUrl,
                            secret = settings.durableHostToken,
                            workingDirectory = activeSessionCwdForReconnect,
                            sessionId = sessionId,
                        )
                        true
                    }
                    check(completed == true) { "Reconnect attempt timed out" }
                    if (connectionAction == connectionActionGeneration && connectionDesired) {
                        _ui.update { it.copy(busy = false, reconnecting = false, canReconnect = true) }
                    }
                    return@launch
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (connectionAction != connectionActionGeneration || !connectionDesired) {
                        return@launch
                    }
                    _ui.update {
                        it.copy(
                            messages = retainedMessages,
                            historyLoading = false,
                            usage = retainedUsage,
                            slashCommands = retainedCommands,
                            busy = false,
                            reconnecting = true,
                            canReconnect = true,
                            connection = ConnectionState.Connecting,
                        )
                    }
                }
            }

            if (connectionAction == connectionActionGeneration && connectionDesired) {
                connectionDesired = false
                _ui.update {
                    it.copy(
                        busy = false,
                        reconnecting = false,
                        canReconnect = true,
                        connection = ConnectionState.Error("Connection lost"),
                        statusLine = "Connection lost · tap link to retry",
                        historyLoading = false,
                    )
                }
            }
        }
    }

    private fun clearChatLocal() {
        nextPromptGeneration += 1
        activePromptGeneration = null
        activeSlashCommand = null
        activeCommandMessages.clear()
        suppressLoadedSlashTurn = false
        loadedPromptIndex = null
        streamingUserId = null
        streamingAssistantId = null
        streamingThoughtId = null
        suppressUserEcho = false
        latestCodexStatus = null
    }

    private fun runLocalSlashCommand(text: String): Boolean {
        val command = slashCommandName(text) ?: return false
        when (command) {
            "stop", "cancel" -> {
                _ui.update { it.copy(draft = "") }
                cancelCurrentRequest(command)
            }
            "new", "clear" -> {
                _ui.update { it.copy(draft = "") }
                startNewSessionFromCommand()
            }
            "home", "welcome" -> {
                _ui.update { it.copy(draft = "") }
                goHome()
            }
            "disconnect" -> {
                _ui.update { it.copy(draft = "") }
                disconnect()
            }
            "context", "usage", "session-info" -> {
                if (activeBackendKind == BackendKind.GROK_BUILD) return false
                _ui.update { it.copy(draft = "") }
                showUsageOutput(command)
            }
            "status" -> {
                if (activeBackendKind != BackendKind.CODEX) return false
                _ui.update { it.copy(draft = "") }
                showStatusOutput()
            }
            "help", "?" -> showSlashHelp()
            else -> return false
        }
        return true
    }

    private fun startNewSessionFromCommand() {
        val departingBackend = backend
        stopConnectionIntent(clearSession = true)
        clearChatLocal()
        _ui.update {
            it.copy(
                busy = true,
                requestInFlight = false,
                cancellationRequested = false,
                reconnecting = false,
            )
        }
        viewModelScope.launch {
            // Detach from the old durable turn; the host keeps owning it.
            runCatching { departingBackend.disconnect() }
            openNewSession()
        }
    }

    private fun slashCommandName(text: String): String? {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith('/')) return null
        return trimmed
            .drop(1)
            .takeWhile { !it.isWhitespace() }
            .lowercase()
            .ifBlank { null }
    }

    private fun showSlashHelp() {
        _ui.update { it.copy(draft = "") }
        val commands = _ui.value.slashCommands
            .sortedBy { it.name.lowercase() }
        val help = buildString {
            appendLine("# Slash commands")
            appendLine()
            commands.forEach { command ->
                append("- `/${command.name}")
                command.argumentHint?.let { append(" <$it>") }
                append("` — ${command.description}")
                if (command.source == SlashCommandSource.APP) append(" (app)")
                appendLine()
            }
            appendLine()
            append("Other input is sent to ${activeBackendKind.displayName} unchanged.")
        }.trim()
        showCommandOutput(command = "help", body = help)
    }

    private fun showUsageOutput(command: String) {
        val usage = _ui.value.usage
        showCommandOutput(
            command = command,
            body = usageBody(usage),
            progress = usageProgress(usage),
        )
    }

    private fun showStatusOutput() {
        showCommandOutput(
            command = "status",
            status = "Refreshing Codex status…",
            running = true,
        )
        viewModelScope.launch {
            runCatching { codexBackend.fetchCodexStatus() }
                .onSuccess { status ->
                    latestCodexStatus = status
                    mergeUsage(
                        AgentUsage(
                            usedTokens = status.contextUsedTokens,
                            contextWindowTokens = status.contextWindowTokens,
                            modelId = status.modelId,
                            modelName = status.modelName,
                            reasoningEffort = status.reasoningEffort,
                        ),
                    )
                    val usage = _ui.value.usage
                    showCommandOutput(
                        command = "status",
                        body = statusBody(usage, status),
                        progress = usageProgress(usage),
                    )
                }
                .onFailure { error ->
                    val usage = _ui.value.usage
                    showCommandOutput(
                        command = "status",
                        body = buildString {
                            append(statusBody(usage, null))
                            appendLine()
                            appendLine()
                            appendLine("**Live account limits unavailable**")
                            append(error.message ?: error::class.java.simpleName)
                        },
                        status = "Refresh failed",
                        isError = true,
                        progress = usageProgress(usage),
                    )
                }
        }
    }

    private fun statusBody(
        usage: AgentUsage,
        status: CodexStatusSnapshot? = latestCodexStatus,
    ): String = buildString {
        append("# OpenAI Codex")
        status?.cliVersion?.let { append(" v$it") }
        appendLine()
        appendLine()
        appendLine("[Codex usage and credits](https://chatgpt.com/codex/settings/usage)")
        appendLine()
        appendLine("**Model**")
        val model = status?.modelName ?: usage.modelName ?: status?.modelId ?: usage.modelId
        val modelDetails = buildList {
            (status?.reasoningEffort ?: usage.reasoningEffort)?.let {
                add("reasoning ${it.lowercase()}")
            }
            status?.reasoningSummary?.let { add("summaries ${it.lowercase()}") }
        }
        append(model?.let { "`$it`" } ?: "Not reported")
        if (modelDetails.isNotEmpty()) append(" (${modelDetails.joinToString()})")
        status?.modelProvider?.takeIf { it.isNotBlank() && it != "openai" }?.let { provider ->
            appendLine()
            append("Provider: $provider")
        }
        appendLine()
        appendLine()
        appendLine("**Permissions**")
        appendLine(permissionSummary(status))
        appendLine()
        appendLine("**Working directory**")
        val connection = _ui.value.connection as? ConnectionState.Connected
        val cwd = status?.cwd
            ?.takeIf { it.isNotBlank() }
            ?: connection?.cwd
            ?.takeIf { it.isNotBlank() }
            ?: _ui.value.settings.workingDirectory.ifBlank { AppSettings.DEFAULT_CWD }
        appendLine("`$cwd`")
        appendLine()
        appendLine("**Agents.md**")
        val agentsFiles = status?.agentsFiles.orEmpty()
        if (agentsFiles.isEmpty()) {
            appendLine("<none>")
        } else {
            appendLine(agentsFiles.joinToString { "`$it`" })
        }

        status?.accountType?.let { accountType ->
            appendLine()
            appendLine("**Account**")
            val account = buildString {
                append(status.accountEmail ?: if (accountType == "apiKey") "API key" else "ChatGPT")
                status.accountPlanType?.let { plan -> append(" (${formatPlanType(plan)})") }
            }
            appendLine(account)
        }
        status?.collaborationMode?.let {
            appendLine()
            appendLine("**Collaboration mode**")
            appendLine(it.replaceFirstChar(Char::uppercase))
        }
        status?.threadName?.let {
            appendLine()
            appendLine("**Thread name**")
            appendLine(it)
        }
        val sessionId = status?.sessionId ?: connection?.sessionId
        sessionId?.let {
            appendLine()
            appendLine("**Session**")
            appendLine("`$it`")
        }
        status?.forkedFrom?.let {
            appendLine()
            appendLine("**Forked from**")
            appendLine("`$it`")
        }

        val contextUsed = usage.usedTokens ?: status?.contextUsedTokens
        val contextWindow = usage.contextWindowTokens ?: status?.contextWindowTokens
        if (contextUsed != null || contextWindow != null) {
            appendLine()
            appendLine("**Context window**")
            when {
                contextUsed != null && contextWindow != null && contextWindow > 0L -> {
                    val left = codexContextPercentRemaining(contextUsed, contextWindow)
                    appendLine(
                        "$left% left (${formatInteger(contextUsed)} used / " +
                            formatInteger(contextWindow) + ")",
                    )
                }
                contextUsed != null -> appendLine("${formatInteger(contextUsed)} tokens used")
                contextWindow != null -> appendLine("${formatInteger(contextWindow)} token capacity")
            }
        }

        if (status?.accountType != "chatgpt") {
            status?.totalTokenUsage?.let { tokenUsage ->
                val input = tokenUsage.inputTokens
                val cached = tokenUsage.cachedInputTokens ?: 0L
                val output = tokenUsage.outputTokens
                if (input != null || output != null) {
                    val nonCachedInput = ((input ?: 0L) - cached).coerceAtLeast(0L)
                    val blendedTotal = nonCachedInput + (output ?: 0L)
                    appendLine()
                    appendLine("**Token usage**")
                    appendLine(
                        "${formatInteger(blendedTotal)} total " +
                            "(${formatInteger(nonCachedInput)} input + " +
                            "${formatInteger(output ?: 0L)} output)",
                    )
                }
            }
        }

        status?.primaryRateLimit?.let { appendRateLimit(it, primary = true) }
        status?.secondaryRateLimit?.let { appendRateLimit(it, primary = false) }
        status?.credits?.let { credits ->
            val value = when {
                credits.unlimited -> "Unlimited"
                credits.hasCredits && !credits.balance.isNullOrBlank() -> "${credits.balance} credits"
                else -> null
            }
            value?.let {
                appendLine()
                appendLine("**Credits**")
                appendLine(it)
            }
        }
        status?.spendLimit?.let { limit ->
            appendLine()
            appendLine("**Monthly credit limit**")
            val remaining = limit.remainingPercent?.let { "$it% left" } ?: "Not reported"
            append(remaining)
            if (limit.used != null && limit.limit != null) {
                append(" · ${limit.used} of ${limit.limit} credits used")
            }
            limit.resetsAtEpochSeconds?.let { append(" · resets ${formatResetTime(it)}") }
            appendLine()
        }
        status?.rateLimitResetCreditsAvailable?.takeIf { it > 0L }?.let {
            appendLine()
            appendLine("**Usage-limit resets**")
            appendLine("$it available")
        }
    }.trim()

    private fun StringBuilder.appendRateLimit(window: CodexRateLimitWindow, primary: Boolean) {
        val used = window.usedPercent?.coerceIn(0, 100) ?: return
        val remaining = 100 - used
        val label = when (window.windowDurationMinutes) {
            300L -> "5h limit"
            10_080L -> "Weekly limit"
            else -> if (primary) "Usage limit" else "Secondary usage limit"
        }
        appendLine()
        appendLine("**$label**")
        append("`${rateLimitBar(remaining)}` $remaining% left")
        window.resetsAtEpochSeconds?.let { append(" · resets ${formatResetTime(it)}") }
        appendLine()
    }

    private fun permissionSummary(status: CodexStatusSnapshot?): String {
        val sandbox = when (status?.sandboxMode) {
            "workspace-write" -> if (status.networkAccess == true) {
                "workspace with network access"
            } else {
                "workspace"
            }
            "read-only" -> "read only"
            "danger-full-access" -> "full access"
            null -> "workspace"
            else -> status.sandboxMode.replace('-', ' ')
        }
        val approval = when (status?.approvalPolicy) {
            "never" -> "never"
            "on-request" -> "ask for approval"
            null -> "never"
            else -> status.approvalPolicy.replace('-', ' ')
        }
        return "Custom ($sandbox, $approval)"
    }

    private fun formatPlanType(value: String): String = when (value.lowercase()) {
        "self_serve_business_usage_based", "team" -> "Business"
        "enterprise_cbp_usage_based", "enterprise" -> "Enterprise"
        else -> value.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }

    private fun codexContextPercentRemaining(used: Long, window: Long): Int {
        val effectiveWindow = window - CODEX_CONTEXT_BASELINE_TOKENS
        if (effectiveWindow <= 0L) return 0
        val effectiveUsed = (used - CODEX_CONTEXT_BASELINE_TOKENS).coerceAtLeast(0L)
        val remaining = (effectiveWindow - effectiveUsed).coerceAtLeast(0L)
        return (remaining.toDouble() / effectiveWindow.toDouble() * 100.0)
            .roundToInt()
            .coerceIn(0, 100)
    }

    private fun rateLimitBar(percentRemaining: Int): String {
        val filled = (percentRemaining.coerceIn(0, 100) / 5.0).roundToInt().coerceIn(0, 20)
        return "[" + "█".repeat(filled) + "░".repeat(20 - filled) + "]"
    }

    private fun formatResetTime(epochSeconds: Long): String {
        val zone = ZoneId.systemDefault()
        val reset = Instant.ofEpochSecond(epochSeconds).atZone(zone)
        val now = ZonedDateTime.now(zone)
        val pattern = if (reset.toLocalDate() == now.toLocalDate()) "HH:mm" else "HH:mm 'on' d MMM"
        return reset.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    private fun usageBody(usage: AgentUsage): String = buildString {
        appendLine("**Model**")
        appendLine(
            when {
                usage.modelName != null && usage.modelId != null ->
                    "${usage.modelName} (`${usage.modelId}`)"
                usage.modelName != null -> usage.modelName
                usage.modelId != null -> "`${usage.modelId}`"
                else -> "Not reported"
            },
        )
        appendLine()
        appendLine("**Reasoning effort**")
        appendLine(
            when (usage.reasoningEffort?.lowercase()) {
                "xhigh" -> "XHigh"
                null -> "Not reported"
                else -> usage.reasoningEffort.replaceFirstChar { it.uppercase() }
            },
        )
        appendLine()
        appendLine("**Context window**")
        val used = usage.usedTokens
        val size = usage.contextWindowTokens
        when {
            used != null && size != null && size > 0L -> {
                val percent = used.toDouble() / size.toDouble() * 100.0
                appendLine(
                    "${formatInteger(used)} / ${formatInteger(size)} tokens " +
                        "(${"%.1f".format(Locale.getDefault(), percent)}%)",
                )
                appendLine("${formatInteger((size - used).coerceAtLeast(0L))} tokens remaining")
            }
            used != null -> appendLine("${formatInteger(used)} tokens used")
            size != null -> appendLine("${formatInteger(size)} token capacity; current use not reported")
            else -> appendLine("${activeBackendKind.displayName} has not reported token usage yet.")
        }

        if (usage.costAmount != null && usage.costCurrency != null) {
            appendLine()
            appendLine("**Session cost**")
            appendLine("${"%.4f".format(Locale.getDefault(), usage.costAmount)} ${usage.costCurrency}")
        }

        val connection = _ui.value.connection as? ConnectionState.Connected
        if (connection != null) {
            appendLine()
            appendLine("**Session**")
            append("`${connection.sessionId.take(8)}…`")
        }
    }.trim()

    private fun usageProgress(usage: AgentUsage): Float? {
        val used = usage.usedTokens ?: return null
        val size = usage.contextWindowTokens?.takeIf { it > 0L } ?: return null
        return (used.toDouble() / size.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    private fun formatInteger(value: Long): String =
        NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

    private fun showCommandOutput(
        command: String,
        body: String = "",
        status: String? = null,
        running: Boolean = false,
        isError: Boolean = false,
        progress: Float? = null,
    ) {
        _ui.update {
            it.copy(
                commandOutput = CommandOutputState(
                    command = command,
                    body = body,
                    status = status,
                    running = running,
                    isError = isError,
                    progress = progress,
                ),
            )
        }
    }

    private fun appendCommandOutput(command: String, event: AgentEvent.AssistantDelta) {
        val messageId = event.messageId
        val body = if (messageId.isNullOrBlank()) {
            null
        } else {
            val previous = activeCommandMessages[messageId].orEmpty()
            activeCommandMessages[messageId] = if (event.replace) {
                event.text
            } else {
                previous + event.text
            }
            activeCommandMessages.values.joinToString("")
        }
        _ui.update { state ->
            val current = state.commandOutput
            if (current == null || current.command != command) {
                state
            } else {
                state.copy(
                    commandOutput = current.copy(
                        body = body ?: current.body + event.text,
                        status = "Running…",
                        running = true,
                    ),
                )
            }
        }
    }

    private fun updateCommandStatus(command: String, status: String) {
        _ui.update { state ->
            val current = state.commandOutput
            if (current == null || current.command != command) {
                state
            } else {
                state.copy(commandOutput = current.copy(status = status, running = true))
            }
        }
    }

    private fun finishCommandOutput(
        command: String,
        status: String?,
        fallbackBody: String = "Command completed.",
        isError: Boolean = false,
    ) {
        _ui.update { state ->
            val current = state.commandOutput
            if (current == null || current.command != command) {
                state
            } else {
                val body = when {
                    current.body.isBlank() -> fallbackBody
                    isError && fallbackBody.isNotBlank() -> current.body + "\n\n" + fallbackBody
                    else -> current.body
                }
                state.copy(
                    commandOutput = current.copy(
                        body = body,
                        status = status,
                        running = false,
                        isError = isError,
                    ),
                )
            }
        }
    }

    private fun reportRequestOutcome(
        message: String,
        isError: Boolean = false,
        useStopPanel: Boolean = false,
    ) {
        val panelCommand = if (useStopPanel) {
            _ui.value.commandOutput
                ?.command
                ?.takeIf { it == "stop" || it == "cancel" }
        } else {
            null
        }
        if (panelCommand != null) {
            finishCommandOutput(
                command = panelCommand,
                status = if (isError) "Failed" else "Stopped",
                fallbackBody = message,
                isError = isError,
            )
        } else {
            pushSystem(message)
        }
    }

    private fun finishActiveRequest() {
        activePromptGeneration = null
        activeSlashCommand = null
        activeCommandMessages.clear()
        suppressUserEcho = false
        finalizeStreaming()
        _ui.update {
            it.copy(
                busy = false,
                requestInFlight = false,
                cancellationRequested = false,
            )
        }
    }

    private fun markInFlightToolsCancelled() {
        _ui.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.role == ChatRole.TOOL && message.streaming) {
                        message.copy(streaming = false, toolStatus = "cancelled")
                    } else {
                        message
                    }
                },
            )
        }
    }

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.ConnectionChanged -> {
                val connectionEnded = event.state is ConnectionState.Error ||
                    event.state is ConnectionState.Disconnected
                val requestWasInterrupted = connectionEnded && _ui.value.requestInFlight
                val interruptedCommand = activeSlashCommand
                if (requestWasInterrupted) {
                    activePromptGeneration = null
                    activeSlashCommand = null
                    activeCommandMessages.clear()
                    suppressLoadedSlashTurn = false
                    loadedPromptIndex = null
                    suppressUserEcho = false
                    finalizeStreaming()
                }

                if (event.state is ConnectionState.Connected) {
                    activeSessionIdForReconnect = event.state.sessionId
                    activeSessionCwdForReconnect = event.state.cwd
                        .ifBlank { activeSessionCwdForReconnect }
                    hasConnectedSession = true
                    connectionDesired = true
                    refreshModelOptions()
                }

                val shouldReconnect = connectionEnded &&
                    connectionDesired &&
                    hasConnectedSession &&
                    activeSessionIdForReconnect != null &&
                    _ui.value.screen == AppScreen.CHAT
                val reconnectAction = if (shouldReconnect && reconnectJob?.isActive != true) {
                    if (appInForeground) ++connectionActionGeneration else null
                } else {
                    null
                }
                val displayState = if (shouldReconnect) {
                    ConnectionState.Connecting
                } else if (event.state is ConnectionState.Error) {
                    ConnectionState.Error("Connection lost")
                } else {
                    event.state
                }
                val line = when (val st = displayState) {
                    ConnectionState.Disconnected -> if (activeSessionIdForReconnect != null) {
                        "Disconnected · tap link to reconnect"
                    } else {
                        "Disconnected"
                    }
                    ConnectionState.Connecting -> when {
                        shouldReconnect || _ui.value.reconnecting ->
                            "Connection lost · reconnecting…"
                        activeSessionIdForReconnect != null && !hasConnectedSession ->
                            "Loading session…"
                        else -> "Connecting…"
                    }
                    is ConnectionState.Connected -> {
                        val cwdShort = st.cwd.ifBlank { _ui.value.settings.workingDirectory }
                            .removePrefix("/home/").let { if (it == st.cwd) st.cwd else "~/$it" }
                        "Connected · ${st.sessionId.take(8)}… · $cwdShort"
                    }
                    is ConnectionState.Error -> if (activeSessionIdForReconnect != null) {
                        "Connection lost · tap link to retry"
                    } else {
                        "Connection error"
                    }
                }
                _ui.update {
                    it.copy(
                        connection = displayState,
                        statusLine = line,
                        busy = if (displayState is ConnectionState.Connecting) it.busy else false,
                        requestInFlight = if (requestWasInterrupted) false else it.requestInFlight,
                        cancellationRequested = if (requestWasInterrupted) false else it.cancellationRequested,
                        reconnecting = shouldReconnect ||
                            (displayState is ConnectionState.Connecting && it.reconnecting),
                        canReconnect = activeSessionIdForReconnect != null,
                        modelSelectionBusy = false,
                    )
                }
                if (requestWasInterrupted && interruptedCommand != null) {
                    finishCommandOutput(
                        command = interruptedCommand,
                        status = "Interrupted",
                        fallbackBody = "The connection closed before the command completed.",
                        isError = true,
                    )
                }
                reconnectAction?.let(::startReconnect)
            }

            is AgentEvent.SlashCommandsChanged -> {
                val commands = (localSlashCommandsFor() + event.commands)
                    .distinctBy { it.name.lowercase() }
                _ui.update { it.copy(slashCommands = commands) }
            }

            is AgentEvent.UsageChanged -> mergeUsage(event.usage)

            AgentEvent.TurnStarted -> {
                _ui.update {
                    it.copy(
                        busy = true,
                        requestInFlight = true,
                        cancellationRequested = false,
                        historyLoading = false,
                    )
                }
            }

            is AgentEvent.UserDelta -> {
                // Skip agent echo of the prompt we already rendered in [send].
                if (suppressUserEcho) return
                val startsNewPrompt = event.promptIndex == null ||
                    event.promptIndex != loadedPromptIndex
                if (startsNewPrompt) {
                    loadedPromptIndex = event.promptIndex
                    suppressLoadedSlashTurn = event.text.trimStart().startsWith('/')
                }
                if (suppressLoadedSlashTurn) return
                appendStreaming(
                    ChatRole.USER,
                    event.text,
                    attachments = event.attachments,
                )
            }
            is AgentEvent.AssistantDelta -> {
                val command = activeSlashCommand
                if (command != null) {
                    appendCommandOutput(command, event)
                } else if (!suppressLoadedSlashTurn) {
                    appendStreaming(
                        role = ChatRole.ASSISTANT,
                        delta = event.text,
                        messageId = event.messageId,
                        replace = event.replace,
                        completed = event.completed,
                    )
                }
            }
            is AgentEvent.ThoughtDelta -> {
                val command = activeSlashCommand
                if (command != null) {
                    updateCommandStatus(command, "Working…")
                } else if (!suppressLoadedSlashTurn) {
                    appendStreaming(
                        role = ChatRole.THOUGHT,
                        delta = event.text,
                        messageId = event.messageId,
                        replace = event.replace,
                        completed = event.completed,
                    )
                }
            }
            is AgentEvent.MessageCompleted -> {
                _ui.update { state ->
                    state.copy(
                        messages = state.messages.map { message ->
                            if (message.id == event.messageId) {
                                message.copy(streaming = false)
                            } else {
                                message
                            }
                        },
                    )
                }
            }
            is AgentEvent.ToolCall -> {
                val command = activeSlashCommand
                if (command != null) {
                    updateCommandStatus(command, event.title)
                } else if (!suppressLoadedSlashTurn) {
                    upsertTool(
                        toolCallId = event.toolCallId,
                        title = event.title,
                        status = event.status,
                        kind = event.kind,
                        detail = event.detail,
                    )
                }
            }
            is AgentEvent.ToolUpdate -> {
                val command = activeSlashCommand
                if (command != null) {
                    event.title?.let { updateCommandStatus(command, it) }
                } else if (!suppressLoadedSlashTurn) {
                    upsertTool(
                        toolCallId = event.toolCallId,
                        title = event.title,
                        status = event.status,
                        kind = event.kind,
                        detail = event.detail,
                    )
                }
            }

            is AgentEvent.TurnComplete -> {
                val wasCancelling = _ui.value.cancellationRequested
                val hadActiveRequest = _ui.value.requestInFlight
                val command = activeSlashCommand
                val stopReason = event.stopReason?.lowercase()
                val completedBeforeStop = wasCancelling &&
                    stopReason != null && stopReason != "cancelled"
                finishActiveRequest()
                if (stopReason == "loaded") {
                    _ui.update { it.copy(historyLoading = false) }
                } else if (appInForeground && _ui.value.screen == AppScreen.CHAT) {
                    activeSessionIdForReconnect?.let {
                        markSessionRead(it, activeBackendKind)
                    }
                }
                suppressLoadedSlashTurn = false
                loadedPromptIndex = null
                if (command != null) {
                    finishCommandOutput(
                        command = command,
                        status = when {
                            completedBeforeStop -> "Completed"
                            wasCancelling -> "Stopped"
                            else -> "Completed"
                        },
                        fallbackBody = when {
                            completedBeforeStop -> "Request completed before it could be stopped."
                            wasCancelling -> "Request stopped."
                            else -> "Command completed."
                        },
                    )
                }
                if (wasCancelling && hadActiveRequest) {
                    val message = if (completedBeforeStop) {
                        "Request completed before it could be stopped."
                    } else {
                        "Request stopped."
                    }
                    val stopPanelVisible = _ui.value.commandOutput
                        ?.command
                        ?.let { it == "stop" || it == "cancel" } == true
                    if (command == null || stopPanelVisible) {
                        reportRequestOutcome(message, useStopPanel = true)
                    }
                }
            }

            is AgentEvent.Error -> {
                val command = activeSlashCommand
                val wasCancelling = _ui.value.cancellationRequested
                finishActiveRequest()
                _ui.update { it.copy(historyLoading = false) }
                suppressLoadedSlashTurn = false
                loadedPromptIndex = null
                if (command != null) {
                    finishCommandOutput(
                        command = command,
                        status = "Failed",
                        fallbackBody = event.message,
                        isError = true,
                    )
                } else {
                    reportRequestOutcome(
                        message = event.message,
                        isError = true,
                        useStopPanel = wasCancelling,
                    )
                }
            }
        }
    }

    private fun mergeUsage(update: AgentUsage) {
        _ui.update { state ->
            val merged = state.usage.copy(
                usedTokens = update.usedTokens ?: state.usage.usedTokens,
                contextWindowTokens = update.contextWindowTokens
                    ?: state.usage.contextWindowTokens,
                modelId = update.modelId ?: state.usage.modelId,
                modelName = update.modelName ?: state.usage.modelName,
                reasoningEffort = update.reasoningEffort ?: state.usage.reasoningEffort,
                costAmount = update.costAmount ?: state.usage.costAmount,
                costCurrency = update.costCurrency ?: state.usage.costCurrency,
            )
            if (merged == state.usage) return@update state
            val output = state.commandOutput
            val updatedOutput = if (
                output != null && !output.running && output.command in informationCommands
            ) {
                output.copy(
                    body = if (output.command == "status") {
                        statusBody(merged, latestCodexStatus)
                    } else {
                        usageBody(merged)
                    },
                    progress = usageProgress(merged),
                )
            } else {
                output
            }
            state.copy(usage = merged, commandOutput = updatedOutput)
        }
    }

    private fun appendStreaming(
        role: ChatRole,
        delta: String,
        messageId: String? = null,
        replace: Boolean = false,
        completed: Boolean = false,
        attachments: List<ImageAttachment> = emptyList(),
    ) {
        if (!messageId.isNullOrBlank()) {
            _ui.update { state ->
                val existingIndex = state.messages.indexOfLast {
                    it.id == messageId && it.role == role
                }
                if (existingIndex >= 0) {
                    state.copy(
                        messages = state.messages.toMutableList().also { messages ->
                            val previous = messages[existingIndex]
                            messages[existingIndex] = previous.copy(
                                text = if (replace) delta else previous.text + delta,
                                streaming = !completed,
                                attachments = if (attachments.isNotEmpty()) {
                                    attachments
                                } else {
                                    previous.attachments
                                },
                            )
                        },
                    )
                } else {
                    state.copy(
                        messages = state.messages.map { message ->
                            if (message.role == role && message.streaming) {
                                message.copy(streaming = false)
                            } else {
                                message
                            }
                        } + ChatMessage(
                            id = messageId,
                            role = role,
                            text = delta,
                            streaming = !completed,
                            attachments = attachments,
                        ),
                    )
                }
            }
            return
        }
        val idField = when (role) {
            ChatRole.USER -> ::streamingUserId
            ChatRole.ASSISTANT -> ::streamingAssistantId
            ChatRole.THOUGHT -> ::streamingThoughtId
            else -> {
                appendStatic(role, delta)
                return
            }
        }
        val last = _ui.value.messages.lastOrNull()
        val existingId = idField.get()
        // Append only when the last bubble is still this open stream (avoids merging turns on load).
        val canAppend = existingId != null && last?.id == existingId && last.streaming
        if (!canAppend) {
            // Seal any previous open stream for this role.
            idField.set(null)
            val id = UUID.randomUUID().toString()
            idField.set(id)
            _ui.update {
                it.copy(
                    messages = it.messages.map { msg ->
                        if (msg.streaming && msg.role == role) msg.copy(streaming = false) else msg
                    } + ChatMessage(
                        id = id,
                        role = role,
                        text = delta,
                        streaming = true,
                        attachments = attachments,
                    ),
                )
            }
        } else {
            _ui.update { state ->
                state.copy(
                    messages = state.messages.map { msg ->
                        if (msg.id == existingId) msg.copy(text = msg.text + delta) else msg
                    },
                )
            }
        }
    }

    private fun finalizeStreaming() {
        val ids = listOfNotNull(streamingUserId, streamingAssistantId, streamingThoughtId)
        streamingUserId = null
        streamingAssistantId = null
        streamingThoughtId = null
        _ui.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (
                        msg.id in ids ||
                        (
                            msg.streaming &&
                                (msg.role == ChatRole.USER ||
                                    msg.role == ChatRole.ASSISTANT ||
                                    msg.role == ChatRole.THOUGHT)
                        )
                    ) {
                        msg.copy(streaming = false)
                    } else {
                        msg
                    }
                },
            )
        }
    }

    private fun appendStatic(role: ChatRole, text: String) {
        _ui.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = role,
                    text = text,
                ),
            )
        }
    }

    /**
     * Merge tool_call / tool_call_update by ACP toolCallId so the timeline
     * shows one collapsible row per tool instead of hundreds of status pings.
     */
    private fun upsertTool(
        toolCallId: String?,
        title: String?,
        status: String?,
        kind: String?,
        detail: String?,
    ) {
        val id = toolCallId?.takeIf { it.isNotBlank() }
        _ui.update { state ->
            val existingIndex = if (id != null) {
                state.messages.indexOfLast { it.role == ChatRole.TOOL && it.toolCallId == id }
            } else {
                -1
            }
            if (existingIndex >= 0) {
                val prev = state.messages[existingIndex]
                val merged = prev.copy(
                    text = title?.takeIf { it.isNotBlank() } ?: prev.text,
                    toolStatus = status?.takeIf { it.isNotBlank() } ?: prev.toolStatus,
                    toolKind = kind?.takeIf { it.isNotBlank() } ?: prev.toolKind,
                    detail = when {
                        detail.isNullOrBlank() -> prev.detail
                        prev.detail.isNullOrBlank() -> detail
                        detail.length >= prev.detail.length -> detail
                        else -> prev.detail
                    },
                    streaming = status == "in_progress" || status == "pending",
                )
                state.copy(
                    messages = state.messages.toMutableList().also { it[existingIndex] = merged },
                )
            } else {
                state.copy(
                    messages = state.messages + ChatMessage(
                        id = id ?: UUID.randomUUID().toString(),
                        role = ChatRole.TOOL,
                        text = title?.ifBlank { kind ?: "tool" } ?: (kind ?: "tool"),
                        toolCallId = id,
                        toolStatus = status,
                        toolKind = kind,
                        detail = detail,
                        streaming = status == "in_progress" || status == "pending",
                    ),
                )
            }
        }
    }

    private fun pushSystem(text: String) = appendStatic(ChatRole.SYSTEM, text)

    override fun onCleared() {
        stopSessionStatusPolling()
        eventsJobs.forEach(Job::cancel)
        grokBackend.shutdown()
        codexBackend.shutdown()
        super.onCleared()
    }

    private companion object {
        const val MAX_IMAGE_ATTACHMENTS = 4
        const val MAX_IMAGE_BYTES = 20L * 1024L * 1024L
        val SUPPORTED_IMAGE_MIME_TYPES = setOf(
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp",
        )
        const val CODEX_CONTEXT_BASELINE_TOKENS = 12_000L
        const val RECONNECT_ATTEMPT_TIMEOUT_MILLIS = 25_000L
        const val SESSION_STATUS_POLL_INTERVAL_MILLIS = 2_000L
    }
}
