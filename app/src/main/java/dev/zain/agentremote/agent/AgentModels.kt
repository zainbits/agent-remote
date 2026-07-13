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
    val reasoningEffort: String? = null,
    val costAmount: Double? = null,
    val costCurrency: String? = null,
)

data class AgentModelOption(
    val id: String,
    val name: String,
    val description: String? = null,
    val contextWindowTokens: Long? = null,
    val reasoningEfforts: List<String> = emptyList(),
    val defaultReasoningEffort: String? = null,
    val isDefault: Boolean = false,
)

data class CodexTokenUsage(
    val inputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningOutputTokens: Long? = null,
    val totalTokens: Long? = null,
)

data class CodexRateLimitWindow(
    val usedPercent: Int? = null,
    val windowDurationMinutes: Long? = null,
    val resetsAtEpochSeconds: Long? = null,
)

data class CodexCredits(
    val hasCredits: Boolean = false,
    val unlimited: Boolean = false,
    val balance: String? = null,
)

data class CodexSpendLimit(
    val used: String? = null,
    val limit: String? = null,
    val remainingPercent: Int? = null,
    val resetsAtEpochSeconds: Long? = null,
)

data class CodexStatusSnapshot(
    val cliVersion: String? = null,
    val modelId: String? = null,
    val modelName: String? = null,
    val modelProvider: String? = null,
    val reasoningEffort: String? = null,
    val reasoningSummary: String? = null,
    val cwd: String? = null,
    val sandboxMode: String? = null,
    val networkAccess: Boolean? = null,
    val approvalPolicy: String? = null,
    val approvalsReviewer: String? = null,
    val agentsFiles: List<String> = emptyList(),
    val accountType: String? = null,
    val accountEmail: String? = null,
    val accountPlanType: String? = null,
    val collaborationMode: String? = null,
    val threadName: String? = null,
    val sessionId: String? = null,
    val forkedFrom: String? = null,
    val contextUsedTokens: Long? = null,
    val contextWindowTokens: Long? = null,
    val lastTokenUsage: CodexTokenUsage? = null,
    val totalTokenUsage: CodexTokenUsage? = null,
    val primaryRateLimit: CodexRateLimitWindow? = null,
    val secondaryRateLimit: CodexRateLimitWindow? = null,
    val credits: CodexCredits? = null,
    val spendLimit: CodexSpendLimit? = null,
    val rateLimitResetCreditsAvailable: Long? = null,
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
    val status: SessionStatus = SessionStatus.IDLE,
)

enum class SessionStatus {
    IDLE,
    QUEUED,
    RUNNING,
    CANCELLING,
    FAILED,
    CANCELLED,
}

val SessionStatus.isActive: Boolean
    get() = this == SessionStatus.QUEUED ||
        this == SessionStatus.RUNNING ||
        this == SessionStatus.CANCELLING

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
    data class AssistantDelta(
        val text: String,
        val messageId: String? = null,
        val replace: Boolean = false,
        val completed: Boolean = false,
    ) : AgentEvent()
    data class ThoughtDelta(
        val text: String,
        val messageId: String? = null,
        val replace: Boolean = false,
        val completed: Boolean = false,
    ) : AgentEvent()
    data class MessageCompleted(val messageId: String) : AgentEvent()
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
    /** A durable host turn is queued or running, including after reconnecting to it. */
    data object TurnStarted : AgentEvent()
    data class TurnComplete(val stopReason: String?) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}

interface AgentBackend {
    val name: String
    suspend fun connectNew(
        baseUrl: String,
        secret: String,
        workingDirectory: String,
        codexFullAccess: Boolean = true,
    )
    suspend fun connectLoad(
        baseUrl: String,
        secret: String,
        workingDirectory: String,
        sessionId: String,
    )
    suspend fun disconnect()
    suspend fun sendPrompt(text: String)
    /** Explicitly requests cancellation of the active turn; disconnecting must not call this. */
    suspend fun cancelCurrentRequest(): Boolean
    fun events(): kotlinx.coroutines.flow.Flow<AgentEvent>
    fun isConnected(): Boolean
}
