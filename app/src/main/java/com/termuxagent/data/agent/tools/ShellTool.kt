package com.termuxagent.data.agent.tools

import com.termuxagent.data.settings.AppSettings
import com.termuxagent.data.ssh.SshClient
import com.termuxagent.data.workspace.WorkspaceManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Run a shell command. When SSH (Cloud Linux) is configured and enabled,
 * commands run on the REMOTE Linux machine via SSH — giving the agent
 * full Linux with package manager, Python, Node, GCC, etc.
 *
 * When SSH is not configured, falls back to Android's local shell
 * (/system/bin/sh + toybox).
 */
class ShellTool(
    private val ws: WorkspaceManager,
    private val settings: AppSettings
) : AgentTool {
    override val name = "shell"
    override val description = """Run a shell command. ${if (settings.sshEnabled && settings.isSshConfigured) "Cloud Linux is ENABLED — commands run on a remote Linux machine via SSH. You have full Linux: apt/yum/apk, Python, Node, Ruby, GCC, Git, curl, wget, etc." else "Runs locally on Android with toybox (ls, cat, grep, sed, awk, find, tar, bc, tr, cut, tee, xargs, etc.)."}
Returns combined stdout+stderr + exit code. Output truncated to ~20KB."""
    override val parametersSchema = objSchema(
        properties = mapOf(
            "command" to strProp("The shell command to run."),
            "timeout_ms" to intProp("Max runtime in milliseconds.", min = 100, max = 60_000)
        ),
        required = listOf("command")
    )

    override suspend fun invoke(args: JsonObject): ToolResult {
        val command = args["command"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Missing 'command' argument.")
        val timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.content?.toIntOrNull() ?: 30_000)
            .coerceIn(100, 60_000)

        // If SSH is enabled and configured, run on remote machine
        if (settings.sshEnabled && settings.isSshConfigured) {
            return executeViaSsh(command, timeoutMs)
        }

        // Otherwise run locally on Android
        return executeLocally(command, timeoutMs)
    }

    private fun executeViaSsh(command: String, timeoutMs: Int): ToolResult {
        val ssh = SshClient(
            host = settings.sshHost,
            port = settings.sshPort,
            user = settings.sshUser,
            password = settings.sshPassword,
            privateKey = settings.sshPrivateKey,
            workingDir = settings.sshWorkingDir
        )
        if (!ssh.connect()) {
            val err = ssh.getLastError()
            return ToolResult(
                false,
                "SSH connection failed to ${settings.sshUser}@${settings.sshHost}:${settings.sshPort}. Error: $err\n\nTroubleshooting:\n- For GitHub Codespaces: make sure the codespace is running. The SSH key must be added to your GitHub account (token needs admin:public_key scope).\n- For VPS: check host, port, username, password.\n- Go to Settings → Cloud Linux → Test SSH to verify.",
                meta = mapOf("command" to command, "env" to "ssh", "exit" to "-1")
            )
        }
        ssh.use {
            val result = it.execute(command, timeoutMs)
            val combined = buildString {
                if (result.output.isNotBlank()) append(result.output)
                if (result.error.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(result.error)
                }
                append("\n[exit code: ${result.exitCode}] [ssh ${settings.sshHost}]")
            }.trim()
            return ToolResult(
                ok = result.exitCode == 0,
                output = combined,
                meta = mapOf("command" to command, "exit" to result.exitCode.toString(), "env" to "ssh")
            )
        }
    }

    private fun executeLocally(command: String, timeoutMs: Int): ToolResult {
        val cwd = ws.root
        val builder = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(cwd)
            .redirectErrorStream(true)
        builder.environment()["HOME"] = cwd.absolutePath
        builder.environment()["TERM"] = "xterm-256color"
        builder.environment()["LANG"] = "en_US.UTF-8"
        builder.environment()["LC_ALL"] = "en_US.UTF-8"
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
            if (!finished) {
                proc.destroyForcibly()
                ToolResult(
                    ok = false,
                    output = stripAnsi(out.toString()) + "\n[TIMEOUT after ${timeoutMs}ms — process killed] [android]",
                    meta = mapOf("command" to command, "exit" to "-1", "env" to "android")
                )
            } else {
                val exit = proc.exitValue()
                ToolResult(
                    ok = exit == 0,
                    output = stripAnsi("$out\n[exit code: $exit] [android]".trim()),
                    meta = mapOf("command" to command, "exit" to exit.toString(), "env" to "android")
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
