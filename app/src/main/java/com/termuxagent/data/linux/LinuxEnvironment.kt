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
import java.util.concurrent.TimeUnit

/**
 * Manages a PRoot-based Alpine Linux environment that gives the AI its own
 * real Linux computer — no root required.
 *
 * How it works:
 *  1. Fetches the Termux package index (Packages file) to find the CURRENT
 *     URLs for proot, libtalloc, and libandroid-shmem .deb files. This is
 *     version-agnostic — works regardless of what version Termux is on.
 *  2. Downloads the .deb files from packages.termux.dev (public, no auth).
 *  3. Parses the `ar` archive format manually to extract data.tar.xz.
 *  4. Uses toybox tar to extract the proot binary + shared libraries.
 *  5. Downloads Alpine minirootfs from dl-cdn.alpinelinux.org.
 *  6. Extracts rootfs into app data dir.
 *  7. Runs: LD_LIBRARY_PATH=lib/ proot -r rootfs/ -b /dev -b /proc -b /sys
 *          -b workspace:/root/workspace /bin/sh -c "command"
 *
 * All files live in THIS app's own data directory (Android sandbox safe).
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
            _state.value = SetupState.Downloading(0, "Resolving package URLs…")
            val urls = resolvePackageUrls()

            _state.value = SetupState.Downloading(5, "Downloading PRoot binary…")
            val prootDeb = downloadDeb(urls.getValue("proot"), "proot.deb") { pct ->
                _state.value = SetupState.Downloading(5 + pct / 10, "Downloading PRoot binary… ${pct}%")
            }

            _state.value = SetupState.Downloading(20, "Downloading libtalloc…")
            val tallocDeb = downloadDeb(urls.getValue("libtalloc"), "libtalloc.deb") { pct ->
                _state.value = SetupState.Downloading(20 + pct / 10, "Downloading libtalloc… ${pct}%")
            }

            _state.value = SetupState.Downloading(25, "Downloading libandroid-shmem…")
            val shmemDeb = downloadDeb(urls.getValue("libandroid-shmem"), "libandroid-shmem.deb") { pct ->
                _state.value = SetupState.Downloading(25 + pct / 5, "Downloading libandroid-shmem… ${pct}%")
            }

            _state.value = SetupState.Extracting(30, "Extracting PRoot binary…")
            extractDeb(prootDeb, "proot", prootBinary, "usr/bin/proot")
            prootBinary.setExecutable(true, true)

            _state.value = SetupState.Extracting(32, "Extracting libtalloc…")
            extractDeb(tallocDeb, "libtalloc", File(libDir, "libtalloc.so.2"), "usr/lib/libtalloc.so.2")

            _state.value = SetupState.Extracting(34, "Extracting libandroid-shmem…")
            extractDeb(shmemDeb, "libandroid-shmem", File(libDir, "libandroid-shmem.so"), "usr/lib/libandroid-shmem.so")

            _state.value = SetupState.Downloading(35, "Downloading Alpine Linux rootfs…")
            val rootfsArchive = downloadAlpineRootfs()

            _state.value = SetupState.Extracting(60, "Extracting Alpine rootfs…")
            extractRootfs(rootfsArchive)

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
     * Fetch the Termux Packages index and find the current .deb URLs for
     * proot, libtalloc, and libandroid-shmem. This is version-agnostic —
     * it always finds the latest version, so it won't break on updates.
     */
    private fun resolvePackageUrls(): Map<String, String> {
        val baseUrl = "https://packages.termux.dev/apt/termux-main/"
        val packagesUrl = "${baseUrl}dists/stable/main/binary-aarch64/Packages"

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(packagesUrl).get().build()
        val packagesText = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Failed to fetch package index: HTTP ${resp.code}")
            resp.body?.string() ?: throw RuntimeException("Empty package index")
        }

        val wanted = setOf("proot", "libtalloc", "libandroid-shmem")
        val result = mutableMapOf<String, String>()
        var currentPackage: String? = null
        var currentFilename: String? = null

        for (line in packagesText.lines()) {
            when {
                line.startsWith("Package: ") -> {
                    currentPackage = line.removePrefix("Package: ").trim()
                    currentFilename = null
                }
                line.startsWith("Filename: ") -> {
                    currentFilename = line.removePrefix("Filename: ").trim()
                }
                line.isBlank() -> {
                    // End of package entry
                    val pkg = currentPackage
                    val fn = currentFilename
                    if (pkg != null && fn != null && pkg in wanted) {
                        result[pkg] = baseUrl + fn
                    }
                    currentPackage = null
                    currentFilename = null
                }
            }
        }

        for (pkg in wanted) {
            if (pkg !in result) {
                throw RuntimeException("Could not find '$pkg' in Termux package index")
            }
        }
        return result
    }

    private fun downloadDeb(url: String, filename: String, onProgress: (Int) -> Unit): File {
        val dest = File(baseDir, filename)
        // Cache: if already downloaded with reasonable size, skip
        if (dest.exists() && dest.length() > 1000) return dest
        downloadFile(url, dest, onProgress)
        return dest
    }

    private fun extractDeb(debFile: File, label: String, outputFile: File, innerPath: String) {
        val tempDir = File(baseDir, "tmp_$label").apply { mkdirs() }
        try {
            val dataTar = parseArAndExtractData(debFile, tempDir)
                ?: throw RuntimeException("Could not find data.tar in .deb for $label")

            // Extract the specific file from data.tar using toybox tar
            // Try both ./path and path formats
            val proc = ProcessBuilder(
                "/system/bin/tar", "-xf", dataTar.absolutePath,
                "-C", tempDir.absolutePath,
                "./$innerPath", innerPath
            ).redirectErrorStream(true).start()
            proc.waitFor(30, TimeUnit.SECONDS)

            val extracted = File(tempDir, innerPath)
            val altExtracted = File(tempDir, "./$innerPath")
            val source = when {
                extracted.exists() -> extracted
                altExtracted.exists() -> altExtracted
                else -> {
                    // List what's actually in the temp dir for debugging
                    val contents = tempDir.walkTopDown().take(20).joinToString("\n")
                    throw RuntimeException("Could not find $innerPath in .deb. Contents:\n$contents")
                }
            }
            source.copyTo(outputFile, overwrite = true)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun parseArAndExtractData(debFile: File, outputDir: File): File? {
        val data = debFile.readBytes()
        val magic = String(data, 0, 8, Charsets.US_ASCII)
        if (magic != "!<arch>\n") {
            throw RuntimeException("Not a valid .deb/ar archive: bad magic")
        }

        var pos = 8
        while (pos + 60 <= data.size) {
            val header = String(data, pos, 60, Charsets.US_ASCII)
            val name = header.substring(0, 16).trim()
            val sizeStr = header.substring(48, 58).trim()
            val size = sizeStr.toLongOrNull() ?: break

            pos += 60
            if (pos + size > data.size) break

            if (name.startsWith("data.tar")) {
                val outFile = File(outputDir, name)
                outFile.outputStream().use { out ->
                    out.write(data, pos, size.toInt())
                }
                return outFile
            }
            pos += size.toInt()
            if (size % 2 != 0L) pos++
        }
        return null
    }

    private fun downloadAlpineRootfs(): File {
        val archive = File(baseDir, "alpine-rootfs.tar.gz")
        if (archive.exists() && archive.length() > 1_000_000) return archive

        val url = "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"
        downloadFile(url, archive) { percent ->
            _state.value = SetupState.Downloading(35 + percent * 25 / 100, "Downloading Alpine rootfs… ${percent}%")
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
}
