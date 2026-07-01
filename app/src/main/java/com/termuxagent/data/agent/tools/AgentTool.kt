package com.termuxagent.data.agent.tools

import com.termuxagent.data.workspace.WorkspaceManager
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One tool the agent can call. Implementations are responsible for:
 *  - declaring their JSON-Schema parameters via [parametersSchema]
 *  - validating + executing via [invoke]
 *
 * The agent loop serialises [ToolResult] back into a "tool" role message.
 */
interface AgentTool {
    val name: String
    val description: String
    val parametersSchema: JsonObject

    suspend fun invoke(args: JsonObject): ToolResult
}

data class ToolResult(
    val ok: Boolean,
    val output: String,
    /** Optional structured metadata the UI can render (e.g. file path, command). */
    val meta: Map<String, String> = emptyMap()
) {
    fun toMessage(): String = buildString {
        if (!ok) append("[ERROR] ")
        append(output)
    }
}

// ── Helpers for building JSON-Schema fragments in pure kotlinx-serialization ──

fun objSchema(
    properties: Map<String, JsonElement>,
    required: List<String> = emptyList(),
    description: String? = null
): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    if (description != null) put("description", JsonPrimitive(description))
    put("properties", buildJsonObject {
        properties.forEach { (k, v) -> put(k, v) }
    })
    if (required.isNotEmpty()) {
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }
}

fun strProp(desc: String, enum: List<String>? = null): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("string"))
    put("description", JsonPrimitive(desc))
    if (enum != null) {
        put("enum", buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } })
    }
}

fun intProp(desc: String, min: Int? = null, max: Int? = null): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("integer"))
    put("description", JsonPrimitive(desc))
    min?.let { put("minimum", JsonPrimitive(it)) }
    max?.let { put("maximum", JsonPrimitive(it)) }
}

fun boolProp(desc: String): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("boolean"))
    put("description", JsonPrimitive(desc))
}

fun WorkspaceManager.pathProp(desc: String = "Workspace-relative path. Use '.' for the workspace root.") =
    strProp(desc)
