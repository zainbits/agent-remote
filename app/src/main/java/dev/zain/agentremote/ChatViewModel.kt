package dev.zain.agentremote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zain.agentremote.agent.AgentEvent
import dev.zain.agentremote.agent.ChatMessage
import dev.zain.agentremote.agent.ChatRole
import dev.zain.agentremote.agent.ConnectionState
import dev.zain.agentremote.agent.GrokAcpClient
import dev.zain.agentremote.agent.SessionIndexClient
import dev.zain.agentremote.agent.SessionSummary
import dev.zain.agentremote.data.AppSettings
import dev.zain.agentremote.data.BackendKind
import dev.zain.agentremote.data.NetworkProfile
import dev.zain.agentremote.data.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class AppScreen {
    HOME,
    CHAT,
}

data class ChatUiState(
    val settings: AppSettings = AppSettings(),
    val screen: AppScreen = AppScreen.HOME,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val busy: Boolean = false,
    val statusLine: String = "Disconnected",
    val sessions: List<SessionSummary> = emptyList(),
    val sessionsLoading: Boolean = false,
    val sessionsError: String? = null,
    val activeSessionTitle: String? = null,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepo = SettingsRepository(application)
    private val backend = GrokAcpClient()
    private val sessionIndex = SessionIndexClient()

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private var eventsJob: Job? = null
    private var streamingUserId: String? = null
    private var streamingAssistantId: String? = null
    private var streamingThoughtId: String? = null

    val settingsFlow = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    init {
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                _ui.update { it.copy(settings = s) }
            }
        }
        eventsJob = viewModelScope.launch {
            backend.events().collect { event -> handleEvent(event) }
        }
        // Initial session list once settings load
        viewModelScope.launch {
            settingsRepo.settings.collect {
                // only auto-refresh when on home and we have a secret
                if (_ui.value.screen == AppScreen.HOME &&
                    _ui.value.settings.agentSecret.isNotBlank() &&
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

    fun refreshSessions() {
        val s = _ui.value.settings
        if (s.agentSecret.isBlank()) {
            _ui.update {
                it.copy(
                    sessionsError = "Set the agent secret in Settings first.",
                    sessions = emptyList(),
                )
            }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(sessionsLoading = true, sessionsError = null) }
            runCatching {
                sessionIndex.listSessions(
                    agentBaseUrl = s.activeBaseUrl,
                    secret = s.agentSecret,
                    cwd = s.workingDirectory.ifBlank { AppSettings.DEFAULT_CWD },
                )
            }.onSuccess { list ->
                _ui.update {
                    it.copy(sessions = list, sessionsLoading = false, sessionsError = null)
                }
            }.onFailure { e ->
                _ui.update {
                    it.copy(
                        sessionsLoading = false,
                        sessionsError = e.message ?: "Failed to load sessions",
                    )
                }
            }
        }
    }

    fun openNewSession() {
        val s = _ui.value.settings
        if (s.backendKind != BackendKind.GROK_BUILD) {
            pushSystem("Only Grok Build is implemented in this version.")
            return
        }
        if (s.agentSecret.isBlank()) {
            pushSystem("Set the agent secret in Settings first.")
            return
        }
        viewModelScope.launch {
            clearChatLocal()
            _ui.update {
                it.copy(
                    screen = AppScreen.CHAT,
                    busy = true,
                    statusLine = "Connecting…",
                    activeSessionTitle = "New chat",
                    messages = emptyList(),
                )
            }
            runCatching {
                backend.connectNew(
                    baseUrl = s.activeBaseUrl,
                    secret = s.agentSecret,
                    workingDirectory = s.workingDirectory.ifBlank { AppSettings.DEFAULT_CWD },
                )
            }.onFailure { e ->
                pushSystem("Connect failed: ${e.message ?: e::class.java.simpleName}")
                _ui.update {
                    it.copy(
                        busy = false,
                        connection = ConnectionState.Error(e.message ?: "error"),
                        statusLine = "Error",
                    )
                }
            }.onSuccess {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun openSession(session: SessionSummary) {
        val s = _ui.value.settings
        if (s.agentSecret.isBlank()) {
            _ui.update { it.copy(sessionsError = "Set the agent secret in Settings first.") }
            return
        }
        viewModelScope.launch {
            clearChatLocal()
            _ui.update {
                it.copy(
                    screen = AppScreen.CHAT,
                    busy = true,
                    statusLine = "Loading session…",
                    activeSessionTitle = session.title,
                    messages = emptyList(),
                )
            }
            val cwd = session.cwd.ifBlank {
                s.workingDirectory.ifBlank { AppSettings.DEFAULT_CWD }
            }
            runCatching {
                backend.connectLoad(
                    baseUrl = s.activeBaseUrl,
                    secret = s.agentSecret,
                    workingDirectory = cwd,
                    sessionId = session.sessionId,
                )
            }.onFailure { e ->
                pushSystem("Load failed: ${e.message ?: e::class.java.simpleName}")
                _ui.update {
                    it.copy(
                        busy = false,
                        connection = ConnectionState.Error(e.message ?: "error"),
                        statusLine = "Error",
                    )
                }
            }.onSuccess {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun goHome() {
        viewModelScope.launch {
            runCatching { backend.disconnect() }
            clearChatLocal()
            _ui.update {
                it.copy(
                    screen = AppScreen.HOME,
                    busy = false,
                    connection = ConnectionState.Disconnected,
                    statusLine = "Disconnected",
                    messages = emptyList(),
                    draft = "",
                    activeSessionTitle = null,
                )
            }
            refreshSessions()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            runCatching { backend.disconnect() }
            clearChatLocal()
            _ui.update {
                it.copy(
                    busy = false,
                    connection = ConnectionState.Disconnected,
                    statusLine = "Disconnected",
                )
            }
        }
    }

    fun send() {
        val text = _ui.value.draft.trim()
        if (text.isEmpty()) return
        if (!backend.isConnected()) {
            pushSystem("Not connected.")
            return
        }
        _ui.update {
            it.copy(
                draft = "",
                busy = true,
                messages = it.messages + ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = ChatRole.USER,
                    text = text,
                ),
            )
        }
        streamingUserId = null
        streamingAssistantId = null
        streamingThoughtId = null
        viewModelScope.launch {
            runCatching { backend.sendPrompt(text) }
                .onFailure { e ->
                    pushSystem("Send failed: ${e.message ?: e::class.java.simpleName}")
                    _ui.update { it.copy(busy = false) }
                }
        }
    }

    fun saveSettings(
        networkProfile: NetworkProfile,
        lanBaseUrl: String,
        tailnetBaseUrl: String,
        agentSecret: String,
        workingDirectory: String,
    ) {
        viewModelScope.launch {
            settingsRepo.update {
                it.copy(
                    networkProfile = networkProfile,
                    lanBaseUrl = lanBaseUrl.trim(),
                    tailnetBaseUrl = tailnetBaseUrl.trim(),
                    agentSecret = agentSecret,
                    workingDirectory = workingDirectory.trim()
                        .ifBlank { AppSettings.DEFAULT_CWD },
                    backendKind = BackendKind.GROK_BUILD,
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

    private fun clearChatLocal() {
        streamingUserId = null
        streamingAssistantId = null
        streamingThoughtId = null
    }

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.ConnectionChanged -> {
                val line = when (val st = event.state) {
                    ConnectionState.Disconnected -> "Disconnected"
                    ConnectionState.Connecting -> "Connecting…"
                    is ConnectionState.Connected -> {
                        val cwdShort = st.cwd.ifBlank { _ui.value.settings.workingDirectory }
                            .removePrefix("/home/").let { if (it == st.cwd) st.cwd else "~/$it" }
                        "Connected · ${st.sessionId.take(8)}… · $cwdShort"
                    }
                    is ConnectionState.Error -> "Error · ${st.message}"
                }
                _ui.update { it.copy(connection = event.state, statusLine = line, busy = false) }
            }

            is AgentEvent.UserDelta -> appendStreaming(ChatRole.USER, event.text)
            is AgentEvent.AssistantDelta -> appendStreaming(ChatRole.ASSISTANT, event.text)
            is AgentEvent.ThoughtDelta -> appendStreaming(ChatRole.THOUGHT, event.text)
            is AgentEvent.ToolCall -> {
                appendStatic(
                    ChatRole.TOOL,
                    "⚙ ${event.title}" + (event.status?.let { " · $it" } ?: ""),
                )
            }

            is AgentEvent.ToolUpdate -> {
                if (event.status != null || event.title != null) {
                    appendStatic(
                        ChatRole.TOOL,
                        "⚙ ${event.title ?: "tool"}" + (event.status?.let { " · $it" } ?: ""),
                    )
                }
            }

            is AgentEvent.TurnComplete -> {
                finalizeStreaming()
                _ui.update { it.copy(busy = false) }
            }

            is AgentEvent.Error -> {
                pushSystem(event.message)
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    private fun appendStreaming(role: ChatRole, delta: String) {
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
        if (ids.isEmpty()) return
        _ui.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (msg.id in ids) msg.copy(streaming = false) else msg
                },
            )
        }
    }

    private fun appendStatic(role: ChatRole, text: String) {
        // When a new role block starts mid-stream, seal previous streaming blobs of other roles.
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

    private fun pushSystem(text: String) = appendStatic(ChatRole.SYSTEM, text)

    override fun onCleared() {
        eventsJob?.cancel()
        backend.shutdown()
        super.onCleared()
    }
}
