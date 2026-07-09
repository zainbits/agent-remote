package dev.zain.agentremote.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
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
    private val nextId = AtomicLong(1)
    private val connected = AtomicBoolean(false)

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var sessionId: String? = null
    private var workingDirectory: String = "/home/user"

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()

    private val events = MutableSharedFlow<AgentEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun events(): Flow<AgentEvent> = events.asSharedFlow()

    override fun isConnected(): Boolean = connected.get() && sessionId != null

    override suspend fun connectNew(baseUrl: String, secret: String, workingDirectory: String) {
        openAndInitialize(baseUrl, secret, workingDirectory)
        val newSession = requestRpc(
            method = "session/new",
            params = JSONObject()
                .put("cwd", this.workingDirectory)
                .put("mcpServers", JSONArray()),
        )
        val sid = newSession.optString("sessionId").ifBlank {
            error("session/new returned no sessionId")
        }
        finishConnected(sid)
    }

    override suspend fun connectLoad(
        baseUrl: String,
        secret: String,
        workingDirectory: String,
        sessionId: String,
    ) {
        openAndInitialize(baseUrl, secret, workingDirectory)
        requestRpc(
            method = "session/load",
            params = JSONObject()
                .put("sessionId", sessionId)
                .put("cwd", this.workingDirectory)
                .put("mcpServers", JSONArray()),
        )
        // session/load may put sessionId in result._meta or use the requested id
        finishConnected(sessionId)
        events.emit(AgentEvent.TurnComplete(stopReason = "loaded"))
    }

    private suspend fun openAndInitialize(
        baseUrl: String,
        secret: String,
        workingDirectory: String,
    ) {
        disconnectQuiet()
        this.workingDirectory = workingDirectory.ifBlank { "/home/user" }

        val url = buildWebSocketUrl(baseUrl, secret)
        events.emit(AgentEvent.ConnectionChanged(ConnectionState.Connecting))

        val opened = CompletableDeferred<Unit>()

        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleMessage(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!opened.isCompleted) {
                    opened.completeExceptionally(t)
                }
                scope.launch {
                    connected.set(false)
                    sessionId = null
                    failAllPending(t)
                    events.emit(
                        AgentEvent.ConnectionChanged(
                            ConnectionState.Error(t.message ?: "WebSocket failure"),
                        ),
                    )
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    connected.set(false)
                    sessionId = null
                    events.emit(AgentEvent.ConnectionChanged(ConnectionState.Disconnected))
                }
            }
        }

        socket = http.newWebSocket(request, listener)

        try {
            withTimeout(20_000) { opened.await() }

            val initResult = requestRpc(
                method = "initialize",
                params = JSONObject()
                    .put("protocolVersion", 1)
                    .put(
                        "clientCapabilities",
                        JSONObject()
                            .put("fs", JSONObject().put("readTextFile", true).put("writeTextFile", true))
                            .put("terminal", true),
                    )
                    .put(
                        "clientInfo",
                        JSONObject()
                            .put("name", "AgentRemote")
                            .put("title", "AgentRemote")
                            .put("version", "0.1.0"),
                    ),
            )

            // Prefer host-reported cwd when settings left blank-ish, but keep client cwd for session.
            val meta = initResult.optJSONObject("_meta")
            val hostCwd = meta?.optString("currentWorkingDirectory").orEmpty()
            if (this.workingDirectory.isBlank() && hostCwd.isNotBlank()) {
                this.workingDirectory = hostCwd
            }

            val caps = initResult.optJSONObject("agentCapabilities")
            // loadSession is checked by callers of connectLoad; new always works.
            if (caps != null && !caps.optBoolean("loadSession", true)) {
                // still allow new sessions
            }
        } catch (t: Throwable) {
            disconnectQuiet()
            events.emit(
                AgentEvent.ConnectionChanged(
                    ConnectionState.Error(t.message ?: "Failed to connect"),
                ),
            )
            throw t
        }
    }

    private suspend fun finishConnected(sid: String) {
        sessionId = sid
        connected.set(true)
        events.emit(
            AgentEvent.ConnectionChanged(
                ConnectionState.Connected(sessionId = sid, cwd = workingDirectory),
            ),
        )
    }

    override suspend fun disconnect() {
        disconnectQuiet()
        events.emit(AgentEvent.ConnectionChanged(ConnectionState.Disconnected))
    }

    private fun disconnectQuiet() {
        connected.set(false)
        sessionId = null
        failAllPending(IllegalStateException("Disconnected"))
        socket?.close(1000, "client disconnect")
        socket = null
    }

    override suspend fun sendPrompt(text: String) {
        val sid = sessionId ?: error("Not connected")
        val prompt = JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", text),
        )
        requestRpc(
            method = "session/prompt",
            params = JSONObject()
                .put("sessionId", sid)
                .put("prompt", prompt),
        )
        events.emit(AgentEvent.TurnComplete(stopReason = null))
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
                    deferred?.completeExceptionally(
                        IllegalStateException(err.optString("message", "RPC error")),
                    )
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
                            respond(msg.getLong("id"), JSONObject())
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleSessionUpdate(params: JSONObject?) {
        if (params == null) return
        val update = params.optJSONObject("update") ?: return
        when (update.optString("sessionUpdate")) {
            "user_message_chunk" -> {
                val text = update.optJSONObject("content")?.optString("text").orEmpty()
                if (text.isNotEmpty()) events.emit(AgentEvent.UserDelta(text))
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

    private suspend fun respond(id: Long, result: JSONObject) {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)
        sendRaw(encodeJson(payload))
    }

    private suspend fun sendRaw(text: String) = withContext(Dispatchers.IO) {
        val ws = socket ?: error("Socket not open")
        if (!ws.send(text)) {
            error("Failed to enqueue WebSocket frame")
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
        scope.cancel()
        socket?.cancel()
        http.dispatcher.executorService.shutdown()
    }

    companion object {
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
