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
    val durableLanBaseUrl: String = "",
    val durableTailnetBaseUrl: String = "",
    val durableHostToken: String = "",
    /** Path on the Linux host; ~ expands to the host user's home directory. */
    val workingDirectory: String = DEFAULT_CWD,
    /** Default permission mode for newly created Codex sessions. */
    val codexFullAccess: Boolean = true,
    /**
     * Post a local notification when a durable turn finishes and the user is not
     * already viewing that chat. Watchers arm only while sessions are active.
     */
    val notifyWhenAgentFinished: Boolean = true,
    val backendKind: BackendKind = BackendKind.GROK_BUILD,
) {
    val activeDurableBaseUrl: String
        get() = when (networkProfile) {
            NetworkProfile.LAN -> durableLanBaseUrl.trim()
            NetworkProfile.TAILNET -> durableTailnetBaseUrl.trim()
        }

    companion object {
        /** Host home directory (expanded on the host) used as the default project/cwd. */
        const val DEFAULT_CWD: String = "~"
    }
}
