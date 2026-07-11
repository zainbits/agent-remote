package dev.zain.agentremote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zain.agentremote.agent.AgentEvent
import dev.zain.agentremote.agent.AgentRequestCancelledException
import dev.zain.agentremote.agent.AgentUsage
import dev.zain.agentremote.agent.ChatMessage
import dev.zain.agentremote.agent.ChatRole
import dev.zain.agentremote.agent.ConnectionState
import dev.zain.agentremote.agent.DurableAgentClient
import dev.zain.agentremote.agent.SessionSummary
import dev.zain.agentremote.agent.SlashCommand
import dev.zain.agentremote.agent.SlashCommandSource
import dev.zain.agentremote.data.AppSettings
import dev.zain.agentremote.data.BackendKind
import dev.zain.agentremote.data.NetworkProfile
import dev.zain.agentremote.data.SettingsRepository
import dev.zain.agentremote.data.displayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

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

data class ChatUiState(
    val settings: AppSettings = AppSettings(),
    val screen: AppScreen = AppScreen.HOME,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val busy: Boolean = false,
    val requestInFlight: Boolean = false,
    val cancellationRequested: Boolean = false,
    val reconnecting: Boolean = false,
    val canReconnect: Boolean = false,
    val statusLine: String = "Disconnected",
    val slashCommands: List<SlashCommand> = emptyList(),
    val usage: AgentUsage = AgentUsage(),
    val commandOutput: CommandOutputState? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val sessionsLoading: Boolean = false,
    val sessionsError: String? = null,
    val activeSessionTitle: String? = null,
    val historyLoading: Boolean = false,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepo = SettingsRepository(application)
    private val grokBackend = DurableAgentClient(BackendKind.GROK_BUILD)
    private val codexBackend = DurableAgentClient(BackendKind.CODEX)
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
    private var suppressLoadedSlashTurn: Boolean = false
    private var loadedPromptIndex: Long? = null
    private var reconnectJob: Job? = null
    private var connectionActionGeneration: Long = 0
    private var connectionDesired: Boolean = false
    private var hasConnectedSession: Boolean = false
    private var appInForeground: Boolean = true
    private var activeSessionIdForReconnect: String? = null
    private var activeSessionCwdForReconnect: String = AppSettings.DEFAULT_CWD
    /**
     * After a local [send], the agent echoes the prompt as `user_message_chunk`.
     * We already inserted an optimistic USER bubble, so ignore those deltas until
     * the turn ends. Session load still applies UserDelta (flag stays false).
     */
    private var suppressUserEcho: Boolean = false

    private val informationCommands = setOf("context", "usage", "session-info")

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

    val settingsFlow = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    init {
        _ui.update { it.copy(slashCommands = localSlashCommands) }
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                if (_ui.value.screen == AppScreen.HOME) activeBackendKind = s.backendKind
                _ui.update { it.copy(settings = s) }
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

    fun refreshSessions() {
        refreshSessions(_ui.value.settings.backendKind)
    }

    private fun refreshSessions(kind: BackendKind) {
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
                if (it.settings.backendKind == kind) {
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
            }.onFailure { e ->
                val message = e.message ?: "Failed to load ${kind.displayName} sessions"
                sessionErrorsByBackend[kind] = message
                _ui.update {
                    if (it.settings.backendKind == kind) {
                        it.copy(sessionsLoading = false, sessionsError = message)
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun selectBackend(kind: BackendKind) {
        val state = _ui.value
        if (state.screen != AppScreen.HOME || state.busy || state.settings.backendKind == kind) return
        activeBackendKind = kind
        _ui.update {
            it.copy(
                settings = it.settings.copy(backendKind = kind),
                sessions = sessionsByBackend[kind].orEmpty(),
                sessionsError = sessionErrorsByBackend[kind],
                sessionsLoading = false,
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
                    slashCommands = localSlashCommands,
                    usage = AgentUsage(),
                    commandOutput = null,
                )
            }
            runCatching {
                backend.connectNew(
                    baseUrl = s.activeDurableBaseUrl,
                    secret = secret,
                    workingDirectory = cwd,
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
                    slashCommands = localSlashCommands,
                    usage = AgentUsage(),
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
                    slashCommands = localSlashCommands,
                    usage = AgentUsage(),
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
                    slashCommands = localSlashCommands,
                    usage = AgentUsage(),
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
        // Keep a healthy session connected, but do not spend the bounded retry
        // budget while Android has stopped the activity. Foregrounding restarts
        // an interrupted recovery from a new socket generation.
        reconnectJob?.cancel()
        reconnectJob = null
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
        val text = _ui.value.draft.trim()
        if (text.isEmpty()) return
        if (runLocalSlashCommand(text)) return
        val commandName = slashCommandName(text)
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
        suppressUserEcho = true
        streamingUserId = null
        streamingAssistantId = null
        streamingThoughtId = null
        _ui.update { state ->
            state.copy(
                draft = "",
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
                    )
                } else {
                    state.messages
                },
            )
        }
        viewModelScope.launch {
            runCatching { backend.sendPrompt(text) }
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

    private fun beginConnectionAction(sessionId: String?, cwd: String): Long {
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
                        slashCommands = localSlashCommands,
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
        suppressLoadedSlashTurn = false
        loadedPromptIndex = null
        streamingUserId = null
        streamingAssistantId = null
        streamingThoughtId = null
        suppressUserEcho = false
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

    private fun appendCommandOutput(command: String, delta: String) {
        _ui.update { state ->
            val current = state.commandOutput
            if (current == null || current.command != command) {
                state
            } else {
                state.copy(
                    commandOutput = current.copy(
                        body = current.body + delta,
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
                val commands = (localSlashCommands + event.commands)
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
                appendStreaming(ChatRole.USER, event.text)
            }
            is AgentEvent.AssistantDelta -> {
                val command = activeSlashCommand
                if (command != null) {
                    appendCommandOutput(command, event.text)
                } else if (!suppressLoadedSlashTurn) {
                    appendStreaming(ChatRole.ASSISTANT, event.text)
                }
            }
            is AgentEvent.ThoughtDelta -> {
                val command = activeSlashCommand
                if (command != null) {
                    updateCommandStatus(command, "Working…")
                } else if (!suppressLoadedSlashTurn) {
                    appendStreaming(ChatRole.THOUGHT, event.text)
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
                    body = usageBody(merged),
                    progress = usageProgress(merged),
                )
            } else {
                output
            }
            state.copy(usage = merged, commandOutput = updatedOutput)
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
        eventsJobs.forEach(Job::cancel)
        grokBackend.shutdown()
        codexBackend.shutdown()
        super.onCleared()
    }

    private companion object {
        const val RECONNECT_ATTEMPT_TIMEOUT_MILLIS = 25_000L
    }
}
