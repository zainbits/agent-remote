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

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val sessionId: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

sealed class AgentEvent {
    data class ConnectionChanged(val state: ConnectionState) : AgentEvent()
    data class AssistantDelta(val text: String) : AgentEvent()
    data class ThoughtDelta(val text: String) : AgentEvent()
    data class ToolCall(val title: String, val status: String?) : AgentEvent()
    data class ToolUpdate(val title: String?, val status: String?) : AgentEvent()
    data class TurnComplete(val stopReason: String?) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}

interface AgentBackend {
    val name: String
    suspend fun connect(baseUrl: String, secret: String, workingDirectory: String)
    suspend fun disconnect()
    suspend fun sendPrompt(text: String)
    fun events(): kotlinx.coroutines.flow.Flow<AgentEvent>
    fun isConnected(): Boolean
}
