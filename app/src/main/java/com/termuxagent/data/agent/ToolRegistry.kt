package com.termuxagent.data.agent

import com.termuxagent.data.agent.tools.AgentTool
import com.termuxagent.data.agent.tools.CopyClipboardTool
import com.termuxagent.data.agent.tools.OpenUrlTool
import com.termuxagent.data.agent.tools.ShareFileTool
import com.termuxagent.data.agent.tools.DeleteTool
import com.termuxagent.data.agent.tools.EditFileTool
import com.termuxagent.data.agent.tools.AppendFileTool
import com.termuxagent.data.agent.tools.GrepTool
import com.termuxagent.data.agent.tools.HttpFetchTool
import com.termuxagent.data.agent.tools.ListDirTool
import com.termuxagent.data.agent.tools.ListInterpretersTool
import com.termuxagent.data.agent.tools.MkdirTool
import com.termuxagent.data.agent.tools.ReadFileTool
import com.termuxagent.data.agent.tools.ShellTool
import com.termuxagent.data.agent.tools.TreeTool
import com.termuxagent.data.agent.tools.WriteFileTool
import com.termuxagent.data.api.ToolDef
import com.termuxagent.data.api.ToolFunction
import com.termuxagent.data.workspace.WorkspaceManager
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Holds the tool implementations and produces the OpenAI-style `tools` array
 * to send with chat requests.
 */
class ToolRegistry(ws: WorkspaceManager) {
    private val tools: List<AgentTool> = listOf(
        ShellTool(ws),
        ReadFileTool(ws),
        WriteFileTool(ws),
        EditFileTool(ws),
        AppendFileTool(ws),
        ListDirTool(ws),
        TreeTool(ws),
        GrepTool(ws),
        MkdirTool(ws),
        DeleteTool(ws),
        HttpFetchTool(),
        ListInterpretersTool(),
        CopyClipboardTool(),
        ShareFileTool(ws),
        OpenUrlTool()
    )

    private val byName: Map<String, AgentTool> = tools.associateBy { it.name }

    fun names(): List<String> = tools.map { it.name }

    fun has(name: String): Boolean = byName.containsKey(name)

    suspend fun invoke(name: String, args: JsonObject) =
        byName[name]?.invoke(args)
            ?: com.termuxagent.data.agent.tools.ToolResult(
                ok = false,
                output = "Unknown tool: $name. Available: ${names().joinToString(", ")}"
            )

    /** OpenAI-style tools array, serialised into the request body. */
    fun toolDefs(): List<ToolDef> = tools.map { t ->
        ToolDef(
            function = ToolFunction(
                name = t.name,
                description = t.description,
                parameters = t.parametersSchema
            )
        )
    }
}
