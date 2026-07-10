package dev.zain.agentremote.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Codex app-server WebSocket adapter.
 *
 * The protocol is JSON-RPC 2.0 without the `jsonrpc` field on the wire:
 * initialize -> initialized -> thread/start|resume -> turn/start + notifications.
 */
class CodexAppServerClient : AgentBackend {
    override val name: String = "Codex"

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 256)
    private val incomingMessages = Channel<IncomingMessage>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val nextRequestId = AtomicLong(1)
    private val connectionGeneration = AtomicLong(0)
    private val connected = AtomicBoolean(false)
    private val connectionLock = Any()
    private val toolDetails = ConcurrentHashMap<String, String>()
    private val streamedAgentItems = ConcurrentHashMap.newKeySet<String>()
    private val streamedReasoningItems = ConcurrentHashMap.newKeySet<String>()

    private var socket: WebSocket? = null
    private var opening: CompletableDeferred<Unit>? = null
    @Volatile private var threadId: String? = null
    @Volatile private var workingDirectory: String = ""
    @Volatile private var activeTurnId: String? = null
    @Volatile private var activeTurnCompletion: CompletableDeferred<String?>? = null
    @Volatile private var activeTurnStarted: CompletableDeferred<String>? = null

    init {
        scope.launch {
            for (incoming in incomingMessages) {
                if (isCurrentConnection(incoming.generation)) {
                    handleMessage(incoming.text)
                }
            }
        }
    }

    override fun events(): Flow<AgentEvent> = events.asSharedFlow()

    override fun isConnected(): Boolean = connected.get() && threadId != null

    override suspend fun connectNew(baseUrl: String, secret: String, workingDirectory: String) {
        val generation = openAndInitialize(baseUrl, secret, notifyConnection = true)
        try {
            val result = requestRpc(
                method = "thread/start",
                params = threadParams(workingDirectory),
            )
            check(isCurrentConnection(generation)) { "Connection was replaced" }
            val thread = result.optJSONObject("thread") ?: error("thread/start returned no thread")
            emitModel(result)
            finishConnected(thread, generation)
        } catch (t: Throwable) {
            disconnectQuiet(expectedGeneration = generation)
            throw t
        }
    }

    override suspend fun connectLoad(
        baseUrl: String,
        secret: String,
        workingDirectory: String,
        sessionId: String,
    ) {
        val generation = openAndInitialize(baseUrl, secret, notifyConnection = true)
        try {
            val result = requestRpc(
                method = "thread/resume",
                params = threadParams(workingDirectory).put("threadId", sessionId),
            )
            check(isCurrentConnection(generation)) { "Connection was replaced" }
            val thread = result.optJSONObject("thread") ?: error("thread/resume returned no thread")
            emitModel(result)
            finishConnected(thread, generation)
            replayThread(thread)
            events.emit(AgentEvent.TurnComplete(stopReason = "loaded"))
        } catch (t: Throwable) {
            disconnectQuiet(expectedGeneration = generation)
            throw t
        }
    }

    suspend fun listSessions(
        baseUrl: String,
        secret: String,
        workingDirectory: String,
        limit: Int = 50,
    ): List<SessionSummary> {
        val generation = openAndInitialize(baseUrl, secret, notifyConnection = false)
        return try {
            val result = requestRpc(
                method = "thread/list",
                params = JSONObject()
                    .put("cwd", workingDirectory)
                    .put("limit", limit)
                    .put("sortKey", "updated_at")
                    .put("sortDirection", "desc"),
            )
            parseSessions(result.optJSONArray("data"))
        } finally {
            disconnectQuiet(expectedGeneration = generation)
        }
    }

    override suspend fun sendPrompt(text: String) {
        val currentThreadId = threadId ?: error("Not connected")
        check(activeTurnCompletion == null) { "A Codex turn is already running" }

        val completion = CompletableDeferred<String?>()
        val started = CompletableDeferred<String>()
        activeTurnCompletion = completion
        activeTurnStarted = started
        activeTurnId = null

        try {
            val result = requestRpc(
                method = "turn/start",
                params = JSONObject()
                    .put("threadId", currentThreadId)
                    .put(
                        "input",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "text")
                                .put("text", text),
                        ),
                    ),
            )
            val turn = result.optJSONObject("turn") ?: error("turn/start returned no turn")
            val turnId = turn.optString("id").ifBlank { error("turn/start returned no turn id") }
            activeTurnId = turnId
            started.complete(turnId)
            completion.await()
        } finally {
            if (activeTurnCompletion === completion) activeTurnCompletion = null
            if (activeTurnStarted === started) activeTurnStarted = null
            activeTurnId = null
        }
    }

    override suspend fun cancelCurrentRequest(): Boolean {
        val currentThreadId = threadId ?: return false
        val turnId = activeTurnId ?: run {
            val started = activeTurnStarted ?: return false
            runCatching { withTimeout(5_000) { started.await() } }.getOrNull() ?: return false
        }
        requestRpc(
            method = "turn/interrupt",
            params = JSONObject()
                .put("threadId", currentThreadId)
                .put("turnId", turnId),
        )
        return true
    }

    override suspend fun disconnect() {
        disconnectQuiet(notify = true)
    }

    private fun threadParams(cwd: String): JSONObject = JSONObject()
        .put("cwd", cwd.ifBlank { "/home/user" })
        // AgentRemote has no approval-dialog surface yet. Keep Codex useful while
        // containing writes to the selected workspace and rejecting escalations.
        .put("approvalPolicy", "never")
        .put("sandbox", "workspace-write")

    private suspend fun openAndInitialize(
        baseUrl: String,
        secret: String,
        notifyConnection: Boolean,
    ): Long {
        val url = normalizeWebSocketUrl(baseUrl)
        val requestBuilder = Request.Builder().url(url)
        if (secret.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $secret")
        }

        val opened = CompletableDeferred<Unit>()
        val generation = beginConnection(opened, notifyConnection)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(connectionLock) {
                    if (!isCurrentConnection(generation) || opening !== opened) return
                    opening = null
                    opened.complete(Unit)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isCurrentConnection(generation)) {
                    incomingMessages.trySend(IncomingMessage(generation, text))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                retireConnection(
                    generation = generation,
                    cause = t,
                    state = ConnectionState.Error(t.message ?: "WebSocket failure"),
                    opened = opened,
                    notifyConnection = notifyConnection,
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                retireConnection(
                    generation = generation,
                    cause = IllegalStateException("WebSocket closed ($code)"),
                    state = ConnectionState.Disconnected,
                    opened = opened,
                    notifyConnection = notifyConnection,
                )
            }
        }

        val newSocket = http.newWebSocket(requestBuilder.build(), listener)
        val accepted = synchronized(connectionLock) {
            if (isCurrentConnection(generation)) {
                socket = newSocket
                true
            } else {
                false
            }
        }
        if (!accepted) {
            newSocket.cancel()
            error("Connection was replaced")
        }

        try {
            withTimeout(20_000) { opened.await() }
            requestRpc(
                method = "initialize",
                params = JSONObject().put(
                    "clientInfo",
                    JSONObject()
                        .put("name", "agentremote")
                        .put("title", "AgentRemote")
                        .put("version", "0.1.0"),
                ),
            )
            sendNotification("initialized", JSONObject())
            check(isCurrentConnection(generation)) { "Connection was replaced" }
        } catch (t: Throwable) {
            disconnectQuiet(expectedGeneration = generation)
            throw t
        }
        return generation
    }

    private fun beginConnection(
        opened: CompletableDeferred<Unit>,
        notifyConnection: Boolean,
    ): Long {
        val oldSocket: WebSocket?
        val generation: Long
        synchronized(connectionLock) {
            generation = connectionGeneration.incrementAndGet()
            connected.set(false)
            threadId = null
            failActiveTurn(IllegalStateException("Connection was replaced"))
            opening?.completeExceptionally(IllegalStateException("Connection was replaced"))
            opening = opened
            failAllPending(IllegalStateException("Connection was replaced"))
            oldSocket = socket
            socket = null
            if (notifyConnection) {
                events.tryEmit(AgentEvent.ConnectionChanged(ConnectionState.Connecting))
            }
        }
        closeSocketQuietly(oldSocket)
        toolDetails.clear()
        streamedAgentItems.clear()
        streamedReasoningItems.clear()
        return generation
    }

    private fun finishConnected(thread: JSONObject, generation: Long) {
        val id = thread.optString("id").ifBlank { error("Codex thread has no id") }
        val cwd = thread.optString("cwd").ifBlank { workingDirectory }
        synchronized(connectionLock) {
            check(isCurrentConnection(generation)) { "Connection was replaced" }
            threadId = id
            workingDirectory = cwd
            connected.set(true)
            events.tryEmit(
                AgentEvent.ConnectionChanged(
                    ConnectionState.Connected(sessionId = id, cwd = cwd),
                ),
            )
        }
    }

    private fun retireConnection(
        generation: Long,
        cause: Throwable,
        state: ConnectionState,
        opened: CompletableDeferred<Unit>,
        notifyConnection: Boolean,
    ) {
        synchronized(connectionLock) {
            if (!isCurrentConnection(generation)) return
            connectionGeneration.incrementAndGet()
            connected.set(false)
            threadId = null
            socket = null
            if (opening === opened) {
                opening = null
                opened.completeExceptionally(cause)
            }
            failAllPending(cause)
            failActiveTurn(cause)
            if (notifyConnection) events.tryEmit(AgentEvent.ConnectionChanged(state))
        }
    }

    private fun disconnectQuiet(
        notify: Boolean = false,
        expectedGeneration: Long? = null,
    ) {
        val oldSocket: WebSocket?
        synchronized(connectionLock) {
            if (expectedGeneration != null && !isCurrentConnection(expectedGeneration)) return
            connectionGeneration.incrementAndGet()
            connected.set(false)
            threadId = null
            opening?.completeExceptionally(IllegalStateException("Disconnected"))
            opening = null
            failAllPending(IllegalStateException("Disconnected"))
            failActiveTurn(IllegalStateException("Disconnected"))
            oldSocket = socket
            socket = null
            if (notify) events.tryEmit(AgentEvent.ConnectionChanged(ConnectionState.Disconnected))
        }
        closeSocketQuietly(oldSocket)
    }

    private suspend fun handleMessage(raw: String) {
        val message = runCatching { JSONObject(raw) }.getOrElse {
            events.emit(AgentEvent.Error("Invalid JSON from Codex: ${it.message}"))
            return
        }

        when {
            message.has("id") && (message.has("result") || message.has("error")) -> {
                val id = message.optLong("id", -1L)
                val deferred = pending.remove(id)
                if (message.has("error")) {
                    val error = message.optJSONObject("error") ?: JSONObject()
                    deferred?.completeExceptionally(
                        CodexRpcException(
                            code = error.optInt("code", -1),
                            message = error.optString("message", "Codex RPC error"),
                        ),
                    )
                } else {
                    deferred?.complete(message.optJSONObject("result") ?: JSONObject())
                }
            }

            message.has("id") && message.has("method") -> handleServerRequest(message)
            message.has("method") -> handleNotification(
                method = message.optString("method"),
                params = message.optJSONObject("params") ?: JSONObject(),
            )
        }
    }

    private suspend fun handleNotification(method: String, params: JSONObject) {
        if (!matchesActiveThread(params)) return
        when (method) {
            "turn/started" -> {
                val id = params.optJSONObject("turn")?.optString("id").orEmpty()
                if (id.isNotBlank()) {
                    activeTurnId = id
                    activeTurnStarted?.complete(id)
                }
            }

            "turn/completed" -> {
                val turn = params.optJSONObject("turn") ?: JSONObject()
                val status = turn.optString("status").ifBlank { null }
                val stopReason = if (status == "interrupted") "cancelled" else status
                if (status == "failed") {
                    val error = turn.optJSONObject("error")?.optString("message").orEmpty()
                    if (error.isNotBlank()) events.emit(AgentEvent.Error(error))
                }
                events.emit(AgentEvent.TurnComplete(stopReason))
                activeTurnCompletion?.complete(stopReason)
            }

            "item/started" -> handleItem(params.optJSONObject("item"), completed = false)
            "item/completed" -> handleItem(params.optJSONObject("item"), completed = true)

            "item/agentMessage/delta" -> {
                val itemId = params.optString("itemId")
                streamedAgentItems += itemId
                params.optString("delta").takeIf { it.isNotEmpty() }
                    ?.let { events.emit(AgentEvent.AssistantDelta(it)) }
            }

            "item/reasoning/summaryTextDelta" -> {
                val itemId = params.optString("itemId")
                streamedReasoningItems += itemId
                params.optString("delta").takeIf { it.isNotEmpty() }
                    ?.let { events.emit(AgentEvent.ThoughtDelta(it)) }
            }

            "item/plan/delta" -> params.optString("delta").takeIf { it.isNotEmpty() }
                ?.let { events.emit(AgentEvent.ThoughtDelta(it)) }

            "item/commandExecution/outputDelta", "item/fileChange/outputDelta" -> {
                appendToolDelta(params.optString("itemId"), params.optString("delta"))
            }

            "thread/tokenUsage/updated" -> emitUsage(params.optJSONObject("tokenUsage"))
            "error" -> {
                val error = params.optJSONObject("error")?.optString("message").orEmpty()
                if (error.isNotBlank() && !params.optBoolean("willRetry")) {
                    events.emit(AgentEvent.Error(error))
                }
            }

        }
    }

    private suspend fun handleServerRequest(message: JSONObject) {
        val id = message.optLong("id", -1L)
        when (message.optString("method")) {
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval",
            -> respond(id, JSONObject().put("decision", "decline"))

            "item/tool/requestUserInput" -> {
                events.emit(
                    AgentEvent.ToolCall(
                        toolCallId = message.optJSONObject("params")
                            ?.optString("itemId")
                            ?.ifBlank { null },
                        title = "Interactive input requested",
                        status = "declined",
                        kind = "user_input",
                        detail = "AgentsRemote does not support interactive question forms yet.",
                    ),
                )
                respond(id, JSONObject().put("answers", JSONObject()))
            }

            "mcpServer/elicitation/request" -> respond(
                id,
                JSONObject().put("action", "decline"),
            )

            "currentTime/read" -> respond(
                id,
                JSONObject().put("currentTimeAt", Instant.now().epochSecond),
            )

            else -> respondError(id, -32601, "Unsupported client request")
        }
    }

    private suspend fun handleItem(item: JSONObject?, completed: Boolean) {
        if (item == null) return
        val id = item.optString("id").ifBlank { null }
        when (item.optString("type")) {
            "userMessage" -> userMessageText(item.optJSONArray("content"))
                .takeIf { it.isNotBlank() }
                ?.let { events.emit(AgentEvent.UserDelta(it)) }

            "agentMessage" -> {
                val text = item.optString("text")
                if (completed && !id.isNullOrBlank() && id !in streamedAgentItems && text.isNotBlank()) {
                    events.emit(AgentEvent.AssistantDelta(text))
                }
            }

            "reasoning" -> {
                val summary = jsonStringList(item.optJSONArray("summary")).joinToString("\n")
                if (
                    completed && !id.isNullOrBlank() && id !in streamedReasoningItems &&
                    summary.isNotBlank()
                ) {
                    events.emit(AgentEvent.ThoughtDelta(summary))
                }
            }

            "plan" -> item.optString("text").takeIf { completed && it.isNotBlank() }
                ?.let { events.emit(AgentEvent.ThoughtDelta(it)) }

            "commandExecution", "fileChange", "mcpToolCall", "dynamicToolCall",
            "collabAgentToolCall", "webSearch", "imageGeneration", "imageView",
            -> emitToolItem(item, completed)
        }
    }

    private suspend fun emitToolItem(item: JSONObject, completed: Boolean) {
        val id = item.optString("id").ifBlank { null }
        val type = item.optString("type")
        val title = when (type) {
            "commandExecution" -> item.optString("command").ifBlank { "Command" }
            "fileChange" -> if (completed) "File changes" else "Applying file changes"
            "mcpToolCall" -> listOf(item.optString("server"), item.optString("tool"))
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { "MCP tool" }
            "dynamicToolCall" -> item.optString("tool").ifBlank { "Tool" }
            "collabAgentToolCall" -> item.optString("tool").ifBlank { "Agent task" }
            "webSearch" -> "Web search · ${item.optString("query")}".trimEnd()
            "imageGeneration" -> "Image generation"
            "imageView" -> "View image"
            else -> type.ifBlank { "Tool" }
        }
        val detail = toolDetail(item)
        if (!id.isNullOrBlank() && detail.isNotBlank()) toolDetails[id] = detail
        val status = normalizeStatus(item.optString("status").ifBlank {
            if (completed) "completed" else "inProgress"
        })
        if (completed) {
            events.emit(
                AgentEvent.ToolUpdate(
                    toolCallId = id,
                    title = title,
                    status = status,
                    kind = type,
                    detail = detail.ifBlank { id?.let(toolDetails::get) },
                ),
            )
        } else {
            events.emit(
                AgentEvent.ToolCall(
                    toolCallId = id,
                    title = title,
                    status = status,
                    kind = type,
                    detail = detail.ifBlank { null },
                ),
            )
        }
    }

    private suspend fun appendToolDelta(itemId: String, delta: String) {
        if (itemId.isBlank() || delta.isEmpty()) return
        val detail = toolDetails.merge(itemId, delta) { previous, addition -> previous + addition }
            ?: delta
        events.emit(
            AgentEvent.ToolUpdate(
                toolCallId = itemId,
                title = null,
                status = "in_progress",
                detail = detail.takeLast(MAX_TOOL_DETAIL_CHARS),
            ),
        )
    }

    private fun toolDetail(item: JSONObject): String = when (item.optString("type")) {
        "commandExecution" -> buildString {
            item.optString("cwd").takeIf { it.isNotBlank() }?.let { appendLine("cwd: $it") }
            item.optString("aggregatedOutput").takeIf { it.isNotBlank() }?.let { append(it) }
        }.trim().takeLast(MAX_TOOL_DETAIL_CHARS)

        "fileChange" -> buildString {
            val changes = item.optJSONArray("changes") ?: JSONArray()
            for (index in 0 until changes.length()) {
                val change = changes.optJSONObject(index) ?: continue
                append(change.optString("kind").ifBlank { "update" })
                append(" · ")
                appendLine(change.optString("path"))
            }
        }.trim()

        "mcpToolCall", "dynamicToolCall" -> buildString {
            item.opt("arguments")?.takeUnless { it == JSONObject.NULL }?.let {
                appendLine("Arguments: ${formatJson(it)}")
            }
            item.opt("result")?.takeUnless { it == JSONObject.NULL }?.let {
                appendLine("Result: ${formatJson(it)}")
            }
            item.opt("error")?.takeUnless { it == JSONObject.NULL }?.let {
                appendLine("Error: ${formatJson(it)}")
            }
        }.trim().takeLast(MAX_TOOL_DETAIL_CHARS)

        "collabAgentToolCall" -> item.optString("prompt").take(MAX_TOOL_DETAIL_CHARS)
        "webSearch" -> item.optString("query")
        "imageGeneration" -> item.optString("result").take(MAX_TOOL_DETAIL_CHARS)
        "imageView" -> item.optString("path")
        else -> ""
    }

    private suspend fun replayThread(thread: JSONObject) {
        val turns = thread.optJSONArray("turns") ?: return
        for (turnIndex in 0 until turns.length()) {
            val turn = turns.optJSONObject(turnIndex) ?: continue
            val items = turn.optJSONArray("items") ?: JSONArray()
            for (itemIndex in 0 until items.length()) {
                replayItem(items.optJSONObject(itemIndex), promptIndex = turnIndex.toLong())
            }
            val status = turn.optString("status")
            if (status != "inProgress") {
                events.emit(
                    AgentEvent.TurnComplete(
                        stopReason = if (status == "interrupted") "cancelled" else status,
                    ),
                )
            }
        }
    }

    private suspend fun replayItem(item: JSONObject?, promptIndex: Long) {
        if (item == null) return
        when (item.optString("type")) {
            "userMessage" -> userMessageText(item.optJSONArray("content"))
                .takeIf { it.isNotBlank() }
                ?.let { events.emit(AgentEvent.UserDelta(it, promptIndex)) }

            "agentMessage" -> item.optString("text").takeIf { it.isNotBlank() }
                ?.let { events.emit(AgentEvent.AssistantDelta(it)) }

            "reasoning" -> jsonStringList(item.optJSONArray("summary"))
                .joinToString("\n")
                .takeIf { it.isNotBlank() }
                ?.let { events.emit(AgentEvent.ThoughtDelta(it)) }

            "plan" -> item.optString("text").takeIf { it.isNotBlank() }
                ?.let { events.emit(AgentEvent.ThoughtDelta(it)) }

            "commandExecution", "fileChange", "mcpToolCall", "dynamicToolCall",
            "collabAgentToolCall", "webSearch", "imageGeneration", "imageView",
            -> emitToolItem(item, completed = true)
        }
    }

    private fun emitModel(result: JSONObject) {
        val model = result.optString("model").ifBlank { null } ?: return
        events.tryEmit(AgentEvent.UsageChanged(AgentUsage(modelId = model, modelName = model)))
    }

    private suspend fun emitUsage(tokenUsage: JSONObject?) {
        if (tokenUsage == null) return
        val latest = tokenUsage.optJSONObject("last")
        val usedTokens = latest?.takeIf { it.has("totalTokens") }?.optLong("totalTokens")
        val contextWindow = tokenUsage.takeIf { it.has("modelContextWindow") }
            ?.optLong("modelContextWindow")
        if (usedTokens != null || contextWindow != null) {
            events.emit(
                AgentEvent.UsageChanged(
                    AgentUsage(
                        usedTokens = usedTokens,
                        contextWindowTokens = contextWindow,
                    ),
                ),
            )
        }
    }

    private fun matchesActiveThread(params: JSONObject): Boolean {
        val current = threadId ?: return true
        val eventThread = params.optString("threadId")
        return eventThread.isBlank() || eventThread == current
    }

    private suspend fun requestRpc(method: String, params: JSONObject): JSONObject {
        var retryDelay = 150L
        repeat(4) { attempt ->
            try {
                return requestRpcOnce(method, params)
            } catch (error: CodexRpcException) {
                if (error.code != SERVER_OVERLOADED_CODE || attempt == 3) throw error
                delay(retryDelay + Random.nextLong(0, retryDelay.coerceAtLeast(1)))
                retryDelay *= 2
            }
        }
        error("Unreachable")
    }

    private suspend fun requestRpcOnce(method: String, params: JSONObject): JSONObject {
        val id = nextRequestId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        try {
            sendRaw(
                JSONObject()
                    .put("method", method)
                    .put("id", id)
                    .put("params", params)
                    .toString(),
            )
            return withTimeout(REQUEST_TIMEOUT_MILLIS) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun sendNotification(method: String, params: JSONObject) {
        sendRaw(JSONObject().put("method", method).put("params", params).toString())
    }

    private suspend fun respond(id: Long, result: JSONObject) {
        sendRaw(JSONObject().put("id", id).put("result", result).toString())
    }

    private suspend fun respondError(id: Long, code: Int, message: String) {
        sendRaw(
            JSONObject()
                .put("id", id)
                .put("error", JSONObject().put("code", code).put("message", message))
                .toString(),
        )
    }

    private fun sendRaw(text: String) {
        val currentSocket = synchronized(connectionLock) { socket } ?: error("Socket not open")
        if (!currentSocket.send(text)) error("Failed to enqueue WebSocket frame")
    }

    private fun failAllPending(cause: Throwable) {
        val copy = pending.values.toList()
        pending.clear()
        copy.forEach { it.completeExceptionally(cause) }
    }

    private fun failActiveTurn(cause: Throwable) {
        activeTurnStarted?.completeExceptionally(cause)
        activeTurnCompletion?.completeExceptionally(cause)
        activeTurnStarted = null
        activeTurnCompletion = null
        activeTurnId = null
    }

    private fun closeSocketQuietly(webSocket: WebSocket?) {
        if (webSocket != null && !webSocket.close(1000, "client disconnect")) {
            webSocket.cancel()
        }
    }

    private fun isCurrentConnection(generation: Long): Boolean =
        connectionGeneration.get() == generation

    fun shutdown() {
        disconnectQuiet()
        incomingMessages.close()
        scope.cancel()
        http.dispatcher.executorService.shutdown()
    }

    private data class IncomingMessage(val generation: Long, val text: String)

    private class CodexRpcException(val code: Int, message: String) :
        IllegalStateException(message)

    companion object {
        private const val REQUEST_TIMEOUT_MILLIS = 60_000L
        private const val SERVER_OVERLOADED_CODE = -32001
        private const val MAX_TOOL_DETAIL_CHARS = 16_000

        fun normalizeWebSocketUrl(baseUrl: String): String {
            var url = baseUrl.trim().trimEnd('/')
            when {
                url.startsWith("http://") -> url = "ws://" + url.removePrefix("http://")
                url.startsWith("https://") -> url = "wss://" + url.removePrefix("https://")
                !url.startsWith("ws://") && !url.startsWith("wss://") -> url = "ws://$url"
            }
            return url
        }

        private fun normalizeStatus(status: String): String = when (status) {
            "inProgress" -> "in_progress"
            else -> status.lowercase()
        }

        private fun userMessageText(content: JSONArray?): String = buildString {
            if (content == null) return@buildString
            for (index in 0 until content.length()) {
                val input = content.optJSONObject(index) ?: continue
                if (input.optString("type") == "text") append(input.optString("text"))
            }
        }

        private fun jsonStringList(array: JSONArray?): List<String> = buildList {
            if (array == null) return@buildList
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }

        private fun formatJson(value: Any): String = when (value) {
            is JSONObject -> value.toString(2)
            is JSONArray -> value.toString(2)
            else -> value.toString()
        }

        private fun parseSessions(array: JSONArray?): List<SessionSummary> = buildList {
            if (array == null) return@buildList
            for (index in 0 until array.length()) {
                val thread = array.optJSONObject(index) ?: continue
                val id = thread.optString("id")
                if (id.isBlank()) continue
                val preview = thread.optString("preview").trim()
                val title = thread.optString("name").trim().ifBlank {
                    preview.lineSequence().firstOrNull().orEmpty().take(120).ifBlank {
                        "New Codex thread"
                    }
                }
                add(
                    SessionSummary(
                        sessionId = id,
                        title = title,
                        cwd = thread.optString("cwd"),
                        createdAt = epochSecondsToIso(thread, "createdAt"),
                        updatedAt = epochSecondsToIso(thread, "updatedAt"),
                        messageCount = null,
                    ),
                )
            }
        }

        private fun epochSecondsToIso(value: JSONObject, key: String): String? =
            value.takeIf { it.has(key) && !it.isNull(key) }
                ?.optLong(key)
                ?.let { Instant.ofEpochSecond(it).toString() }
    }
}
