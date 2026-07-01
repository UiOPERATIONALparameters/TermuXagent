package com.termuxagent.data.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream

/**
 * SSH client that connects to a remote Linux machine and runs commands.
 * Supports both password auth (any VPS) and private key auth (GitHub Codespaces).
 *
 * For GitHub Codespaces:
 *   host = "ssh.github.com", port = 443, user = codespace_name, privateKey = RSA key
 *
 * For any VPS:
 *   host = your.server.com, port = 22, user = root, password = your_password
 */
class SshClient(
    private val host: String,
    private val port: Int = 22,
    private val user: String,
    private val password: String = "",
    private val privateKey: String = "",
    private val workingDir: String = ""
) : AutoCloseable {

    private var session: Session? = null

    fun connect(): Boolean {
        return try {
            val jsch = JSch()
            val session = jsch.getSession(user, host, port)

            if (privateKey.isNotBlank()) {
                // Key-based auth (GitHub Codespaces)
                val keyFile = java.io.File.createTempFile("termuxagent_key", ".pem")
                keyFile.writeText(privateKey)
                keyFile.deleteOnExit()
                jsch.addIdentity(keyFile.absolutePath)
            } else {
                // Password-based auth (VPS)
                session.setPassword(password)
            }

            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", "publickey,password")
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
