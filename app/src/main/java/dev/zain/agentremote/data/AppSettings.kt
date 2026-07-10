package dev.zain.agentremote.data

enum class NetworkProfile {
    LAN,
    TAILNET,
}

enum class BackendKind {
    GROK_BUILD,
    CODEX,
}

val BackendKind.displayName: String
    get() = when (this) {
        BackendKind.GROK_BUILD -> "Grok"
        BackendKind.CODEX -> "Codex"
    }

data class AppSettings(
    val networkProfile: NetworkProfile = NetworkProfile.LAN,
    val lanBaseUrl: String = "ws://192.168.1.50:2419",
    val tailnetBaseUrl: String = "ws://100.64.0.1:2419",
    val agentSecret: String = "",
    val codexLanBaseUrl: String = "ws://192.168.1.50:2430",
    val codexTailnetBaseUrl: String = "ws://100.64.0.1:2430",
    val codexAgentSecret: String = "",
    /** Absolute path on the Linux host — default is the host $HOME. */
    val workingDirectory: String = DEFAULT_CWD,
    val backendKind: BackendKind = BackendKind.GROK_BUILD,
) {
    val activeBaseUrl: String
        get() = when (networkProfile) {
            NetworkProfile.LAN -> lanBaseUrl.trim()
            NetworkProfile.TAILNET -> tailnetBaseUrl.trim()
        }

    val activeCodexBaseUrl: String
        get() = when (networkProfile) {
            NetworkProfile.LAN -> codexLanBaseUrl.trim()
            NetworkProfile.TAILNET -> codexTailnetBaseUrl.trim()
        }

    fun activeBaseUrl(kind: BackendKind): String = when (kind) {
        BackendKind.GROK_BUILD -> activeBaseUrl
        BackendKind.CODEX -> activeCodexBaseUrl
    }

    fun agentSecret(kind: BackendKind): String = when (kind) {
        BackendKind.GROK_BUILD -> agentSecret
        BackendKind.CODEX -> codexAgentSecret
    }

    companion object {
        /** Host home directory used as the default project/cwd. */
        const val DEFAULT_CWD: String = "/home/user"
    }
}
