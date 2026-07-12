package dev.zain.agentremote

import android.os.Bundle
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.zain.agentremote.ui.ChatScreen
import dev.zain.agentremote.ui.HomeScreen
import dev.zain.agentremote.ui.SettingsScreen
import dev.zain.agentremote.ui.theme.AgentRemoteTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AgentRemoteTheme {
                val vm: ChatViewModel = viewModel()
                val state by vm.ui.collectAsState()
                val nav = rememberNavController()
                val navEntry by nav.currentBackStackEntryAsState()

                LaunchedEffect(navEntry?.destination?.route, state.screen) {
                    vm.onSessionListVisibilityChanged(
                        navEntry?.destination?.route == "main" && state.screen == AppScreen.HOME,
                    )
                }

                LifecycleStartEffect(vm) {
                    vm.onAppForegrounded()
                    onStopOrDispose { vm.onAppBackgrounded() }
                }

                NavHost(navController = nav, startDestination = "main") {
                    composable("main") {
                        val backProgress = remember { Animatable(0f) }
                        var backSwipeEdge by remember {
                            mutableIntStateOf(BackEventCompat.EDGE_LEFT)
                        }

                        LaunchedEffect(state.screen) {
                            if (state.screen == AppScreen.HOME) backProgress.snapTo(0f)
                        }

                        PredictiveBackHandler(enabled = state.screen == AppScreen.CHAT) { events ->
                            try {
                                events.collect { event ->
                                    backSwipeEdge = event.swipeEdge
                                    backProgress.snapTo(event.progress.coerceIn(0f, 1f))
                                }
                                backProgress.animateTo(1f, tween(durationMillis = 90))
                                vm.goHome()
                            } catch (cancelled: CancellationException) {
                                // The callback's coroutine is cancelled with the gesture. Finish the
                                // visual rollback in a non-cancellable context so it never snaps.
                                withContext(NonCancellable) {
                                    backProgress.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    )
                                }
                            }
                        }

                        val progress = backProgress.value
                        val homeRevealProgress = if (state.screen == AppScreen.HOME) {
                            1f
                        } else {
                            progress
                        }
                        Box(Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val homeScale = 0.97f + (0.03f * homeRevealProgress)
                                        scaleX = homeScale
                                        scaleY = homeScale
                                        alpha = 0.78f + (0.22f * homeRevealProgress)
                                    },
                            ) {
                                HomeScreen(
                                    state = state,
                                    onOpenSession = vm::openSession,
                                    onNewSession = vm::openNewSession,
                                    onRefresh = vm::refreshSessions,
                                    onSelectBackend = vm::selectBackend,
                                    onOpenSettings = { nav.navigate("settings") },
                                    interactionEnabled =
                                        state.screen == AppScreen.HOME && !state.busy,
                                )
                            }

                            if (state.screen == AppScreen.CHAT) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val direction = if (
                                                backSwipeEdge == BackEventCompat.EDGE_LEFT
                                            ) {
                                                1f
                                            } else {
                                                -1f
                                            }
                                            val sessionScale = 1f - (0.08f * progress)
                                            scaleX = sessionScale
                                            scaleY = sessionScale
                                            alpha = 1f - (0.48f * progress)
                                            translationX = direction * 28.dp.toPx() * progress
                                            transformOrigin = TransformOrigin.Center
                                            shape = RoundedCornerShape((28f * progress).dp)
                                            clip = progress > 0f
                                            shadowElevation = 18.dp.toPx() * progress
                                        },
                                ) {
                                    ChatScreen(
                                        state = state,
                                        onDraftChange = vm::onDraftChange,
                                        onSend = vm::send,
                                        onCancel = vm::cancelCurrentRequest,
                                        onSelectSlashCommand = vm::selectSlashCommand,
                                        onSelectModel = vm::selectModel,
                                        onDismissCommandOutput = vm::dismissCommandOutput,
                                        onDisconnect = vm::disconnect,
                                        onReconnect = vm::reconnect,
                                        onBack = vm::goHome,
                                        onOpenSettings = { nav.navigate("settings") },
                                    )
                                }
                            }
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
