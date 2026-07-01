package com.termuxagent.data.workspace

import android.content.Context
import java.io.File
import java.nio.file.Paths

/**
 * The agent's persistent filesystem. Rooted at the app's external files dir so
 * the user can also browse it from a file manager, and so it survives app
 * upgrades. Everything the agent reads/writes/runs is scoped under this root.
 *
 * Path semantics:
 *  - All public methods accept "workspace-relative" paths (e.g. "src/Main.kt").
 *  - Empty string or "." means the workspace root.
 *  - Paths are normalized and rejected if they try to escape the root (../etc).
 */
object WorkspaceManager {

    private lateinit var rootFile: File

    fun init(context: Context) {
        val base = context.getExternalFilesDir(null)
            ?: context.filesDir // fallback if no external storage mounted
        rootFile = File(base, "workspace").apply { if (!exists()) mkdirs() }
        // Seed a small README so the workspace isn't empty on first run.
        val readme = File(rootFile, "README.md")
        if (!readme.exists()) {
            readme.writeText(
                """
                # AetherAgent Workspace

                This is your AI's persistent workspace. Anything you ask the agent to build,
                write, or download will live here. Files persist across app restarts.

                The agent can:
                - read & write files
                - run shell commands (toybox-powered: ls, cat, grep, sed, awk, find, tar, …)
                - search the workspace
                - fetch URLs

                Try asking: *"Create a Python script that prints the first 20 Fibonacci numbers,
                then run it."*
                """.trimIndent()
            )
        }
    }

    val root: File get() = rootFile

    fun resolve(relPath: String): File {
        val cleaned = relPath.trim().trimStart('/').replace("\\", "/")
        val resolved = File(rootFile, cleaned).canonicalFile
        val rootCanon = rootFile.canonicalFile
        if (!resolved.path.startsWith(rootCanon.path)) {
            throw SecurityException("Path escapes workspace: $relPath")
        }
        return resolved
    }

    fun listDir(relPath: String): List<WorkspaceEntry> {
        val dir = resolve(relPath)
        if (!dir.exists()) return emptyList()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.sortedWith(compareByDescending< File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.map { f ->
                WorkspaceEntry(
                    name = f.name,
                    path = f.toRelativeString(rootFile),
                    isDirectory = f.isDirectory,
                    size = if (f.isFile) f.length() else 0L,
                    lastModified = f.lastModified()
                )
            } ?: emptyList()
    }

    fun readFile(relPath: String, maxBytes: Long = 256_000L): String {
        val f = resolve(relPath)
        if (!f.exists()) throw IllegalArgumentException("File not found: $relPath")
        if (f.isDirectory) throw IllegalArgumentException("Path is a directory: $relPath")
        val limited = if (f.length() > maxBytes) {
            f.readBytes().copyOf(maxBytes.toInt()).toString(Charsets.UTF_8) +
                "\n…[truncated, ${f.length()} bytes total]"
        } else {
            f.readText(Charsets.UTF_8)
        }
        return limited
    }

    fun writeFile(relPath: String, content: String) {
        val f = resolve(relPath)
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
    }

    fun appendFile(relPath: String, content: String) {
        val f = resolve(relPath)
        f.parentFile?.mkdirs()
        f.appendText(content, Charsets.UTF_8)
    }

    fun delete(relPath: String): Boolean {
        val f = resolve(relPath)
        return f.deleteRecursively()
    }

    fun mkdir(relPath: String): Boolean {
        val f = resolve(relPath)
        return f.mkdirs()
    }

    fun rename(from: String, to: String) {
        val src = resolve(from)
        val dst = resolve(to)
        if (!src.exists()) throw IllegalArgumentException("Source not found: $from")
        src.renameTo(dst)
    }

    fun exists(relPath: String): Boolean = resolve(relPath).exists()

    fun size(relPath: String): Long {
        val f = resolve(relPath)
        return if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
    }

    /** A flat text tree suitable for showing the agent (and the user) the layout. */
    fun tree(relPath: String = ".", maxDepth: Int = 4, maxEntries: Int = 500): String {
        val dir = resolve(relPath)
        if (!dir.exists()) return "(does not exist)"
        val sb = StringBuilder()
        val rootName = if (relPath.isBlank() || relPath == ".") "workspace" else relPath
        sb.appendLine(rootName)
        fun walk(file: File, prefix: String, depth: Int) {
            if (depth >= maxDepth) return
            val children = file.listFiles()?.toList()?.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
            ) ?: return
            for ((i, child) in children.withIndex()) {
                if (sb.lines().size > maxEntries) {
                    sb.appendLine("$prefix… (truncated)")
                    return
                }
                val last = i == children.lastIndex
                val branch = if (last) "└── " else "├── "
                val suffix = if (child.isDirectory) "/" else ""
                sb.appendLine("$prefix$branch${child.name}$suffix")
                if (child.isDirectory) {
                    walk(child, prefix + if (last) "    " else "│   ", depth + 1)
                }
            }
        }
        walk(dir, "", 0)
        return sb.toString().trimEnd()
    }

    data class WorkspaceEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long
    )
}
