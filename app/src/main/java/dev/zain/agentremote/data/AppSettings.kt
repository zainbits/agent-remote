package dev.zain.agentremote.data

enum class NetworkProfile {
    LAN,
    TAILNET,
}

enum class BackendKind {
    GROK_BUILD,
    // Codex reserved for a later backend adapter.
    CODEX,
}

data class AppSettings(
    val networkProfile: NetworkProfile = NetworkProfile.LAN,
    val lanBaseUrl: String = "ws://192.168.1.50:2419",
    val tailnetBaseUrl: String = "ws://100.64.0.1:2419",
    val agentSecret: String = "",
    val workingDirectory: String = "/home/user",
    val backendKind: BackendKind = BackendKind.GROK_BUILD,
) {
    val activeBaseUrl: String
        get() = when (networkProfile) {
            NetworkProfile.LAN -> lanBaseUrl.trim()
            NetworkProfile.TAILNET -> tailnetBaseUrl.trim()
        }
}
