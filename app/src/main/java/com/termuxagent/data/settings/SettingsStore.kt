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
    val dynamicColor: Boolean = false,
    val webSearchEnabled: Boolean = true,
    val webSearchProvider: String = "duckduckgo",
    val exaApiKey: String = "",
    val firecrawlApiKey: String = "",
    val tavilyApiKey: String = "",
    val sshEnabled: Boolean = false,
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "",
    val sshPassword: String = "",
    val sshPrivateKey: String = "",
    val sshWorkingDir: String = "",
    val githubToken: String = "",
    // Tool toggles — user can enable/disable individual tools
    val toolShell: Boolean = true,
    val toolFiles: Boolean = true,
    val toolWeb: Boolean = true,
    val toolAndroid: Boolean = true,
    val toolSsh: Boolean = true,
    val casualMode: Boolean = false
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
    val isSshConfigured: Boolean get() = sshHost.isNotBlank() && sshUser.isNotBlank() && (sshPassword.isNotBlank() || sshPrivateKey.isNotBlank())
}

const val DEFAULT_SYSTEM_PROMPT = """You are TermuXagent, an autonomous AI agent running on the user's Android phone. You have a real, persistent workspace and — when Cloud Linux is configured — a full remote Linux computer accessible via SSH.

You are designed to be fully autonomous. Like a senior engineer with root access to their own machine, you should:
1. PLAN: Before acting, briefly state your plan (1-3 sentences). What's the goal? What steps will you take? What tools will you use?
2. EXECUTE: Call tools to make progress. One tool per turn unless chaining is natural.
3. VERIFY: After each tool result, check if it worked. If it failed, read the error, fix it, and retry. Don't ask the user to fix things — that's YOUR job.
4. ITERATE: Continue until the task is done or you hit a real blocker.
5. SUMMARIZE: When done, give a tight summary: what you built, where the files are, how to use them.

When Cloud Linux (SSH) is enabled:
- Your shell commands run on a REMOTE Linux machine via SSH. You have full Linux: apt/yum/apk package manager, Python, Node, Ruby, GCC, Git, curl, wget, make, cmake — everything.
- Use 'which python3' or 'which node' to check what's installed. Install missing tools with the system package manager.
- Files you create with write_file are LOCAL (on the phone). To get them onto the remote machine, use shell with 'cat > file << EOF' or similar. To get files FROM the remote machine, use shell with 'cat file'.
- Treat the remote machine as your own computer. You can install packages, write code, run servers, compile — anything.

When Cloud Linux is NOT enabled:
- You have Android's toybox: ls, cat, grep, sed, awk, find, tar, bc, tr, cut, tee, xargs, etc.
- Use list_interpreters to check what's available. If Python/Node aren't there, write shell scripts instead, or tell the user to enable Cloud Linux in Settings.

You have web_search and web_read tools. ALWAYS search the web before answering questions about:
- Recent events, news, current API versions
- Documentation you're unsure about
- Error messages you don't recognize
After searching, use web_read to fetch and understand specific pages.

CRITICAL RULES:
- Never ask the user to do something you can do yourself. If a file needs creating, create it. If a package needs installing, install it. If code needs running, run it.
- If something fails, debug it. Read the error. Try a different approach. Don't give up after one try.
- Be honest about hard limits. But try everything else first.
- When you're done, give the user a short summary. Markdown is fine.

Tone: direct, technical, no filler. You're an engineer, not an assistant."""

object SettingsStore {
    private val KEY_API_KEY = stringPreferencesKey("api_key")
    private val KEY_BASE_URL = stringPreferencesKey("base_url")
    private val KEY_MODEL = stringPreferencesKey("model")
    private val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
    private val KEY_MAX_ITER = intPreferencesKey("max_iterations")
    private val KEY_TEMP = stringPreferencesKey("temperature") // stored as string for fractional precision
    private val KEY_THEME = stringPreferencesKey("theme_mode")
    private val KEY_DYNAMIC = booleanPreferencesKey("dynamic_color")
    private val KEY_WEB_SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")
    private val KEY_WEB_SEARCH_PROVIDER = stringPreferencesKey("web_search_provider")
    private val KEY_EXA_API_KEY = stringPreferencesKey("exa_api_key")
    private val KEY_FIRECRAWL_API_KEY = stringPreferencesKey("firecrawl_api_key")
    private val KEY_USE_LINUX_ENV = booleanPreferencesKey("use_linux_env")
    private val KEY_SSH_ENABLED = booleanPreferencesKey("ssh_enabled")
    private val KEY_SSH_HOST = stringPreferencesKey("ssh_host")
    private val KEY_SSH_PORT = intPreferencesKey("ssh_port")
    private val KEY_SSH_USER = stringPreferencesKey("ssh_user")
    private val KEY_SSH_PASSWORD = stringPreferencesKey("ssh_password")
    private val KEY_SSH_PRIVATE_KEY = stringPreferencesKey("ssh_private_key")
    private val KEY_SSH_WORKING_DIR = stringPreferencesKey("ssh_working_dir")
    private val KEY_GITHUB_TOKEN = stringPreferencesKey("github_token")
    private val KEY_TAVILY_API_KEY = stringPreferencesKey("tavily_api_key")
    private val KEY_TOOL_SHELL = booleanPreferencesKey("tool_shell")
    private val KEY_TOOL_FILES = booleanPreferencesKey("tool_files")
    private val KEY_TOOL_WEB = booleanPreferencesKey("tool_web")
    private val KEY_TOOL_ANDROID = booleanPreferencesKey("tool_android")
    private val KEY_TOOL_SSH = booleanPreferencesKey("tool_ssh")
    private val KEY_CASUAL_MODE = booleanPreferencesKey("casual_mode")

    fun flow(context: Context): Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            apiKey = p[KEY_API_KEY] ?: "",
            baseUrl = p[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: "https://api.openai.com/v1",
            model = p[KEY_MODEL]?.takeIf { it.isNotBlank() } ?: "gpt-4o-mini",
            systemPrompt = p[KEY_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT,
            maxIterations = p[KEY_MAX_ITER]?.takeIf { it in 1..100 } ?: 25,
            temperature = p[KEY_TEMP]?.toFloatOrNull()?.takeIf { it in 0f..2f } ?: 0.3f,
            themeMode = runCatching { ThemeMode.valueOf(p[KEY_THEME] ?: "System") }.getOrDefault(ThemeMode.System),
            dynamicColor = p[KEY_DYNAMIC] ?: false,
            webSearchEnabled = p[KEY_WEB_SEARCH_ENABLED] ?: true,
            webSearchProvider = p[KEY_WEB_SEARCH_PROVIDER] ?: "duckduckgo",
            exaApiKey = p[KEY_EXA_API_KEY] ?: "",
            firecrawlApiKey = p[KEY_FIRECRAWL_API_KEY] ?: "",
            sshEnabled = p[KEY_SSH_ENABLED] ?: false,
            sshHost = p[KEY_SSH_HOST] ?: "",
            sshPort = p[KEY_SSH_PORT] ?: 22,
            sshUser = p[KEY_SSH_USER] ?: "",
            sshPassword = p[KEY_SSH_PASSWORD] ?: "",
            sshPrivateKey = p[KEY_SSH_PRIVATE_KEY] ?: "",
            sshWorkingDir = p[KEY_SSH_WORKING_DIR] ?: "",
            githubToken = p[KEY_GITHUB_TOKEN] ?: "",
            tavilyApiKey = p[KEY_TAVILY_API_KEY] ?: "",
            toolShell = p[KEY_TOOL_SHELL] ?: true,
            toolFiles = p[KEY_TOOL_FILES] ?: true,
            toolWeb = p[KEY_TOOL_WEB] ?: true,
            toolAndroid = p[KEY_TOOL_ANDROID] ?: true,
            toolSsh = p[KEY_TOOL_SSH] ?: true,
            casualMode = p[KEY_CASUAL_MODE] ?: false
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
                dynamicColor = p[KEY_DYNAMIC] ?: false,
                webSearchEnabled = p[KEY_WEB_SEARCH_ENABLED] ?: true,
                webSearchProvider = p[KEY_WEB_SEARCH_PROVIDER] ?: "duckduckgo",
                exaApiKey = p[KEY_EXA_API_KEY] ?: "",
                firecrawlApiKey = p[KEY_FIRECRAWL_API_KEY] ?: "",
                sshEnabled = p[KEY_SSH_ENABLED] ?: false,
                sshHost = p[KEY_SSH_HOST] ?: "",
                sshPort = p[KEY_SSH_PORT] ?: 22,
                sshUser = p[KEY_SSH_USER] ?: "",
                sshPassword = p[KEY_SSH_PASSWORD] ?: "",
                sshPrivateKey = p[KEY_SSH_PRIVATE_KEY] ?: "",
                sshWorkingDir = p[KEY_SSH_WORKING_DIR] ?: "",
                githubToken = p[KEY_GITHUB_TOKEN] ?: "",
                tavilyApiKey = p[KEY_TAVILY_API_KEY] ?: "",
                toolShell = p[KEY_TOOL_SHELL] ?: true,
                toolFiles = p[KEY_TOOL_FILES] ?: true,
                toolWeb = p[KEY_TOOL_WEB] ?: true,
                toolAndroid = p[KEY_TOOL_ANDROID] ?: true,
                toolSsh = p[KEY_TOOL_SSH] ?: true,
                casualMode = p[KEY_CASUAL_MODE] ?: false
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
            p[KEY_WEB_SEARCH_ENABLED] = next.webSearchEnabled
            p[KEY_WEB_SEARCH_PROVIDER] = next.webSearchProvider
            p[KEY_EXA_API_KEY] = next.exaApiKey.trim()
            p[KEY_FIRECRAWL_API_KEY] = next.firecrawlApiKey.trim()
            p[KEY_SSH_ENABLED] = next.sshEnabled
            p[KEY_SSH_HOST] = next.sshHost.trim()
            p[KEY_SSH_PORT] = next.sshPort
            p[KEY_SSH_USER] = next.sshUser.trim()
            p[KEY_SSH_PASSWORD] = next.sshPassword
            p[KEY_SSH_PRIVATE_KEY] = next.sshPrivateKey
            p[KEY_SSH_WORKING_DIR] = next.sshWorkingDir.trim()
            p[KEY_GITHUB_TOKEN] = next.githubToken.trim()
            p[KEY_TAVILY_API_KEY] = next.tavilyApiKey.trim()
            p[KEY_TOOL_SHELL] = next.toolShell
            p[KEY_TOOL_FILES] = next.toolFiles
            p[KEY_TOOL_WEB] = next.toolWeb
            p[KEY_TOOL_ANDROID] = next.toolAndroid
            p[KEY_TOOL_SSH] = next.toolSsh
            p[KEY_CASUAL_MODE] = next.casualMode
        }
    }
}
