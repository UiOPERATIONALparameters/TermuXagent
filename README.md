# AetherAgent

> Your AI, its own computer, in your pocket.

AetherAgent is a BYOK (Bring Your Own Key) AI agent for Android that gives any
OpenAI-compatible model **its own Linux computer** — a full Alpine Linux
environment via PRoot, no root required. The agent can install packages with
`apk`, run Python/Node/Ruby/GCC, write and execute code, search the web, and
iterate autonomously until your task is done.

On par with z.ai agentic mode — but on your phone, offline-capable, and
totally private (your API key never leaves your device).

Built with Kotlin + Jetpack Compose + Material 3. Pure e-ink monochrome UI.

## Features (v2.0)

### 🐧 Linux Environment (NEW)
- **PRoot + Alpine Linux** — the AI gets a real Linux computer with `apk`
  package manager. It can `apk add python3 nodejs ruby gcc git curl wget make
  cmake` and anything else in Alpine's repos.
- **No root required** — PRoot uses ptrace syscall interception (same approach
  as UserLAnd, Andronix, Anlinux).
- **Shared workspace** — the agent's workspace is bind-mounted at
  `/root/workspace` inside Linux, so files are shared between Android and Linux.
- **Toggle on/off** in Settings.

### 🤖 Agent
- **BYOK, OpenAI-compatible** — works with OpenAI, OpenRouter, Together, Groq,
  Ollama, anything with `/v1/chat/completions`.
- **Auto-fetch models** — type your API key + base URL, pick from the
  auto-fetched model list.
- **Streaming chat** with live tool-call cards.
- **Tool-calling agent loop** — up to 25 iterations (configurable).
- **17+ tools:** `shell` (Linux-powered), `read/write/edit/append_file`,
  `list_dir`, `tree`, `grep`, `mkdir`, `delete`, `file_info`, `http_fetch`,
  `download_url`, `list_interpreters`, `copy_to_clipboard`, `share_file`,
  `open_url`, `web_search`, `web_read`.

### 🔍 Web Search (3 providers)
- **DuckDuckGo** — free, no API key
- **Exa** — API key required, returns content snippets
- **Firecrawl** — API key required
- Toggle on/off in Settings. The AI gets `web_search` + `web_read` tools.

### 💬 Chat Persistence
- Sessions auto-save to JSON files, survive app restart.
- Session list with new/switch/delete.
- Auto-title from first user message.

### 🖥️ Terminal Screen
- Type shell commands directly.
- When Linux env is on, you get a real bash shell with `apk`, `python3`, etc.
- Command history, ANSI support.

### 📁 Workspace
- Persistent file browser with in-app editor.
- Download/share button on every file.
- Files at `Android/data/com.aetheragent/files/workspace`.

### 🎨 E-ink UI
- Pure black/white/gray monochrome.
- Three theme modes: System / Black / White.
- Dynamic color toggle (Android 12+).
- Rounded corners, modern card-based chat.

## Install

Grab the latest APK from the [Releases page](../../releases/latest), allow
"Install unknown apps" for your browser/files app, and tap to install.

## First run

1. Open AetherAgent.
2. Tap ⚙️ → **Settings**.
3. Enter **API Key** + **Base URL** → pick a **Model** from the auto-fetched list.
4. (Optional) Enable **Linux Environment** — requires Termux + `pkg install proot`.
5. (Optional) Enable **Web Search** — pick a provider.
6. Go back to Chat and ask anything.

## For maximum power (Linux env)

1. Install [Termux from F-Droid](https://f-droid.org/packages/com.termux/) (not Play Store).
2. Open Termux, run: `pkg install proot`
3. In AetherAgent → Settings → Linux Environment → toggle ON.
4. The agent can now `apk add python3 nodejs ruby gcc git` and run anything.

## Try these prompts

- *"Install Python and write a script that prints the first 20 Fibonacci numbers, then run it."*
- *"Search the web for the latest Node.js LTS version, then write a Node script that fetches a URL and prints the response."*
- *"Build a C program that computes pi to 100 digits. Install gcc first."*
- *"Create a static website with a countdown timer to 2026-12-31, then share_file index.html."*

## Architecture

```
data/
  api/         OpenAI-compatible client + SSE parser
  agent/       Agent loop + ToolRegistry
  agent/tools/ 17 tools (shell, files, web, android, linux)
  chat/        UI message model + session persistence
  linux/       PRoot + Alpine rootfs manager  ← NEW
  settings/    DataStore-backed settings
  workspace/   Scoped filesystem manager
ui/
  theme/       E-ink palette
  chat/        Chat + sessions + composer + tool cards
  terminal/    Direct shell access
  workspace/   File browser + editor
  settings/    Full settings form
  nav/         Bottom nav (Chat/Terminal/Files/Settings)
```

## Build from source

```bash
git clone https://github.com/UiOPERATIONALparameters/AetherAgent.git
cd AetherAgent
./gradlew assembleRelease
```

Requires JDK 17 and Android SDK 34.

## How PRoot works

PRoot intercepts syscalls via `ptrace(PTRACE_SYSCALL)`. Every file path the
process tries to access is translated to a path inside the "guest" rootfs.
This has ~10-20% overhead but requires no root. The agent's shell runs:

```
proot -r rootfs/ -b /dev -b /proc -b /sys -b workspace:/root/workspace \
  /bin/sh -c "cd /root/workspace && <command>"
```

Inside, the AI gets a real Alpine Linux with `apk` package manager.

## License

MIT. See [LICENSE](LICENSE).
