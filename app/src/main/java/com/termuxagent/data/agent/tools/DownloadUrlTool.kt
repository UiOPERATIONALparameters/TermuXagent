package com.termuxagent.data.agent.tools

import com.termuxagent.data.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Download a file from a URL into the workspace. Streams the response body
 * to disk (no in-memory size limit beyond the workspace's available storage).
 * Useful for: grabbing datasets, fetching images, pulling scripts.
 */
class DownloadUrlTool(
    private val ws: WorkspaceManager,
    private val client: OkHttpClient = defaultClient()
) : AgentTool {

    override val name = "download_url"
    override val description = """Download a file from a URL and save it to the workspace. Streams to disk (no size limit). Use for: datasets, images, scripts, archives.
Returns the saved path + byte count. The filename is auto-derived from the URL if 'path' is omitted."""
    override val parametersSchema = objSchema(
        properties = mapOf(
            "url" to strProp("Absolute URL to download."),
            "path" to strProp("Workspace-relative destination path. If omitted, derives a filename from the URL.")
        ),
        required = listOf("url")
    )

    override suspend fun invoke(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val url = args["url"]?.jsonPrimitive?.content
            ?: return@withContext ToolResult(false, "Missing 'url'.")
        val path = args["path"]?.jsonPrimitive?.content ?: deriveFilename(url)
        if (path.isBlank()) return@withContext ToolResult(false, "Could not derive filename from URL — pass 'path' explicitly.")

        val target = ws.resolve(path)
        target.parentFile?.mkdirs()

        runCatching {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@runCatching ToolResult(false, "HTTP ${resp.code} ${resp.message}", meta = mapOf("url" to url))
                }
                val body = resp.body ?: return@runCatching ToolResult(false, "No response body", meta = mapOf("url" to url))
                val contentType = body.contentType()?.toString() ?: "application/octet-stream"
                target.outputStream().use { out ->
                    body.byteStream().use { input ->
                        input.copyTo(out, bufferSize = 8192)
                    }
                }
                val size = target.length()
                ToolResult(
                    ok = true,
                    output = "Downloaded $url → $path ($size bytes, $contentType)",
                    meta = mapOf("url" to url, "path" to path, "bytes" to size.toString(), "mime" to contentType)
                )
            }
        }.getOrElse { e ->
            ToolResult(false, "Download failed: ${e.message}", meta = mapOf("url" to url))
        }
    }

    private fun deriveFilename(url: String): String {
        val cleaned = url.substringBefore('?').substringAfterLast('/')
        return cleaned.ifBlank { "download_${System.currentTimeMillis()}" }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}

/** Stat a file/directory — returns size, mtime, type, permissions. */
class FileInfoTool(private val ws: WorkspaceManager) : AgentTool {
    override val name = "file_info"
    override val description = "Get detailed info about a file or directory: size, last modified, readable/writable/executable, absolute path."
    override val parametersSchema = objSchema(
        properties = mapOf("path" to ws.pathProp("Path to inspect.")),
        required = listOf("path")
    )

    override suspend fun invoke(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Missing 'path'.")
        return runCatching {
            val f = ws.resolve(path)
            if (!f.exists()) return ToolResult(false, "Not found: $path", meta = mapOf("path" to path))
            val sb = StringBuilder()
            sb.appendLine("path:     $path")
            sb.appendLine("absolute: ${f.absolutePath}")
            sb.appendLine("type:     ${if (f.isDirectory) "directory" else "file"}")
            sb.appendLine("size:     ${if (f.isDirectory) ws.size(path) else f.length()} bytes")
            sb.appendLine("modified: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(f.lastModified()))}")
            sb.appendLine("readable: ${f.canRead()}")
            sb.appendLine("writable: ${f.canWrite()}")
            sb.appendLine("exec:     ${f.canExecute()}")
            if (f.isDirectory) {
                val count = f.listFiles()?.size ?: 0
                sb.appendLine("entries:  $count")
            }
            ToolResult(true, sb.toString(), meta = mapOf("path" to path))
        }.getOrElse { e ->
            ToolResult(false, e.message ?: "stat failed", meta = mapOf("path" to path))
        }
    }
}
