package dev.zain.agentremote.ui

import dev.zain.agentremote.agent.ChatMessage
import dev.zain.agentremote.agent.ChatRole

internal sealed interface ChatTimelineItem {
    val key: String

    data class MessageItem(
        val message: ChatMessage,
    ) : ChatTimelineItem {
        override val key: String = "message:${message.id}"
    }

    data class ToolGroupItem(
        val messages: List<ChatMessage>,
    ) : ChatTimelineItem {
        init {
            require(messages.isNotEmpty()) { "A tool group cannot be empty." }
            require(messages.all { it.role == ChatRole.TOOL }) {
                "A tool group can contain only tool messages."
            }
        }

        override val key: String = "tool-group:${messages.first().id}"
    }
}

/**
 * Groups only adjacent tool messages. Assistant text, thoughts, and system
 * messages remain hard chronology boundaries between tool-use phases.
 */
internal fun groupChatTimeline(messages: List<ChatMessage>): List<ChatTimelineItem> {
    val timeline = mutableListOf<ChatTimelineItem>()
    val pendingTools = mutableListOf<ChatMessage>()

    fun flushTools() {
        if (pendingTools.isNotEmpty()) {
            timeline += ChatTimelineItem.ToolGroupItem(pendingTools.toList())
            pendingTools.clear()
        }
    }

    messages.forEach { message ->
        if (message.role == ChatRole.TOOL) {
            pendingTools += message
        } else {
            flushTools()
            timeline += ChatTimelineItem.MessageItem(message)
        }
    }
    flushTools()

    return timeline
}
