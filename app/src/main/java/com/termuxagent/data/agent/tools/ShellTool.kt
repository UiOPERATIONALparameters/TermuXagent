package com.termuxagent.data.agent.tools

import com.termuxagent.data.linux.LinuxEnvironment
import com.termuxagent.data.workspace.WorkspaceManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Run a shell command. When a [LinuxEnvironment] is provided and ready,
 * commands run inside the Alpine Linux rootfs via PRoot — giving the agent
 * access to apk, python3, nodejs, ruby, gcc, git, etc.
 *
 * When no Linux env is available (or not yet set up), falls back to
 * Android's /system/bin/sh + toybox.
 */
class ShellTool(
    private val ws: WorkspaceManager,
    private val linuxEnv: LinuxEnvironment? = null
) : AgentTool {
    override val name = "shell"
    override val description = """Run a shell command. When Linux env is enabled, runs inside Alpine Linux (apk, python3, nodejs, ruby, gcc, git, curl, wget, etc. all available via 'apk add'). Otherwise uses Android's toybox (ls, cat, grep, sed, awk, find, tar, bc, tr, cut, tee, xargs, etc.).
The workspace is mounted at /root/workspace inside Linux env. Returns combined stdout+stderr + exit code. Output truncated to ~20KB."""
    override val parametersSchema = objSchema(
        properties = mapOf(
            "command" to strProp("The shell command to run, e.g. 'ls -la', 'python3 main.py', or 'apk add python3 && python3 script.py'."),
            "timeout_ms" to intProp("Max runtime in milliseconds.", min = 100, max = 60_000)
        ),
        required = listOf("command")
    )

    override suspend fun invoke(args: JsonObject): ToolResult {
        val command = args["command"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Missing 'command' argument.")
        val timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.content?.toIntOrNull() ?: 30_000)
            .coerceIn(100, 60_000)

        // Decide: Linux env or Android shell?
        val useLinux = linuxEnv?.isReady == true
        val actualCommand = if (useLinux) {
            linuxEnv!!.buildProotCommand(command) ?: command
        } else {
            command
        }

        val cwd = ws.root
        val builder = ProcessBuilder("/system/bin/sh", "-c", actualCommand)
            .directory(cwd)
            .redirectErrorStream(true)
        builder.environment()["HOME"] = cwd.absolutePath
        builder.environment()["TERM"] = "xterm-256color"
        builder.environment()["LANG"] = "en_US.UTF-8"
        builder.environment()["LC_ALL"] = "en_US.UTF-8"
        // If Termux is installed, prepend its bin dir to PATH
        val termuxBin = "/data/data/com.termux/files/usr/bin"
        if (java.io.File(termuxBin).exists()) {
            val currentPath = builder.environment()["PATH"] ?: ""
            builder.environment()["PATH"] = "$termuxBin:$currentPath"
        }

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
            val envTag = if (useLinux) " [linux]" else " [android]"
            if (!finished) {
                proc.destroyForcibly()
                ToolResult(
                    ok = false,
                    output = stripAnsi(out.toString()) + "\n[TIMEOUT after ${timeoutMs}ms — process killed]$envTag",
                    meta = mapOf("command" to command, "exit" to "-1", "env" to if (useLinux) "linux" else "android")
                )
            } else {
                val exit = proc.exitValue()
                ToolResult(
                    ok = exit == 0,
                    output = stripAnsi("$out\n[exit code: $exit]$envTag".trim()),
                    meta = mapOf("command" to command, "exit" to exit.toString(), "env" to if (useLinux) "linux" else "android")
                )
            }
        }.getOrElse { e ->
            ToolResult(false, "Failed to execute: ${e.message}", meta = mapOf("command" to command))
        }
    }

    private fun stripAnsi(s: String): String =
        s.replace(Regex("\u001B\\[[0-9;]*[ -/]*[@-~]"), "")
         .replace("\r", "")
}
