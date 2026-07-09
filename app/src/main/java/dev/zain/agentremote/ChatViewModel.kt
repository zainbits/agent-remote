package dev.zain.agentremote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zain.agentremote.agent.AgentEvent
import dev.zain.agentremote.agent.ChatMessage
import dev.zain.agentremote.agent.ChatRole
import dev.zain.agentremote.agent.ConnectionState
import dev.zain.agentremote.agent.GrokAcpClient
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

data class ChatUiState(
    val settings: AppSettings = AppSettings(),
    val connection: ConnectionState = ConnectionState.Disconnected,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val busy: Boolean = false,
    val statusLine: String = "Disconnected",
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepo = SettingsRepository(application)
    private val backend = GrokAcpClient()

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private var eventsJob: Job? = null
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
    }

    fun onDraftChange(value: String) {
        _ui.update { it.copy(draft = value) }
    }

    fun connect() {
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
            _ui.update { it.copy(busy = true, statusLine = "Connecting…") }
            runCatching {
                backend.connect(
                    baseUrl = s.activeBaseUrl,
                    secret = s.agentSecret,
                    workingDirectory = s.workingDirectory,
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

    fun disconnect() {
        viewModelScope.launch {
            runCatching { backend.disconnect() }
            streamingAssistantId = null
            streamingThoughtId = null
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
            pushSystem("Not connected. Open Settings, then Connect.")
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
                    workingDirectory = workingDirectory.trim().ifBlank { "/home/user" },
                    backendKind = BackendKind.GROK_BUILD,
                )
            }
        }
    }

    fun setNetworkProfile(profile: NetworkProfile) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(networkProfile = profile) }
        }
    }

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.ConnectionChanged -> {
                val line = when (val s = event.state) {
                    ConnectionState.Disconnected -> "Disconnected"
                    ConnectionState.Connecting -> "Connecting…"
                    is ConnectionState.Connected -> "Connected · ${s.sessionId.take(8)}…"
                    is ConnectionState.Error -> "Error · ${s.message}"
                }
                _ui.update { it.copy(connection = event.state, statusLine = line, busy = false) }
            }

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
        val idField = if (role == ChatRole.ASSISTANT) ::streamingAssistantId else ::streamingThoughtId
        val existingId = idField.get()
        if (existingId == null) {
            val id = UUID.randomUUID().toString()
            idField.set(id)
            _ui.update {
                it.copy(
                    messages = it.messages + ChatMessage(
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
        val ids = listOfNotNull(streamingAssistantId, streamingThoughtId)
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
