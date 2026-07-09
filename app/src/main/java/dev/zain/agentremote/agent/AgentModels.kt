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

data class SessionSummary(
    val sessionId: String,
    val title: String,
    val cwd: String,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val messageCount: Int = 0,
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
    data class UserDelta(val text: String) : AgentEvent()
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
    fun events(): kotlinx.coroutines.flow.Flow<AgentEvent>
    fun isConnected(): Boolean
}
