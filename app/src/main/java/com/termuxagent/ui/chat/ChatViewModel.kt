package com.termuxagent.ui.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termuxagent.data.agent.Agent
import com.termuxagent.data.agent.AgentEvent
import com.termuxagent.data.agent.ToolRegistry
import com.termuxagent.data.agent.tools.ToolResult
import com.termuxagent.data.api.ChatMessage
import com.termuxagent.data.api.OpenAIClient
import com.termuxagent.data.chat.AssistantBlock
import com.termuxagent.data.chat.SessionStore
import com.termuxagent.data.chat.StoredSession
import com.termuxagent.data.chat.ToolCallStatus
import com.termuxagent.data.chat.UiMessage
import com.termuxagent.data.chat.deriveTitle
import com.termuxagent.data.chat.toStored
import com.termuxagent.data.chat.toUi
import com.termuxagent.data.chat.toWireFormat
import com.termuxagent.data.settings.AppSettings
import com.termuxagent.data.settings.SettingsStore
import com.termuxagent.data.workspace.WorkspaceManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val isRunning: Boolean = false,
    val error: String? = null,
    val settings: AppSettings = AppSettings(),
    val needsConfig: Boolean = false,
    val currentSessionId: String? = null,
    val sessions: List<SessionStore.SessionMeta> = emptyList(),
    val showSessionList: Boolean = false,
    val linuxReady: Boolean = false,
    val linuxSetupProgress: String? = null
)

class ChatViewModel(private val context: Context) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var runJob: Job? = null
    private var saveJob: Job? = null
    private val sessionStore = SessionStore(context)
    val linuxEnvironment = com.termuxagent.data.linux.LinuxEnvironment(context)

    init {
        // Observe settings continuously.
        viewModelScope.launch {
            SettingsStore.flow(context).collect { s ->
                _state.update { it.copy(settings = s, needsConfig = !s.isConfigured) }
                // If Linux env is enabled, make sure it's set up.
                if (s.useLinuxEnv && linuxEnvironment.isReady) {
                    _state.update { it.copy(linuxReady = true) }
                }
            }
        }
        // Observe Linux env setup state.
        viewModelScope.launch {
            linuxEnvironment.state.collect { st ->
                _state.update {
                    it.copy(
                        linuxReady = st is com.termuxagent.data.linux.LinuxEnvironment.SetupState.Ready,
                        linuxSetupProgress = when (st) {
                            is com.termuxagent.data.linux.LinuxEnvironment.SetupState.Downloading -> st.message
                            is com.termuxagent.data.linux.LinuxEnvironment.SetupState.Extracting -> st.message
                            is com.termuxagent.data.linux.LinuxEnvironment.SetupState.Failed -> "Linux setup failed: ${st.error}"
                            else -> null
                        }
                    )
                }
                // If setup just completed, notify.
                if (st is com.termuxagent.data.linux.LinuxEnvironment.SetupState.Ready) {
                    _state.update { it.copy(linuxReady = true) }
                }
            }
        }
        // Load the most recent session (or start fresh).
        viewModelScope.launch {
            val sessions = sessionStore.listSessions()
            val mostRecent = sessions.firstOrNull()
            if (mostRecent != null) {
                val loaded = sessionStore.loadSession(mostRecent.id)
                if (loaded != null) {
                    _state.update {
                        it.copy(
                            messages = loaded.messages.toUi(),
                            currentSessionId = loaded.id,
                            sessions = sessions
                        )
                    }
                    return@launch
                }
            }
            // No sessions — create a new one.
            val newSession = sessionStore.createSession()
            sessionStore.saveSession(newSession)
            _state.update {
                it.copy(
                    currentSessionId = newSession.id,
                    sessions = sessionStore.listSessions()
                )
            }
        }
    }

    fun reloadSettings() { /* no-op: observed continuously */ }

    fun setupLinuxEnv() {
        if (linuxEnvironment.isReady) {
            _state.update { it.copy(linuxReady = true) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(linuxSetupProgress = "Starting Linux setup…") }
            val ok = linuxEnvironment.setup()
            if (!ok) {
                _state.update { it.copy(linuxSetupProgress = null) }
            }
        }
    }

    fun toggleSessionList() {
        _state.update { it.copy(showSessionList = !it.showSessionList) }
    }

    fun hideSessionList() {
        _state.update { it.copy(showSessionList = false) }
    }

    fun newSession() {
        // Save current first.
        saveCurrentSession()
        val newSession = sessionStore.createSession()
        sessionStore.saveSession(newSession)
        _state.update {
            it.copy(
                messages = emptyList(),
                currentSessionId = newSession.id,
                sessions = sessionStore.listSessions(),
                showSessionList = false,
                error = null
            )
        }
    }

    fun switchToSession(id: String) {
        if (id == _state.value.currentSessionId) {
            _state.update { it.copy(showSessionList = false) }
            return
        }
        saveCurrentSession()
        val loaded = sessionStore.loadSession(id)
        if (loaded != null) {
            _state.update {
                it.copy(
                    messages = loaded.messages.toUi(),
                    currentSessionId = id,
                    showSessionList = false,
                    error = null
                )
            }
        }
    }

    fun deleteSession(id: String) {
        sessionStore.deleteSession(id)
        val remaining = sessionStore.listSessions()
        if (id == _state.value.currentSessionId) {
            // We deleted the current session — switch to the next available or create new
            val next = remaining.firstOrNull()
            if (next != null) {
                val loaded = sessionStore.loadSession(next.id)
                _state.update {
                    it.copy(
                        messages = loaded?.messages?.toUi() ?: emptyList(),
                        currentSessionId = next.id,
                        sessions = remaining
                    )
                }
            } else {
                val newSession = sessionStore.createSession()
                sessionStore.saveSession(newSession)
                _state.update {
                    it.copy(
                        messages = emptyList(),
                        currentSessionId = newSession.id,
                        sessions = listOf(SessionStore.SessionMeta(newSession.id, newSession.title, newSession.updatedAt, 0))
                    )
                }
            }
        } else {
            _state.update { it.copy(sessions = remaining) }
        }
    }

    private fun saveCurrentSession() {
        val sid = _state.value.currentSessionId ?: return
        val messages = _state.value.messages
        if (messages.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = sessionStore.loadSession(sid)
        val session = StoredSession(
            id = sid,
            title = existing?.title?.takeIf { it != "New chat" } ?: deriveTitle(messages),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            messages = messages.toStored()
        )
        sessionStore.saveSession(session)
        // Refresh session list in state.
        _state.update { it.copy(sessions = sessionStore.listSessions()) }
    }

    /** Debounced auto-save: saves 1.5s after the last message change. */
    private fun scheduleAutoSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1500)
            saveCurrentSession()
        }
    }

    fun send(text: String) {
        if (text.isBlank() || _state.value.isRunning) return
        val current = _state.value
        if (!current.settings.isConfigured) {
            _state.update { it.copy(error = "Add your API key, base URL, and model in Settings first.") }
            return
        }
        // If Linux env is enabled but not set up, start setup now.
        if (current.settings.useLinuxEnv && !linuxEnvironment.isReady) {
            setupLinuxEnv()
        }
        // Append the user message immediately.
        val userMsg = UiMessage.User(text = text.trim())
        // Also append a placeholder assistant message we'll mutate as events arrive.
        val assistantId = java.util.UUID.randomUUID().toString()
        val assistantMsg = UiMessage.Assistant(
            id = assistantId,
            blocks = emptyList(),
            isStreaming = true
        )
        _state.update { it.copy(messages = it.messages + userMsg + assistantMsg, isRunning = true, error = null) }
        scheduleAutoSave()

        // Build the agent + run.
        val settings = current.settings
        val client = OpenAIClient(baseUrl = settings.baseUrl, apiKey = settings.apiKey)
        val linuxEnv = if (settings.useLinuxEnv) linuxEnvironment else null
        val registry = ToolRegistry(WorkspaceManager, settings, linuxEnv)
        val agent = Agent(settings = settings, registry = registry, client = client)
        val history = (_state.value.messages.dropLast(2)) // exclude the just-added user+assistant placeholders
            .toWireFormat()

        runJob = viewModelScope.launch {
            try {
                agent.run(history = history, userInput = text.trim()).collect { ev ->
                    handleAgentEvent(assistantId, ev)
                    scheduleAutoSave()
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                finalizeAssistant(assistantId, cancelled = true)
                throw ce
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: "Agent failed", isRunning = false) }
                finalizeAssistant(assistantId, error = t.message)
            } finally {
                _state.update { it.copy(isRunning = false) }
                saveCurrentSession()
            }
        }
    }

    fun stop() {
        runJob?.cancel()
        runJob = null
        _state.update { it.copy(isRunning = false) }
        saveCurrentSession()
    }

    fun clear() {
        if (_state.value.isRunning) stop()
        _state.update { it.copy(messages = emptyList(), error = null) }
        saveCurrentSession()
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun handleAgentEvent(assistantId: String, ev: AgentEvent) {
        when (ev) {
            is AgentEvent.TextDelta -> {
                _state.update { st ->
                    st.copy(messages = st.messages.map { m ->
                        if (m.id != assistantId || m !is UiMessage.Assistant) m
                        else {
                            // Find the last Text block (if streaming) and append, else create one.
                            val blocks = m.blocks.toMutableList()
                            val lastText = blocks.lastOrNull() as? AssistantBlock.Text
                            if (lastText != null && lastText.isStreaming) {
                                blocks[blocks.lastIndex] = lastText.copy(text = lastText.text + ev.text)
                            } else {
                                blocks.add(AssistantBlock.Text(text = ev.text, isStreaming = true))
                            }
                            m.copy(blocks = blocks)
                        }
                    })
                }
            }
            is AgentEvent.ToolCallStart -> {
                _state.update { st ->
                    st.copy(messages = st.messages.map { m ->
                        if (m.id != assistantId || m !is UiMessage.Assistant) m
                        else {
                            // Finalize any streaming Text block.
                            val blocks = m.blocks.map { b ->
                                if (b is AssistantBlock.Text && b.isStreaming) b.copy(isStreaming = false) else b
                            }.toMutableList()
                            blocks.add(
                                AssistantBlock.ToolCall(
                                    toolCallId = ev.toolCallId,
                                    name = ev.name,
                                    argsRaw = "",
                                    status = ToolCallStatus.STREAMING
                                )
                            )
                            m.copy(blocks = blocks)
                        }
                    })
                }
            }
            is AgentEvent.ToolCallArgs -> {
                _state.update { st ->
                    st.copy(messages = st.messages.map { m ->
                        if (m.id != assistantId || m !is UiMessage.Assistant) m
                        else {
                            val blocks = m.blocks.map { b ->
                                if (b is AssistantBlock.ToolCall && b.toolCallId == ev.toolCallId) {
                                    b.copy(argsRaw = ev.argsRaw)
                                } else b
                            }
                            m.copy(blocks = blocks)
                        }
                    })
                }
            }
            is AgentEvent.ToolCallRunning -> {
                _state.update { st ->
                    st.copy(messages = st.messages.map { m ->
                        if (m.id != assistantId || m !is UiMessage.Assistant) m
                        else {
                            val blocks = m.blocks.map { b ->
                                if (b is AssistantBlock.ToolCall && b.toolCallId == ev.toolCallId) {
                                    b.copy(status = ToolCallStatus.RUNNING, argsRaw = ev.argsRaw)
                                } else b
                            }
                            m.copy(blocks = blocks)
                        }
                    })
                }
            }
            is AgentEvent.ToolResultEvent -> {
                _state.update { st ->
                    st.copy(messages = st.messages.map { m ->
                        if (m.id != assistantId || m !is UiMessage.Assistant) m
                        else {
                            val blocks = m.blocks.map { b ->
                                if (b is AssistantBlock.ToolCall && b.toolCallId == ev.toolCallId) {
                                    b.copy(
                                        status = if (ev.ok) ToolCallStatus.DONE else ToolCallStatus.FAILED,
                                        result = ev.output,
                                        ok = ev.ok,
                                        meta = ev.meta
                                    )
                                } else b
                            }
                            m.copy(blocks = blocks)
                        }
                    })
                }
            }
            is AgentEvent.Iteration -> {
                // Could surface "thinking…" but we keep it implicit via tool cards.
            }
            is AgentEvent.Done -> {
                finalizeAssistant(assistantId)
            }
            is AgentEvent.Error -> {
                _state.update { it.copy(error = ev.message) }
                finalizeAssistant(assistantId, error = ev.message)
            }
        }
    }

    /** Mark the streaming assistant message as finalized. */
    private fun finalizeAssistant(assistantId: String, error: String? = null, cancelled: Boolean = false) {
        _state.update { st ->
            st.copy(
                isRunning = false,
                messages = st.messages.map { m ->
                    if (m.id != assistantId || m !is UiMessage.Assistant) m
                    else {
                        val blocks = m.blocks.map { b ->
                            when (b) {
                                is AssistantBlock.Text -> b.copy(isStreaming = false)
                                is AssistantBlock.ToolCall -> when (b.status) {
                                    ToolCallStatus.STREAMING, ToolCallStatus.RUNNING ->
                                        b.copy(status = ToolCallStatus.FAILED, result = b.result ?: "Interrupted")
                                    else -> b
                                }
                            }
                        }
                        // Drop trailing empty Text blocks (e.g. when only tool calls happened).
                        val cleaned = blocks.filterNot { it is AssistantBlock.Text && it.text.isBlank() }
                        m.copy(blocks = cleaned, isStreaming = false, error = error)
                    }
                }
            )
        }
    }
}
