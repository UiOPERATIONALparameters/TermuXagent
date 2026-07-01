package com.termuxagent.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termuxagent.data.api.OpenAIClient
import com.termuxagent.data.settings.AppSettings
import com.termuxagent.data.settings.SettingsStore
import com.termuxagent.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editable form fields. These are the *local* source of truth for the text
 * fields — they update synchronously on every keystroke (no DataStore
 * round-trip), so typing never glitches. A debounced coroutine mirrors them
 * to DataStore ~700ms after the user stops typing.
 */
data class EditableFields(
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val systemPrompt: String = "",
    val exaApiKey: String = "",
    val firecrawlApiKey: String = ""
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val editing: EditableFields = EditableFields(),
    val testing: Boolean = false,
    val testResult: String? = null,
    val fetchingModels: Boolean = false,
    val availableModels: List<String> = emptyList(),
    val modelsError: String? = null,
    val termuxInstalled: Boolean = false,
    val termuxPath: String? = null
)

class SettingsViewModel(private val context: Context) : ViewModel() {
    private val _mutable = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _mutable.asStateFlow()

    private var lastFetchedKey: String = ""
    private var lastFetchedUrl: String = ""
    private var fetchJob: Job? = null
    private var persistJob: Job? = null
    private var fieldsInitialised = false

    init {
        viewModelScope.launch {
            SettingsStore.flow(context).collect { s ->
                // Only seed the editable fields once (from persisted state).
                // After that, the editable fields are the source of truth and
                // we don't overwrite them — that would clobber typing.
                if (!fieldsInitialised) {
                    _mutable.update {
                        it.copy(
                            settings = s,
                            editing = EditableFields(
                                apiKey = s.apiKey,
                                baseUrl = s.baseUrl,
                                model = s.model,
                                systemPrompt = s.systemPrompt,
                                exaApiKey = s.exaApiKey,
                                firecrawlApiKey = s.firecrawlApiKey
                            )
                        )
                    }
                    fieldsInitialised = true
                    // Initial model fetch if we have credentials.
                    maybeFetchModels(s.apiKey, s.baseUrl)
                } else {
                    // Keep settings (for theme/dynamic/etc.) in sync, but
                    // leave editing fields alone.
                    _mutable.update { it.copy(settings = s) }
                }
            }
        }
        // Detect Termux once.
        viewModelScope.launch {
            val installed = isTermuxInstalled()
            val path = termuxBinPath()
            _mutable.update { it.copy(termuxInstalled = installed, termuxPath = path) }
        }
    }

    // ── Editable field setters (synchronous, no DataStore write) ──────────────

    fun setApiKey(v: String) {
        _mutable.update { it.copy(editing = it.editing.copy(apiKey = v)) }
        schedulePersist { s, e -> s.copy(apiKey = e.apiKey) }
        maybeFetchModels(v, _mutable.value.editing.baseUrl)
    }

    fun setBaseUrl(v: String) {
        _mutable.update { it.copy(editing = it.editing.copy(baseUrl = v)) }
        schedulePersist { s, e -> s.copy(baseUrl = e.baseUrl) }
        maybeFetchModels(_mutable.value.editing.apiKey, v)
    }

    fun setModel(v: String) {
        _mutable.update { it.copy(editing = it.editing.copy(model = v)) }
        schedulePersist { s, e -> s.copy(model = e.model) }
    }

    fun setSystemPrompt(v: String) {
        _mutable.update { it.copy(editing = it.editing.copy(systemPrompt = v)) }
        schedulePersist { s, e -> s.copy(systemPrompt = e.systemPrompt) }
    }

    fun setExaApiKey(v: String) {
        _mutable.update { it.copy(editing = it.editing.copy(exaApiKey = v)) }
        schedulePersist { s, e -> s.copy(exaApiKey = e.exaApiKey) }
    }

    fun setFirecrawlApiKey(v: String) {
        _mutable.update { it.copy(editing = it.editing.copy(firecrawlApiKey = v)) }
        schedulePersist { s, e -> s.copy(firecrawlApiKey = e.firecrawlApiKey) }
    }

    /** Debounced write-through to DataStore. */
    private fun schedulePersist(apply: (AppSettings, EditableFields) -> AppSettings) {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(500)
            val editing = _mutable.value.editing
            SettingsStore.update(context) { s -> apply(s, editing) }
        }
    }

    // ── Non-text settings (these never had the glitch — they're discrete actions) ──

    fun setMaxIterations(v: Int) = updateImmediate { it.copy(maxIterations = v) }
    fun setTemperature(v: Float) = updateImmediate { it.copy(temperature = v) }
    fun setThemeMode(v: ThemeMode) = updateImmediate { it.copy(themeMode = v) }
    fun setDynamicColor(v: Boolean) = updateImmediate { it.copy(dynamicColor = v) }
    fun setWebSearchEnabled(v: Boolean) = updateImmediate { it.copy(webSearchEnabled = v) }
    fun setWebSearchProvider(v: String) = updateImmediate { it.copy(webSearchProvider = v) }
    fun setUseLinuxEnv(v: Boolean) = updateImmediate { it.copy(useLinuxEnv = v) }

    private fun updateImmediate(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { SettingsStore.update(context, transform) }
    }

    // ── Model fetching ─────────────────────────────────────────────────────────

    private fun maybeFetchModels(apiKey: String, baseUrl: String) {
        if (apiKey.isBlank() || baseUrl.isBlank()) return
        if (apiKey == lastFetchedKey && baseUrl == lastFetchedUrl) return
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            delay(800) // debounce
            lastFetchedKey = apiKey
            lastFetchedUrl = baseUrl
            _mutable.update { it.copy(fetchingModels = true, modelsError = null) }
            val client = OpenAIClient(baseUrl = baseUrl, apiKey = apiKey)
            val result = withContext(Dispatchers.IO) { client.listModels() }
            result.fold(
                onSuccess = { r ->
                    val ids = r.data.map { it.id }.sorted()
                    _mutable.update {
                        it.copy(fetchingModels = false, availableModels = ids, modelsError = null)
                    }
                },
                onFailure = { e ->
                    _mutable.update {
                        it.copy(
                            fetchingModels = false,
                            modelsError = e.message ?: e::class.simpleName
                        )
                    }
                }
            )
        }
    }

    fun retryFetchModels() {
        val e = _mutable.value.editing
        lastFetchedKey = "" // force re-fetch
        maybeFetchModels(e.apiKey, e.baseUrl)
    }

    // ── Test connection ────────────────────────────────────────────────────────

    fun testConnection() {
        val e = _mutable.value.editing
        if (e.apiKey.isBlank() || e.baseUrl.isBlank() || e.model.isBlank()) {
            _mutable.update { it.copy(testResult = "Set API key, base URL, and model first.") }
            return
        }
        _mutable.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val client = OpenAIClient(baseUrl = e.baseUrl, apiKey = e.apiKey)
            val result = withContext(Dispatchers.IO) { client.listModels() }
            _mutable.update {
                it.copy(
                    testing = false,
                    testResult = result.fold(
                        onSuccess = { r -> "OK — ${r.data.size} models visible. Using: ${e.model}" },
                        onFailure = { err -> "Failed: ${err.message ?: err::class.simpleName}" }
                    )
                )
            }
        }
    }

    fun clearTestResult() {
        _mutable.update { it.copy(testResult = null) }
    }

    // ── Termux detection ───────────────────────────────────────────────────────

    private fun isTermuxInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.termux", 0)
        true
    }.getOrDefault(false)

    private fun termuxBinPath(): String? = if (isTermuxInstalled()) {
        "/data/data/com.termux/files/usr/bin"
    } else null
}
