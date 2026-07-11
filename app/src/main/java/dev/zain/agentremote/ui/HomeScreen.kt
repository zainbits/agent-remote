package dev.zain.agentremote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.zain.agentremote.ChatUiState
import dev.zain.agentremote.agent.SessionSummary
import dev.zain.agentremote.agent.SessionStatus
import dev.zain.agentremote.agent.isActive
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
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AgentRemote")
                        Text(
                            text = "${backend.displayName} · $profile",
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
            ExtendedFloatingActionButton(
                onClick = { if (interactionEnabled) onNewSession() },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New session") },
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                WorkspaceCard(
                    cwd = cwd,
                    backendName = backend.displayName,
                    profile = profile,
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Recent sessions",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    SessionCountPill(state.sessions.size)
                }
            }

            if (state.sessionsLoading) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape),
                    )
                }
            }

            state.sessionsError?.let { err ->
                item {
                    SessionError(message = err)
                }
            }

            if (!state.sessionsLoading && state.sessions.isEmpty() && state.sessionsError == null) {
                item {
                    EmptySessions(
                        backendName = backend.displayName,
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
private fun WorkspaceCard(
    cwd: String,
    backendName: String,
    profile: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Workspace",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = cwd,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "$backendName host · $profile",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SessionCountPill(count: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = count.toString(),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun SessionError(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EmptySessions(backendName: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = null)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "No $backendName sessions yet",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Start a new session, or make sure the $backendName host service is running.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (session.status.isActive) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        text = buildString {
                            sessionStatusLabel(session.status)?.let {
                                append(it)
                                append(" · ")
                            }
                            append(
                                session.messageCount?.let { count ->
                                    buildString {
                                        append(count)
                                        append(if (count == 1) " msg" else " msgs")
                                        append(" · ")
                                        append(session.sessionId.take(8))
                                        append('…')
                                    }
                                } ?: "${session.sessionId.take(8)}…",
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (session.cwd.isNotBlank()) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                )
                Text(
                    text = session.cwd,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun sessionStatusLabel(status: SessionStatus): String? = when (status) {
    SessionStatus.QUEUED -> "Queued"
    SessionStatus.RUNNING -> "Running"
    SessionStatus.CANCELLING -> "Stopping"
    SessionStatus.FAILED -> "Failed"
    SessionStatus.CANCELLED -> "Stopped"
    SessionStatus.IDLE -> null
}

private fun formatWhen(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    // ISO: 2026-07-09T11:56:37... → 2026-07-09 11:56
    return raw
        .replace('T', ' ')
        .take(16)
}
