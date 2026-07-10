package dev.zain.agentremote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "agent_remote_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val networkProfile = stringPreferencesKey("network_profile")
        val lanBaseUrl = stringPreferencesKey("lan_base_url")
        val tailnetBaseUrl = stringPreferencesKey("tailnet_base_url")
        val agentSecret = stringPreferencesKey("agent_secret")
        val codexLanBaseUrl = stringPreferencesKey("codex_lan_base_url")
        val codexTailnetBaseUrl = stringPreferencesKey("codex_tailnet_base_url")
        val codexAgentSecret = stringPreferencesKey("codex_agent_secret")
        val workingDirectory = stringPreferencesKey("working_directory")
        val backendKind = stringPreferencesKey("backend_kind")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            networkProfile = prefs[Keys.networkProfile]
                ?.let { runCatching { NetworkProfile.valueOf(it) }.getOrNull() }
                ?: NetworkProfile.LAN,
            lanBaseUrl = prefs[Keys.lanBaseUrl] ?: AppSettings().lanBaseUrl,
            tailnetBaseUrl = prefs[Keys.tailnetBaseUrl] ?: AppSettings().tailnetBaseUrl,
            agentSecret = prefs[Keys.agentSecret] ?: "",
            codexLanBaseUrl = prefs[Keys.codexLanBaseUrl] ?: AppSettings().codexLanBaseUrl,
            codexTailnetBaseUrl = prefs[Keys.codexTailnetBaseUrl]
                ?: AppSettings().codexTailnetBaseUrl,
            codexAgentSecret = prefs[Keys.codexAgentSecret] ?: "",
            workingDirectory = prefs[Keys.workingDirectory] ?: AppSettings().workingDirectory,
            backendKind = prefs[Keys.backendKind]
                ?.let { runCatching { BackendKind.valueOf(it) }.getOrNull() }
                ?: BackendKind.GROK_BUILD,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = AppSettings(
                networkProfile = prefs[Keys.networkProfile]
                    ?.let { runCatching { NetworkProfile.valueOf(it) }.getOrNull() }
                    ?: NetworkProfile.LAN,
                lanBaseUrl = prefs[Keys.lanBaseUrl] ?: AppSettings().lanBaseUrl,
                tailnetBaseUrl = prefs[Keys.tailnetBaseUrl] ?: AppSettings().tailnetBaseUrl,
                agentSecret = prefs[Keys.agentSecret] ?: "",
                codexLanBaseUrl = prefs[Keys.codexLanBaseUrl] ?: AppSettings().codexLanBaseUrl,
                codexTailnetBaseUrl = prefs[Keys.codexTailnetBaseUrl]
                    ?: AppSettings().codexTailnetBaseUrl,
                codexAgentSecret = prefs[Keys.codexAgentSecret] ?: "",
                workingDirectory = prefs[Keys.workingDirectory] ?: AppSettings().workingDirectory,
                backendKind = prefs[Keys.backendKind]
                    ?.let { runCatching { BackendKind.valueOf(it) }.getOrNull() }
                    ?: BackendKind.GROK_BUILD,
            )
            val next = transform(current)
            prefs[Keys.networkProfile] = next.networkProfile.name
            prefs[Keys.lanBaseUrl] = next.lanBaseUrl
            prefs[Keys.tailnetBaseUrl] = next.tailnetBaseUrl
            prefs[Keys.agentSecret] = next.agentSecret
            prefs[Keys.codexLanBaseUrl] = next.codexLanBaseUrl
            prefs[Keys.codexTailnetBaseUrl] = next.codexTailnetBaseUrl
            prefs[Keys.codexAgentSecret] = next.codexAgentSecret
            prefs[Keys.workingDirectory] = next.workingDirectory
            prefs[Keys.backendKind] = next.backendKind.name
        }
    }
}
