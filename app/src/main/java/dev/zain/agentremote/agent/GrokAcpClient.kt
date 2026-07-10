package dev.zain.agentremote.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Grok Build ACP client over WebSocket.
 *
 * Host is expected to run with always-approve, e.g.:
 *   grok agent --always-approve serve --bind 0.0.0.0:2419 --secret <token>
 *
 * URL shape: ws://host:2419/ws?server-key=<token>
 */
class GrokAcpClient : AgentBackend {
    override val name: String = "Grok Build"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val incomingMessages = Channel<IncomingMessage>(Channel.UNLIMITED)
    private val nextId = AtomicLong(1)
    private val connected = AtomicBoolean(false)
    private val connectionGeneration = AtomicLong(0)
    private val connectionLock = Any()

    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var sessionId: String? = null

    private var opening: CompletableDeferred<Unit>? = null
    private var workingDirectory: String = "/home/user"

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()

    private val events = MutableSharedFlow<AgentEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        // Preserve WebSocket frame order so the prompt response cannot overtake
        // its final streamed delta and send command output into the chat thread.
        scope.launch {
            for (incoming in incomingMessages) {
                if (isCurrentConnection(incoming.generation)) {
                    handleMessage(incoming.text)
                }
            }
        }
    }

    override fun events(): Flow<AgentEvent> = events.asSharedFlow()

    override fun isConnected(): Boolean = connected.get() && sessionId != null

    override suspend fun connectNew(baseUrl: String, secret: String, workingDirectory: String) {
        val generation = openAndInitialize(baseUrl, secret, workingDirectory)
        try {
            val newSession = requestRpc(
                method = "session/new",
                params = JSONObject()
                    .put("cwd", this.workingDirectory)
                    .put("mcpServers", JSONArray()),
            )
            check(isCurrentConnection(generation)) { "Connection was replaced" }
            val sid = newSession.optString("sessionId").ifBlank {
                error("session/new returned no sessionId")
            }
            emitModelUsage(newSession.optJSONObject("models"))
            finishConnected(sid, generation)
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
        val generation = openAndInitialize(baseUrl, secret, workingDirectory)
        try {
            val loadedSession = requestRpc(
                method = "session/load",
                params = JSONObject()
                    .put("sessionId", sessionId)
                    .put("cwd", this.workingDirectory)
                    .put("mcpServers", JSONArray()),
            )
            check(isCurrentConnection(generation)) { "Connection was replaced" }
            emitModelUsage(loadedSession.optJSONObject("models"))
            // session/load may put sessionId in result._meta or use the requested id
            finishConnected(sessionId, generation)
            events.emit(AgentEvent.TurnComplete(stopReason = "loaded"))
        } catch (t: Throwable) {
            disconnectQuiet(expectedGeneration = generation)
            throw t
        }
    }

    private suspend fun openAndInitialize(
        baseUrl: String,
        secret: String,
        workingDirectory: String,
    ): Long {
        this.workingDirectory = workingDirectory.ifBlank { "/home/user" }

        val url = buildWebSocketUrl(baseUrl, secret)
        val request = Request.Builder().url(url).build()
        val opened = CompletableDeferred<Unit>()
        val generation = beginConnection(opened)

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
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                retireConnection(
                    generation = generation,
                    cause = IllegalStateException("WebSocket closed ($code)"),
                    state = ConnectionState.Disconnected,
                    opened = opened,
                )
            }
        }

        val newSocket = try {
            http.newWebSocket(request, listener)
        } catch (t: Throwable) {
            disconnectQuiet(expectedGeneration = generation)
            throw t
        }
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

            val initResult = requestRpc(
                method = "initialize",
                params = JSONObject()
                    .put("protocolVersion", 1)
                    .put(
                        "clientCapabilities",
                        // AgentRemote is a remote UI for the host workspace. An empty
                        // capability object keeps filesystem and terminal execution on
                        // the Grok host instead of delegating it to the Android client.
                        JSONObject(),
                    )
                    .put(
                        "clientInfo",
                        JSONObject()
                            .put("name", "AgentRemote")
                            .put("title", "AgentRemote")
                            .put("version", "0.1.0"),
                    ),
            )
            check(isCurrentConnection(generation)) { "Connection was replaced" }

            // Prefer host-reported cwd when settings left blank-ish, but keep client cwd for session.
            val meta = initResult.optJSONObject("_meta")
            val hostCwd = meta?.optString("currentWorkingDirectory").orEmpty()
            if (this.workingDirectory.isBlank() && hostCwd.isNotBlank()) {
                this.workingDirectory = hostCwd
            }
            events.emit(
                AgentEvent.SlashCommandsChanged(
                    parseAvailableCommands(meta?.optJSONArray("availableCommands")),
                ),
            )
            emitModelUsage(meta?.optJSONObject("modelState"))

            val caps = initResult.optJSONObject("agentCapabilities")
            // loadSession is checked by callers of connectLoad; new always works.
            if (caps != null && !caps.optBoolean("loadSession", true)) {
                // still allow new sessions
            }
        } catch (t: Throwable) {
            disconnectQuiet(expectedGeneration = generation)
            throw t
        }
        return generation
    }

    private fun finishConnected(sid: String, generation: Long) {
        synchronized(connectionLock) {
            check(isCurrentConnection(generation)) { "Connection was replaced" }
            sessionId = sid
            connected.set(true)
            events.tryEmit(
                AgentEvent.ConnectionChanged(
                    ConnectionState.Connected(sessionId = sid, cwd = workingDirectory),
                ),
            )
        }
    }

    override suspend fun disconnect() {
        disconnectQuiet(notify = true)
    }

    private fun beginConnection(opened: CompletableDeferred<Unit>): Long {
        val oldSocket: WebSocket?
        val generation: Long
        synchronized(connectionLock) {
            generation = connectionGeneration.incrementAndGet()
            connected.set(false)
            sessionId = null
            opening?.completeExceptionally(IllegalStateException("Connection was replaced"))
            opening = opened
            failAllPending(IllegalStateException("Connection was replaced"))
            oldSocket = socket
            socket = null
            events.tryEmit(AgentEvent.ConnectionChanged(ConnectionState.Connecting))
        }
        closeSocketQuietly(oldSocket)
        return generation
    }

    private fun retireConnection(
        generation: Long,
        cause: Throwable,
        state: ConnectionState,
        opened: CompletableDeferred<Unit>,
    ) {
        synchronized(connectionLock) {
            if (!isCurrentConnection(generation)) return
            connectionGeneration.incrementAndGet()
            connected.set(false)
            sessionId = null
            socket = null
            if (opening === opened) {
                opening = null
                opened.completeExceptionally(cause)
            }
            failAllPending(cause)
            events.tryEmit(AgentEvent.ConnectionChanged(state))
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
            sessionId = null
            opening?.completeExceptionally(IllegalStateException("Disconnected"))
            opening = null
            failAllPending(IllegalStateException("Disconnected"))
            oldSocket = socket
            socket = null
            if (notify) {
                events.tryEmit(AgentEvent.ConnectionChanged(ConnectionState.Disconnected))
            }
        }
        closeSocketQuietly(oldSocket)
    }

    private fun closeSocketQuietly(webSocket: WebSocket?) {
        if (webSocket != null && !webSocket.close(1000, "client disconnect")) {
            webSocket.cancel()
        }
    }

    override suspend fun sendPrompt(text: String) {
        val sid = sessionId ?: error("Not connected")
        val prompt = JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", text),
        )
        val result = requestRpc(
            method = "session/prompt",
            params = JSONObject()
                .put("sessionId", sid)
                .put("prompt", prompt),
        )
        events.emit(
            AgentEvent.TurnComplete(
                stopReason = result.optString("stopReason").ifBlank { null },
            ),
        )
    }

    override suspend fun cancelCurrentRequest(): Boolean {
        val sid = sessionId ?: return false
        sendNotification(
            method = "session/cancel",
            params = JSONObject().put("sessionId", sid),
        )
        return true
    }

    private suspend fun handleMessage(raw: String) {
        val msg = runCatching { JSONObject(raw) }.getOrElse {
            events.emit(AgentEvent.Error("Invalid JSON from agent: ${it.message}"))
            return
        }

        when {
            msg.has("id") && (msg.has("result") || msg.has("error")) -> {
                val id = msg.optLong("id", -1L)
                val deferred = pending.remove(id)
                if (msg.has("error")) {
                    val err = msg.getJSONObject("error")
                    val message = err.optString("message", "RPC error")
                    val failure = if (err.optInt("code") == REQUEST_CANCELLED_ERROR_CODE) {
                        AgentRequestCancelledException(message)
                    } else {
                        IllegalStateException(message)
                    }
                    deferred?.completeExceptionally(failure)
                } else {
                    deferred?.complete(msg.optJSONObject("result") ?: JSONObject())
                }
            }

            msg.has("method") -> {
                when (msg.optString("method")) {
                    "session/update" -> handleSessionUpdate(msg.optJSONObject("params"))
                    "session/request_permission" -> autoApprovePermission(msg)
                    else -> {
                        if (msg.has("id")) {
                            respondError(
                                id = msg.getLong("id"),
                                code = METHOD_NOT_FOUND_ERROR_CODE,
                                message = "Method not supported by AgentRemote: ${msg.optString("method")}",
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleSessionUpdate(params: JSONObject?) {
        if (params == null) return
        val update = params.optJSONObject("update") ?: return
        emitUsage(update, params.optJSONObject("_meta"))
        when (update.optString("sessionUpdate")) {
            "available_commands_update" -> {
                events.emit(
                    AgentEvent.SlashCommandsChanged(
                        parseAvailableCommands(update.optJSONArray("availableCommands")),
                    ),
                )
            }

            "user_message_chunk" -> {
                val text = update.optJSONObject("content")?.optString("text").orEmpty()
                val promptIndex = update.optJSONObject("_meta")
                    ?.takeIf { it.has("promptIndex") && !it.isNull("promptIndex") }
                    ?.optLong("promptIndex")
                if (text.isNotEmpty()) {
                    events.emit(AgentEvent.UserDelta(text, promptIndex = promptIndex))
                }
            }

            "agent_message_chunk" -> {
                val text = update.optJSONObject("content")?.optString("text").orEmpty()
                if (text.isNotEmpty()) events.emit(AgentEvent.AssistantDelta(text))
            }

            "agent_thought_chunk" -> {
                val text = update.optJSONObject("content")?.optString("text").orEmpty()
                if (text.isNotEmpty()) events.emit(AgentEvent.ThoughtDelta(text))
            }

            "tool_call" -> {
                events.emit(
                    AgentEvent.ToolCall(
                        toolCallId = update.optString("toolCallId").ifBlank { null },
                        title = update.optString("title").ifBlank {
                            update.optString("kind", "tool")
                        },
                        status = update.optString("status").ifBlank { "pending" },
                        kind = toolKind(update),
                        detail = toolDetail(update),
                    ),
                )
            }

            "tool_call_update" -> {
                events.emit(
                    AgentEvent.ToolUpdate(
                        toolCallId = update.optString("toolCallId").ifBlank { null },
                        title = update.optString("title").ifBlank { null },
                        status = update.optString("status").ifBlank { null },
                        kind = toolKind(update),
                        detail = toolDetail(update),
                    ),
                )
            }

            "plan" -> {
                val entries = update.optJSONArray("entries")
                if (entries != null) {
                    val summary = buildString {
                        for (i in 0 until entries.length()) {
                            val e = entries.optJSONObject(i) ?: continue
                            appendLine("• ${e.optString("content")}")
                        }
                    }.trim()
                    if (summary.isNotEmpty()) {
                        events.emit(AgentEvent.ThoughtDelta("\n$summary\n"))
                    }
                }
            }
        }
    }

    /**
     * Grok 0.2.93 reports current context use in each notification's custom
     * `params._meta.totalTokens`. Newer ACP agents can instead use the standard
     * `usage_update` shape, so accept both.
     */
    private suspend fun emitUsage(update: JSONObject, notificationMeta: JSONObject?) {
        val isStandardUsage = update.optString("sessionUpdate") == "usage_update"
        val hasCustomTotal = notificationMeta?.has("totalTokens") == true &&
            !notificationMeta.isNull("totalTokens")
        val cost = if (isStandardUsage) update.optJSONObject("cost") else null

        if (!isStandardUsage && !hasCustomTotal) return

        events.emit(
            AgentEvent.UsageChanged(
                AgentUsage(
                    usedTokens = when {
                        isStandardUsage && update.has("used") && !update.isNull("used") ->
                            update.optLong("used")
                        hasCustomTotal -> notificationMeta.optLong("totalTokens")
                        else -> null
                    },
                    contextWindowTokens = if (
                        isStandardUsage && update.has("size") && !update.isNull("size")
                    ) {
                        update.optLong("size")
                    } else {
                        null
                    },
                    costAmount = cost?.takeIf { it.has("amount") && !it.isNull("amount") }
                        ?.optDouble("amount"),
                    costCurrency = cost?.optString("currency")?.ifBlank { null },
                ),
            ),
        )
    }

    private suspend fun emitModelUsage(modelState: JSONObject?) {
        if (modelState == null) return
        val currentModelId = modelState.optString("currentModelId").ifBlank { null }
        val availableModels = modelState.optJSONArray("availableModels")
        val currentModel = availableModels?.let { models ->
            (0 until models.length())
                .asSequence()
                .mapNotNull { index -> models.optJSONObject(index) }
                .firstOrNull { model ->
                    currentModelId == null || model.optString("modelId") == currentModelId
                }
        }
        val contextSize = currentModel
            ?.optJSONObject("_meta")
            ?.takeIf { it.has("totalContextTokens") && !it.isNull("totalContextTokens") }
            ?.optLong("totalContextTokens")

        if (currentModelId == null && currentModel == null && contextSize == null) return

        events.emit(
            AgentEvent.UsageChanged(
                AgentUsage(
                    contextWindowTokens = contextSize,
                    modelId = currentModelId ?: currentModel?.optString("modelId")?.ifBlank { null },
                    modelName = currentModel?.optString("name")?.ifBlank { null },
                ),
            ),
        )
    }

    private fun toolKind(update: JSONObject): String? {
        val metaTool = update.optJSONObject("_meta")?.optJSONObject("x.ai/tool")
        val kind = metaTool?.optString("name")
            ?.ifBlank { null }
            ?: metaTool?.optString("kind")?.ifBlank { null }
            ?: update.optString("kind").ifBlank { null }
        return kind
    }

    /**
     * Compact expandable body: input path/command + truncated output.
     */
    private fun toolDetail(update: JSONObject): String? {
        val parts = mutableListOf<String>()

        val rawInput = update.optJSONObject("rawInput")
        if (rawInput != null) {
            val inputLine = summarizeJsonObject(rawInput, maxLen = 400)
            if (inputLine.isNotBlank()) parts += "in: $inputLine"
        }

        val locations = update.optJSONArray("locations")
        if (locations != null && locations.length() > 0) {
            val paths = buildString {
                for (i in 0 until minOf(locations.length(), 4)) {
                    val loc = locations.optJSONObject(i) ?: continue
                    val path = loc.optString("path")
                    if (path.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(path)
                    }
                }
            }
            if (paths.isNotBlank() && parts.none { it.contains(paths.take(40)) }) {
                parts += "path: $paths"
            }
        }

        val rawOutput = update.opt("rawOutput")
        when (rawOutput) {
            is JSONObject -> {
                val out = extractOutputText(rawOutput)
                if (out.isNotBlank()) parts += "out:\n${out.take(1200)}"
            }
            is String -> if (rawOutput.isNotBlank()) parts += "out:\n${rawOutput.take(1200)}"
        }

        val content = update.opt("content")
        if (content is JSONArray) {
            val texts = buildString {
                for (i in 0 until content.length()) {
                    val c = content.optJSONObject(i) ?: continue
                    val t = c.optString("text").ifBlank {
                        c.optJSONObject("content")?.optString("text").orEmpty()
                    }
                    if (t.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(t)
                    }
                }
            }
            if (texts.isNotBlank()) parts += texts.take(1200)
        }

        return parts.joinToString("\n").ifBlank { null }
    }

    private fun extractOutputText(obj: JSONObject): String {
        // Nested shapes from Grok tools (ListDir, Read, shell, etc.)
        obj.optJSONObject("Content")?.optString("content")?.takeIf { it.isNotBlank() }?.let {
            return it
        }
        obj.optString("content").takeIf { it.isNotBlank() }?.let { return it }
        obj.optString("stdout").takeIf { it.isNotBlank() }?.let { return it }
        obj.optString("text").takeIf { it.isNotBlank() }?.let { return it }
        return summarizeJsonObject(obj, maxLen = 800)
    }

    private fun summarizeJsonObject(obj: JSONObject, maxLen: Int): String {
        // Prefer common path-like fields
        val preferred = listOf(
            "target_file", "target_directory", "path", "command",
            "query", "pattern", "url", "file_path", "directory",
        )
        for (key in preferred) {
            val v = obj.optString(key)
            if (v.isNotBlank()) return v.take(maxLen)
        }
        val keys = obj.keys().asSequence().toList()
        val compact = keys.take(6).joinToString(", ") { k ->
            val v = obj.opt(k)
            val vs = when (v) {
                is JSONObject, is JSONArray -> "…"
                null, JSONObject.NULL -> "null"
                else -> v.toString().take(80)
            }
            "$k=$vs"
        }
        return compact.take(maxLen)
    }

    /**
     * Host should use --always-approve. If a permission request still arrives,
     * auto-allow so the phone never blocks the agent.
     */
    private suspend fun autoApprovePermission(msg: JSONObject) {
        if (!msg.has("id")) return
        val result = JSONObject()
            .put(
                "outcome",
                JSONObject()
                    .put("outcome", "selected")
                    .put("optionId", "allow-always"),
            )
        result.put("permissionOutcome", "allow_always")
        respond(msg.getLong("id"), result)
    }

    private suspend fun requestRpc(method: String, params: JSONObject): JSONObject {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
        sendRaw(encodeJson(payload))
        return try {
            withTimeout(300_000) { deferred.await() }
        } catch (t: Throwable) {
            pending.remove(id)
            throw t
        }
    }

    private suspend fun sendNotification(method: String, params: JSONObject) {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
        sendRaw(encodeJson(payload))
    }

    private suspend fun respond(id: Long, result: JSONObject) {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)
        sendRaw(encodeJson(payload))
    }

    private suspend fun respondError(id: Long, code: Int, message: String) {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put(
                "error",
                JSONObject()
                    .put("code", code)
                    .put("message", message),
            )
        sendRaw(encodeJson(payload))
    }

    private suspend fun sendRaw(text: String) = withContext(Dispatchers.IO) {
        val ws = synchronized(connectionLock) { socket } ?: error("Socket not open")
        if (!ws.send(text)) {
            error("Failed to enqueue WebSocket frame")
        }
    }

    private fun parseAvailableCommands(rawCommands: JSONArray?): List<SlashCommand> {
        if (rawCommands == null) return emptyList()
        return buildList {
            for (index in 0 until rawCommands.length()) {
                val raw = rawCommands.optJSONObject(index) ?: continue
                val name = raw.optString("name")
                    .trim()
                    .removePrefix("/")
                if (name.isBlank()) continue
                add(
                    SlashCommand(
                        name = name,
                        description = raw.optString("description").ifBlank { "Run /$name" },
                        argumentHint = raw.optJSONObject("input")
                            ?.optString("hint")
                            ?.ifBlank { null },
                    ),
                )
            }
        }
    }

    /**
     * org.json escapes `/` as `\/`. Grok's ACP decoder uses zero-copy string
     * borrows and rejects those escapes ("expected a borrowed string").
     */
    private fun encodeJson(obj: JSONObject): String =
        obj.toString().replace("\\/", "/")

    private fun failAllPending(t: Throwable) {
        val copy = pending.values.toList()
        pending.clear()
        copy.forEach { it.completeExceptionally(t) }
    }

    fun shutdown() {
        disconnectQuiet()
        incomingMessages.close()
        scope.cancel()
        http.dispatcher.executorService.shutdown()
    }

    private fun isCurrentConnection(generation: Long): Boolean =
        connectionGeneration.get() == generation

    private data class IncomingMessage(
        val generation: Long,
        val text: String,
    )

    companion object {
        private const val METHOD_NOT_FOUND_ERROR_CODE = -32601
        private const val REQUEST_CANCELLED_ERROR_CODE = -32800

        fun buildWebSocketUrl(baseUrl: String, secret: String): String {
            var url = baseUrl.trim().trimEnd('/')
            if (url.startsWith("http://")) url = "ws://" + url.removePrefix("http://")
            if (url.startsWith("https://")) url = "wss://" + url.removePrefix("https://")
            if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
                url = "ws://$url"
            }

            if (!url.contains("/ws")) {
                url = "$url/ws"
            }

            val sep = if (url.contains('?')) '&' else '?'
            return if (secret.isNotBlank()) {
                if (url.contains("server-key=")) url else "$url${sep}server-key=$secret"
            } else {
                url
            }
        }

        fun newMessageId(): String = UUID.randomUUID().toString()
    }
}
