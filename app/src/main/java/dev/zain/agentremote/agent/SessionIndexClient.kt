package dev.zain.agentremote.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Host-side session list (Grok ACP has no session/list).
 *
 * Default: HTTP on agent port + 1 (2419 → 2420), same secret as the agent.
 */
class SessionIndexClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun listSessions(
        agentBaseUrl: String,
        secret: String,
        cwd: String,
        limit: Int = 50,
    ): List<SessionSummary> = withContext(Dispatchers.IO) {
        val base = sessionApiBase(agentBaseUrl)
        val encodedCwd = java.net.URLEncoder.encode(cwd, Charsets.UTF_8.name())
        val url = "$base/api/sessions?cwd=$encodedCwd&limit=$limit"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $secret")
            .header("X-Server-Key", secret)
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("Session list HTTP ${resp.code}: ${body.take(200)}")
            }
            parseSessions(body)
        }
    }

    companion object {
        fun sessionApiBase(agentBaseUrl: String): String {
            var url = agentBaseUrl.trim().trimEnd('/')
            when {
                url.startsWith("ws://") -> url = "http://" + url.removePrefix("ws://")
                url.startsWith("wss://") -> url = "https://" + url.removePrefix("wss://")
                url.startsWith("http://") || url.startsWith("https://") -> Unit
                else -> url = "http://$url"
            }
            // strip /ws path if present
            url = url.removeSuffix("/ws")
            val withoutQuery = url.substringBefore('?')
            return bumpPort(withoutQuery, delta = 1)
        }

        private fun bumpPort(httpBase: String, delta: Int): String {
            val schemeSep = httpBase.indexOf("://")
            if (schemeSep < 0) return httpBase
            val scheme = httpBase.substring(0, schemeSep)
            val rest = httpBase.substring(schemeSep + 3)
            val hostPort = rest.substringBefore('/')
            val path = rest.removePrefix(hostPort)
            val host: String
            val port: Int?
            if (hostPort.startsWith("[")) {
                // IPv6 [addr]:port
                val end = hostPort.indexOf(']')
                host = hostPort.substring(0, end + 1)
                port = hostPort.substring(end + 1).removePrefix(":").toIntOrNull()
            } else if (hostPort.contains(':')) {
                host = hostPort.substringBeforeLast(':')
                port = hostPort.substringAfterLast(':').toIntOrNull()
            } else {
                host = hostPort
                port = null
            }
            val newPort = (port ?: 80) + delta
            return "$scheme://$host:$newPort$path"
        }

        fun parseSessions(json: String): List<SessionSummary> {
            val root = JSONObject(json)
            val arr = root.optJSONArray("sessions") ?: return emptyList()
            val out = ArrayList<SessionSummary>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("sessionId")
                if (id.isBlank()) continue
                out.add(
                    SessionSummary(
                        sessionId = id,
                        title = o.optString("title").ifBlank { "(no title)" },
                        cwd = o.optString("cwd"),
                        createdAt = o.optString("createdAt").ifBlank { null },
                        updatedAt = o.optString("updatedAt").ifBlank { null },
                        messageCount = o.optInt("messageCount", 0),
                        modelId = o.optString("modelId").ifBlank { null },
                    ),
                )
            }
            return out
        }
    }
}
