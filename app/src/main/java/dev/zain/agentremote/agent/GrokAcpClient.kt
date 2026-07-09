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
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun events(): Flow<AgentEvent> = events.asSharedFlow()

    override fun isConnected(): Boolean = connected.get() && sessionId != null

    override suspend fun connect(baseUrl: String, secret: String, workingDirectory: String) {
        disconnect()
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

            requestRpc(
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
                        JSONObject().put("name", "AgentRemote").put("version", "0.1.0"),
                    ),
            )

            // ACP optional auth notification; ignore failures on hosts that do not require it.
            runCatching {
                notify("authenticated", JSONObject().put("methodId", "none"))
            }

            val newSession = requestRpc(
                method = "session/new",
                params = JSONObject()
                    .put("cwd", this.workingDirectory)
                    .put("mcpServers", JSONArray()),
            )
            val sid = newSession.optString("sessionId").ifBlank {
                error("session/new returned no sessionId")
            }
            sessionId = sid
            connected.set(true)
            events.emit(AgentEvent.ConnectionChanged(ConnectionState.Connected(sid)))
        } catch (t: Throwable) {
            disconnect()
            events.emit(
                AgentEvent.ConnectionChanged(
                    ConnectionState.Error(t.message ?: "Failed to connect"),
                ),
            )
            throw t
        }
    }

    override suspend fun disconnect() {
        connected.set(false)
        sessionId = null
        failAllPending(IllegalStateException("Disconnected"))
        socket?.close(1000, "client disconnect")
        socket = null
        events.emit(AgentEvent.ConnectionChanged(ConnectionState.Disconnected))
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
                        // Ignore unknown notifications; respond to requests with empty result if needed.
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
                        title = update.optString("title").ifBlank { update.optString("kind", "tool") },
                        status = update.optString("status").ifBlank { null },
                    ),
                )
            }

            "tool_call_update" -> {
                events.emit(
                    AgentEvent.ToolUpdate(
                        title = update.optString("title").ifBlank { null },
                        status = update.optString("status").ifBlank { null },
                    ),
                )
            }

            "plan" -> {
                // Surface plan text if present.
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
        // Fallback shape used by some ACP agents.
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
        sendRaw(payload.toString())
        return try {
            withTimeout(300_000) { deferred.await() }
        } catch (t: Throwable) {
            pending.remove(id)
            throw t
        }
    }

    private suspend fun notify(method: String, params: JSONObject) {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
        sendRaw(payload.toString())
    }

    private suspend fun respond(id: Long, result: JSONObject) {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)
        sendRaw(payload.toString())
    }

    private suspend fun sendRaw(text: String) = withContext(Dispatchers.IO) {
        val ws = socket ?: error("Socket not open")
        if (!ws.send(text)) {
            error("Failed to enqueue WebSocket frame")
        }
    }

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

            // Accept either bare host:port, ws://host:port, or full path already including /ws
            if (!url.contains("/ws")) {
                url = "$url/ws"
            }

            val sep = if (url.contains('?')) '&' else '?'
            return if (secret.isNotBlank()) {
                // Avoid duplicating server-key if user pasted a full URL with secret.
                if (url.contains("server-key=")) url else "$url${sep}server-key=$secret"
            } else {
                url
            }
        }

        fun newMessageId(): String = UUID.randomUUID().toString()
    }
}
