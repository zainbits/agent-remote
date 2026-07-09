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
        lanBaseUrl: String,
        tailnetBaseUrl: String,
        agentSecret: String,
        workingDirectory: String,
    ) -> Unit,
) {
    var networkProfile by remember(settings.networkProfile) {
        mutableStateOf(settings.networkProfile)
    }
    var lanBaseUrl by remember(settings.lanBaseUrl) { mutableStateOf(settings.lanBaseUrl) }
    var tailnetBaseUrl by remember(settings.tailnetBaseUrl) {
        mutableStateOf(settings.tailnetBaseUrl)
    }
    var agentSecret by remember(settings.agentSecret) { mutableStateOf(settings.agentSecret) }
    var workingDirectory by remember(settings.workingDirectory) {
        mutableStateOf(settings.workingDirectory)
    }
    var showSecret by remember { mutableStateOf(false) }

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
            Text("Network", style = MaterialTheme.typography.titleMedium)
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

            OutlinedTextField(
                value = lanBaseUrl,
                onValueChange = { lanBaseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("LAN base URL") },
                supportingText = { Text("Example: ws://192.168.1.50:2419") },
                singleLine = true,
            )
            OutlinedTextField(
                value = tailnetBaseUrl,
                onValueChange = { tailnetBaseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tailnet base URL") },
                supportingText = { Text("Example: ws://100.64.0.1:2419") },
                singleLine = true,
            )
            OutlinedTextField(
                value = agentSecret,
                onValueChange = { agentSecret = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Agent secret") },
                supportingText = { Text("Same token as GROK_AGENT_SECRET / grokserve") },
                singleLine = true,
                visualTransformation = if (showSecret) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Text(
                            if (showSecret) "Hide" else "Show",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
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

            Text("Backend", style = MaterialTheme.typography.titleMedium)
            Text(
                "Grok Build (active). Codex CLI reserved for a later release.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    onSave(
                        networkProfile,
                        lanBaseUrl,
                        tailnetBaseUrl,
                        agentSecret,
                        workingDirectory,
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }

            Text(
                "Host: run  grokserve  in a terminal (manual, not a boot service).\n" +
                    "It prints LAN + Tailnet WebSocket URLs and the secret.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
