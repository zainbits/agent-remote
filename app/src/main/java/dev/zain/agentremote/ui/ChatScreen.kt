package dev.zain.agentremote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Psychology
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    MessageBlock(msg)
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
private fun MessageBlock(message: ChatMessage) {
    when (message.role) {
        ChatRole.USER -> UserBubble(message)
        ChatRole.ASSISTANT -> AssistantMessage(message)
        ChatRole.THOUGHT -> ThoughtCollapsible(message)
        ChatRole.TOOL -> ToolCollapsible(message)
        ChatRole.SYSTEM -> SystemBanner(message)
    }
}

@Composable
private fun UserBubble(message: ChatMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = 18.dp,
                bottomEnd = 4.dp,
            ),
            modifier = Modifier.widthIn(max = 340.dp),
            shadowElevation = 1.dp,
        ) {
            Text(
                text = message.text + if (message.streaming) "▍" else "",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun AssistantMessage(message: ChatMessage) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Grok",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        MarkdownText(
            markdown = message.text,
            streaming = message.streaming,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ThoughtCollapsible(message: ChatMessage) {
    // Expanded while streaming so live thought is visible; collapsed after by default.
    var expanded by rememberSaveable(message.id) {
        mutableStateOf(message.streaming)
    }
    LaunchedEffect(message.streaming) {
        if (message.streaming) expanded = true
    }
    val preview = message.text
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.take(90)
        ?: "Thinking"

    CollapsibleCard(
        expanded = expanded,
        onToggle = { expanded = !expanded },
        icon = {
            Icon(
                Icons.Default.Psychology,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
        },
        title = if (message.streaming) "Thinking…" else "Thinking",
        subtitle = if (!expanded) preview else null,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = message.text + if (message.streaming) "▍" else "",
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun ToolCollapsible(message: ChatMessage) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val status = message.toolStatus.orEmpty()
    val statusLabel = when (status) {
        "completed" -> "done"
        "in_progress" -> "running"
        "pending" -> "pending"
        "failed" -> "failed"
        else -> status.ifBlank { null }
    }
    val titleBase = message.text.ifBlank { message.toolKind ?: "tool" }
    val kind = message.toolKind

    CollapsibleCard(
        expanded = expanded,
        onToggle = { expanded = !expanded },
        icon = {
            Icon(
                Icons.Default.Build,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        title = buildString {
            append(titleBase)
            if (statusLabel != null) append(" · $statusLabel")
        },
        subtitle = when {
            !expanded && !message.detail.isNullOrBlank() ->
                message.detail.lineSequence().firstOrNull()?.take(80)
            !expanded && kind != null && kind != titleBase -> kind
            else -> null
        },
        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        val body = buildString {
            if (kind != null) appendLine("tool: $kind")
            if (status.isNotBlank()) appendLine("status: $status")
            if (!message.detail.isNullOrBlank()) {
                if (isNotEmpty()) appendLine()
                append(message.detail)
            }
            if (isEmpty()) append(titleBase)
        }
        Text(
            text = body.trim(),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun SystemBanner(message: ChatMessage) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun CollapsibleCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    containerColor: Color,
    contentColor: Color,
    content: @Composable () -> Unit,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                icon()
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                    content()
                }
            }
        }
    }
}
