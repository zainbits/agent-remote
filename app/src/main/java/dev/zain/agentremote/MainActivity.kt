package dev.zain.agentremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.zain.agentremote.ui.ChatScreen
import dev.zain.agentremote.ui.SettingsScreen
import dev.zain.agentremote.ui.theme.AgentRemoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AgentRemoteTheme {
                val vm: ChatViewModel = viewModel()
                val state by vm.ui.collectAsState()
                val nav = rememberNavController()

                NavHost(navController = nav, startDestination = "chat") {
                    composable("chat") {
                        ChatScreen(
                            state = state,
                            onDraftChange = vm::onDraftChange,
                            onSend = vm::send,
                            onConnect = vm::connect,
                            onDisconnect = vm::disconnect,
                            onOpenSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            settings = state.settings,
                            onBack = { nav.popBackStack() },
                            onSave = vm::saveSettings,
                        )
                    }
                }
            }
        }
    }
}
