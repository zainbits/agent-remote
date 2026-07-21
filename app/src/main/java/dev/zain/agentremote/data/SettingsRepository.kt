package dev.zain.agentremote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "agent_remote_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val networkProfile = stringPreferencesKey("network_profile")
        val durableLanBaseUrl = stringPreferencesKey("durable_lan_base_url")
        val durableTailnetBaseUrl = stringPreferencesKey("durable_tailnet_base_url")
        val durableHostToken = stringPreferencesKey("durable_host_token")
        val legacyLanBaseUrl = stringPreferencesKey("lan_base_url")
        val legacyTailnetBaseUrl = stringPreferencesKey("tailnet_base_url")
        val legacyAgentSecret = stringPreferencesKey("agent_secret")
        val legacyCodexLanBaseUrl = stringPreferencesKey("codex_lan_base_url")
        val legacyCodexTailnetBaseUrl = stringPreferencesKey("codex_tailnet_base_url")
        val legacyCodexAgentSecret = stringPreferencesKey("codex_agent_secret")
        val workingDirectory = stringPreferencesKey("working_directory")
        val codexFullAccess = booleanPreferencesKey("codex_full_access")
        val notifyWhenAgentFinished = booleanPreferencesKey("notify_when_agent_finished")
        val backendKind = stringPreferencesKey("backend_kind")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            networkProfile = prefs[Keys.networkProfile]
                ?.let { runCatching { NetworkProfile.valueOf(it) }.getOrNull() }
                ?: NetworkProfile.LAN,
            durableLanBaseUrl = prefs[Keys.durableLanBaseUrl] ?: AppSettings().durableLanBaseUrl,
            durableTailnetBaseUrl = prefs[Keys.durableTailnetBaseUrl]
                ?: AppSettings().durableTailnetBaseUrl,
            durableHostToken = prefs[Keys.durableHostToken] ?: "",
            workingDirectory = prefs[Keys.workingDirectory] ?: AppSettings().workingDirectory,
            codexFullAccess = prefs[Keys.codexFullAccess] ?: true,
            notifyWhenAgentFinished = prefs[Keys.notifyWhenAgentFinished] ?: true,
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
                durableLanBaseUrl = prefs[Keys.durableLanBaseUrl]
                    ?: AppSettings().durableLanBaseUrl,
                durableTailnetBaseUrl = prefs[Keys.durableTailnetBaseUrl]
                    ?: AppSettings().durableTailnetBaseUrl,
                durableHostToken = prefs[Keys.durableHostToken] ?: "",
                workingDirectory = prefs[Keys.workingDirectory] ?: AppSettings().workingDirectory,
                codexFullAccess = prefs[Keys.codexFullAccess] ?: true,
                notifyWhenAgentFinished = prefs[Keys.notifyWhenAgentFinished] ?: true,
                backendKind = prefs[Keys.backendKind]
                    ?.let { runCatching { BackendKind.valueOf(it) }.getOrNull() }
                    ?: BackendKind.GROK_BUILD,
            )
            val next = transform(current)
            prefs[Keys.networkProfile] = next.networkProfile.name
            prefs[Keys.durableLanBaseUrl] = next.durableLanBaseUrl
            prefs[Keys.durableTailnetBaseUrl] = next.durableTailnetBaseUrl
            prefs[Keys.durableHostToken] = next.durableHostToken
            prefs[Keys.workingDirectory] = next.workingDirectory
            prefs[Keys.codexFullAccess] = next.codexFullAccess
            prefs[Keys.notifyWhenAgentFinished] = next.notifyWhenAgentFinished
            prefs[Keys.backendKind] = next.backendKind.name
            prefs.remove(Keys.legacyLanBaseUrl)
            prefs.remove(Keys.legacyTailnetBaseUrl)
            prefs.remove(Keys.legacyAgentSecret)
            prefs.remove(Keys.legacyCodexLanBaseUrl)
            prefs.remove(Keys.legacyCodexTailnetBaseUrl)
            prefs.remove(Keys.legacyCodexAgentSecret)
        }
    }
}
