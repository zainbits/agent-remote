package dev.zain.agentremote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zain.agentremote.ChatUiState
import dev.zain.agentremote.agent.SessionSummary
import dev.zain.agentremote.data.BackendKind
import dev.zain.agentremote.data.NetworkProfile
import dev.zain.agentremote.data.displayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: ChatUiState,
    onOpenSession: (SessionSummary) -> Unit,
    onNewSession: () -> Unit,
    onRefresh: () -> Unit,
    onSelectBackend: (BackendKind) -> Unit,
    onOpenSettings: () -> Unit,
    interactionEnabled: Boolean = true,
) {
    val cwd = state.settings.workingDirectory
    val profile = when (state.settings.networkProfile) {
        NetworkProfile.LAN -> "LAN"
        NetworkProfile.TAILNET -> "Tailnet"
    }
    val backend = state.settings.backendKind

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("${backend.displayName} sessions")
                        Text(
                            text = "Project · $cwd  ·  $profile",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = interactionEnabled) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh sessions")
                    }
                    IconButton(onClick = onOpenSettings, enabled = interactionEnabled) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (interactionEnabled) onNewSession() },
            ) {
                Icon(Icons.Default.Add, contentDescription = "New chat")
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = backend == BackendKind.GROK_BUILD,
                    onClick = { onSelectBackend(BackendKind.GROK_BUILD) },
                    enabled = interactionEnabled,
                    icon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                    label = { Text("Grok") },
                )
                NavigationBarItem(
                    selected = backend == BackendKind.CODEX,
                    onClick = { onSelectBackend(BackendKind.CODEX) },
                    enabled = interactionEnabled,
                    icon = { Icon(Icons.Default.Code, contentDescription = null) },
                    label = { Text("Codex") },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Text(
                        "Sessions",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Host working directory is the project the agent uses for tools " +
                            "(files, shell, git). Default is the host home directory. " +
                            "Change it in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "cwd: $cwd",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (state.sessionsLoading) {
                item {
                    Text(
                        "Loading sessions…",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.sessionsError?.let { err ->
                item {
                    Text(
                        err,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (!state.sessionsLoading && state.sessions.isEmpty() && state.sessionsError == null) {
                item {
                    Text(
                        "No sessions for this directory yet.\n" +
                            "Tap + for a new chat, or start the ${backend.displayName} " +
                            "host service.",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(state.sessions, key = { it.sessionId }) { session ->
                SessionRow(
                    session = session,
                    interactionEnabled = interactionEnabled,
                    onClick = { onOpenSession(session) },
                )
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    interactionEnabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = interactionEnabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatWhen(session.updatedAt ?: session.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = session.messageCount?.let { count ->
                        buildString {
                            append(count)
                            append(if (count == 1) " msg" else " msgs")
                            append(" · ")
                            append(session.sessionId.take(8))
                            append('…')
                        }
                    } ?: "${session.sessionId.take(8)}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session.cwd.isNotBlank()) {
                Text(
                    text = session.cwd,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun formatWhen(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    // ISO: 2026-07-09T11:56:37... → 2026-07-09 11:56
    return raw
        .replace('T', ' ')
        .take(16)
}
