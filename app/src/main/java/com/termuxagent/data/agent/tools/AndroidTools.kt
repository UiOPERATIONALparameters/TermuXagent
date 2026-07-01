package com.termuxagent.data.agent.tools

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.termuxagent.data.workspace.WorkspaceManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Holds the application Context needed by tools that touch the Android
 * framework (clipboard, share intents, browser). Set once at app start by
 * [com.termuxagent.AetherAgentApp].
 */
object AndroidContext {
    @Volatile private var ctx: Context? = null
    fun bind(context: Context) { ctx = context.applicationContext }
    fun get(): Context = ctx ?: error("AndroidContext not bound")
}

/** Copy text to the system clipboard. */
class CopyClipboardTool : AgentTool {
    override val name = "copy_to_clipboard"
    override val description = "Copy text to the Android system clipboard. The user can paste it anywhere."
    override val parametersSchema = objSchema(
        properties = mapOf(
            "text" to strProp("The text to put on the clipboard."),
            "label" to strProp("Optional short label for the clipboard entry (visible in some clipboard managers).")
        ),
        required = listOf("text")
    )

    override suspend fun invoke(args: JsonObject): ToolResult {
        val text = args["text"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Missing 'text'.")
        val label = args["label"]?.jsonPrimitive?.content ?: "AetherAgent"
        return runCatching {
            val ctx = AndroidContext.get()
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            ToolResult(true, "Copied ${text.length} chars to clipboard.", meta = mapOf("label" to label))
        }.getOrElse { e ->
            ToolResult(false, "Clipboard failed: ${e.message}")
        }
    }
}

/**
 * Share a file (or arbitrary text) via Android's share sheet. The user picks
 * the target (messenger, email, drive, etc.). Files outside the workspace
 * can't be shared — only workspace paths.
 */
class ShareFileTool(private val ws: WorkspaceManager) : AgentTool {
    override val name = "share_file"
    override val description = "Open the Android share sheet for a workspace file (or arbitrary text). The user picks where to send it (messenger, email, Drive, etc.)."
    override val parametersSchema = objSchema(
        properties = mapOf(
            "path" to strProp("Workspace-relative path of the file to share. Mutually exclusive with 'text'."),
            "text" to strProp("Arbitrary text to share. Mutually exclusive with 'path'."),
            "mime_type" to strProp("Optional MIME type, e.g. 'image/png' or 'application/pdf'. Defaults to guessed from extension or 'text/plain'.")
        )
    )

    override suspend fun invoke(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content
        val text = args["text"]?.jsonPrimitive?.content
        val mime = args["mime_type"]?.jsonPrimitive?.content
        if (path.isNullOrBlank() && text.isNullOrBlank()) {
            return ToolResult(false, "Provide either 'path' or 'text'.")
        }
        return runCatching {
            val ctx = AndroidContext.get()
            val intent = Intent(Intent.ACTION_SEND).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!text.isNullOrBlank()) {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                } else {
                    val file = ws.resolve(path!!)
                    if (!file.exists()) error("File not found: $path")
                    val authority = "${ctx.packageName}.fileprovider"
                    val uri: Uri = FileProvider.getUriForFile(ctx, authority, file)
                    type = mime ?: guessMime(file) ?: "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            ctx.startActivity(Intent.createChooser(intent, "Share").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            ToolResult(true, if (path != null) "Share sheet opened for $path" else "Share sheet opened for text")
        }.getOrElse { e ->
            ToolResult(false, "Share failed: ${e.message}")
        }
    }

    private fun guessMime(file: File): String? = when (file.extension.lowercase()) {
        "txt", "md", "log" -> "text/plain"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "csv" -> "text/csv"
        "xml" -> "application/xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "gz", "tgz" -> "application/gzip"
        else -> null
    }
}

/** Open a URL in the user's default browser. */
class OpenUrlTool : AgentTool {
    override val name = "open_url"
    override val description = "Open a URL in the user's default Android browser. Use to launch a built website (via file:// if you wrote HTML to the workspace) or any external link."
    override val parametersSchema = objSchema(
        properties = mapOf(
            "url" to strProp("Absolute URL. http/https for external; file:// for a workspace file you want to preview as HTML.")
        ),
        required = listOf("url")
    )

    override suspend fun invoke(args: JsonObject): ToolResult {
        val url = args["url"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Missing 'url'.")
        return runCatching {
            val ctx = AndroidContext.get()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            ToolResult(true, "Opened $url in browser.")
        }.getOrElse { e ->
            ToolResult(false, "Open URL failed: ${e.message}")
        }
    }
}

/**
 * Detect which interpreters/runtimes are available on this device. Critical
 * for the agent — it must know whether python3/node/ruby/etc. exist before
 * trying to run them. Reports PATH, the workspace root, and a list of
 * available commands with versions.
 */
class ListInterpretersTool : AgentTool {
    override val name = "list_interpreters"
    override val description = "Probe the device for available runtimes & interpreters (python3, python, node, ruby, lua, etc.) and report PATH, ANDROID_HOME, and the workspace root. Call this before writing code in any language."
    override val parametersSchema = objSchema(properties = emptyMap())

    override suspend fun invoke(args: JsonObject): ToolResult {
        val probes = listOf(
            "python3 --version",
            "python --version",
            "node --version",
            "ruby --version",
            "lua -v",
            "php --version",
            "perl --version",
            "sh --version",
            "toybox",
            "bc --version",
            "git --version",
            "curl --version",
            "wget --version",
            "ssh -V"
        )
        val sb = StringBuilder()
        sb.appendLine("== Environment ==")
        sb.appendLine("PATH: ${System.getenv("PATH") ?: "(unset)"}")
        sb.appendLine("ANDROID_HOME: ${System.getenv("ANDROID_HOME") ?: "(unset)"}")
        sb.appendLine("HOME: ${System.getenv("HOME") ?: "(unset)"}")
        sb.appendLine()
        sb.appendLine("== Available runtimes ==")
        for (probe in probes) {
            val bin = probe.substringBefore(' ')
            val found = runCatching {
                val proc = ProcessBuilder("/system/bin/sh", "-c", "command -v $bin 2>/dev/null")
                    .redirectErrorStream(true)
                    .start()
                proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                val out = proc.inputStream.bufferedReader().readText().trim()
                out.isNotBlank()
            }.getOrDefault(false)
            if (found) {
                val version = runCatching {
                    val proc = ProcessBuilder("/system/bin/sh", "-c", probe)
                        .redirectErrorStream(true)
                        .start()
                    proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                    proc.inputStream.bufferedReader().readText().trim().take(120)
                }.getOrDefault("(version unavailable)")
                sb.appendLine("✓ $bin -> $version")
            }
        }
        sb.appendLine()
        sb.appendLine("Tip: install Python/Node/Ruby via Termux (F-Droid) to extend what you can run.")
        return ToolResult(true, sb.toString(), meta = mapOf("type" to "env_info"))
    }
}
