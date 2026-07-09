package dev.zain.agentremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zain.agentremote.ChatUiState
import dev.zain.agentremote.agent.ChatMessage
import dev.zain.agentremote.agent.ChatRole
import dev.zain.agentremote.agent.ConnectionState
import dev.zain.agentremote.data.NetworkProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    val connected = state.connection is ConnectionState.Connected
    val cwd = when (val c = state.connection) {
        is ConnectionState.Connected -> c.cwd.ifBlank { state.settings.workingDirectory }
        else -> state.settings.workingDirectory
    }
    val title = state.activeSessionTitle?.takeIf { it.isNotBlank() } ?: "Chat"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = buildString {
                                append(state.statusLine)
                                append(" · ")
                                append(
                                    when (state.settings.networkProfile) {
                                        NetworkProfile.LAN -> "LAN"
                                        NetworkProfile.TAILNET -> "Tailnet"
                                    },
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = cwd,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to sessions",
                        )
                    }
                },
                actions = {
                    if (connected) {
                        IconButton(onClick = onDisconnect) {
                            Icon(Icons.Default.LinkOff, contentDescription = "Disconnect")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.messages.isEmpty()) {
                    item {
                        Text(
                            text = if (state.busy) {
                                "Loading conversation…"
                            } else {
                                "Send a prompt to the host agent.\nProject: $cwd"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                items(state.messages, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Grok…") },
                    maxLines = 6,
                    enabled = !state.busy || connected,
                )
                FilledIconButton(
                    onClick = onSend,
                    enabled = state.draft.isNotBlank() && connected && !state.busy,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
    val container = when (message.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.primaryContainer
        ChatRole.ASSISTANT -> MaterialTheme.colorScheme.surfaceVariant
        ChatRole.THOUGHT -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
        ChatRole.TOOL -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        ChatRole.SYSTEM -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
    }
    val align = if (isUser) Alignment.End else Alignment.Start

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Text(
            text = when (message.role) {
                ChatRole.USER -> "You"
                ChatRole.ASSISTANT -> "Grok"
                ChatRole.THOUGHT -> "Thinking"
                ChatRole.TOOL -> "Tool"
                ChatRole.SYSTEM -> "System"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Surface(
            color = container,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Text(
                text = message.text + if (message.streaming) "▍" else "",
                modifier = Modifier.padding(12.dp),
                style = when (message.role) {
                    ChatRole.THOUGHT -> MaterialTheme.typography.bodySmall.copy(
                        fontStyle = FontStyle.Italic,
                    )
                    ChatRole.TOOL -> MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    )
                    else -> MaterialTheme.typography.bodyMedium
                },
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}
