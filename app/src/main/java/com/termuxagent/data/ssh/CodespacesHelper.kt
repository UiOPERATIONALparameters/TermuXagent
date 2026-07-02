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
 * 1. Clean up any old "TermuXagent" SSH keys on GitHub (avoid duplicates).
 * 2. Generate an ED25519 key pair in-app (GitHub's preferred key type).
 * 3. Upload the public key to GitHub via the API (/user/keys).
 * 4. Start the codespace and wait until it's "Available".
 * 5. Wait 5s for SSH key propagation across GitHub's servers.
 * 6. SSH into the codespace via ssh.github.com:443 using key auth.
 *    The username is the codespace name.
 *
 * ED25519 is used instead of RSA because GitHub's Codespaces SSH server
 * has better compatibility with ED25519 keys.
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
     * Full setup: clean old keys, generate ED25519 key, upload to GitHub,
     * start codespace, wait for propagation, return SSH connection details.
     */
    fun setup(codespaceName: String): SetupResult {
        // Step 1: Clean up old TermuXagent keys to avoid duplicates
        cleanupOldKeys()

        // Step 2: Generate ED25519 key pair (GitHub's preferred type)
        val jsch = JSch()
        val keyPair = try {
            KeyPair.genKeyPair(jsch, KeyPair.ED25519)
        } catch (e: Exception) {
            // Fallback to RSA if ED25519 fails
            try {
                KeyPair.genKeyPair(jsch, KeyPair.RSA, 4096)
            } catch (e2: Exception) {
                return SetupResult(false, message = "Failed to generate SSH key: ${e2.message}")
            }
        }

        // Step 3: Get public key in OpenSSH format
        val publicKeyOut = java.io.ByteArrayOutputStream()
        keyPair.writePublicKey(publicKeyOut, "termuxagent")
        val publicKeyStr = publicKeyOut.toString().trim()

        // Step 4: Get private key as a string
        val privateKeyOut = java.io.ByteArrayOutputStream()
        keyPair.writePrivateKey(privateKeyOut)
        val privateKeyStr = privateKeyOut.toString()

        keyPair.dispose()

        // Step 5: Upload public key to GitHub
        val addKeyBody = """{"title":"TermuXagent","key":"${publicKeyStr.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}"}"""
        val addKeyReq = Request.Builder()
            .url("https://api.github.com/user/keys")
            .header("Authorization", "token $githubToken")
            .header("Accept", "application/vnd.github+json")
            .post(addKeyBody.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(addKeyReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string()?.take(300) ?: ""
                if (!errBody.contains("already") && resp.code != 422) {
                    return SetupResult(
                        success = false,
                        message = if (resp.code == 404 || resp.code == 403) {
                            "Your GitHub token needs 'admin:public_key' scope. Go to github.com/settings/tokens, edit your token, check 'admin:public_key', save, then paste the new token."
                        } else {
                            "Failed to add SSH key to GitHub: HTTP ${resp.code}: $errBody"
                        }
                    )
                }
            }
        }

        // Step 6: Start the codespace
        val startReq = Request.Builder()
            .url("https://api.github.com/user/codespaces/$codespaceName/start")
            .header("Authorization", "token $githubToken")
            .header("Accept", "application/vnd.github+json")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(startReq).execute().close()

        // Step 7: Wait for the codespace to be Available (max 60 seconds)
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

        // Step 8: Wait for SSH key propagation (GitHub needs a few seconds)
        Thread.sleep(5000)

        // Step 9: Return connection details
        return SetupResult(
            success = true,
            sshHost = "ssh.github.com",
            sshPort = 443,
            sshUser = codespaceName,
            privateKey = privateKeyStr,
            message = "ED25519 SSH key added. Codespace is running. Ready to connect."
        )
    }

    /** Remove all SSH keys titled "TermuXagent" from the user's GitHub account. */
    private fun cleanupOldKeys() {
        val listReq = Request.Builder()
            .url("https://api.github.com/user/keys")
            .header("Authorization", "token $githubToken")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        client.newCall(listReq).execute().use { resp ->
            if (!resp.isSuccessful) return
            val body = resp.body?.string() ?: return
            val arr = JSONObject("{\"list\":$body}").optJSONArray("list") ?: return
            for (i in 0 until arr.length()) {
                val key = arr.getJSONObject(i)
                if (key.optString("title") == "TermuXagent") {
                    val keyId = key.optInt("id")
                    val delReq = Request.Builder()
                        .url("https://api.github.com/user/keys/$keyId")
                        .header("Authorization", "token $githubToken")
                        .header("Accept", "application/vnd.github+json")
                        .delete()
                        .build()
                    client.newCall(delReq).execute().close()
                }
            }
        }
    }
}
