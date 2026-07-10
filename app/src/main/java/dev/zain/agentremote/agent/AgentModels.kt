package dev.zain.agentremote.agent

enum class ChatRole {
    USER,
    ASSISTANT,
    THOUGHT,
    TOOL,
    SYSTEM,
}

/**
 * One renderable chat block. Tools are keyed by [toolCallId] so updates merge
 * instead of flooding the timeline.
 */
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val streaming: Boolean = false,
    /** ACP toolCallId when [role] is TOOL. */
    val toolCallId: String? = null,
    val toolStatus: String? = null,
    val toolKind: String? = null,
    /** Expanded body: input path, output snippet, etc. */
    val detail: String? = null,
)

/** A command the chat composer can complete after the user types `/`. */
data class SlashCommand(
    val name: String,
    val description: String,
    val argumentHint: String? = null,
    val source: SlashCommandSource = SlashCommandSource.AGENT,
)

/** Latest session-level context and model metadata reported by the ACP agent. */
data class AgentUsage(
    val usedTokens: Long? = null,
    val contextWindowTokens: Long? = null,
    val modelId: String? = null,
    val modelName: String? = null,
    val costAmount: Double? = null,
    val costCurrency: String? = null,
)

enum class SlashCommandSource {
    /** Implemented by AgentRemote itself. */
    APP,

    /** Advertised by the connected ACP agent. */
    AGENT,
}

/** The ACP agent ended an in-flight request because it was cancelled. */
class AgentRequestCancelledException(message: String) : IllegalStateException(message)

data class SessionSummary(
    val sessionId: String,
    val title: String,
    val cwd: String,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** Null when the backend's list API does not return a cheap message count. */
    val messageCount: Int? = null,
    val modelId: String? = null,
)

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(
        val sessionId: String,
        val cwd: String = "",
    ) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

sealed class AgentEvent {
    data class ConnectionChanged(val state: ConnectionState) : AgentEvent()
    data class SlashCommandsChanged(val commands: List<SlashCommand>) : AgentEvent()
    data class UsageChanged(val usage: AgentUsage) : AgentEvent()
    data class UserDelta(val text: String, val promptIndex: Long? = null) : AgentEvent()
    data class AssistantDelta(val text: String) : AgentEvent()
    data class ThoughtDelta(val text: String) : AgentEvent()
    data class ToolCall(
        val toolCallId: String?,
        val title: String,
        val status: String?,
        val kind: String? = null,
        val detail: String? = null,
    ) : AgentEvent()
    data class ToolUpdate(
        val toolCallId: String?,
        val title: String?,
        val status: String?,
        val kind: String? = null,
        val detail: String? = null,
    ) : AgentEvent()
    data class TurnComplete(val stopReason: String?) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}

interface AgentBackend {
    val name: String
    suspend fun connectNew(baseUrl: String, secret: String, workingDirectory: String)
    suspend fun connectLoad(
        baseUrl: String,
        secret: String,
        workingDirectory: String,
        sessionId: String,
    )
    suspend fun disconnect()
    suspend fun sendPrompt(text: String)
    /** Sends ACP's session-scoped cancellation notification for the active turn. */
    suspend fun cancelCurrentRequest(): Boolean
    fun events(): kotlinx.coroutines.flow.Flow<AgentEvent>
    fun isConnected(): Boolean
}
