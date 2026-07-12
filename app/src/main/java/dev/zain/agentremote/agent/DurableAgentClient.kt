package dev.zain.agentremote.agent

import dev.zain.agentremote.data.BackendKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Observer/controller for host-owned jobs. Closing this client only detaches the UI;
 * the durable host process remains the owner of every queued or running turn.
 */
class DurableAgentClient(private val kind: BackendKind) : AgentBackend {
    override val name: String = when (kind) {
        BackendKind.GROK_BUILD -> "Grok"
        BackendKind.CODEX -> "Codex"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong(0)
    private val stateLock = Any()
    private val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 512)

    @Volatile
    private var baseUrl: String = ""

    @Volatile
    private var secret: String = ""

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var workingDirectory: String = ""

    @Volatile
    private var lastEventId: Long = 0

    private var pollingJob: Job? = null
    private var activePollCall: Call? = null
    private var activeTurnCompletion: CompletableDeferred<String?>? = null

    override fun events(): Flow<AgentEvent> = events.asSharedFlow()

    override fun isConnected(): Boolean = sessionId != null

    suspend fun listSessions(
        baseUrl: String,
        secret: String,
        workingDirectory: String,
        limit: Int = 50,
    ): List<SessionSummary> {
        val query = "backend=${encode(kind.apiName)}&cwd=${encode(workingDirectory)}&limit=$limit"
        val root = request(
            baseUrl = normalizeBaseUrl(baseUrl),
            secret = secret,
            method = "GET",
            path = "/api/v1/sessions?$query",
        )
        return parseSessions(root.optJSONArray("sessions"))
    }

    suspend fun fetchCodexStatus(): CodexStatusSnapshot {
        check(kind == BackendKind.CODEX) { "Status details are only available for Codex" }
        val id = sessionId ?: error("Not connected")
        val root = request(
            method = "GET",
            path = "/api/v1/sessions/${encodePath(id)}/status",
        )
        val status = root.optJSONObject("status")
            ?: error("Durable host returned no Codex status")
        return parseCodexStatus(status)
    }

    suspend fun fetchModels(): List<AgentModelOption> {
        check(sessionId != null) { "Not connected" }
        val root = request(
            method = "GET",
            path = "/api/v1/models?backend=${encode(kind.apiName)}",
        )
        return parseModels(root.optJSONArray("models"))
    }

    suspend fun selectModel(modelId: String): AgentUsage {
        val id = sessionId ?: error("Not connected")
        val root = request(
            method = "POST",
            path = "/api/v1/sessions/${encodePath(id)}/model",
            body = JSONObject().put("modelId", modelId),
        )
        val session = root.optJSONObject("session")
            ?: error("Durable host returned no session")
        return parseUsage(session)
    }

    override suspend fun connectNew(
        baseUrl: String,
        secret: String,
        workingDirectory: String,
        codexFullAccess: Boolean,
    ) {
        val connectionGeneration = beginConnection(baseUrl, secret, workingDirectory)
        try {
            val response = request(
                method = "POST",
                path = "/api/v1/sessions",
                body = JSONObject()
                    .put("backend", kind.apiName)
                    .put("cwd", workingDirectory)
                    .put("codexFullAccess", codexFullAccess),
            )
            val session = response.optJSONObject("session")
                ?: error("Durable host returned no session")
            finishConnection(session, connectionGeneration)
            val bundle = request(method = "GET", path = "/api/v1/sessions/${encodePath(sessionId!!)}")
            val currentSession = bundle.optJSONObject("session") ?: session
            lastEventId = bundle.optLong("latestEventId", 0L)
            emitUsage(currentSession)
            emitCommands(bundle.optJSONArray("commands"))
            startPolling(connectionGeneration)
        } catch (error: Throwable) {
            retireConnection(connectionGeneration, error)
            throw error
        }
    }

    override suspend fun connectLoad(
        baseUrl: String,
        secret: String,
        workingDirectory: String,
        sessionId: String,
    ) {
        val connectionGeneration = beginConnection(baseUrl, secret, workingDirectory)
        try {
            val bundle = request(method = "GET", path = "/api/v1/sessions/${encodePath(sessionId)}")
            val session = bundle.optJSONObject("session")
                ?: error("Durable host returned no session")
            check(session.optString("backend") == kind.apiName) {
                "Session belongs to a different backend"
            }
            finishConnection(session, connectionGeneration)
            replayMessages(bundle.optJSONArray("messages"))
            emitUsage(session)
            emitCommands(bundle.optJSONArray("commands"))
            lastEventId = bundle.optLong("latestEventId", 0L)
            events.emit(AgentEvent.TurnComplete(stopReason = "loaded"))
            if (session.isActive) events.emit(AgentEvent.TurnStarted)
            startPolling(connectionGeneration)
        } catch (error: Throwable) {
            retireConnection(connectionGeneration, error)
            throw error
        }
    }

    override suspend fun sendPrompt(text: String) {
        val id = sessionId ?: error("Not connected")
        val completion = CompletableDeferred<String?>()
        synchronized(stateLock) {
            check(activeTurnCompletion == null) { "A turn is already active in this client" }
            activeTurnCompletion = completion
        }
        try {
            request(
                method = "POST",
                path = "/api/v1/sessions/${encodePath(id)}/turns",
                body = JSONObject().put("prompt", text),
            )
            completion.await()
        } finally {
            synchronized(stateLock) {
                if (activeTurnCompletion === completion) activeTurnCompletion = null
            }
        }
    }

    override suspend fun cancelCurrentRequest(): Boolean {
        val id = sessionId ?: return false
        val response = request(
            method = "POST",
            path = "/api/v1/sessions/${encodePath(id)}/cancel",
            body = JSONObject(),
        )
        return response.optBoolean("cancelled", false)
    }

    override suspend fun disconnect() {
        val oldJob: Job?
        synchronized(stateLock) {
            generation.incrementAndGet()
            oldJob = pollingJob
            pollingJob = null
            activePollCall?.cancel()
            activePollCall = null
            sessionId = null
            lastEventId = 0
            activeTurnCompletion?.cancel(
                CancellationException("Observer detached; host turn continues"),
            )
            activeTurnCompletion = null
        }
        oldJob?.cancel()
        events.emit(AgentEvent.ConnectionChanged(ConnectionState.Disconnected))
    }

    fun shutdown() {
        generation.incrementAndGet()
        pollingJob?.cancel()
        pollingJob = null
        activePollCall?.cancel()
        activePollCall = null
        sessionId = null
        scope.cancel()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun beginConnection(baseUrl: String, secret: String, cwd: String): Long {
        require(secret.isNotBlank()) { "Set the durable host token in Settings first" }
        val normalized = normalizeBaseUrl(baseUrl)
        val next: Long
        synchronized(stateLock) {
            next = generation.incrementAndGet()
            pollingJob?.cancel()
            pollingJob = null
            activePollCall?.cancel()
            activePollCall = null
            activeTurnCompletion?.cancel(CancellationException("Connection replaced"))
            activeTurnCompletion = null
            sessionId = null
            lastEventId = 0
            this.baseUrl = normalized
            this.secret = secret
            this.workingDirectory = cwd
        }
        events.tryEmit(AgentEvent.ConnectionChanged(ConnectionState.Connecting))
        return next
    }

    private suspend fun finishConnection(session: JSONObject, expectedGeneration: Long) {
        check(generation.get() == expectedGeneration) { "Connection was replaced" }
        val id = session.optString("id").ifBlank { error("Durable session has no id") }
        val cwd = session.optString("cwd").ifBlank { workingDirectory }
        synchronized(stateLock) {
            check(generation.get() == expectedGeneration) { "Connection was replaced" }
            sessionId = id
            workingDirectory = cwd
        }
        events.emit(
            AgentEvent.ConnectionChanged(ConnectionState.Connected(sessionId = id, cwd = cwd)),
        )
    }

    private fun retireConnection(expectedGeneration: Long, error: Throwable) {
        synchronized(stateLock) {
            if (generation.get() != expectedGeneration) return
            generation.incrementAndGet()
            sessionId = null
            pollingJob?.cancel()
            pollingJob = null
            activePollCall?.cancel()
            activePollCall = null
            activeTurnCompletion?.completeExceptionally(error)
            activeTurnCompletion = null
        }
        events.tryEmit(
            AgentEvent.ConnectionChanged(
                ConnectionState.Error(error.message ?: "Durable host connection failed"),
            ),
        )
    }

    private fun startPolling(expectedGeneration: Long) {
        val id = sessionId ?: return
        pollingJob = scope.launch {
            try {
                while (generation.get() == expectedGeneration && sessionId == id) {
                    val cursor = lastEventId
                    val response = request(
                        method = "GET",
                        path = "/api/v1/sessions/${encodePath(id)}/events" +
                            "?after=$cursor&wait=20",
                        trackAsPoll = true,
                    )
                    val incoming = response.optJSONArray("events") ?: JSONArray()
                    for (index in 0 until incoming.length()) {
                        val event = incoming.optJSONObject(index) ?: continue
                        if (generation.get() != expectedGeneration) return@launch
                        lastEventId = maxOf(lastEventId, event.optLong("id", lastEventId))
                        handleDurableEvent(event)
                    }
                    response.optJSONObject("session")?.let { emitUsage(it) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                retireConnection(expectedGeneration, error)
            }
        }
    }

    private suspend fun handleDurableEvent(event: JSONObject) {
        val data = event.optJSONObject("data") ?: JSONObject()
        when (event.optString("type")) {
            "message.created" -> if (data.optString("role") == "user") {
                events.emit(
                    AgentEvent.UserDelta(
                        text = data.optString("text"),
                        promptIndex = event.optLong("id"),
                    ),
                )
            }
            "message.delta" -> when (data.optString("role")) {
                "assistant" -> events.emit(AgentEvent.AssistantDelta(data.optString("delta")))
                "thought" -> events.emit(AgentEvent.ThoughtDelta(data.optString("delta")))
                "user" -> events.emit(
                    AgentEvent.UserDelta(data.optString("delta"), event.optLong("id")),
                )
            }
            "tool.updated" -> events.emit(
                AgentEvent.ToolUpdate(
                    toolCallId = data.optNullableString("messageId"),
                    title = data.optNullableString("title"),
                    status = data.optNullableString("status"),
                    kind = data.optNullableString("kind"),
                    detail = data.optNullableString("detail"),
                ),
            )
            "usage.updated" -> events.emit(
                AgentEvent.UsageChanged(
                    AgentUsage(
                        usedTokens = data.optNullableLong("usedTokens"),
                        contextWindowTokens = data.optNullableLong("contextWindowTokens"),
                        modelId = data.optNullableString("modelId"),
                        modelName = data.optNullableString("modelName")
                            ?: data.optNullableString("modelId"),
                        reasoningEffort = data.optNullableString("reasoningEffort"),
                    ),
                ),
            )
            "turn.queued", "turn.started" -> events.emit(AgentEvent.TurnStarted)
            "turn.completed" -> finishObservedTurn(data.optNullableString("stopReason") ?: "completed")
            "turn.cancelled" -> finishObservedTurn("cancelled")
            "turn.failed" -> {
                val message = data.optNullableString("error") ?: "Host agent turn failed."
                activeTurnCompletion?.complete("failed")
                events.emit(AgentEvent.Error(message))
            }
        }
    }

    private suspend fun finishObservedTurn(stopReason: String?) {
        activeTurnCompletion?.complete(stopReason)
        events.emit(AgentEvent.TurnComplete(stopReason))
    }

    private suspend fun replayMessages(messages: JSONArray?) {
        if (messages == null) return
        var promptIndex = 0L
        var previousTurn: String? = null
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val turnId = message.optNullableString("turnId")
            if (previousTurn != null && turnId != previousTurn) {
                events.emit(AgentEvent.TurnComplete(stopReason = "history"))
            }
            if (turnId != previousTurn && message.optString("role") == "user") promptIndex += 1
            previousTurn = turnId
            val text = message.optString("text")
            when (message.optString("role")) {
                "user" -> events.emit(AgentEvent.UserDelta(text, promptIndex))
                "assistant" -> events.emit(AgentEvent.AssistantDelta(text))
                "thought" -> events.emit(AgentEvent.ThoughtDelta(text))
                "tool" -> events.emit(
                    AgentEvent.ToolCall(
                        toolCallId = message.optString("id"),
                        title = text,
                        status = message.optNullableString("status"),
                        kind = message.optNullableString("kind"),
                        detail = message.optNullableString("detail"),
                    ),
                )
            }
        }
    }

    private suspend fun emitUsage(session: JSONObject) {
        val usage = parseUsage(session)
        if (usage != AgentUsage()) events.emit(AgentEvent.UsageChanged(usage))
    }

    private fun parseUsage(session: JSONObject): AgentUsage = AgentUsage(
        usedTokens = session.optNullableLong("usedTokens"),
        contextWindowTokens = session.optNullableLong("contextWindowTokens"),
        modelId = session.optNullableString("modelId"),
        modelName = session.optNullableString("modelName")
            ?: session.optNullableString("modelId"),
        reasoningEffort = session.optNullableString("reasoningEffort"),
    )

    private suspend fun emitCommands(commands: JSONArray?) {
        if (commands == null) return
        val parsed = buildList {
            for (index in 0 until commands.length()) {
                val command = commands.optJSONObject(index) ?: continue
                val name = command.optString("name")
                if (name.isBlank()) continue
                add(
                    SlashCommand(
                        name = name,
                        description = command.optString("description").ifBlank { "Agent command" },
                        argumentHint = command.optNullableString("argumentHint"),
                        source = SlashCommandSource.AGENT,
                    ),
                )
            }
        }
        events.emit(AgentEvent.SlashCommandsChanged(parsed))
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        baseUrl: String = this.baseUrl,
        secret: String = this.secret,
        trackAsPoll: Boolean = false,
    ): JSONObject = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $secret")
            .header("Accept", "application/json")
        when (method) {
            "GET" -> requestBuilder.get()
            "POST" -> requestBuilder.post(
                (body ?: JSONObject()).toString()
                    .toRequestBody(JSON_MEDIA_TYPE),
            )
            else -> error("Unsupported HTTP method: $method")
        }
        val call = http.newCall(requestBuilder.build())
        if (trackAsPoll) synchronized(stateLock) { activePollCall = call }
        try {
            call.execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
                if (!response.isSuccessful) {
                    throw IOException(
                        json.optString("error").ifBlank {
                            "Durable host returned HTTP ${response.code}"
                        },
                    )
                }
                json
            }
        } finally {
            if (trackAsPoll) synchronized(stateLock) {
                if (activePollCall === call) activePollCall = null
            }
        }
    }

    private fun parseSessions(array: JSONArray?): List<SessionSummary> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                add(
                    SessionSummary(
                        sessionId = id,
                        title = item.optString("title").ifBlank { "New $name session" },
                        cwd = item.optString("cwd"),
                        createdAt = item.optNullableString("createdAt"),
                        updatedAt = item.optNullableString("updatedAt"),
                        messageCount = item.optNullableInt("messageCount"),
                        modelId = item.optNullableString("modelId"),
                        status = item.optString("status").toSessionStatus(),
                    ),
                )
            }
        }
    }

    private fun parseModels(array: JSONArray?): List<AgentModelOption> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                add(
                    AgentModelOption(
                        id = id,
                        name = item.optString("name").ifBlank { id },
                        description = item.optNullableString("description"),
                        contextWindowTokens = item.optNullableLong("contextWindowTokens"),
                        reasoningEfforts = item.optJSONArray("reasoningEfforts").toStringList(),
                        defaultReasoningEffort = item.optNullableString("defaultReasoningEffort"),
                        isDefault = item.optBoolean("isDefault", false),
                    ),
                )
            }
        }
    }

    private fun parseCodexStatus(status: JSONObject): CodexStatusSnapshot {
        val account = status.optJSONObject("account")
        val limits = status.optJSONObject("rateLimits")
        return CodexStatusSnapshot(
            cliVersion = status.optNullableString("cliVersion"),
            modelId = status.optNullableString("modelId"),
            modelName = status.optNullableString("modelName")
                ?: status.optNullableString("modelId"),
            modelProvider = status.optNullableString("modelProvider"),
            reasoningEffort = status.optNullableString("reasoningEffort"),
            reasoningSummary = status.optNullableString("reasoningSummary"),
            cwd = status.optNullableString("cwd"),
            sandboxMode = status.optNullableString("sandboxMode"),
            networkAccess = status.optNullableBoolean("networkAccess"),
            approvalPolicy = status.optNullableString("approvalPolicy"),
            approvalsReviewer = status.optNullableString("approvalsReviewer"),
            agentsFiles = status.optJSONArray("agentsFiles").toStringList(),
            accountType = account?.optNullableString("type"),
            accountEmail = account?.optNullableString("email"),
            accountPlanType = account?.optNullableString("planType"),
            collaborationMode = status.optNullableString("collaborationMode"),
            threadName = status.optNullableString("threadName"),
            sessionId = status.optNullableString("sessionId"),
            forkedFrom = status.optNullableString("forkedFrom"),
            contextUsedTokens = status.optNullableLong("contextUsedTokens"),
            contextWindowTokens = status.optNullableLong("contextWindowTokens"),
            lastTokenUsage = status.optJSONObject("lastTokenUsage").toCodexTokenUsage(),
            totalTokenUsage = status.optJSONObject("totalTokenUsage").toCodexTokenUsage(),
            primaryRateLimit = limits?.optJSONObject("primary").toRateLimitWindow(),
            secondaryRateLimit = limits?.optJSONObject("secondary").toRateLimitWindow(),
            credits = limits?.optJSONObject("credits")?.let {
                CodexCredits(
                    hasCredits = it.optBoolean("hasCredits", false),
                    unlimited = it.optBoolean("unlimited", false),
                    balance = it.optNullableString("balance"),
                )
            },
            spendLimit = limits?.optJSONObject("individualLimit")?.let {
                CodexSpendLimit(
                    used = it.optNullableString("used"),
                    limit = it.optNullableString("limit"),
                    remainingPercent = it.optNullableInt("remainingPercent"),
                    resetsAtEpochSeconds = it.optNullableLong("resetsAt"),
                )
            },
            rateLimitResetCreditsAvailable =
                status.optNullableLong("rateLimitResetCreditsAvailable"),
        )
    }

    private fun JSONObject?.toCodexTokenUsage(): CodexTokenUsage? = this?.let {
        CodexTokenUsage(
            inputTokens = it.optNullableLong("inputTokens"),
            cachedInputTokens = it.optNullableLong("cachedInputTokens"),
            outputTokens = it.optNullableLong("outputTokens"),
            reasoningOutputTokens = it.optNullableLong("reasoningOutputTokens"),
            totalTokens = it.optNullableLong("totalTokens"),
        )
    }

    private fun JSONObject?.toRateLimitWindow(): CodexRateLimitWindow? = this?.let {
        CodexRateLimitWindow(
            usedPercent = it.optNullableInt("usedPercent"),
            windowDurationMinutes = it.optNullableLong("windowDurationMins"),
            resetsAtEpochSeconds = it.optNullableLong("resetsAt"),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private val JSONObject.isActive: Boolean
        get() = optString("status") in setOf("queued", "running", "cancelling")

    private fun JSONObject.optNullableString(key: String): String? =
        takeIf { has(key) && !isNull(key) }?.optString(key)?.ifBlank { null }

    private fun JSONObject.optNullableLong(key: String): Long? =
        takeIf { has(key) && !isNull(key) }?.optLong(key)

    private fun JSONObject.optNullableInt(key: String): Int? =
        takeIf { has(key) && !isNull(key) }?.optInt(key)

    private fun JSONObject.optNullableBoolean(key: String): Boolean? =
        takeIf { has(key) && !isNull(key) }?.optBoolean(key)

    private fun String.toSessionStatus(): SessionStatus = when (lowercase()) {
        "queued" -> SessionStatus.QUEUED
        "running" -> SessionStatus.RUNNING
        "cancelling" -> SessionStatus.CANCELLING
        "failed" -> SessionStatus.FAILED
        "cancelled" -> SessionStatus.CANCELLED
        else -> SessionStatus.IDLE
    }

    private val BackendKind.apiName: String
        get() = when (this) {
            BackendKind.GROK_BUILD -> "grok"
            BackendKind.CODEX -> "codex"
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            require(trimmed.isNotBlank()) { "Durable host URL is empty" }
            return when {
                trimmed.startsWith("ws://", ignoreCase = true) -> "http://${trimmed.drop(5)}"
                trimmed.startsWith("wss://", ignoreCase = true) -> "https://${trimmed.drop(6)}"
                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                else -> "http://$trimmed"
            }
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

        private fun encodePath(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")
    }
}
