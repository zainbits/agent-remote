package dev.zain.agentremote

import dev.zain.agentremote.agent.DurableAgentClient
import dev.zain.agentremote.agent.SessionStatus
import dev.zain.agentremote.agent.isActive
import dev.zain.agentremote.data.BackendKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One long-poll observer per active durable session that the UI is not already
 * observing. When the watch set is empty every HTTP call is cancelled and no
 * timers remain — there is no idle polling.
 */
class CompletionWatchCoordinator(
    private val scope: CoroutineScope,
    private val onTerminal: (WatchedTurnTerminal) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mutex = Mutex()
    private val watches = linkedMapOf<String, WatchHandle>()
    private val enabled = AtomicBoolean(true)

    data class WatchTarget(
        val backend: BackendKind,
        val sessionId: String,
        val title: String,
        val baseUrl: String,
        val secret: String,
        /**
         * When true, a session that is already terminal on the first snapshot
         * still produces a completion callback (used after leaving mid-turn).
         */
        val notifyIfAlreadyTerminal: Boolean = false,
    )

    data class WatchedTurnTerminal(
        val backend: BackendKind,
        val sessionId: String,
        val title: String,
        val outcome: AgentFinishOutcome,
        val errorMessage: String? = null,
    )

    fun setEnabled(value: Boolean) {
        enabled.set(value)
        if (!value) stopAll()
    }

    fun isEnabled(): Boolean = enabled.get()

    /** Active watch count; zero means no network activity from this coordinator. */
    fun activeWatchCount(): Int = watches.size

    /**
     * Reconcile watches for [backend] with the given active sessions.
     * Targets for other backends are left alone. [excludeSessionId] is skipped
     * (the UI client already long-polls that session).
     */
    fun syncBackend(
        backend: BackendKind,
        targets: Collection<WatchTarget>,
        excludeSessionId: String? = null,
    ) {
        if (!enabled.get()) {
            stopAll()
            return
        }
        scope.launch {
            mutex.withLock {
                val desiredKeys = targets
                    .filter { it.backend == backend }
                    .filter { it.sessionId != excludeSessionId }
                    .filter { it.secret.isNotBlank() && it.baseUrl.isNotBlank() }
                    .associateBy { key(it.backend, it.sessionId) }

                val stale = watches.keys.filter { key ->
                    key.startsWith(backendKeyPrefix(backend)) && key !in desiredKeys
                }
                stale.forEach { stopLocked(it) }

                for ((watchKey, target) in desiredKeys) {
                    val existing = watches[watchKey]
                    if (existing != null &&
                        existing.target.baseUrl == target.baseUrl &&
                        existing.target.secret == target.secret
                    ) {
                        // Keep the long-poll; refresh title / already-terminal flag.
                        existing.target = target.copy(
                            notifyIfAlreadyTerminal = existing.target.notifyIfAlreadyTerminal ||
                                target.notifyIfAlreadyTerminal,
                        )
                        continue
                    }
                    stopLocked(watchKey)
                    startLocked(target)
                }
            }
        }
    }

    /** Ensure a single session is watched (e.g. after leaving mid-turn). */
    fun watch(target: WatchTarget) {
        if (!enabled.get()) return
        if (target.secret.isBlank() || target.baseUrl.isBlank()) return
        scope.launch {
            mutex.withLock {
                val watchKey = key(target.backend, target.sessionId)
                val existing = watches[watchKey]
                if (existing != null &&
                    existing.target.baseUrl == target.baseUrl &&
                    existing.target.secret == target.secret
                ) {
                    existing.target = target.copy(
                        notifyIfAlreadyTerminal = existing.target.notifyIfAlreadyTerminal ||
                            target.notifyIfAlreadyTerminal,
                    )
                    return@withLock
                }
                stopLocked(watchKey)
                startLocked(target)
            }
        }
    }

    fun unwatch(backend: BackendKind, sessionId: String) {
        scope.launch {
            mutex.withLock {
                stopLocked(key(backend, sessionId))
            }
        }
    }

    fun stopAll() {
        scope.launch {
            mutex.withLock {
                watches.keys.toList().forEach { stopLocked(it) }
            }
        }
    }

    fun shutdown() {
        enabled.set(false)
        watches.values.forEach { it.job.cancel() }
        watches.clear()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun startLocked(target: WatchTarget) {
        val watchKey = key(target.backend, target.sessionId)
        val handle = WatchHandle(target)
        handle.job = scope.launch(Dispatchers.IO) {
            try {
                observeUntilTerminal(handle)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Transient host/network failures: retry with backoff while still watched.
                while (scope.isActive && enabled.get() && watches[watchKey] === handle) {
                    delay(RETRY_DELAY_MILLIS)
                    try {
                        observeUntilTerminal(handle)
                        return@launch
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // keep retrying until unwatched or process dies
                    }
                }
            } finally {
                mutex.withLock {
                    if (watches[watchKey] === handle) {
                        watches.remove(watchKey)
                    }
                }
            }
        }
        watches[watchKey] = handle
    }

    private fun stopLocked(watchKey: String) {
        watches.remove(watchKey)?.job?.cancel()
    }

    private suspend fun observeUntilTerminal(handle: WatchHandle) {
        val target = handle.target
        val baseUrl = DurableAgentClient.normalizeBaseUrl(target.baseUrl)
        val snapshot = getJson(
            baseUrl = baseUrl,
            secret = target.secret,
            path = "/api/v1/sessions/${encodePath(target.sessionId)}",
        )
        val session = snapshot.optJSONObject("session")
            ?: error("Durable host returned no session")
        val status = session.optString("status").toSessionStatus()
        val title = session.optString("title").ifBlank { target.title }
        handle.target = target.copy(title = title)

        if (!status.isActive) {
            if (target.notifyIfAlreadyTerminal) {
                onTerminal(
                    WatchedTurnTerminal(
                        backend = target.backend,
                        sessionId = target.sessionId,
                        title = title,
                        outcome = status.toOutcome(),
                    ),
                )
            }
            return
        }

        var cursor = snapshot.optLong("latestEventId", 0L)
        while (scope.isActive && enabled.get()) {
            val current = handle.target
            val response = getJson(
                baseUrl = baseUrl,
                secret = current.secret,
                path = "/api/v1/sessions/${encodePath(current.sessionId)}/events" +
                    "?after=$cursor&wait=20",
            )
            val events = response.optJSONArray("events")
            if (events != null) {
                for (index in 0 until events.length()) {
                    val event = events.optJSONObject(index) ?: continue
                    cursor = maxOf(cursor, event.optLong("id", cursor))
                    val terminal = terminalFromEvent(event) ?: continue
                    val eventTitle = response.optJSONObject("session")
                        ?.optString("title")
                        ?.takeIf { it.isNotBlank() }
                        ?: handle.target.title
                    onTerminal(
                        WatchedTurnTerminal(
                            backend = current.backend,
                            sessionId = current.sessionId,
                            title = eventTitle,
                            outcome = terminal.first,
                            errorMessage = terminal.second,
                        ),
                    )
                    return
                }
            }
            response.optJSONObject("session")?.let { live ->
                val liveTitle = live.optString("title")
                if (liveTitle.isNotBlank()) {
                    handle.target = handle.target.copy(title = liveTitle)
                }
                val liveStatus = live.optString("status").toSessionStatus()
                if (!liveStatus.isActive) {
                    onTerminal(
                        WatchedTurnTerminal(
                            backend = current.backend,
                            sessionId = current.sessionId,
                            title = handle.target.title,
                            outcome = liveStatus.toOutcome(),
                        ),
                    )
                    return
                }
            }
        }
    }

    private fun terminalFromEvent(event: JSONObject): Pair<AgentFinishOutcome, String?>? {
        val data = event.optJSONObject("data") ?: JSONObject()
        return when (event.optString("type")) {
            "turn.completed" -> AgentFinishOutcome.COMPLETED to null
            "turn.cancelled" -> AgentFinishOutcome.CANCELLED to null
            "turn.failed" -> AgentFinishOutcome.FAILED to
                data.optString("error").takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private suspend fun getJson(
        baseUrl: String,
        secret: String,
        path: String,
    ): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $secret")
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
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
    }

    private class WatchHandle(
        @Volatile var target: WatchTarget,
    ) {
        lateinit var job: Job
    }

    companion object {
        private const val RETRY_DELAY_MILLIS = 5_000L

        fun key(backend: BackendKind, sessionId: String): String =
            "${backend.name}:$sessionId"

        private fun backendKeyPrefix(backend: BackendKind): String =
            "${backend.name}:"

        private fun encodePath(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")

        private fun String.toSessionStatus(): SessionStatus = when (lowercase()) {
            "queued" -> SessionStatus.QUEUED
            "running" -> SessionStatus.RUNNING
            "cancelling" -> SessionStatus.CANCELLING
            "failed" -> SessionStatus.FAILED
            "cancelled" -> SessionStatus.CANCELLED
            else -> SessionStatus.IDLE
        }

        private fun SessionStatus.toOutcome(): AgentFinishOutcome = when (this) {
            SessionStatus.FAILED -> AgentFinishOutcome.FAILED
            SessionStatus.CANCELLED -> AgentFinishOutcome.CANCELLED
            else -> AgentFinishOutcome.COMPLETED
        }

        /**
         * Pure helper for tests and call sites: suppress a system notification when
         * the user is already looking at that live chat.
         */
        fun shouldNotify(
            notifyEnabled: Boolean,
            appInForeground: Boolean,
            screenIsChat: Boolean,
            viewingSessionId: String?,
            viewingBackend: BackendKind?,
            finishedBackend: BackendKind,
            finishedSessionId: String,
        ): Boolean {
            if (!notifyEnabled) return false
            if (appInForeground &&
                screenIsChat &&
                viewingSessionId == finishedSessionId &&
                viewingBackend == finishedBackend
            ) {
                return false
            }
            return true
        }
    }
}
