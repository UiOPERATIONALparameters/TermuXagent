# PLANNING: Giving TermuXagent its own Linux computer

## Goal

Give the AI a real, self-contained Linux environment inside the app — no root,
no Termux dependency, no cloud. The agent should be able to `apk add python3`,
`pip install requests`, `node script.js`, compile C code, etc.

## How z.ai / Kimi / Claude Code do it

| Platform | Approach | Where it runs |
|---|---|---|
| z.ai agentic | Cloud sandbox — full Ubuntu container, root inside | Cloud (per-user) |
| Kimi | Same — container per session | Cloud |
| Claude Code | Local — uses the user's actual shell | Desktop |
| Cursor | Local — uses the user's actual shell | Desktop |

**Key insight:** All cloud approaches use containers. On Android, the
equivalent is **PRoot** — a userspace tool that emulates chroot/bind-mount
via ptrace syscall interception. No root required.

## The Android constraint

Non-rooted Android apps can only execute:
1. System binaries (`/system/bin/sh`, toybox)
2. Binaries in their own data dir (if marked executable)
3. Termux's binaries (if Termux is installed)

A non-rooted app **cannot**:
- Use `chroot()` (requires root)
- Mount filesystems
- Use `pivot_root()`
- Access `/proc` of other processes

**PRoot works around this** by intercepting syscalls via `ptrace(PTRACE_SYSCALL)`.
Every file path the process tries to access is translated to a path inside the
"guest" rootfs. This has ~10-20% overhead but requires no root.

## Recommended approach: PRoot + Alpine Linux

### Phase 1: Ship the PRoot binary (v1.4)

1. Cross-compile `proot` for ARM64 (statically linked, ~2MB)
2. Bundle it in the APK as `assets/proot_arm64`
3. On first run, extract to `app_data/files/usr/bin/proot` and `chmod +x`
4. Verify it works: `proot --version`

### Phase 2: Bootstrap Alpine rootfs (v1.4)

1. On first run (or on-demand), download a minimal Alpine Linux rootfs:
   - URL: `https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.0-aarch64.tar.gz`
   - Size: ~3MB compressed, ~10MB extracted
2. Extract to `app_data/files/rootfs/`
3. Set up bind mounts (via PRoot's `-b` flag):
   - `/dev`, `/proc`, `/sys` from Android
   - `app_data/files/workspace` → `/root/workspace` (so the agent's files are shared)
4. Test: `proot -r rootfs/ -b /dev -b /proc /bin/sh -c "apk add python3 && python3 --version"`

### Phase 3: Wire into the agent (v1.4)

1. Modify `ShellTool` to optionally run commands inside the PRoot environment
2. Add a setting: "Use Linux environment" (on/off toggle)
3. When on, the `shell` tool runs:
   ```
   proot -r rootfs/ -b /dev -b /proc -b /sys -b workspace:/root/workspace \
     /bin/sh -c "cd /root/workspace && <command>"
   ```
4. The agent gets: `apk` (package manager), `bash`, `coreutils`, `python3`,
   `nodejs`, `ruby`, `gcc`, `git`, `curl`, `wget`, `ssh`, `make`, `cmake`, etc.
5. The agent can `apk add` anything available in Alpine's repos.

### Phase 4: Terminal screen integration (v1.5)

1. The Terminal screen can optionally use the PRoot environment
2. User gets a real Linux shell: `bash` with `apk`, `pip`, `npm`, etc.
3. Toggle between "Android shell" and "Linux shell" in settings

### Phase 5: Pre-install popular packages (v1.5+)

Ship a pre-built rootfs image with common packages pre-installed:
- python3 + pip
- nodejs + npm
- git
- curl + wget
- gcc + make
- vim

This would be ~50MB but saves the first-run `apk add` time.

## Technical considerations

### PRoot performance
- Syscall interception via ptrace has ~10-20% overhead
- File I/O is the main bottleneck (path translation on every open/read/write)
- For CPU-bound tasks (compilation, data processing), overhead is acceptable
- For I/O-heavy tasks (large file operations), it can be 2-3x slower

### Rootfs lifecycle
- Rootfs lives in `app_data/files/rootfs/` (~10-300MB depending on installed packages)
- Survives app updates (data dir is preserved)
- User can reset it from Settings ("Reinstall Linux environment")
- Packages installed by the agent persist across sessions

### Security
- PRoot is a user-level process; no privilege escalation
- The agent's PRoot environment is isolated from the Android system
- Bind mounts are explicit (only /dev, /proc, /sys, and workspace are shared)
- The agent cannot access other apps' data or the system partition

### APK size impact
- PRoot binary: ~2MB
- Alpine rootfs: ~3MB compressed (downloaded on first run, not in APK)
- Total APK size increase: ~2MB
- Total storage after setup: ~12MB (base) + packages installed by agent

## Alternatives considered

### Option A: Bundle a full Linux userspace in the APK
- Ship busybox + Python + Node compiled for ARM64
- Pros: No first-run download
- Cons: APK balloons to 100MB+, hard to update individual tools, no package manager
- **Verdict:** Too heavy, too rigid

### Option B: Rely on Termux only (current approach)
- Pros: Mature, full-featured, small APK
- Cons: Requires user to install a separate app, no control over what's installed
- **Verdict:** Keep as fallback, but PRoot is the primary path

### Option C: Use Android's Termux:API or Run as a companion
- Launch Termux via intent and send commands
- Pros: Full Termux power
- Cons: Fragile inter-app communication, requires Termux to be running
- **Verdict:** Too fragile

### Option D: Cloud sandbox (like z.ai)
- Run commands on a remote server
- Pros: Full power, no local resource usage
- Cons: Requires internet, latency, privacy concerns, hosting cost
- **Verdict:** Goes against the "on-device" promise

## Implementation timeline

| Version | Feature | Effort |
|---|---|---|
| v1.3 (current) | Web search, chat persistence, bug fixes | Done |
| v1.4 | PRoot binary + Alpine rootfs + shell integration | ~2 days |
| v1.5 | Terminal PRoot integration + pre-installed packages | ~1 day |
| v1.6 | Package management UI + rootfs reset/settings | ~0.5 day |

## Risks

1. **PRoot compile issues**: ARM64 static compilation of PRoot can be tricky.
   Mitigation: use a known-good pre-built binary from Termux's packages.
2. **Alpine download failure**: First-run download needs a reliable mirror.
   Mitigation: fall back to a bundled rootfs (smaller, in APK).
3. **Performance complaints**: PRoot overhead might frustrate users.
   Mitigation: make it opt-in, keep the native Android shell as default.
4. **Storage usage**: Rootfs grows as the agent installs packages.
   Mitigation: show storage usage in Settings, provide "reset" button.

## Conclusion

PRoot + Alpine is the clear winner for giving the AI "its own computer" on a
non-rooted Android device. It's how UserLAnd, Andronix, and Anlinux have solved
this problem for years. The implementation is well-understood, the performance
is acceptable, and the result is a genuine Linux environment where the agent
can install and run anything.
