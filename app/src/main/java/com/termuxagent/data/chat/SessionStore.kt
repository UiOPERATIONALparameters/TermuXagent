package com.termuxagent.data.chat

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

// ── Serializable models for persistence ──────────────────────────────────────

@Serializable
data class StoredSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<StoredMessage>
)

@Serializable
data class StoredMessage(
    val role: String,  // "user" or "assistant"
    val content: String?,  // text content
    val toolCalls: List<StoredToolCall>? = null,
    val error: String? = null
)

@Serializable
data class StoredToolCall(
    val toolCallId: String,
    val name: String,
    val argsRaw: String,
    val status: String,  // "done" or "failed"
    val result: String? = null,
    val ok: Boolean? = null
)

// ── Session store ────────────────────────────────────────────────────────────

class SessionStore(context: Context) {
    private val dir = File(context.filesDir, "sessions").apply { mkdirs() }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    data class SessionMeta(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val messageCount: Int
    )

    fun listSessions(): List<SessionMeta> {
        return dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching {
                    val session = json.decodeFromString(StoredSession.serializer(), f.readText())
                    SessionMeta(session.id, session.title, session.updatedAt, session.messages.size)
                }.getOrNull()
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    fun loadSession(id: String): StoredSession? {
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return runCatching {
            json.decodeFromString(StoredSession.serializer(), f.readText())
        }.getOrNull()
    }

    fun saveSession(session: StoredSession) {
        val f = File(dir, "${session.id}.json")
        f.writeText(json.encodeToString(StoredSession.serializer(), session))
    }

    fun deleteSession(id: String) {
        File(dir, "$id.json").delete()
    }

    fun createSession(title: String = "New chat"): StoredSession {
        val now = System.currentTimeMillis()
        return StoredSession(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
            messages = emptyList()
        )
    }
}

// ── Conversion between UiMessage and StoredMessage ──────────────────────────

fun List<UiMessage>.toStored(): List<StoredMessage> = map { msg ->
    when (msg) {
        is UiMessage.User -> StoredMessage(role = "user", content = msg.text)
        is UiMessage.Assistant -> {
            val textParts = msg.blocks.filterIsInstance<AssistantBlock.Text>()
                .joinToString("") { it.text }
            val toolCalls = msg.blocks.filterIsInstance<AssistantBlock.ToolCall>()
                .filter { it.status == ToolCallStatus.DONE || it.status == ToolCallStatus.FAILED }
                .map { tc ->
                    StoredToolCall(
                        toolCallId = tc.toolCallId,
                        name = tc.name,
                        argsRaw = tc.argsRaw,
                        status = tc.status.name.lowercase(),
                        result = tc.result,
                        ok = tc.ok
                    )
                }
            StoredMessage(
                role = "assistant",
                content = textParts.ifBlank { null },
                toolCalls = toolCalls.ifEmpty { null },
                error = msg.error
            )
        }
    }
}

fun List<StoredMessage>.toUi(): List<UiMessage> = map { msg ->
    when (msg.role) {
        "user" -> UiMessage.User(text = msg.content ?: "")
        "assistant" -> {
            val blocks = mutableListOf<AssistantBlock>()
            if (!msg.content.isNullOrBlank()) {
                blocks.add(AssistantBlock.Text(text = msg.content, isStreaming = false))
            }
            msg.toolCalls?.forEach { tc ->
                val status = when (tc.status) {
                    "done" -> ToolCallStatus.DONE
                    "failed" -> ToolCallStatus.FAILED
                    else -> ToolCallStatus.DONE
                }
                blocks.add(
                    AssistantBlock.ToolCall(
                        toolCallId = tc.toolCallId,
                        name = tc.name,
                        argsRaw = tc.argsRaw,
                        status = status,
                        result = tc.result,
                        ok = tc.ok,
                        meta = emptyMap()
                    )
                )
            }
            UiMessage.Assistant(
                blocks = blocks,
                isStreaming = false,
                error = msg.error
            )
        }
        else -> UiMessage.User(text = msg.content ?: "")
    }
}

fun deriveTitle(messages: List<UiMessage>): String {
    val firstUserMsg = messages.firstOrNull { it is UiMessage.User } as? UiMessage.User
    val text = firstUserMsg?.text?.trim()?.take(50) ?: "New chat"
    return if (text.length > 47) text.take(47) + "…" else text
}
