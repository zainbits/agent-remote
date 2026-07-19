package dev.zain.agentremote.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zain.agentremote.ChatUiState
import dev.zain.agentremote.CommandOutputState
import dev.zain.agentremote.agent.ChatMessage
import dev.zain.agentremote.agent.ChatRole
import dev.zain.agentremote.agent.AgentModelOption
import dev.zain.agentremote.agent.ConnectionState
import dev.zain.agentremote.agent.ImageAttachment
import dev.zain.agentremote.agent.SlashCommand
import dev.zain.agentremote.agent.SlashCommandSource
import dev.zain.agentremote.data.NetworkProfile
import dev.zain.agentremote.data.displayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onDraftChange: (String) -> Unit,
    onImagesSelected: (List<Uri>) -> Unit,
    onRemoveImage: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onSelectSlashCommand: (SlashCommand) -> Unit,
    onSelectModel: (AgentModelOption) -> Unit,
    onSelectReasoningEffort: (String) -> Unit,
    onDismissCommandOutput: () -> Unit,
    onReconnect: () -> Unit,
    onRenameSession: (String) -> Unit,
    onBack: () -> Unit,
) {
    val imagePicker = rememberLauncherForActivityResult(
        contract = PickMultipleVisualMedia(4),
        onResult = onImagesSelected,
    )
    val connected = state.connection is ConnectionState.Connected
    val cwd = when (val c = state.connection) {
        is ConnectionState.Connected -> c.cwd.ifBlank { state.settings.workingDirectory }
        else -> state.settings.workingDirectory
    }
    val title = state.activeSessionTitle?.takeIf { it.isNotBlank() } ?: "Chat"
    var renameDialogVisible by rememberSaveable { mutableStateOf(false) }
    var renameDraft by rememberSaveable { mutableStateOf("") }

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
                                append(if (state.cancellationRequested) "Stopping…" else state.statusLine)
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
                    IconButton(
                        onClick = {
                            renameDraft = title
                            renameDialogVisible = true
                        },
                        enabled = state.canReconnect && state.sessionActionId == null,
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename session")
                    }
                    if (!connected && state.canReconnect) {
                        IconButton(
                            onClick = onReconnect,
                            enabled = !state.reconnecting,
                        ) {
                            Icon(
                                Icons.Default.Link,
                                contentDescription = if (state.reconnecting) {
                                    "Reconnecting"
                                } else {
                                    "Reconnect"
                                },
                            )
                        }
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
            if (state.historyLoading) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Loading conversation…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ChatHistory(
                    messages = state.messages,
                    assistantName = state.settings.backendKind.displayName,
                    emptyText = "Send a prompt to the host agent.\nProject: $cwd",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }

            state.commandOutput?.let { output ->
                CommandOutputPanel(
                    output = output,
                    onClose = onDismissCommandOutput,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            SlashCommandSuggestions(
                draft = state.draft,
                commands = state.slashCommands,
                agentName = state.settings.backendKind.displayName,
                onSelect = onSelectSlashCommand,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            )

            val commandName = slashCommandName(state.draft)
            val isLocalCommand = state.pendingImages.isEmpty() && state.slashCommands.any {
                it.source == SlashCommandSource.APP && it.name.equals(commandName, ignoreCase = true)
            }
            val canSendWhileBusy = commandName == "stop" || commandName == "cancel"
            MessageComposer(
                draft = state.draft,
                pendingImages = state.pendingImages,
                attachmentError = state.attachmentError,
                attachmentSelectionBusy = state.attachmentSelectionBusy,
                agentName = state.settings.backendKind.displayName,
                modelId = state.usage.modelId,
                modelName = state.usage.modelName ?: state.usage.modelId,
                reasoningEffort = state.usage.reasoningEffort,
                modelOptions = state.modelOptions,
                modelSelectionEnabled = connected &&
                    !state.busy &&
                    !state.requestInFlight &&
                    !state.modelSelectionBusy,
                modelSelectionBusy = state.modelSelectionBusy,
                inputEnabled = !state.busy || connected,
                requestInFlight = state.requestInFlight,
                cancellationRequested = state.cancellationRequested,
                attachmentEnabled = connected &&
                    !state.busy &&
                    !state.requestInFlight &&
                    !state.attachmentSelectionBusy &&
                    !state.modelSelectionBusy,
                sendEnabled = (state.draft.isNotBlank() || state.pendingImages.isNotEmpty()) &&
                    (connected || isLocalCommand) &&
                    !state.attachmentSelectionBusy &&
                    !state.modelSelectionBusy &&
                    (!state.busy || canSendWhileBusy),
                onSelectModel = onSelectModel,
                onSelectReasoningEffort = onSelectReasoningEffort,
                onDraftChange = onDraftChange,
                onAttachImages = {
                    imagePicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                },
                onRemoveImage = onRemoveImage,
                onSend = onSend,
                onCancel = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, top = 6.dp, end = 10.dp, bottom = 10.dp),
            )
        }
    }

    if (renameDialogVisible) {
        AlertDialog(
            onDismissRequest = { renameDialogVisible = false },
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
                        onRenameSession(renameDraft)
                        renameDialogVisible = false
                    },
                    enabled = renameDraft.trim().isNotEmpty() &&
                        renameDraft.trim() != title &&
                        state.sessionActionId == null,
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogVisible = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ChatHistory(
    messages: List<ChatMessage>,
    assistantName: String,
    emptyText: String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var followLatest by remember { mutableStateOf(true) }
    val latest = messages.lastOrNull()
    val timeline = remember(messages) { groupChatTimeline(messages) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                followLatest = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
            }
        }
    }

    LaunchedEffect(
        messages.size,
        latest?.id,
        latest?.text?.length,
        latest?.detail?.length,
        latest?.toolStatus,
        latest?.attachments?.size,
    ) {
        if (followLatest && !listState.isScrollInProgress && messages.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
    ) {
        if (messages.isEmpty()) {
            item {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        items(timeline.asReversed(), key = { it.key }) { item ->
            when (item) {
                is ChatTimelineItem.MessageItem -> MessageBlock(item.message, assistantName)
                is ChatTimelineItem.ToolGroupItem -> ToolGroupCollapsible(item.messages)
            }
        }
    }
}

@Composable
private fun MessageComposer(
    draft: String,
    pendingImages: List<ImageAttachment>,
    attachmentError: String?,
    attachmentSelectionBusy: Boolean,
    agentName: String,
    modelId: String?,
    modelName: String?,
    reasoningEffort: String?,
    modelOptions: List<AgentModelOption>,
    modelSelectionEnabled: Boolean,
    modelSelectionBusy: Boolean,
    inputEnabled: Boolean,
    requestInFlight: Boolean,
    cancellationRequested: Boolean,
    attachmentEnabled: Boolean,
    sendEnabled: Boolean,
    onDraftChange: (String) -> Unit,
    onAttachImages: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onSelectModel: (AgentModelOption) -> Unit,
    onSelectReasoningEffort: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        ),
        tonalElevation = 3.dp,
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(start = 10.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        ) {
            if (pendingImages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    pendingImages.forEach { image ->
                        AttachmentThumbnail(
                            attachment = image,
                            onRemove = { onRemoveImage(image.id) },
                        )
                    }
                }
            }

            attachmentError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                enabled = inputEnabled,
                minLines = 1,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (inputEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(
                                text = "Message $agentName…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = if (inputEnabled) 0.75f else 0.38f,
                                ),
                            )
                        }
                        innerTextField()
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilledTonalIconButton(
                    onClick = onAttachImages,
                    enabled = attachmentEnabled && pendingImages.size < 4,
                    modifier = Modifier.size(48.dp),
                ) {
                    if (attachmentSelectionBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Attach images")
                    }
                }

                ComposerModelRail(
                    modelId = modelId,
                    modelName = modelName ?: "$agentName model",
                    reasoningEffort = reasoningEffort,
                    modelOptions = modelOptions,
                    selectionEnabled = modelSelectionEnabled,
                    selectionBusy = modelSelectionBusy,
                    onSelectModel = onSelectModel,
                    onSelectReasoningEffort = onSelectReasoningEffort,
                    modifier = Modifier.weight(1f),
                )

                if (requestInFlight) {
                    FilledIconButton(
                        onClick = onCancel,
                        enabled = !cancellationRequested,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = if (cancellationRequested) {
                                "Stopping request"
                            } else {
                                "Stop request"
                            },
                        )
                    }
                }

                FilledIconButton(
                    onClick = onSend,
                    enabled = sendEnabled,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
                }
            }
        }
    }
}

@Composable
private fun AttachmentThumbnail(
    attachment: ImageAttachment,
    onRemove: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.size(68.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp)),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(14.dp),
        ) {
            val thumbnail = attachment.thumbnail
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = attachment.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    modifier = Modifier.padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Default.Photo,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                    )
                    Text(
                        text = attachment.fileName,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (onRemove != null) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.68f)),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove ${attachment.fileName}",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ComposerModelRail(
    modelId: String?,
    modelName: String,
    reasoningEffort: String?,
    modelOptions: List<AgentModelOption>,
    selectionEnabled: Boolean,
    selectionBusy: Boolean,
    onSelectModel: (AgentModelOption) -> Unit,
    onSelectReasoningEffort: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var modelExpanded by rememberSaveable { mutableStateOf(false) }
    var effortExpanded by rememberSaveable { mutableStateOf(false) }
    val currentModel = remember(modelId, modelOptions) {
        modelOptions.firstOrNull { it.id == modelId }
            ?: modelOptions.firstOrNull { modelId == null && it.isDefault }
    }
    val effortOptions = currentModel?.reasoningEfforts.orEmpty()
    val effortLabel = reasoningEffort?.let(::formatReasoningEffort) ?: "Effort —"

    LaunchedEffect(selectionEnabled, effortOptions) {
        if (!selectionEnabled) {
            modelExpanded = false
            effortExpanded = false
        } else if (effortOptions.isEmpty()) {
            effortExpanded = false
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = selectionEnabled && modelOptions.isNotEmpty(),
                            onClick = {
                                effortExpanded = false
                                modelExpanded = true
                            },
                        )
                        .padding(start = 10.dp, top = 7.dp, end = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = modelName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!selectionBusy && modelOptions.isNotEmpty()) {
                        Icon(
                            imageVector = if (modelExpanded) {
                                Icons.Default.ExpandLess
                            } else {
                                Icons.Default.ExpandMore
                            },
                            contentDescription = "Choose model",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                DropdownMenu(
                    expanded = modelExpanded,
                    onDismissRequest = { modelExpanded = false },
                    modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
                ) {
                    modelOptions.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = model.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (model.id == modelId) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Normal
                                        },
                                    )
                                    model.description?.let { description ->
                                        Text(
                                            text = if (model.isDefault) {
                                                "Default · $description"
                                            } else {
                                                description
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            },
                            trailingIcon = if (model.id == modelId) {
                                {
                                    Icon(Icons.Default.Check, contentDescription = "Selected")
                                }
                            } else {
                                null
                            },
                            onClick = {
                                modelExpanded = false
                                onSelectModel(model)
                            },
                            enabled = selectionEnabled && model.id != modelId,
                        )
                    }
                }
            }

            Spacer(
                modifier = Modifier
                    .size(width = 1.dp, height = 24.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            Box {
                Row(
                    modifier = Modifier
                        .clickable(
                            enabled = selectionEnabled && effortOptions.isNotEmpty(),
                            onClick = {
                                modelExpanded = false
                                effortExpanded = true
                            },
                        )
                        .padding(start = 8.dp, top = 7.dp, end = 8.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        text = effortLabel,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                    when {
                        selectionBusy -> CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        effortOptions.isNotEmpty() -> Icon(
                            imageVector = if (effortExpanded) {
                                Icons.Default.ExpandLess
                            } else {
                                Icons.Default.ExpandMore
                            },
                            contentDescription = "Choose reasoning effort",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                DropdownMenu(
                    expanded = effortExpanded,
                    onDismissRequest = { effortExpanded = false },
                    modifier = Modifier.widthIn(min = 190.dp, max = 260.dp),
                ) {
                    effortOptions.forEach { effort ->
                        val isDefault = effort == currentModel?.defaultReasoningEffort
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = formatReasoningEffort(effort),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (effort == reasoningEffort) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Normal
                                        },
                                    )
                                    if (isDefault) {
                                        Text(
                                            text = "Default for ${currentModel.name}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            },
                            trailingIcon = if (effort == reasoningEffort) {
                                {
                                    Icon(Icons.Default.Check, contentDescription = "Selected")
                                }
                            } else {
                                null
                            },
                            onClick = {
                                effortExpanded = false
                                onSelectReasoningEffort(effort)
                            },
                            enabled = selectionEnabled && effort != reasoningEffort,
                        )
                    }
                }
            }
        }
    }
}

private fun formatReasoningEffort(effort: String): String =
    when (effort.lowercase()) {
        "xhigh" -> "XHigh"
        else -> effort.replaceFirstChar { it.uppercase() }
    }

@Composable
private fun CommandOutputPanel(
    output: CommandOutputState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(output.body.length) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    val containerColor = if (output.isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (output.isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 6.dp, bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "/${output.command}",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                if (output.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close command output")
                }
            }

            output.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp, bottom = 8.dp),
                )
            }

            output.status?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (output.isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.padding(end = 8.dp, bottom = 6.dp),
                )
            }

            if (output.body.isNotBlank()) {
                MarkdownText(
                    markdown = output.body,
                    streaming = output.running,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(scrollState)
                        .padding(end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SlashCommandSuggestions(
    draft: String,
    commands: List<SlashCommand>,
    agentName: String,
    onSelect: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val matchingCommands = matchingSlashCommands(draft, commands)
    if (matchingCommands.isEmpty()) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.padding(bottom = 4.dp),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            matchingCommands.forEach { command ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(command) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = buildString {
                                append("/${command.name}")
                                command.argumentHint?.let { append(" <$it>") }
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = command.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = when (command.source) {
                            SlashCommandSource.APP -> "App"
                            SlashCommandSource.AGENT -> agentName
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun matchingSlashCommands(
    draft: String,
    commands: List<SlashCommand>,
): List<SlashCommand> {
    val trimmed = draft.trimStart()
    if (!trimmed.startsWith('/')) return emptyList()
    val query = trimmed.drop(1)
    if (query.any { it.isWhitespace() }) return emptyList()

    return commands
        .filter { command ->
            command.name.contains(query, ignoreCase = true) ||
                command.description.contains(query, ignoreCase = true)
        }
        .sortedBy { it.name.lowercase() }
        .take(6)
}

private fun slashCommandName(draft: String): String? {
    val trimmed = draft.trimStart()
    if (!trimmed.startsWith('/')) return null
    return trimmed
        .drop(1)
        .takeWhile { !it.isWhitespace() }
        .lowercase()
        .ifBlank { null }
}

@Composable
private fun MessageBlock(message: ChatMessage, assistantName: String) {
    when (message.role) {
        ChatRole.USER -> UserBubble(message)
        ChatRole.ASSISTANT -> AssistantMessage(message, assistantName)
        ChatRole.THOUGHT -> ThoughtCollapsible(message)
        ChatRole.TOOL -> ToolCollapsible(message)
        ChatRole.SYSTEM -> SystemBanner(message)
    }
}

@Composable
private fun UserBubble(message: ChatMessage) {
    val copyText = remember(message.text, message.attachments) {
        buildString {
            append(message.text)
            message.attachments.forEach { attachment ->
                if (isNotEmpty()) appendLine()
                append("[Image: ${attachment.fileName}]")
            }
        }
    }
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
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                if (message.attachments.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = if (message.text.isNotBlank()) 8.dp else 0.dp),
                    ) {
                        message.attachments.forEach { attachment ->
                            AttachmentThumbnail(attachment)
                        }
                    }
                }
                if (message.text.isNotBlank() || message.streaming) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = message.text + if (message.streaming) "▍" else "",
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        MessageCopyButton(
                            text = copyText,
                            contentDescription = "Copy user message",
                            tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                        )
                    }
                } else if (copyText.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        MessageCopyButton(
                            text = copyText,
                            contentDescription = "Copy user message",
                            tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(message: ChatMessage, assistantName: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = assistantName,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 2.dp),
            )
            MessageCopyButton(
                text = message.text,
                contentDescription = "Copy assistant message",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MarkdownText(
            markdown = message.text,
            streaming = message.streaming,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
@Suppress("DEPRECATION")
private fun MessageCopyButton(
    text: String,
    contentDescription: String,
    tint: Color,
) {
    val clipboard = LocalClipboardManager.current
    IconButton(
        onClick = { clipboard.setText(AnnotatedString(text)) },
        enabled = text.isNotEmpty(),
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            Icons.Default.ContentCopy,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
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
private fun ToolGroupCollapsible(messages: List<ChatMessage>) {
    val groupId = messages.first().id
    var expanded by rememberSaveable(groupId) { mutableStateOf(false) }
    val statuses = messages.map { it.toolStatus.orEmpty().lowercase() }
    val failedCount = statuses.count { it == "failed" || it == "error" }
    val cancelledCount = statuses.count { it == "cancelled" || it == "interrupted" }
    val active = messages.any { message ->
        message.streaming || message.toolStatus.orEmpty().lowercase() in setOf("in_progress", "pending")
    }
    val actionLabel = if (messages.size == 1) "1 action" else "${messages.size} actions"
    val title = buildString {
        append(if (active) "Working" else "Worked")
        append(" · $actionLabel")
        when {
            failedCount > 0 -> {
                append(" · $failedCount failed")
            }
            cancelledCount > 0 -> append(" · stopped")
        }
    }
    val hasFailure = failedCount > 0
    val containerColor = if (hasFailure) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
    }
    val contentColor = if (hasFailure) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    CollapsibleCard(
        expanded = expanded,
        onToggle = { expanded = !expanded },
        icon = {
            when {
                hasFailure -> Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                active -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                cancelledCount > 0 -> Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
        },
        title = title,
        subtitle = if (!expanded && hasFailure) "Tap to review failed actions" else null,
        containerColor = containerColor,
        contentColor = contentColor,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            messages.forEach { message ->
                ToolCollapsible(message)
            }
        }
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
