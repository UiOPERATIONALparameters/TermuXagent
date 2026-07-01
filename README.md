# TermuXagent

> Your AI, your computer, in your pocket.

TermuXagent is a BYOK (Bring Your Own Key) AI agent for Android that gives any
OpenAI-compatible model a real, persistent on-device workspace. The agent can
read & write files, run shell commands, search the workspace, fetch URLs, copy
to clipboard, share files, open URLs in the browser, and iterate autonomously
until your task is done.

Built with Kotlin + Jetpack Compose + Material 3. Pure e-ink monochrome UI.

## Features (v1.1)

- **BYOK, OpenAI-compatible.** Point at any provider (OpenAI, OpenRouter,
  Together, Groq, local Llamafile, Ollama with the OpenAI shim, …) by setting
  Base URL + API key + model.
- **Auto-fetch models.** When you enter your API key + Base URL, the app
  fetches the list of available models from `/v1/models` and shows them in a
  picker. Tap to choose, or type a custom name.
- **Streaming chat.** Server-sent events; the assistant's text and tool calls
  render live as they arrive. Typing caret + pulsing "thinking" dots.
- **Tool-calling agent loop.** The agent calls tools, sees results, and
  continues until it's done — up to a configurable max-iterations cap.
- **Real workspace.** A persistent folder the agent fully controls. Files
  survive app restarts. Browse + edit files in-app.
- **15 tools:** `shell`, `read_file`, `write_file`, `edit_file`, `append_file`,
  `list_dir`, `tree`, `grep`, `mkdir`, `delete`, `http_fetch`,
  `list_interpreters` (detects python3/node/ruby/etc.), `copy_to_clipboard`,
  `share_file` (Android share sheet), `open_url` (browser).
- **Clean e-ink UI.** Pure black + pure white + grays only. Color reserved for
  status icons. Rounded corners (16-24dp). Three theme modes: System / Black /
  White. Dynamic color toggle.
- **Quick prompts.** Empty chat shows one-tap templates (Hello world, List
  interpreters, Workspace tour, Build a static site).

## Install

Grab the latest APK from the [Releases page](../../releases/latest), allow
"Install unknown apps" for your browser/files app on your phone, and tap to
install.

## First run

1. Open TermuXagent.
2. Tap ⚙️ → **Settings**.
3. Enter your **API Key** and **Base URL** (e.g. `https://api.openai.com/v1`).
4. Tap the **Model** row — pick from the auto-fetched list (or type a custom
   name).
5. Tap **Test connection** — you should see "OK — N models visible".
6. Go back to Chat and ask anything. Try a quick prompt.

## Try these prompts

- *"What runtimes are available on this device? Use list_interpreters and tell
  me what I can run."*
- *"Create a file called hello.sh that prints 'Hello from TermuXagent!' and
  run it with shell."*
- *"Build a minimal static site: index.html, style.css, app.js. Make it a
  countdown timer to 2026-12-31. Save to workspace, then share_file index.html
  so I can preview."*
- *"Fetch https://api.github.com/repos/JetBrains/kotlin and write a 3-bullet
  summary of the repo to summary.md. Then copy_to_clipboard the URL."*

## Architecture

```
data/
  api/         OpenAI-compatible client + SSE parser + JSON models
  agent/       Agent loop + ToolRegistry
  agent/tools/ shell, file ops, grep, http_fetch, list_interpreters,
               copy_to_clipboard, share_file, open_url
  chat/        UI message model + wire-format mapper
  settings/    DataStore-backed settings
  workspace/   Scoped filesystem manager
ui/
  theme/       Color (e-ink palette), Type, Theme
  chat/        ChatScreen + ViewModel + message rows + tool cards + composer
  workspace/   File browser + in-app editor
  settings/    Settings form + model picker
  nav/         Single-activity NavHost
```

## Build from source

```bash
git clone https://github.com/UiOPERATIONALparameters/TermuXagent.git
cd TermuXagent
./gradlew assembleRelease
# APK at app/build/outputs/apk/release/app-release.apk
```

Requires JDK 17 and Android SDK 34.

## Limits

- **Non-rooted Android.** Shell commands run via `/system/bin/sh` + Android's
  built-in `toybox`. That gives you `ls`, `cat`, `cp`, `mv`, `rm`, `mkdir`,
  `find`, `grep`, `sed`, `awk`, `gzip`, `tar`, `head`, `tail`, `sort`, `uniq`,
  `wc`, `bc`, `tr`, `cut`, `tee`, `xargs`, etc. — but **no `python`, `node`,
  `pip`, `apt`, or `curl`** unless you've separately installed them (e.g. via
  Termux). The `list_interpreters` tool tells the agent exactly what's
  available so it can adapt.
- The agent is sandboxed to its app-private workspace (`Android/data/
  com.termuxagent/files/workspace`). It cannot access the rest of your phone's
  filesystem.
- Tool output is truncated (shell ~20KB, file reads ~1MB, http ~16KB) so a
  single tool call can't OOM the chat.

## License

MIT. See [LICENSE](LICENSE).
