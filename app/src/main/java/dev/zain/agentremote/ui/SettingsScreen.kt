package dev.zain.agentremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.zain.agentremote.SessionCleanupUiState
import dev.zain.agentremote.data.AppSettings
import dev.zain.agentremote.data.NetworkProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    sessionCleanup: SessionCleanupUiState,
    onBack: () -> Unit,
    onSave: (
        networkProfile: NetworkProfile,
        durableLanBaseUrl: String,
        durableTailnetBaseUrl: String,
        durableHostToken: String,
        workingDirectory: String,
        codexFullAccess: Boolean,
        notifyWhenAgentFinished: Boolean,
    ) -> Unit,
    onPreviewOldSessions: (baseUrl: String, token: String, olderThanDays: Int) -> Unit,
    onDeleteOldSessions: (baseUrl: String, token: String) -> Unit,
    onDismissOldSessions: () -> Unit,
) {
    var networkProfile by remember(settings.networkProfile) {
        mutableStateOf(settings.networkProfile)
    }
    var durableLanBaseUrl by remember(settings.durableLanBaseUrl) {
        mutableStateOf(settings.durableLanBaseUrl)
    }
    var durableTailnetBaseUrl by remember(settings.durableTailnetBaseUrl) {
        mutableStateOf(settings.durableTailnetBaseUrl)
    }
    var durableHostToken by remember(settings.durableHostToken) {
        mutableStateOf(settings.durableHostToken)
    }
    var workingDirectory by remember(settings.workingDirectory) {
        mutableStateOf(settings.workingDirectory)
    }
    var codexFullAccess by remember(settings.codexFullAccess) {
        mutableStateOf(settings.codexFullAccess)
    }
    var notifyWhenAgentFinished by remember(settings.notifyWhenAgentFinished) {
        mutableStateOf(settings.notifyWhenAgentFinished)
    }
    var showToken by remember { mutableStateOf(false) }
    val cleanupBaseUrl = when (networkProfile) {
        NetworkProfile.LAN -> durableLanBaseUrl.trim()
        NetworkProfile.TAILNET -> durableTailnetBaseUrl.trim()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Network profile", style = MaterialTheme.typography.titleMedium)
            Text(
                "Save both URLs and switch when you leave home Wi‑Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = networkProfile == NetworkProfile.LAN,
                    onClick = { networkProfile = NetworkProfile.LAN },
                    label = { Text("LAN") },
                )
                FilterChip(
                    selected = networkProfile == NetworkProfile.TAILNET,
                    onClick = { networkProfile = NetworkProfile.TAILNET },
                    label = { Text("Tailnet") },
                )
            }

            Text("Durable host", style = MaterialTheme.typography.titleMedium)
            Text(
                "The host owns Grok and Codex turns, so they keep running after this app closes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = durableLanBaseUrl,
                onValueChange = { durableLanBaseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Host LAN URL") },
                supportingText = { Text("Example: http://192.168.1.50:2440") },
                singleLine = true,
            )

            OutlinedTextField(
                value = durableTailnetBaseUrl,
                onValueChange = { durableTailnetBaseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Host Tailnet URL") },
                supportingText = { Text("Example: http://100.64.0.1:2440") },
                singleLine = true,
            )

            OutlinedTextField(
                value = durableHostToken,
                onValueChange = { durableHostToken = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Durable host token") },
                supportingText = { Text("Run host/agentremotesrv --show-token") },
                singleLine = true,
                visualTransformation = if (showToken) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Text(
                            if (showToken) "Hide" else "Show",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )

            Text("Workspace", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = workingDirectory,
                onValueChange = { workingDirectory = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Host project directory (cwd)") },
                supportingText = {
                    Text(
                        "Absolute path on the Linux host. Default is host \$HOME " +
                            "(/home/user). Sessions and tools use this directory.",
                    )
                },
                singleLine = true,
            )

            Text("Codex permissions", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("Full host access", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Default for new Codex sessions. Allows unrestricted access to files, " +
                            "credentials, processes, and the network without approval prompts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = codexFullAccess,
                    onCheckedChange = { codexFullAccess = it },
                    modifier = Modifier.semantics {
                        contentDescription = "Full host access for new Codex sessions"
                    },
                )
            }

            Text("Notifications", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("Notify when agent finishes", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Uses a long-poll only while a turn is active. No background polling " +
                            "when every session is idle. Alerts are skipped while you are " +
                            "viewing that chat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = notifyWhenAgentFinished,
                    onCheckedChange = { notifyWhenAgentFinished = it },
                    modifier = Modifier.semantics {
                        contentDescription = "Notify when agent finishes"
                    },
                )
            }

            Text("Session cleanup", style = MaterialTheme.typography.titleMedium)
            Text(
                "Permanently delete unpinned, idle Grok and Codex sessions whose latest " +
                    "activity is older than the selected cutoff. Both options check every " +
                    "workspace and keep pinned and active sessions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 30).forEach { days ->
                    OldSessionCleanupButton(
                        olderThanDays = days,
                        checking = sessionCleanup.checking &&
                            sessionCleanup.requestedDays == days,
                        enabled = !sessionCleanup.busy &&
                            cleanupBaseUrl.isNotBlank() &&
                            durableHostToken.isNotBlank(),
                        onClick = {
                            onPreviewOldSessions(cleanupBaseUrl, durableHostToken, days)
                        },
                    )
                }
            }
            sessionCleanup.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            sessionCleanup.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    onSave(
                        networkProfile,
                        durableLanBaseUrl,
                        durableTailnetBaseUrl,
                        durableHostToken,
                        workingDirectory,
                        codexFullAccess,
                        notifyWhenAgentFinished,
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }

            Text(
                "Host: install the checked-in agentremote-durable user service once. " +
                    "The same service runs both backends and survives phone disconnects.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val cleanupPreview = sessionCleanup.preview
    if (cleanupPreview != null) {
        AlertDialog(
            onDismissRequest = {
                if (!sessionCleanup.deleting) onDismissOldSessions()
            },
            icon = {
                Icon(Icons.Default.Delete, contentDescription = null)
            },
            title = { Text("Delete old sessions?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Permanently delete ${cleanupPreview.eligible.total} sessions with no " +
                            "activity in the last ${cleanupPreview.olderThanDays} days?",
                    )
                    Text(
                        "Grok ${cleanupPreview.eligible.grok} · " +
                            "Codex ${cleanupPreview.eligible.codex}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    val protected = cleanupPreview.skippedPinned + cleanupPreview.skippedActive
                    Text(
                        if (protected > 0) {
                            "${cleanupPreview.skippedPinned} pinned and " +
                                "${cleanupPreview.skippedActive} active old sessions will be kept."
                        } else {
                            "Pinned and active sessions are always kept."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (sessionCleanup.deleting) {
                        Text(
                            if (sessionCleanup.cleanupStatus == null ||
                                sessionCleanup.cleanupStatus == "scanning"
                            ) {
                                "Preparing session cleanup…"
                            } else {
                                "Deleting ${sessionCleanup.processed} of " +
                                    "${sessionCleanup.total ?: cleanupPreview.eligible.total} sessions…"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                        val progress = sessionCleanup.progress
                        if (progress == null) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Text("This cannot be undone.")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteOldSessions(cleanupBaseUrl, durableHostToken)
                    },
                    enabled = !sessionCleanup.deleting,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(if (sessionCleanup.deleting) "Deleting…" else "Delete all")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissOldSessions,
                    enabled = !sessionCleanup.deleting,
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun OldSessionCleanupButton(
    olderThanDays: Int,
    checking: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) {
        if (checking) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(Icons.Default.Delete, contentDescription = null)
        }
        Text(
            if (checking) {
                "Checking old sessions…"
            } else {
                "Delete sessions older than $olderThanDays days"
            },
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
