package com.termuxagent.data.linux

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages a PRoot-based Alpine Linux environment that gives the AI (and the
 * user via the Terminal screen) a real Linux computer — no root required.
 *
 * Architecture:
 *  1. A statically-compiled `proot` binary (ARM64) is extracted from APK
 *     assets to [prootBinary] on first run. If the asset is missing, falls
 *     back to Termux's proot if installed.
 *  2. An Alpine Linux minirootfs (~3MB compressed, ~10MB extracted) is
 *     downloaded and extracted to [rootfsDir].
 *  3. The agent's shell tool (and the Terminal screen) can optionally run
 *     commands inside the PRoot environment.
 *
 * Inside PRoot, the AI gets: apk (package manager), bash, coreutils, and
 * can `apk add python3 nodejs ruby gcc git curl wget make cmake` etc.
 *
 * PRoot works by intercepting syscalls via ptrace — no root needed. This is
 * the same approach used by UserLAnd, Andronix, and Anlinux.
 */
class LinuxEnvironment(private val context: Context) {

    sealed class SetupState {
        object NotStarted : SetupState()
        data class Downloading(val percent: Int, val message: String) : SetupState()
        data class Extracting(val percent: Int) : SetupState()
        object Ready : SetupState()
        data class Failed(val error: String) : SetupState()
    }

    private val _state = MutableStateFlow<SetupState>(SetupState.NotStarted)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    val baseDir: File = File(context.filesDir, "linux").apply { mkdirs() }
    val prootBinary: File = File(baseDir, "proot")
    val rootfsDir: File = File(baseDir, "rootfs")
    val markerFile: File = File(baseDir, ".setup_complete")

    val isReady: Boolean get() = markerFile.exists() && prootBinary.exists() && rootfsDir.exists()

    /** The workspace bind-mount path inside the Linux env. */
    val workspaceMount: String = "/root/workspace"

    /**
     * Build the PRoot command to run a shell command inside the Linux env.
     * Returns null if the environment isn't set up.
     */
    fun buildProotCommand(command: String, cwd: String? = null): String? {
        if (!isReady) return null
        val wsRoot = com.termuxagent.data.workspace.WorkspaceManager.root.absolutePath
        val cwdArg = cwd?.let { "cd '$it' && " } ?: ""
        val parts = listOf(
            prootBinary.absolutePath,
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "$wsRoot:$workspaceMount",
            "-b", "/dev/urandom:/dev/random",
            "-w", workspaceMount,
            "/bin/sh", "-c", "$cwdArg$command"
        )
        return parts.joinToString(" ") { escapeShellArg(it) }
    }

    private fun escapeShellArg(s: String): String {
        if (s.all { it.isLetterOrDigit() || it in "/._-:" }) return s
        return "'" + s.replace("'", "'\\''") + "'"
    }

    suspend fun setup(): Boolean = withContext(Dispatchers.IO) {
        if (isReady) {
            _state.value = SetupState.Ready
            return@withContext true
        }
        try {
            _state.value = SetupState.Downloading(0, "Extracting PRoot binary…")
            extractProotBinary()

            _state.value = SetupState.Downloading(10, "Downloading Alpine Linux rootfs…")
            val rootfsArchive = downloadAlpineRootfs()

            _state.value = SetupState.Extracting(50)
            extractRootfs(rootfsArchive)

            _state.value = SetupState.Downloading(90, "Configuring…")
            configureRootfs()

            markerFile.writeText(System.currentTimeMillis().toString())
            _state.value = SetupState.Ready
            true
        } catch (e: Exception) {
            _state.value = SetupState.Failed(e.message ?: "Setup failed")
            false
        }
    }

    private fun extractProotBinary() {
        try {
            context.assets.open("proot_arm64").use { input ->
                FileOutputStream(prootBinary).use { output -> input.copyTo(output) }
            }
            prootBinary.setExecutable(true, true)
        } catch (e: Exception) {
            // Asset not found — try Termux
            val termuxProot = File("/data/data/com.termux/files/usr/bin/proot")
            if (termuxProot.exists()) {
                if (!prootBinary.exists()) {
                    prootBinary.writeText("#!/system/bin/sh\nexec ${termuxProot.absolutePath} \"\$@\"")
                    prootBinary.setExecutable(true, true)
                }
            } else {
                throw RuntimeException("PRoot binary not found. Install Termux with 'pkg install proot' to enable the Linux environment.")
            }
        }
    }

    private fun downloadAlpineRootfs(): File {
        val archive = File(baseDir, "alpine-rootfs.tar.gz")
        val url = "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"
        if (archive.exists() && archive.length() > 1_000_000) return archive

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val req = okhttp3.Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Failed to download rootfs: HTTP ${resp.code}")
            val body = resp.body ?: throw RuntimeException("Empty response body")
            val total = body.contentLength()
            archive.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(8192)
                    var read: Int
                    var downloaded = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = (10 + (downloaded * 40 / total)).toInt()
                            _state.value = SetupState.Downloading(percent, "Downloading rootfs… ${downloaded / 1024}KB / ${total / 1024}KB")
                        }
                    }
                }
            }
        }
        return archive
    }

    private fun extractRootfs(archive: File) {
        rootfsDir.mkdirs()
        val proc = ProcessBuilder("/system/bin/tar", "-xzf", archive.absolutePath, "-C", rootfsDir.absolutePath)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor(120, TimeUnit.SECONDS)
        if (proc.exitValue() != 0) {
            throw RuntimeException("tar extraction failed: $out")
        }
    }

    private fun configureRootfs() {
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        val hosts = File(rootfsDir, "etc/hosts")
        hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(rootfsDir, "root/workspace").mkdirs()
        val repos = File(rootfsDir, "etc/apk/repositories")
        repos.writeText("https://dl-cdn.alpinelinux.org/alpine/v3.20/main\nhttps://dl-cdn.alpinelinux.org/alpine/v3.20/community\n")
    }

    fun reset() {
        rootfsDir.deleteRecursively()
        prootBinary.delete()
        markerFile.delete()
        _state.value = SetupState.NotStarted
    }

    fun storageUsageMB(): Long {
        return baseDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
    }
}
