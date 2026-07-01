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

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val testing: Boolean = false,
    val testResult: String? = null,
    val fetchingModels: Boolean = false,
    val availableModels: List<String> = emptyList(),
    val modelsError: String? = null
)

class SettingsViewModel(private val context: Context) : ViewModel() {
    private val _mutable = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _mutable.asStateFlow()

    private var lastFetchedKey: String = ""
    private var lastFetchedUrl: String = ""
    private var fetchJob: Job? = null

    init {
        viewModelScope.launch {
            SettingsStore.flow(context).collect { s ->
                _mutable.update { it.copy(settings = s) }
                // Auto-fetch models when both key + URL are set and have changed.
                maybeFetchModels(s.apiKey, s.baseUrl)
            }
        }
    }

    private fun maybeFetchModels(apiKey: String, baseUrl: String) {
        if (apiKey.isBlank() || baseUrl.isBlank()) return
        if (apiKey == lastFetchedKey && baseUrl == lastFetchedUrl) return
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            delay(700) // debounce
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

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            SettingsStore.update(context, transform)
        }
    }

    fun setApiKey(v: String) = update { it.copy(apiKey = v) }
    fun setBaseUrl(v: String) = update { it.copy(baseUrl = v) }
    fun setModel(v: String) = update { it.copy(model = v) }
    fun setSystemPrompt(v: String) = update { it.copy(systemPrompt = v) }
    fun setMaxIterations(v: Int) = update { it.copy(maxIterations = v) }
    fun setTemperature(v: Float) = update { it.copy(temperature = v) }
    fun setThemeMode(v: ThemeMode) = update { it.copy(themeMode = v) }
    fun setDynamicColor(v: Boolean) = update { it.copy(dynamicColor = v) }

    fun testConnection() {
        val s = _mutable.value.settings
        if (!s.isConfigured) {
            _mutable.update { it.copy(testResult = "Set API key, base URL, and model first.") }
            return
        }
        _mutable.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val client = OpenAIClient(baseUrl = s.baseUrl, apiKey = s.apiKey)
            // MUST run on IO — OkHttp perform() does real network I/O.
            val result = withContext(Dispatchers.IO) { client.listModels() }
            _mutable.update {
                it.copy(
                    testing = false,
                    testResult = result.fold(
                        onSuccess = { r -> "OK — ${r.data.size} models visible. Using: ${s.model}" },
                        onFailure = { e -> "Failed: ${e.message ?: e::class.simpleName}" }
                    )
                )
            }
        }
    }

    fun clearTestResult() {
        _mutable.update { it.copy(testResult = null) }
    }
}
