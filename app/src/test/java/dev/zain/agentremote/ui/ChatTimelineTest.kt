package dev.zain.agentremote.ui

import dev.zain.agentremote.agent.ChatMessage
import dev.zain.agentremote.agent.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineTest {
    @Test
    fun consecutiveToolsBecomeOneGroup() {
        val timeline = groupChatTimeline(
            listOf(
                message("user", ChatRole.USER),
                message("tool-1", ChatRole.TOOL),
                message("tool-2", ChatRole.TOOL),
                message("assistant", ChatRole.ASSISTANT),
            ),
        )

        assertEquals(3, timeline.size)
        val group = timeline[1] as ChatTimelineItem.ToolGroupItem
        assertEquals(listOf("tool-1", "tool-2"), group.messages.map { it.id })
    }

    @Test
    fun assistantContentSeparatesToolPhases() {
        val timeline = groupChatTimeline(
            listOf(
                message("tool-1", ChatRole.TOOL),
                message("assistant", ChatRole.ASSISTANT),
                message("tool-2", ChatRole.TOOL),
            ),
        )

        assertEquals(3, timeline.size)
        assertTrue(timeline[0] is ChatTimelineItem.ToolGroupItem)
        assertTrue(timeline[1] is ChatTimelineItem.MessageItem)
        assertTrue(timeline[2] is ChatTimelineItem.ToolGroupItem)
    }

    @Test
    fun toolGroupKeyStaysStableAsActionsArrive() {
        val first = message("tool-1", ChatRole.TOOL)
        val initial = groupChatTimeline(listOf(first)).single()
        val updated = groupChatTimeline(
            listOf(first, message("tool-2", ChatRole.TOOL)),
        ).single()

        assertEquals("tool-group:tool-1", initial.key)
        assertEquals(initial.key, updated.key)
    }

    private fun message(id: String, role: ChatRole) = ChatMessage(
        id = id,
        role = role,
        text = id,
    )
}
