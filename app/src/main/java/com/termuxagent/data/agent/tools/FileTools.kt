package com.termuxagent.data.agent.tools

import com.termuxagent.data.workspace.WorkspaceManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Read a file's contents (text). Truncated to 256KB. */
class ReadFileTool(private val ws: WorkspaceManager) : AgentTool {
    override val name = "read_file"
    override val description = "Read the contents of a text file in the workspace. Truncated to 256KB. Returns the file's text."
    override val parametersSchema = objSchema(
        properties = mapOf("path" to ws.pathProp("Path of the file to read, e.g. 'src/main.py'.")),
        required = listOf("path")
    )

    override suspend fun invoke(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Missing 'path'.")
        return runCatching {
            val text = ws.readFile(path)
            ToolResult(true, text, meta = mapOf("path" to path))
        }.getOrElse { e ->
            ToolResult(false, e.message ?: "read failed", meta = mapOf("path" to path))
        }
    }
}

/** Write/create/overwrite a text file. Creates parent dirs. */
class WriteFileTool(private val ws: WorkspaceManager) : AgentTool {
    override val name = "write_file"
    override val description = "Write text to a file in the workspace. Creates parent directories. Overwrites if it exists."
    override val parametersSchema = objSchema(
        properties = mapOf(
            "path" to ws.pathProp("Path of the file to write."),
            "content" to strProp("The full text content to write. Will replace any existing content.")
        ),
        required = listOf("path", "content")
    )

    override suspend fun invoke(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Missing 'path'.")
        val content = args["content"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Missing 'content'.")
        return runCatching {
            ws.writeFile(path, content)
            ToolResult(true, "Wrote ${content.length} chars to $path", meta = mapOf("path" to path, "bytes" to content.toByteArray().size.toString()))
        }.getOrElse { e ->
            ToolResult(false, e.message ?: "write failed", meta = mapOf("path" to path))
        }
    }
}

/** In-place text replacement within a file. */
class EditFileTool(private val ws: WorkspaceManager) : AgentTool {
    override val name = "edit_file"
    override val description = "Replace occurrences of 'find' with 'replace' inside an existing file. Use replace_all to control single vs. multi replacement."
    override val parametersSchema = objSchema(
        properties = mapOf(
            "path" to ws.pathProp("Path of the file to edit."),
            "find" to strProp("Exact string to find (literal, not regex)."),
            "replace" to strProp("String to replace it with."),
            "replace_all" to boolProp("If true (default), replace every occurrence; if false, only the first.")
        ),
        required = listOf("path", "find", "replace")
    )

    override suspend fun invoke(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content ?: return ToolResult(false, "Missing 'path'.")
        val find = args["find"]?.jsonPrimitive?.content ?: return ToolResult(false, "Missing 'find'.")
        val replace = args["replace"]?.jsonPrimitive?.content ?: return ToolResult(false, "Missing 'replace'.")
        val all = args["replace_all"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        return runCatching {
            val original = ws.readFile(path)
            val new = if (all) original.replace(find, replace) else original.replaceFirst(find, replace)
            if (new == original) {
                ToolResult(false, "No occurrences of 'find' in $path — file unchanged.", meta = mapOf("path" to path))
            } else {
                ws.writeFile(path, new)
                ToolResult(true, "Edited $path.", meta = mapOf("path" to path))
            }
        }.getOrElse { e ->
            ToolResult(false, e.message ?: "edit failed", meta = mapOf("path" to path))
        }
    }
}

/** Append text to a file (creates if missing). */
class AppendFileTool(private val ws: WorkspaceManager) : AgentTool {
    override val name = "append_file"
    override val description = "Append text to the end of a file. Creates the file if it doesn't exist."
    override val parametersSchema = objSchema(
        properties = mapOf(
            "path" to ws.pathProp("Path of the file to append to."),
            "content" to strProp("Text to append.")
        ),
        required = listOf("path", "content")
    )

    override suspend fun invoke(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content ?: return ToolResult(false, "Missing 'path'.")
        val content = args["content"]?.jsonPrimitive?.content ?: return ToolResult(false, "Missing 'content'.")
        return runCatching {
            ws.appendFile(path, content)
            ToolResult(true, "Appended ${content.length} chars to $path", meta = mapOf("path" to path))
        }.getOrElse { e ->
            ToolResult(false, e.message ?: "append failed", meta = mapOf("path" to path))
        }
    }
}

/** List a directory's entries. */
class ListDirTool(private val ws: WorkspaceManager) : AgentTool {
    override val name = "list_dir"
    override val description = "List entries in a workspace directory. Returns names, types (file/dir), sizes, and mtimes."
    override val parametersSchema = objSchema(
        properties = mapOf("path" to ws.pathProp("Directory to list. Defaults to workspace root."))
    )

    override suspend fun invoke(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content ?: "."
        return runCatching {
            val entries = ws.listDir(path)
            if (entries.isEmpty()) {
                ToolResult(true, "(empty) $path", meta = mapOf("path" to path))
            } else {
                val sb = StringBuilder()
                sb.appendLine("$path  (${entries.size} entries)")
                sb.appendLine("─".repeat(40))
                for (e in entries) {
                    val tag = if (e.isDirectory) "[DIR] " else "      "
                    val size = if (e.isDirectory) "" else "  ${formatSize(e.size)}"
                    sb.appendLine("$tag${e.name}$size")
                }
                ToolResult(true, sb.toString(), meta = mapOf("path" to path, "count" to entries.size.toString()))
            }
        }.getOrElse { e ->
            ToolResult(false, e.message ?: "list failed", meta = mapOf("path" to path))
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}K"
        else -> "${bytes / (1024 * 1024)}M"
    }
}

/** Render a directory tree (text). */
class TreeTool(private val ws: WorkspaceManager) : AgentTool {
    override val name = "tree"
    override val description = "Print a directory tree of the workspace (or a subpath). Useful for orientation."
    override val parametersSchema = objSchema(
        properties = mapOf(
            "path" to ws.pathProp("Subdirectory to tree. Defaults to workspace root."),
            "max_depth" to intProp("Max depth to walk.", min = 1, max = 8)
        )
    )

    override suspend fun invoke(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content ?: "."
        val depth = args["max_depth"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 8) ?: 4
        return runCatching {
            val tree = ws.tree(path, maxDepth = depth)
            ToolResult(true, tree, meta = mapOf("path" to path))
        }.getOrElse { e ->
            ToolResult(false, e.message ?: "tree failed", meta = mapOf("path" to path))
        }
    }
}

/** Recursive text search across the workspace. */
class GrepTool(private val ws: WorkspaceManager) : AgentTool {
    override val name = "grep"
    override val description = "Search for a pattern across files in the workspace. Returns matching file paths and matching lines."
    override val parametersSchema = objSchema(
        properties = mapOf(
            "pattern" to strProp("Substring to search for (case-insensitive)."),
            "path" to ws.pathProp("Directory or file to search. Defaults to workspace root."),
            "max_results" to intProp("Max number of matching lines to return.", min = 1, max = 200)
        ),
        required = listOf("pattern")
    )

    override suspend fun invoke(args: JsonObject): ToolResult {
        val pattern = args["pattern"]?.jsonPrimitive?.content ?: return ToolResult(false, "Missing 'pattern'.")
        val path = args["path"]?.jsonPrimitive?.content ?: "."
        val max = args["max_results"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        return runCatching {
            val root = ws.resolve(path)
            if (!root.exists()) return ToolResult(false, "Not found: $path", meta = mapOf("path" to path))
            val matches = mutableListOf<String>()
            val needle = pattern.lowercase()
            root.walkTopDown().filter { it.isFile && it.length() < 512_000 }.forEach { f ->
                try {
                    val lines = f.readLines()
                    for ((i, line) in lines.withIndex()) {
                        if (line.lowercase().contains(needle)) {
                            val rel = f.toRelativeString(ws.root)
                            matches.add("$rel:${i + 1}: ${line.take(200)}")
                            if (matches.size >= max) return@forEach
                        }
                    }
                } catch (_: Exception) { /* skip unreadable */ }
            }
            val out = if (matches.isEmpty()) "No matches for '$pattern'."
            else matches.joinToString("\n", prefix = "Found ${matches.size} match(es) for '$pattern':\n")
            ToolResult(true, out, meta = mapOf("path" to path, "matches" to matches.size.toString()))
        }.getOrElse { e ->
            ToolResult(false, e.message ?: "grep failed", meta = mapOf("path" to path))
        }
    }
}

/** Create a directory. */
class MkdirTool(private val ws: WorkspaceManager) : AgentTool {
    override val name = "mkdir"
    override val description = "Create a directory (and parents) in the workspace."
    override val parametersSchema = objSchema(
        properties = mapOf("path" to ws.pathProp("Directory path to create.")),
        required = listOf("path")
    )

    override suspend fun invoke(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content ?: return ToolResult(false, "Missing 'path'.")
        return runCatching {
            ws.mkdir(path)
            ToolResult(true, "Created $path", meta = mapOf("path" to path))
        }.getOrElse { e ->
            ToolResult(false, e.message ?: "mkdir failed", meta = mapOf("path" to path))
        }
    }
}

/** Delete a file or directory (recursive). */
class DeleteTool(private val ws: WorkspaceManager) : AgentTool {
    override val name = "delete"
    override val description = "Delete a file or directory (recursive). Use with care."
    override val parametersSchema = objSchema(
        properties = mapOf("path" to ws.pathProp("Path of the file or directory to delete.")),
        required = listOf("path")
    )

    override suspend fun invoke(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content ?: return ToolResult(false, "Missing 'path'.")
        return runCatching {
            val ok = ws.delete(path)
            ToolResult(ok, if (ok) "Deleted $path" else "Could not delete $path", meta = mapOf("path" to path))
        }.getOrElse { e ->
            ToolResult(false, e.message ?: "delete failed", meta = mapOf("path" to path))
        }
    }
}
