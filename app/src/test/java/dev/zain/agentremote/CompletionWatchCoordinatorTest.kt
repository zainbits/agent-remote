package dev.zain.agentremote

import dev.zain.agentremote.data.BackendKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionWatchCoordinatorTest {
    @Test
    fun shouldNotifyIsFalseWhenDisabled() {
        assertFalse(
            CompletionWatchCoordinator.shouldNotify(
                notifyEnabled = false,
                appInForeground = false,
                screenIsChat = false,
                viewingSessionId = null,
                viewingBackend = null,
                finishedBackend = BackendKind.GROK_BUILD,
                finishedSessionId = "s1",
            ),
        )
    }

    @Test
    fun shouldNotifyIsFalseWhenViewingThatChat() {
        assertFalse(
            CompletionWatchCoordinator.shouldNotify(
                notifyEnabled = true,
                appInForeground = true,
                screenIsChat = true,
                viewingSessionId = "s1",
                viewingBackend = BackendKind.CODEX,
                finishedBackend = BackendKind.CODEX,
                finishedSessionId = "s1",
            ),
        )
    }

    @Test
    fun shouldNotifyWhenBackgroundedEvenIfChatWasOpen() {
        assertTrue(
            CompletionWatchCoordinator.shouldNotify(
                notifyEnabled = true,
                appInForeground = false,
                screenIsChat = true,
                viewingSessionId = "s1",
                viewingBackend = BackendKind.CODEX,
                finishedBackend = BackendKind.CODEX,
                finishedSessionId = "s1",
            ),
        )
    }

    @Test
    fun shouldNotifyForOtherSessionWhileViewingDifferentChat() {
        assertTrue(
            CompletionWatchCoordinator.shouldNotify(
                notifyEnabled = true,
                appInForeground = true,
                screenIsChat = true,
                viewingSessionId = "s1",
                viewingBackend = BackendKind.GROK_BUILD,
                finishedBackend = BackendKind.GROK_BUILD,
                finishedSessionId = "s2",
            ),
        )
    }

    @Test
    fun shouldNotifyFromHomeScreen() {
        assertTrue(
            CompletionWatchCoordinator.shouldNotify(
                notifyEnabled = true,
                appInForeground = true,
                screenIsChat = false,
                viewingSessionId = null,
                viewingBackend = BackendKind.GROK_BUILD,
                finishedBackend = BackendKind.GROK_BUILD,
                finishedSessionId = "s1",
            ),
        )
    }
}
