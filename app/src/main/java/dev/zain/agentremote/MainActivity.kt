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
import dev.zain.agentremote.ui.HomeScreen
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

                NavHost(navController = nav, startDestination = "main") {
                    composable("main") {
                        when (state.screen) {
                            AppScreen.HOME -> HomeScreen(
                                state = state,
                                onOpenSession = vm::openSession,
                                onNewSession = vm::openNewSession,
                                onRefresh = vm::refreshSessions,
                                onOpenSettings = { nav.navigate("settings") },
                            )
                            AppScreen.CHAT -> ChatScreen(
                                state = state,
                                onDraftChange = vm::onDraftChange,
                                onSend = vm::send,
                                onDisconnect = vm::disconnect,
                                onBack = vm::goHome,
                                onOpenSettings = { nav.navigate("settings") },
                            )
                        }
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
