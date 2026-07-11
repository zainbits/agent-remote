package dev.zain.agentremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.zain.agentremote.data.AppSettings
import dev.zain.agentremote.data.NetworkProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onSave: (
        networkProfile: NetworkProfile,
        durableLanBaseUrl: String,
        durableTailnetBaseUrl: String,
        durableHostToken: String,
        workingDirectory: String,
    ) -> Unit,
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
    var showToken by remember { mutableStateOf(false) }

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

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    onSave(
                        networkProfile,
                        durableLanBaseUrl,
                        durableTailnetBaseUrl,
                        durableHostToken,
                        workingDirectory,
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
}
