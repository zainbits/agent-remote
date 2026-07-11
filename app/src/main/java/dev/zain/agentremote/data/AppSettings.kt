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
    val durableLanBaseUrl: String = "http://192.168.1.50:2440",
    val durableTailnetBaseUrl: String = "http://100.64.0.1:2440",
    val durableHostToken: String = "",
    /** Absolute path on the Linux host — default is the host $HOME. */
    val workingDirectory: String = DEFAULT_CWD,
    val backendKind: BackendKind = BackendKind.GROK_BUILD,
) {
    val activeDurableBaseUrl: String
        get() = when (networkProfile) {
            NetworkProfile.LAN -> durableLanBaseUrl.trim()
            NetworkProfile.TAILNET -> durableTailnetBaseUrl.trim()
        }

    companion object {
        /** Host home directory used as the default project/cwd. */
        const val DEFAULT_CWD: String = "/home/user"
    }
}
