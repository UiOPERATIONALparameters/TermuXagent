package com.termuxagent.ui.workspace

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

data class WorkspaceUiState(
    val currentPath: String = ".",
    val entries: List<WorkspaceManager.WorkspaceEntry> = emptyList(),
    val editing: EditTarget? = null,
    val error: String? = null,
    val saved: Boolean = false
)

sealed class EditTarget {
    abstract val path: String
    abstract val initialContent: String
    data class TextFile(
        override val path: String,
        override val initialContent: String,
        val totalBytes: Long
    ) : EditTarget()
    data class Directory(
        override val path: String,
        override val initialContent: String // tree text
    ) : EditTarget()
}

class WorkspaceViewModel(private val context: Context) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val path = _state.value.currentPath
            val entries = runCatching { WorkspaceManager.listDir(path) }.getOrElse { e ->
                _state.update { it.copy(error = e.message ?: "list failed") }
                emptyList()
            }
            _state.update { it.copy(entries = entries, error = null) }
        }
    }

    fun navigate(path: String) {
        _state.update { it.copy(currentPath = path) }
        refresh()
    }

    fun navigateUp(): Boolean {
        val cur = _state.value.currentPath
        if (cur.isBlank() || cur == ".") return false
        val parent = cur.substringBeforeLast('/', "").ifBlank { "." }
        navigate(parent)
        return true
    }

    fun open(entry: WorkspaceManager.WorkspaceEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            val target = runCatching {
                if (entry.isDirectory) {
                    EditTarget.Directory(entry.path, WorkspaceManager.tree(entry.path, maxDepth = 3))
                } else {
                    val text = WorkspaceManager.readFile(entry.path, maxBytes = 1_000_000L)
                    val size = WorkspaceManager.size(entry.path)
                    EditTarget.TextFile(entry.path, text, size)
                }
            }.getOrElse { e ->
                _state.update { it.copy(error = e.message ?: "open failed") }
                return@launch
            }
            _state.update { it.copy(editing = target, saved = false) }
        }
    }

    fun closeEditor() {
        _state.update { it.copy(editing = null, saved = false) }
    }

    fun saveContent(path: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { WorkspaceManager.writeFile(path, content) }
            _state.update { it.copy(saved = true) }
        }
    }

    fun delete(entry: WorkspaceManager.WorkspaceEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { WorkspaceManager.delete(entry.path) }
            refresh()
        }
    }

    fun newFile(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val rel = if (_state.value.currentPath == ".") name else "${_state.value.currentPath}/$name"
            runCatching { WorkspaceManager.writeFile(rel, "") }
            refresh()
        }
    }

    fun newFolder(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val rel = if (_state.value.currentPath == ".") name else "${_state.value.currentPath}/$name"
            runCatching { WorkspaceManager.mkdir(rel) }
            refresh()
        }
    }

    fun shareFile(entry: WorkspaceManager.WorkspaceEntry) {
        // Triggered from UI via AndroidTools.ShareFileTool-like flow; we just open share intent here.
        viewModelScope.launch(Dispatchers.IO) {
            // Not implemented inline; user can ask agent to share. Or expose via UI later.
        }
    }
}
