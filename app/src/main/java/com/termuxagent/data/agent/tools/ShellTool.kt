package com.termuxagent.data.agent.tools

import com.termuxagent.data.workspace.WorkspaceManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Run a shell command in the workspace. Uses Android's /system/bin/sh with
 * toybox providing a usable Unix toolset (ls, cat, cp, mv, rm, mkdir, find,
 * grep, sed, awk, gzip, tar, head, tail, sort, uniq, wc, …). Non-rooted:
 * commands are scoped to the workspace and the app's data dir.
 *
 * Detects common interpreters (python, python3, node) only if the user has
 * installed them (e.g. via Termux:API or by linking). Otherwise shells out
 * normally.
 */
class ShellTool(private val ws: WorkspaceManager) : AgentTool {
    override val name = "shell"
    override val description = """Run a shell command in the workspace. The workspace root is the cwd.
Toybox provides: ls, cat, cp, mv, rm, mkdir, find, grep, sed, awk, gzip, tar, head, tail, sort, uniq, wc, bc, tr, cut, tee, xargs, etc.
Use this to: list files, run scripts you wrote, install nothing (no package manager), inspect output, chain commands.
Returns combined stdout+stderr and the exit code. Output is truncated to ~20KB."""
    override val parametersSchema = objSchema(
        properties = mapOf(
            "command" to strProp("The shell command to run, e.g. 'ls -la' or 'python3 main.py'."),
            "timeout_ms" to intProp("Max runtime in milliseconds.", min = 100, max = 60_000)
        ),
        required = listOf("command")
    )

    override suspend fun invoke(args: JsonObject): ToolResult {
        val command = args["command"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Missing 'command' argument.")
        val timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.content?.toIntOrNull() ?: 30_000)
            .coerceIn(100, 60_000)

        val cwd = ws.root
        val builder = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(cwd)
            .redirectErrorStream(true)
        // Make the workspace the home so commands like 'cd ~' behave.
        builder.environment()["HOME"] = cwd.absolutePath
        builder.environment()["TERM"] = "xterm-256color"
        builder.environment()["LANG"] = "en_US.UTF-8"
        builder.environment()["LC_ALL"] = "en_US.UTF-8"

        return runCatching {
            val proc = builder.start()
            val out = StringBuilder()
            val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
            val charBuf = CharArray(4096)
            var read: Int
            var total = 0
            val maxBytes = 20_000
            while (reader.read(charBuf).also { read = it } != -1) {
                if (total + read > maxBytes) {
                    out.append(charBuf, 0, (maxBytes - total).coerceAtLeast(0))
                    out.append("\n…[output truncated at ${maxBytes} chars]")
                    break
                }
                out.append(charBuf, 0, read)
                total += read
            }
            val finished = proc.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                ToolResult(
                    ok = false,
                    output = stripAnsi(out.toString()) + "\n[TIMEOUT after ${timeoutMs}ms — process killed]",
                    meta = mapOf("command" to command, "exit" to "-1")
                )
            } else {
                val exit = proc.exitValue()
                ToolResult(
                    ok = exit == 0,
                    output = stripAnsi("$out\n[exit code: $exit]".trim()),
                    meta = mapOf("command" to command, "exit" to exit.toString())
                )
            }
        }.getOrElse { e ->
            ToolResult(false, "Failed to execute: ${e.message}", meta = mapOf("command" to command))
        }
    }

    /** Strip ANSI escape sequences (colors, cursor moves) for clean display. */
    private fun stripAnsi(s: String): String =
        s.replace(Regex("\u001B\\[[0-9;]*[ -/]*[@-~]"), "")
         .replace("\r", "")
}
