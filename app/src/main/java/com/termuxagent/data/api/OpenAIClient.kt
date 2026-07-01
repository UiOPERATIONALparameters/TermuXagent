package com.termuxagent.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sealed stream of events from a chat completion request. The agent loop
 * consumes this and the UI surfaces it live.
 */
sealed class ChatStreamEvent {
    /** A text delta for the assistant message. */
    data class Delta(val text: String) : ChatStreamEvent()
    /** Notification that a tool call started (id + name known). May arrive with arguments partial. */
    data class ToolCallStart(val index: Int, val id: String, val name: String) : ChatStreamEvent()
    /** A partial arguments string for an in-flight tool call. */
    data class ToolCallArgs(val index: Int, val argsDelta: String) : ChatStreamEvent()
    /** The model finished producing this assistant turn. */
    data class Done(val finishReason: String?) : ChatStreamEvent()
    /** An error at any point — the flow will complete after this. */
    data class Error(val message: String, val cause: Throwable? = null) : ChatStreamEvent()
}

/**
 * A thin OpenAI-compatible client. Designed to work with any provider that
 * implements the /v1/chat/completions endpoint with SSE streaming and the
 * /v1/models endpoint for connection testing.
 */
class OpenAIClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        // MUST encode defaults: ToolDef.type = "function" is a required field
        // in the OpenAI tools schema, even though it's the default value.
        // encodeDefaults = false would strip it and the API returns 400:
        // "tools[0]: missing field 'type'".
        encodeDefaults = true
        explicitNulls = false
    }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = callbackFlow {
        val bodyStr = json.encodeToString(ChatRequest.serializer(), request)
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .post(bodyStr.toRequestBody(mediaType))
            .build()

        val factory = EventSources.createFactory(client)
        val es = factory.newEventSource(req, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    trySend(ChatStreamEvent.Done(finishReason = "stop"))
                    return
                }
                runCatching {
                    val chunk = json.decodeFromString(StreamChunk.serializer(), data)
                    for (choice in chunk.choices) {
                        val delta = choice.delta
                        delta.content?.let { trySend(ChatStreamEvent.Delta(it)) }
                        if (choice.finishReason != null) {
                            trySend(ChatStreamEvent.Done(choice.finishReason))
                        }
                        delta.toolCalls?.forEach { tc ->
                            val id = tc.id
                            val name = tc.function?.name
                            if (id != null && name != null && id.isNotEmpty() && name.isNotEmpty()) {
                                trySend(ChatStreamEvent.ToolCallStart(tc.index, id, name))
                            }
                            tc.function?.arguments?.let { args ->
                                if (args.isNotEmpty()) {
                                    trySend(ChatStreamEvent.ToolCallArgs(tc.index, args))
                                }
                            }
                        }
                    }
                }.onFailure { e ->
                    trySend(ChatStreamEvent.Error("Failed to parse SSE data: ${e.message}", e))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                channel.close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val code = response?.code
                val msg = buildString {
                    append("HTTP ")
                    append(code ?: "n/a")
                    if (code != null && code in 400..599) {
                        try { append(": ").append(response.body?.string()?.take(800)) } catch (_: Exception) {}
                    } else {
                        append(": ").append(t?.message ?: "network error")
                    }
                }
                trySend(ChatStreamEvent.Error(msg, t))
                channel.close()
            }
        })

        awaitClose {
            es.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /** Non-streaming call to /v1/models — used by the "Test connection" button. */
    suspend fun listModels(): Result<ModelsResponse> = runCatching {
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string()?.take(500).orEmpty()
                throw IOException("HTTP ${resp.code}: $body")
            }
            json.decodeFromString(ModelsResponse.serializer(), resp.body!!.string())
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
