package com.termuxagent.ui.terminal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termuxagent.data.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class TerminalLine(
    val id: Long,
    val kind: Kind,
    val text: String
) {
    enum class Kind { Input, Output, Error, Info }
}

data class TerminalUiState(
    val lines: List<TerminalLine> = emptyList(),
    val cwd: String = ".",
    val running: Boolean = false,
    val history: List<String> = emptyList(),
    val historyIndex: Int = -1
) {
    val cwdDisplay: String get() = if (cwd == ".") "workspace" else cwd
}

class TerminalViewModel(private val context: Context) : ViewModel() {
    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()
    private var lineId: Long = 0L

    init {
        appendLine(TerminalLine.Kind.Info, "TermuXagent terminal — workspace: ${WorkspaceManager.root.absolutePath}")
        appendLine(TerminalLine.Kind.Info, "Type 'help' for built-in commands. Toybox provides: ls, cat, grep, sed, awk, find, tar, bc, ...")
        appendLine(TerminalLine.Kind.Info, "")
    }

    fun execute(input: String) {
        if (input.isBlank() || _state.value.running) return
        val cmd = input.trim()
        appendLine(TerminalLine.Kind.Input, "$ ${cmd}")
        // Add to history
        _state.update {
            it.copy(history = (it.history + cmd).takeLast(100), historyIndex = -1)
        }

        // Built-in commands
        when {
            cmd == "help" -> {
                appendLine(TerminalLine.Kind.Output, "Built-in: cd <dir>, pwd, clear, help, env")
                appendLine(TerminalLine.Kind.Output, "Shell: anything else runs via /system/bin/sh -c '<cmd>' in the workspace.")
                appendLine(TerminalLine.Kind.Output, "")
                return
            }
            cmd == "clear" -> {
                _state.update { it.copy(lines = emptyList()) }
                return
            }
            cmd == "pwd" -> {
                appendLine(TerminalLine.Kind.Output, _state.value.cwd)
                appendLine(TerminalLine.Kind.Output, "")
                return
            }
            cmd == "env" -> {
                appendLine(TerminalLine.Kind.Output, "PATH=${System.getenv("PATH")}")
                appendLine(TerminalLine.Kind.Output, "HOME=${WorkspaceManager.root.absolutePath}")
                appendLine(TerminalLine.Kind.Output, "ANDROID_HOME=${System.getenv("ANDROID_HOME") ?: "(unset)"}")
                appendLine(TerminalLine.Kind.Output, "")
                return
            }
            cmd.startsWith("cd ") -> {
                val arg = cmd.removePrefix("cd ").trim()
                val target = if (arg.startsWith("/")) arg else "${_state.value.cwd}/$arg"
                val resolved = WorkspaceManager.resolve(target)
                if (resolved.exists() && resolved.isDirectory) {
                    val rel = resolved.toRelativeString(WorkspaceManager.root)
                    _state.update { it.copy(cwd = rel) }
                    appendLine(TerminalLine.Kind.Output, "")
                } else {
                    appendLine(TerminalLine.Kind.Error, "cd: no such directory: $arg")
                    appendLine(TerminalLine.Kind.Output, "")
                }
                return
            }
        }

        // Real shell
        _state.update { it.copy(running = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val cwdPath = WorkspaceManager.resolve(_state.value.cwd).absolutePath
            val builder = ProcessBuilder("/system/bin/sh", "-c", cmd)
                .directory(java.io.File(cwdPath))
                .redirectErrorStream(true)
            builder.environment()["HOME"] = WorkspaceManager.root.absolutePath
            builder.environment()["TERM"] = "xterm-256color"
            builder.environment()["LANG"] = "en_US.UTF-8"
            builder.environment()["LC_ALL"] = "en_US.UTF-8"
            // Extend PATH with Termux bin if available (so python/node/etc. work)
            val termuxBin = "/data/data/com.termux/files/usr/bin"
            if (java.io.File(termuxBin).exists()) {
                val currentPath = builder.environment()["PATH"] ?: ""
                builder.environment()["PATH"] = "$termuxBin:$currentPath"
            }

            try {
                val proc = builder.start()
                val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
                val buf = CharArray(4096)
                var read: Int
                val sb = StringBuilder()
                var total = 0
                val maxBytes = 50_000
                while (reader.read(buf).also { read = it } != -1) {
                    if (total + read > maxBytes) {
                        sb.append(buf, 0, (maxBytes - total).coerceAtLeast(0))
                        sb.append("\n…[truncated at $maxBytes chars]")
                        break
                    }
                    sb.append(buf, 0, read)
                    total += read
                }
                val finished = proc.waitFor(30, TimeUnit.SECONDS)
                val exit = if (finished) proc.exitValue() else -1
                // Strip ANSI
                val clean = sb.toString()
                    .replace(Regex("\u001B\\[[0-9;]*[ -/]*[@-~]"), "")
                    .replace("\r", "")
                if (clean.isNotBlank()) {
                    appendLine(TerminalLine.Kind.Output, clean.trimEnd())
                }
                if (!finished) {
                    appendLine(TerminalLine.Kind.Error, "[TIMEOUT after 30s — killed]")
                    proc.destroyForcibly()
                } else if (exit != 0) {
                    appendLine(TerminalLine.Kind.Error, "[exit code: $exit]")
                }
                appendLine(TerminalLine.Kind.Output, "")
            } catch (e: Exception) {
                appendLine(TerminalLine.Kind.Error, "Error: ${e.message}")
                appendLine(TerminalLine.Kind.Output, "")
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun navigateHistory(forward: Boolean): String? {
        val s = _state.value
        if (s.history.isEmpty()) return null
        val newIndex = if (s.historyIndex == -1) {
            if (forward) return null else s.history.lastIndex
        } else {
            val next = if (forward) s.historyIndex + 1 else s.historyIndex - 1
            next.coerceIn(-1, s.history.lastIndex)
        }
        _state.update { it.copy(historyIndex = newIndex) }
        return if (newIndex == -1) "" else s.history[newIndex]
    }

    private fun appendLine(kind: TerminalLine.Kind, text: String) {
        _state.update {
            it.copy(lines = it.lines + TerminalLine(lineId++, kind, text))
        }
    }
}
