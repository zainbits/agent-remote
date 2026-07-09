package dev.zain.agentremote.agent

enum class ChatRole {
    USER,
    ASSISTANT,
    THOUGHT,
    TOOL,
    SYSTEM,
}

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val streaming: Boolean = false,
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
    data class ToolCall(val title: String, val status: String?) : AgentEvent()
    data class ToolUpdate(val title: String?, val status: String?) : AgentEvent()
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
