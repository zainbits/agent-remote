package dev.zain.agentremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionCleanupUiStateTest {
    @Test
    fun progressIsAvailableOnlyWithAPositiveTotalAndIsClamped() {
        assertNull(SessionCleanupUiState(processed = 1).progress)
        assertNull(SessionCleanupUiState(processed = 0, total = 0).progress)
        assertEquals(0.5f, SessionCleanupUiState(processed = 2, total = 4).progress)
        assertEquals(1f, SessionCleanupUiState(processed = 5, total = 4).progress)
    }
}
