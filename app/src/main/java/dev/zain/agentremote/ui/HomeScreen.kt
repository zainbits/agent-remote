package dev.zain.agentremote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.PushPin as OutlinedPushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: ChatUiState,
    onOpenSession: (SessionSummary) -> Unit,
    onNewSession: () -> Unit,
    onRefresh: () -> Unit,
    onRenameSession: (SessionSummary, String) -> Unit,
    onToggleSessionPin: (SessionSummary) -> Unit,
    onDeleteSession: (SessionSummary) -> Unit,
    onSelectBackend: (BackendKind) -> Unit,
    onOpenSettings: () -> Unit,
    interactionEnabled: Boolean = true,
) {
    var renameSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameDraft by rememberSaveable { mutableStateOf("") }
    var deleteSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    val cwd = state.settings.workingDirectory
    val profile = when (state.settings.networkProfile) {
        NetworkProfile.LAN -> "LAN"
        NetworkProfile.TAILNET -> "Tailnet"
    }
    val backend = state.settings.backendKind
    val pinnedSessions = state.sessions.filter { it.pinned }
    val recentSessions = state.sessions.filterNot { it.pinned }

    LaunchedEffect(backend) {
        renameSessionId = null
        deleteSessionId = null
    }

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

            if (pinnedSessions.isNotEmpty()) {
                item {
                    SessionSectionHeader(
                        title = "Pinned",
                        count = pinnedSessions.size,
                        showPin = true,
                    )
                }

                items(pinnedSessions, key = { it.sessionId }) { session ->
                    SessionRow(
                        session = session,
                        interactionEnabled = interactionEnabled && state.sessionActionId == null,
                        actionInProgress = state.sessionActionId == session.sessionId,
                        onClick = { onOpenSession(session) },
                        onRename = {
                            renameSessionId = session.sessionId
                            renameDraft = session.title
                        },
                        onTogglePin = { onToggleSessionPin(session) },
                        onDeleteRequest = { deleteSessionId = session.sessionId },
                    )
                }
            }

            item {
                SessionSectionHeader(
                    title = "Recent sessions",
                    count = recentSessions.size,
                )
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

            items(recentSessions, key = { it.sessionId }) { session ->
                SessionRow(
                    session = session,
                    interactionEnabled = interactionEnabled && state.sessionActionId == null,
                    actionInProgress = state.sessionActionId == session.sessionId,
                    onClick = { onOpenSession(session) },
                    onRename = {
                        renameSessionId = session.sessionId
                        renameDraft = session.title
                    },
                    onTogglePin = { onToggleSessionPin(session) },
                    onDeleteRequest = { deleteSessionId = session.sessionId },
                )
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    val renameTarget = state.sessions.firstOrNull { it.sessionId == renameSessionId }
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameSessionId = null },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it.take(200) },
                    label = { Text("Session name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameSession(renameTarget, renameDraft)
                        renameSessionId = null
                    },
                    enabled = renameDraft.trim().isNotEmpty() &&
                        renameDraft.trim() != renameTarget.title &&
                        state.sessionActionId == null,
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameSessionId = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    val deleteTarget = state.sessions.firstOrNull { it.sessionId == deleteSessionId }
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteSessionId = null },
            title = { Text("Delete session?") },
            text = {
                Text(
                    "This permanently deletes the AgentRemote session and its linked " +
                        "${backend.displayName} CLI history. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSession(deleteTarget)
                        deleteSessionId = null
                    },
                    enabled = state.sessionActionId == null && !deleteTarget.status.isActive,
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteSessionId = null }) {
                    Text("Cancel")
                }
            },
        )
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
private fun SessionSectionHeader(
    title: String,
    count: Int,
    showPin: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showPin) {
            Icon(
                Icons.Default.PushPin,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        SessionCountPill(count)
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
    actionInProgress: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    var actionsExpanded by remember { mutableStateOf(false) }
    val revealWidth = 96.dp
    val revealWidthPx = with(LocalDensity.current) { revealWidth.toPx() }
    val layoutDirection = LocalLayoutDirection.current
    val openOffset = if (layoutDirection == LayoutDirection.Ltr) {
        -revealWidthPx
    } else {
        revealWidthPx
    }
    val anchors = remember(openOffset) {
        DraggableAnchors {
            SessionSlideState.Closed at 0f
            SessionSlideState.Revealed at openOffset
        }
    }
    val slideState = remember(session.sessionId, anchors) {
        AnchoredDraggableState(
            initialValue = SessionSlideState.Closed,
            anchors = anchors,
        )
    }
    val scope = rememberCoroutineScope()
    val canDelete = interactionEnabled && !session.status.isActive

    LaunchedEffect(canDelete) {
        if (!canDelete) slideState.snapTo(SessionSlideState.Closed)
    }

    LaunchedEffect(interactionEnabled) {
        if (!interactionEnabled) actionsExpanded = false
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Column(
                modifier = Modifier
                    .width(revealWidth)
                    .fillMaxHeight()
                    .clickable(enabled = canDelete, onClick = onDeleteRequest),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete session",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "Delete",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset {
                    IntOffset(
                        x = slideState.offset.takeUnless { it.isNaN() }?.roundToInt() ?: 0,
                        y = 0,
                    )
                }
                .anchoredDraggable(
                    state = slideState,
                    orientation = Orientation.Horizontal,
                    enabled = canDelete,
                )
                .combinedClickable(
                    enabled = interactionEnabled,
                    onClickLabel = "Open session",
                    onLongClickLabel = "Show session actions",
                    onClick = {
                        if (slideState.settledValue == SessionSlideState.Revealed) {
                            scope.launch { slideState.snapTo(SessionSlideState.Closed) }
                        } else {
                            onClick()
                        }
                    },
                    onLongClick = {
                        if (slideState.settledValue == SessionSlideState.Revealed) {
                            scope.launch { slideState.snapTo(SessionSlideState.Closed) }
                        } else {
                            actionsExpanded = true
                        }
                    },
                ),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (session.unread) {
                        Box(
                            modifier = Modifier
                                .padding(end = 9.dp)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(UnreadBlue)
                                .semantics { contentDescription = "Unread session" },
                        )
                    }
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (actionInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
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
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 12.dp),
        ) {
            SessionActionsMenu(
                expanded = actionsExpanded,
                pinned = session.pinned,
                onDismissRequest = { actionsExpanded = false },
                onTogglePin = {
                    actionsExpanded = false
                    onTogglePin()
                },
                onRename = {
                    actionsExpanded = false
                    onRename()
                },
            )
        }
    }
}

@Composable
private fun SessionActionsMenu(
    expanded: Boolean,
    pinned: Boolean,
    onDismissRequest: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.widthIn(min = 216.dp),
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
        ),
    ) {
        Text(
            text = "Session actions",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DropdownMenuItem(
            text = {
                Text(if (pinned) "Unpin session" else "Pin session")
            },
            onClick = onTogglePin,
            leadingIcon = {
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = if (pinned) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentColor = if (pinned) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (pinned) {
                                Icons.Default.PushPin
                            } else {
                                Icons.Outlined.OutlinedPushPin
                            },
                            contentDescription = null,
                            modifier = Modifier
                                .size(20.dp)
                                .then(if (pinned) Modifier else Modifier.rotate(-25f)),
                        )
                    }
                }
            },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        )
        DropdownMenuItem(
            text = { Text("Rename session") },
            onClick = onRename,
            leadingIcon = {
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        )
    }
}

private enum class SessionSlideState {
    Closed,
    Revealed,
}

private val UnreadBlue = Color(0xFF4285F4)

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
