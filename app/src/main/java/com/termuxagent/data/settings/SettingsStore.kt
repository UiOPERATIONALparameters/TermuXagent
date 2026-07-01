package com.termuxagent.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.termuxagent.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "termuxagent_settings")

data class AppSettings(
    val apiKey: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val maxIterations: Int = 25,
    val temperature: Float = 0.3f,
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
}

const val DEFAULT_SYSTEM_PROMPT = """You are TermuXagent, an autonomous AI agent running on the user's Android phone. You have a real, persistent workspace — a folder you fully control. You can read and write files, run shell commands, search the workspace, fetch URLs, copy to clipboard, share files, and open URLs in the browser.

Behave like a senior engineer with full agency:
- Plan briefly before acting (1-3 short sentences), then call tools.
- Prefer the smallest tool call that makes progress. Don't try to do everything in one shot.
- After each tool result, decide: continue, verify, or summarize.
- When writing code, create the file with write_file, then run it with shell to verify it works. If it errors, read the error, fix, and retry — don't ask the user to do it.
- Keep file paths workspace-relative (e.g. "src/main.py"). The workspace root is the cwd for shell commands.
- Use list_interpreters to discover which runtimes (python3, node, ruby, etc.) are available before assuming one. If a language isn't installed, tell the user how to add it (e.g. via Termux) and proceed with a workaround (shell + toybox, or write code in a language that IS available).
- Be honest about limits. If something is impossible on a non-rooted Android device, say so and propose the closest alternative.
- When you're done, give the user a short summary of what you built, where the files are, and how to use them. Keep it tight.

Tone: direct, technical, friendly. No filler. Markdown is fine for the final summary."""

object SettingsStore {
    private val KEY_API_KEY = stringPreferencesKey("api_key")
    private val KEY_BASE_URL = stringPreferencesKey("base_url")
    private val KEY_MODEL = stringPreferencesKey("model")
    private val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
    private val KEY_MAX_ITER = intPreferencesKey("max_iterations")
    private val KEY_TEMP = stringPreferencesKey("temperature") // stored as string for fractional precision
    private val KEY_THEME = stringPreferencesKey("theme_mode")
    private val KEY_DYNAMIC = booleanPreferencesKey("dynamic_color")

    fun flow(context: Context): Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            apiKey = p[KEY_API_KEY] ?: "",
            baseUrl = p[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: "https://api.openai.com/v1",
            model = p[KEY_MODEL]?.takeIf { it.isNotBlank() } ?: "gpt-4o-mini",
            systemPrompt = p[KEY_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT,
            maxIterations = p[KEY_MAX_ITER]?.takeIf { it in 1..100 } ?: 25,
            temperature = p[KEY_TEMP]?.toFloatOrNull()?.takeIf { it in 0f..2f } ?: 0.3f,
            themeMode = runCatching { ThemeMode.valueOf(p[KEY_THEME] ?: "System") }.getOrDefault(ThemeMode.System),
            dynamicColor = p[KEY_DYNAMIC] ?: false
        )
    }

    suspend fun update(context: Context, transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { p ->
            val current = AppSettings(
                apiKey = p[KEY_API_KEY] ?: "",
                baseUrl = p[KEY_BASE_URL] ?: "https://api.openai.com/v1",
                model = p[KEY_MODEL] ?: "gpt-4o-mini",
                systemPrompt = p[KEY_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT,
                maxIterations = p[KEY_MAX_ITER] ?: 25,
                temperature = p[KEY_TEMP]?.toFloatOrNull() ?: 0.3f,
                themeMode = runCatching { ThemeMode.valueOf(p[KEY_THEME] ?: "System") }.getOrDefault(ThemeMode.System),
                dynamicColor = p[KEY_DYNAMIC] ?: false
            )
            val next = transform(current)
            p[KEY_API_KEY] = next.apiKey.trim()
            p[KEY_BASE_URL] = next.baseUrl.trim().trimEnd('/')
            p[KEY_MODEL] = next.model.trim()
            p[KEY_SYSTEM_PROMPT] = next.systemPrompt
            p[KEY_MAX_ITER] = next.maxIterations.coerceIn(1, 100)
            p[KEY_TEMP] = next.temperature.coerceIn(0f, 2f).toString()
            p[KEY_THEME] = next.themeMode.name
            p[KEY_DYNAMIC] = next.dynamicColor
        }
    }
}
