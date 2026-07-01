package com.termuxagent.data.agent

import com.termuxagent.data.api.ChatMessage
import com.termuxagent.data.api.ChatRequest
import com.termuxagent.data.api.ChatStreamEvent
import com.termuxagent.data.api.OpenAIClient
import com.termuxagent.data.api.ToolCall
import com.termuxagent.data.api.ToolCallFunction
import com.termuxagent.data.settings.AppSettings
import com.termuxagent.data.settings.DEFAULT_SYSTEM_PROMPT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Events the UI renders live. Each event corresponds to a discrete update
 * (text delta, tool call, tool result, completion, error).
 */
sealed class AgentEvent {
    /** A streaming text delta for the assistant's prose. */
    data class TextDelta(val text: String) : AgentEvent()
    /** A new tool call has begun (we have id + name). */
    data class ToolCallStart(val toolCallId: String, val name: String) : AgentEvent()
    /** Args for an in-flight tool call were updated (cumulative string). */
    data class ToolCallArgs(val toolCallId: String, val argsRaw: String) : AgentEvent()
    /** Tool call args are complete and execution is starting. */
    data class ToolCallRunning(val toolCallId: String, val name: String, val argsRaw: String) : AgentEvent()
    /** A tool finished. [output] is the tool's reply that will be sent back to the model. */
    data class ToolResultEvent(
        val toolCallId: String,
        val name: String,
        val ok: Boolean,
        val output: String,
        val meta: Map<String, String>
    ) : AgentEvent()
    /** Beginning a new round-trip with the model (after tool results were appended). */
    data class Iteration(val n: Int) : AgentEvent()
    /** The agent finished (either because the model stopped calling tools, or hit max iter). */
    data class Done(val reason: String, val iterations: Int) : AgentEvent()
    /** Something went wrong. */
    data class Error(val message: String) : AgentEvent()
}

/**
 * Runs the agent loop. The caller supplies prior chat history (already in
 * OpenAI message format) plus the user's new message text. The flow emits
 * AgentEvents; the caller is responsible for updating UI state in response.
 *
 * The flow is cold — collect it to start the loop. Cancelling collection
 * cancels the in-flight HTTP request + any tool execution.
 */
class Agent(
    private val settings: AppSettings,
    private val registry: ToolRegistry,
    private val client: OpenAIClient
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * @param history prior OpenAI-format messages (system prompt will be prepended by us)
     * @param userInput the user's new message
     */
    fun run(history: List<ChatMessage>, userInput: String): Flow<AgentEvent> = flow {
        require(settings.isConfigured) {
            emit(AgentEvent.Error("API not configured. Set API key, base URL, and model in Settings."))
            return@flow
        }

        val messages = buildList {
            // If the user cleared the system prompt, fall back to the default.
            val effectiveSystemPrompt = settings.systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }
            add(ChatMessage(role = "system", content = effectiveSystemPrompt))
            addAll(history)
            add(ChatMessage(role = "user", content = userInput))
        }
        val toolDefs = registry.toolDefs()
        var iterations = 0
        val maxIter = settings.maxIterations

        // Working copy of the conversation we mutate as the loop progresses.
        val convo = messages.toMutableList()

        try {
            while (iterations < maxIter) {
                iterations++
                emit(AgentEvent.Iteration(iterations))

                val request = ChatRequest(
                    model = settings.model,
                    messages = convo.toList(),
                    temperature = settings.temperature.toDouble(),
                    stream = true,
                    tools = toolDefs,
                    toolChoice = "auto"
                )

                // Accumulators for the streaming assistant turn.
                val textBuf = StringBuilder()
                // toolCallId -> (name, argsBuffer)
                val toolCalls = LinkedHashMap<String, Pair<String, StringBuilder>>()
                // Order of tool call ids as they arrived (so we can emit in order).
                val toolOrder = mutableListOf<String>()
                var finishReason: String? = null
                var streamError: String? = null

                client.streamChat(request).collect { ev ->
                    when (ev) {
                        is ChatStreamEvent.Delta -> {
                            if (ev.text.isNotEmpty()) {
                                textBuf.append(ev.text)
                                emit(AgentEvent.TextDelta(ev.text))
                            }
                        }
                        is ChatStreamEvent.ToolCallStart -> {
                            // We need a stable id; some servers send the id lazily.
                            val id = ev.id.ifBlank { "call_${toolOrder.size}_${System.currentTimeMillis()}" }
                            if (id !in toolCalls) {
                                toolCalls[id] = ev.name to StringBuilder()
                                toolOrder.add(id)
                                emit(AgentEvent.ToolCallStart(id, ev.name))
                            }
                        }
                        is ChatStreamEvent.ToolCallArgs -> {
                            // Find the right tool call by index in the order list.
                            val id = toolOrder.getOrNull(ev.index) ?: toolOrder.lastOrNull() ?: return@collect
                            toolCalls[id]?.second?.append(ev.argsDelta)
                            emit(AgentEvent.ToolCallArgs(id, toolCalls[id]!!.second.toString()))
                        }
                        is ChatStreamEvent.Done -> {
                            finishReason = ev.finishReason ?: finishReason
                        }
                        is ChatStreamEvent.Error -> {
                            // collect's lambda is crossinline — can't return@flow here.
                            // Capture the error and break out after collect completes.
                            streamError = ev.message
                        }
                    }
                }

                // If the stream errored, surface it and stop the loop.
                streamError?.let {
                    emit(AgentEvent.Error(it))
                    return@flow
                }

                // Build the assistant message for the conversation.
                val assistantMsg = ChatMessage(
                    role = "assistant",
                    content = textBuf.toString().ifBlank { null },
                    toolCalls = if (toolOrder.isEmpty()) null else toolOrder.map { id ->
                        val (name, argsBuf) = toolCalls[id]!!
                        ToolCall(
                            id = id,
                            function = ToolCallFunction(name = name, arguments = argsBuf.toString())
                        )
                    }
                )
                convo.add(assistantMsg)

                // If the model didn't call any tools, we're done.
                if (toolOrder.isEmpty()) {
                    emit(AgentEvent.Done(finishReason ?: "stop", iterations))
                    return@flow
                }

                // Execute each tool call, emit results, append tool messages.
                for (id in toolOrder) {
                    val (name, argsBuf) = toolCalls[id]!!
                    val argsRaw = argsBuf.toString()
                    emit(AgentEvent.ToolCallRunning(id, name, argsRaw))

                    val parsedArgs: JsonObject = parseArgs(argsRaw)
                    val result = runCatching { registry.invoke(name, parsedArgs) }
                        .getOrElse { e ->
                            com.termuxagent.data.agent.tools.ToolResult(
                                ok = false,
                                output = "Tool threw: ${e.message}"
                            )
                        }
                    emit(AgentEvent.ToolResultEvent(id, name, result.ok, result.output, result.meta))
                    convo.add(
                        ChatMessage(
                            role = "tool",
                            content = result.toMessage().take(20_000),
                            toolCallId = id,
                            name = name
                        )
                    )
                }
                // Loop continues — model will see the tool results and decide next step.
            }

            // Hit iteration cap.
            emit(AgentEvent.Done("max_iterations ($maxIter)", iterations))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            emit(AgentEvent.Error(t.message ?: t::class.simpleName ?: "unknown error"))
        }
    }

    private fun parseArgs(raw: String): JsonObject {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return runCatching {
            json.parseToJsonElement(raw) as? JsonObject ?: JsonObject(emptyMap())
        }.getOrElse { JsonObject(emptyMap()) }
    }
}
