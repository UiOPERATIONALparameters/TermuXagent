package com.termuxagent.data.linux

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages a PRoot-based Alpine Linux environment that gives the AI its own
 * real Linux computer — no root required.
 *
 * KEY INSIGHT: Android's app sandbox prevents this app from accessing
 * /data/data/com.termux/files/usr/bin/proot (Termux's private data dir).
 * So instead of relying on Termux's proot, we DOWNLOAD the proot binary
 * and its shared libraries from Termux's public package mirror and extract
 * them into THIS app's own data directory — which the app CAN access.
 *
 * Architecture:
 *  1. Download proot .deb from packages.termux.dev (public URL, no auth)
 *  2. Extract the proot binary + required .so files from the .deb
 *  3. Download Alpine minirootfs from dl-cdn.alpinelinux.org
 *  4. Extract rootfs into app data dir
 *  5. Run: proot -r rootfs/ -b /dev -b /proc -b /sys -b workspace:/root/workspace
 *          /bin/sh -c "command"
 *
 * The proot binary from Termux is dynamically linked against libtalloc.
 * We download libtalloc too and set LD_LIBRARY_PATH so proot can find it.
 * The system linker (/system/lib64/linker64) handles the rest — all other
 * deps (libc, libm, libdl) are satisfied by Android's bionic libc.
 */
class LinuxEnvironment(private val context: Context) {

    sealed class SetupState {
        object NotStarted : SetupState()
        data class Downloading(val percent: Int, val message: String) : SetupState()
        data class Extracting(val percent: Int, val message: String) : SetupState()
        object Ready : SetupState()
        data class Failed(val error: String) : SetupState()
    }

    private val _state = MutableStateFlow<SetupState>(SetupState.NotStarted)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    val baseDir: File = File(context.filesDir, "linux").apply { mkdirs() }
    val prootBinary: File = File(baseDir, "proot")
    val libDir: File = File(baseDir, "lib").apply { mkdirs() }
    val rootfsDir: File = File(baseDir, "rootfs")
    val markerFile: File = File(baseDir, ".setup_complete")

    val isReady: Boolean get() = markerFile.exists() && prootBinary.exists() && prootBinary.canExecute() && rootfsDir.exists()

    val workspaceMount: String = "/root/workspace"

    fun buildProotCommand(command: String, cwd: String? = null): String? {
        if (!isReady) return null
        val wsRoot = com.termuxagent.data.workspace.WorkspaceManager.root.absolutePath
        val cwdArg = cwd?.let { "cd '$it' && " } ?: ""
        // LD_LIBRARY_PATH so proot finds libtalloc.so
        val envPrefix = "LD_LIBRARY_PATH=${libDir.absolutePath} "
        val parts = listOf(
            envPrefix + prootBinary.absolutePath,
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "$wsRoot:$workspaceMount",
            "-b", "/dev/urandom:/dev/random",
            "-w", workspaceMount,
            "/bin/sh", "-c", "$cwdArg$command"
        )
        return parts.joinToString(" ")
    }

    suspend fun setup(): Boolean = withContext(Dispatchers.IO) {
        if (isReady) {
            _state.value = SetupState.Ready
            return@withContext true
        }
        try {
            // Step 1: Download + extract proot binary and libtalloc
            _state.value = SetupState.Downloading(0, "Downloading PRoot binary…")
            downloadAndExtractProot()

            // Step 2: Download Alpine rootfs
            _state.value = SetupState.Downloading(30, "Downloading Alpine Linux rootfs…")
            val rootfsArchive = downloadAlpineRootfs()

            // Step 3: Extract rootfs
            _state.value = SetupState.Extracting(60, "Extracting Alpine rootfs…")
            extractRootfs(rootfsArchive)

            // Step 4: Configure
            _state.value = SetupState.Extracting(90, "Configuring Linux environment…")
            configureRootfs()

            markerFile.writeText(System.currentTimeMillis().toString())
            _state.value = SetupState.Ready
            true
        } catch (e: Exception) {
            _state.value = SetupState.Failed(e.message ?: "Setup failed")
            false
        }
    }

    /**
     * Download the proot .deb from Termux's package mirror and extract the
     * binary + libtalloc.so. A .deb is an `ar` archive containing data.tar.xz.
     * We parse the ar format manually (it's simple) and extract with toybox tar.
     */
    private fun downloadAndExtractProot() {
        val prootDeb = File(baseDir, "proot.deb")
        val tallocDeb = File(baseDir, "libtalloc.deb")

        // Download proot .deb
        if (!prootDeb.exists() || prootDeb.length() < 10000) {
            downloadFile(
                "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.0-55_aarch64.deb",
                prootDeb
            ) { percent ->
                _state.value = SetupState.Downloading(percent / 4, "Downloading PRoot binary… ${percent}%")
            }
        }

        // Download libtalloc .deb (proot depends on it)
        if (!tallocDeb.exists() || tallocDeb.length() < 1000) {
            downloadFile(
                "https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.1-1_aarch64.deb",
                tallocDeb
            ) { percent ->
                _state.value = SetupState.Downloading(25 + percent / 8, "Downloading libtalloc… ${percent}%")
            }
        }

        // Extract proot binary from .deb
        _state.value = SetupState.Extracting(28, "Extracting PRoot binary…")
        extractDeb(prootDeb, "proot", prootBinary, "usr/bin/proot")
        prootBinary.setExecutable(true, true)

        // Extract libtalloc.so from .deb
        _state.value = SetupState.Extracting(29, "Extracting libtalloc…")
        extractDeb(tallocDeb, "libtalloc", File(libDir, "libtalloc.so.2"), "usr/lib/libtalloc.so.2")
    }

    /**
     * Extract a specific file from a .deb package.
     * .deb format: ar archive containing:
     *   - debian-binary
     *   - control.tar.gz (or .xz)
     *   - data.tar.xz (or .gz) — this contains the actual files
     *
     * We parse the ar header to find data.tar.*, extract it, then use toybox
     * tar to extract the specific file we need.
     */
    private fun extractDeb(debFile: File, label: String, outputFile: File, innerPath: String) {
        val tempDir = File(baseDir, "tmp_$label").apply { mkdirs() }
        try {
            // Parse ar archive to find data.tar.*
            val dataTar = parseArAndExtractData(debFile, tempDir)
            if (dataTar == null) {
                throw RuntimeException("Could not find data.tar in .deb for $label")
            }

            // Extract the specific file from data.tar using toybox tar
            // The path inside data.tar is like "./usr/bin/proot" or "./usr/lib/libtalloc.so.2"
            val proc = ProcessBuilder(
                "/system/bin/tar", "-xf", dataTar.absolutePath,
                "-C", tempDir.absolutePath,
                "./$innerPath", ".$innerPath", innerPath
            ).redirectErrorStream(true).start()
            proc.waitFor(30, TimeUnit.SECONDS)
            // tar might fail silently if the exact path doesn't match — try both ./ and no ./
            val extracted = File(tempDir, innerPath)
            if (!extracted.exists()) {
                // Try without ./
                val alt = File(tempDir, "./$innerPath")
                if (alt.exists()) {
                    alt.copyTo(outputFile, overwrite = true)
                } else {
                    // List what's in the temp dir for debugging
                    val contents = tempDir.walkTopDown().take(20).joinToString("\n")
                    throw RuntimeException("Could not find $innerPath in .deb. Contents:\n$contents")
                }
            } else {
                extracted.copyTo(outputFile, overwrite = true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Parse an `ar` archive (the .deb format) and extract the data.tar.* member.
     * ar format:
     *   - Magic: "!<arch>\n" (8 bytes)
     *   - File entries, each with a 60-byte header followed by file data
     *   - Header: name(16) / mod(12) / uid(6) / gid(6) / mode(8) / size(10) / `\x60\n`
     */
    private fun parseArAndExtractData(debFile: File, outputDir: File): File? {
        val data = debFile.readBytes()
        val magic = String(data, 0, 8, Charsets.US_ASCII)
        if (magic != "!<arch>\n") {
            throw RuntimeException("Not a valid .deb/ar archive: bad magic")
        }

        var pos = 8
        while (pos + 60 <= data.size) {
            // Parse 60-byte header
            val header = String(data, pos, 60, Charsets.US_ASCII)
            val name = header.substring(0, 16).trim()
            val sizeStr = header.substring(48, 58).trim()
            val size = sizeStr.toLongOrNull() ?: break

            pos += 60
            if (pos + size > data.size) break

            // Extract data.tar.* member
            if (name.startsWith("data.tar")) {
                val outFile = File(outputDir, name)
                outFile.outputStream().use { out ->
                    out.write(data, pos, size.toInt())
                }
                return outFile
            }
            // Skip to next entry (ar members are padded to 2-byte boundaries)
            pos += size
            if (size % 2 != 0L) pos++
        }
        return null
    }

    private fun downloadAlpineRootfs(): File {
        val archive = File(baseDir, "alpine-rootfs.tar.gz")
        if (archive.exists() && archive.length() > 1_000_000) return archive

        val url = "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"
        downloadFile(url, archive) { percent ->
            _state.value = SetupState.Downloading(30 + percent * 3 / 10, "Downloading Alpine rootfs… ${percent}%")
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
            throw RuntimeException("rootfs extraction failed: $out")
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

    private fun downloadFile(url: String, dest: File, onProgress: (Int) -> Unit) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Download failed: HTTP ${resp.code} for $url")
            val body = resp.body ?: throw RuntimeException("Empty response body for $url")
            val total = body.contentLength()
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(8192)
                    var read: Int
                    var downloaded = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress((downloaded * 100 / total).toInt())
                        }
                    }
                }
            }
        }
    }

    fun reset() {
        rootfsDir.deleteRecursively()
        prootBinary.delete()
        libDir.deleteRecursively()
        markerFile.delete()
        _state.value = SetupState.NotStarted
    }

    fun storageUsageMB(): Long {
        return baseDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
    }

    /**
     * Check if Termux is installed (informational — we don't use Termux's
     * proot anymore, we download our own. But it's useful for the user to know.)
     */
    fun isTermuxInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.termux", 0)
        true
    }.getOrDefault(false)
}
