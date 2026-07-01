package com.termuxagent.data.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream

/**
 * SSH client that connects to a remote Linux machine and runs commands.
 * This gives the AI a REAL Linux computer — full package manager, Python,
 * Node, Ruby, GCC, Git, anything. No PRoot, no ptrace, no extraction bugs.
 *
 * Usage:
 *   val ssh = SshClient(host, port, user, password)
 *   val result = ssh.execute("ls -la /tmp")
 *   ssh.close()
 *
 * The user configures SSH credentials in Settings. Recommended free options:
 * - Oracle Cloud Free Tier (always-free ARM VM, 4 cores, 24GB RAM)
 * - GitHub Codespaces (SSH into a codespace)
 * - Google Cloud Free Tier (e2-micro VM)
 * - Any cheap VPS
 */
class SshClient(
    private val host: String,
    private val port: Int = 22,
    private val user: String,
    private val password: String,
    private val workingDir: String = ""
) : AutoCloseable {

    private var session: Session? = null

    fun connect(): Boolean {
        return try {
            val jsch = JSch()
            val session = jsch.getSession(user, host, port)
            session.setPassword(password)
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", "password,publickey")
            session.connect(15000)
            this.session = session
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isConnected(): Boolean = session?.isConnected == true

    data class Result(
        val exitCode: Int,
        val output: String,
        val error: String
    )

    fun execute(command: String, timeoutMs: Int = 30_000): Result {
        val session = this.session ?: return Result(-1, "", "Not connected")
        if (!session.isConnected) {
            connect()
        }

        val fullCommand = if (workingDir.isNotBlank()) {
            "cd '$workingDir' && $command"
        } else {
            command
        }

        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(fullCommand)
        channel.connect(10_000)

        val outStream = ByteArrayOutputStream()
        val errStream = ByteArrayOutputStream()
        val outThread = Thread {
            channel.inputStream.copyTo(outStream, 8192)
        }
        val errThread = Thread {
            channel.errStream.copyTo(errStream, 8192)
        }
        outThread.start()
        errThread.start()

        // Poll for completion with timeout
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!channel.isClosed && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        if (!channel.isClosed) {
            channel.disconnect()
            outThread.join(2000)
            errThread.join(2000)
            return Result(-1, outStream.toString(), "TIMEOUT after ${timeoutMs}ms")
        }

        outThread.join(3000)
        errThread.join(3000)
        val exitCode = channel.exitStatus
        channel.disconnect()

        val output = outStream.toString(Charsets.UTF_8).take(20_000)
        val error = errStream.toString(Charsets.UTF_8).take(20_000)
        return Result(exitCode, output, error)
    }

    override fun close() {
        session?.disconnect()
        session = null
    }
}
