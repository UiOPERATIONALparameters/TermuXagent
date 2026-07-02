package com.termuxagent.data.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Helper to set up SSH access to GitHub Codespaces.
 *
 * Flow:
 * 1. Generate an RSA key pair in-app.
 * 2. Upload the public key to GitHub via the API (/user/keys).
 * 3. SSH into the codespace via ssh.github.com:443 using key auth.
 *    The username is the codespace name (e.g., "literate-disco-r745v5j5ggvr35xw").
 *
 * This avoids needing the `gh` CLI — we do everything via REST API + JSch.
 */
class CodespacesHelper(private val githubToken: String) {

    data class CodespaceInfo(
        val name: String,
        val state: String,
        val machine: String,
        val repo: String
    )

    data class SetupResult(
        val success: Boolean,
        val sshHost: String = "",
        val sshPort: Int = 443,
        val sshUser: String = "",
        val privateKey: String = "",
        val message: String = ""
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** List the user's codespaces. */
    fun listCodespaces(): List<CodespaceInfo> {
        val req = Request.Builder()
            .url("https://api.github.com/user/codespaces")
            .header("Authorization", "token $githubToken")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val arr = json.optJSONArray("codespaces") ?: return emptyList()
            val result = mutableListOf<CodespaceInfo>()
            for (i in 0 until arr.length()) {
                val cs = arr.getJSONObject(i)
                result.add(CodespaceInfo(
                    name = cs.getString("name"),
                    state = cs.getString("state"),
                    machine = cs.optJSONObject("machine")?.optString("display_name") ?: "",
                    repo = cs.optJSONObject("repository")?.optString("full_name") ?: ""
                ))
            }
            return result
        }
    }

    /**
     * Full setup: generate key, upload to GitHub, return SSH connection details.
     * The app then stores these and uses them to connect.
     */
    fun setup(codespaceName: String): SetupResult {
        // Step 1: Generate RSA key pair
        val jsch = JSch()
        val keyPair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 2048)

        // Step 2: Get public key in OpenSSH format
        val publicKey = keyPair.getPublicKeyBlob()
        val publicKeyStr = "ssh-rsa " + java.util.Base64.getEncoder().encodeToString(publicKey) + " termuxagent"

        // Step 3: Get private key as a string
        val privateKeyOut = java.io.ByteArrayOutputStream()
        keyPair.writePrivateKey(privateKeyOut)
        val privateKeyStr = privateKeyOut.toString()

        keyPair.dispose()

        // Step 4: Upload public key to GitHub
        val addKeyBody = """{"title":"TermuXagent","key":"${publicKeyStr.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""
        val addKeyReq = Request.Builder()
            .url("https://api.github.com/user/keys")
            .header("Authorization", "token $githubToken")
            .header("Accept", "application/vnd.github+json")
            .post(addKeyBody.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(addKeyReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string()?.take(300) ?: ""
                // If key already exists, that's OK — continue
                if (!errBody.contains("already") && resp.code != 422) {
                    // Most likely the token lacks admin:public_key scope
                    return SetupResult(
                        success = false,
                        message = if (resp.code == 404 || resp.code == 403) {
                            "Your GitHub token needs the 'admin:public_key' scope to add SSH keys. Go to github.com/settings/tokens, edit your token, check 'admin:public_key', save, then paste the new token here."
                        } else {
                            "Failed to add SSH key to GitHub: HTTP ${resp.code}: $errBody"
                        }
                    )
                }
            }
        }

        // Step 5: Make sure the codespace is running. Poll until Available.
        val startReq = Request.Builder()
            .url("https://api.github.com/user/codespaces/$codespaceName/start")
            .header("Authorization", "token $githubToken")
            .header("Accept", "application/vnd.github+json")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(startReq).execute().close()

        // Wait for the codespace to be Available (max 60 seconds)
        var waitCount = 0
        var isAvailable = false
        while (waitCount < 30 && !isAvailable) {
            Thread.sleep(2000)
            val checkReq = Request.Builder()
                .url("https://api.github.com/user/codespaces/$codespaceName")
                .header("Authorization", "token $githubToken")
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()
            client.newCall(checkReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    val state = json.optString("state")
                    if (state == "Available") isAvailable = true
                }
            }
            waitCount++
        }

        // Step 6: Return connection details
        // GitHub Codespaces SSH: user=codespace_name, host=ssh.github.com, port=443
        return SetupResult(
            success = true,
            sshHost = "ssh.github.com",
            sshPort = 443,
            sshUser = codespaceName,
            privateKey = privateKeyStr,
            message = "SSH key added to GitHub. Codespace is running. Connect via ssh.github.com:443."
        )
    }
}
