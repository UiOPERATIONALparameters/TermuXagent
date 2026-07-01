package com.termuxagent.data.agent

import com.termuxagent.data.agent.tools.AgentTool
import com.termuxagent.data.agent.tools.CopyClipboardTool
import com.termuxagent.data.agent.tools.DownloadUrlTool
import com.termuxagent.data.agent.tools.FileInfoTool
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
import com.termuxagent.data.agent.tools.WebReadTool
import com.termuxagent.data.agent.tools.WebSearchTool
import com.termuxagent.data.agent.tools.WriteFileTool
import com.termuxagent.data.api.ToolDef
import com.termuxagent.data.api.ToolFunction
import com.termuxagent.data.settings.AppSettings
import com.termuxagent.data.workspace.WorkspaceManager
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Holds the tool implementations and produces the OpenAI-style `tools` array
 * to send with chat requests.
 */
class ToolRegistry(ws: WorkspaceManager, settings: AppSettings) {
    private val tools: List<AgentTool> = buildList {
        add(ShellTool(ws))
        add(ReadFileTool(ws))
        add(WriteFileTool(ws))
        add(EditFileTool(ws))
        add(AppendFileTool(ws))
        add(ListDirTool(ws))
        add(TreeTool(ws))
        add(GrepTool(ws))
        add(MkdirTool(ws))
        add(DeleteTool(ws))
        add(FileInfoTool(ws))
        add(HttpFetchTool())
        add(DownloadUrlTool(ws))
        add(ListInterpretersTool())
        add(CopyClipboardTool())
        add(ShareFileTool(ws))
        add(OpenUrlTool())
        // Web search tools — conditionally included based on settings
        if (settings.webSearchEnabled) {
            add(WebSearchTool(
                provider = settings.webSearchProvider,
                exaApiKey = settings.exaApiKey,
                firecrawlApiKey = settings.firecrawlApiKey
            ))
        }
        // web_read is always available — it's just a URL fetcher, no API cost
        add(WebReadTool())
    }

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
