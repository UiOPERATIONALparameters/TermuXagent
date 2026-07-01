package com.termuxagent.data.chat

import java.util.UUID

/**
 * UI-side representation of a conversation. Distinct from the wire-format
 * [com.termuxagent.data.api.ChatMessage] because the UI needs richer state
 * (streaming flags, tool-call statuses, etc.) that the wire format doesn't
 * carry.
 */
sealed class UiMessage {
    abstract val id: String

    data class User(
        override val id: String = UUID.randomUUID().toString(),
        val text: String
    ) : UiMessage()

    data class Assistant(
        override val id: String = UUID.randomUUID().toString(),
        val blocks: List<AssistantBlock> = emptyList(),
        val isStreaming: Boolean = false,
        val error: String? = null
    ) : UiMessage()
}

sealed class AssistantBlock {
    abstract val id: String

    data class Text(
        override val id: String = UUID.randomUUID().toString(),
        val text: String,
        val isStreaming: Boolean = false
    ) : AssistantBlock()

    data class ToolCall(
        override val id: String = UUID.randomUUID().toString(),
        val toolCallId: String,
        val name: String,
        val argsRaw: String,
        val status: ToolCallStatus,
        val result: String? = null,
        val ok: Boolean? = null,
        val meta: Map<String, String> = emptyMap()
    ) : AssistantBlock()
}

enum class ToolCallStatus {
    STREAMING,    // args still arriving
    RUNNING,      // args done, executing
    DONE,         // finished successfully
    FAILED        // finished with error
}
