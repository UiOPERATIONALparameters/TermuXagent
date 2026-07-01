package com.termuxagent.data.chat

import com.termuxagent.data.api.ChatMessage
import com.termuxagent.data.api.ToolCall
import com.termuxagent.data.api.ToolCallFunction

/**
 * Converts the UI's conversation representation into the wire-format messages
 * the OpenAI-compatible API expects. Tool-call results are attached as 'tool'
 * role messages immediately after the assistant turn that produced them.
 *
 * Streaming/in-flight blocks are emitted too — that way a re-prompt (e.g.
 * after the user clicks Stop) sees the latest committed state.
 */
fun List<UiMessage>.toWireFormat(): List<ChatMessage> = buildList {
    for (msg in this@toWireFormat) {
        when (msg) {
            is UiMessage.User -> {
                if (msg.text.isNotBlank()) add(ChatMessage(role = "user", content = msg.text))
            }
            is UiMessage.Assistant -> {
                // Compose assistant message: text content + tool_calls
                val textParts = msg.blocks.filterIsInstance<AssistantBlock.Text>().joinToString("") { it.text }
                val toolCalls = msg.blocks.filterIsInstance<AssistantBlock.ToolCall>()
                    .filter { it.status != ToolCallStatus.STREAMING } // skip half-streamed calls
                    .map { tc ->
                        ToolCall(
                            id = tc.toolCallId,
                            function = ToolCallFunction(name = tc.name, arguments = tc.argsRaw)
                        )
                    }
                add(
                    ChatMessage(
                        role = "assistant",
                        content = textParts.ifBlank { null },
                        toolCalls = toolCalls.ifEmpty { null }
                    )
                )
                // Emit tool result messages in order.
                for (tc in msg.blocks.filterIsInstance<AssistantBlock.ToolCall>()) {
                    if (tc.status !in setOf(ToolCallStatus.DONE, ToolCallStatus.FAILED)) continue
                    if (tc.result == null) continue
                    add(
                        ChatMessage(
                            role = "tool",
                            content = (if (tc.ok != true) "[ERROR] " else "") + tc.result,
                            toolCallId = tc.toolCallId,
                            name = tc.name
                        )
                    )
                }
            }
        }
    }
}
