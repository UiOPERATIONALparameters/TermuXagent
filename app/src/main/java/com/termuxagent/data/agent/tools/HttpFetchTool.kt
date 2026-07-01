package com.termuxagent.data.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Perform HTTP requests. This is the agent's window to the open internet —
 * fetch documentation, download datasets, call other APIs. Restricted to
 * GET/POST/PUT/DELETE to keep the surface area small.
 *
 * Output is the status line + first ~16KB of the body (text). For binary
 * downloads the agent should use shell + curl-equivalent: not available on
 * stock Android, so for binary we tell them to fetch via this tool and
 * save_file via write_file is not appropriate — we leave a clear error.
 */
class HttpFetchTool(
    private val client: OkHttpClient = defaultClient()
) : AgentTool {

    override val name = "http_fetch"
    override val description = """Perform an HTTP request to any URL. Returns status code + body (truncated to 16KB).
Use for: fetching docs, calling REST APIs, downloading text. For binary files, use shell to fetch via a heredoc-curl substitute — note stock Android has no curl, so prefer this tool for any HTTP."""
    override val parametersSchema = objSchema(
        properties = mapOf(
            "url" to strProp("Absolute URL, e.g. 'https://api.github.com/repos/owner/repo'."),
            "method" to strProp("HTTP method.", enum = listOf("GET", "POST", "PUT", "DELETE")),
            "headers" to strProp("Optional JSON object of header name → value, e.g. '{\"Accept\":\"application/json\"}'."),
            "body" to strProp("Optional request body (string). Sent as-is."),
            "timeout_ms" to intProp("Per-request timeout in ms.", min = 1000, max = 60_000)
        ),
        required = listOf("url")
    )

    override suspend fun invoke(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val url = args["url"]?.jsonPrimitive?.content
            ?: return@withContext ToolResult(false, "Missing 'url'.")
        val method = (args["method"]?.jsonPrimitive?.content ?: "GET").uppercase()
        val headersRaw = args["headers"]?.jsonPrimitive?.content
        val body = args["body"]?.jsonPrimitive?.content
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1000, 60_000) ?: 30_000

        if (method !in setOf("GET", "POST", "PUT", "DELETE")) {
            return@withContext ToolResult(false, "Unsupported method: $method")
        }

        val reqTimeoutClient = client.newBuilder()
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()

        val reqBuilder = Request.Builder().url(url)
        // Parse headers
        if (!headersRaw.isNullOrBlank()) {
            runCatching {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val obj = json.parseToJsonElement(headersRaw).jsonObject
                obj.forEach { (k, v) -> reqBuilder.header(k, v.jsonPrimitive.content) }
            }.onFailure { e ->
                return@withContext ToolResult(false, "Invalid headers JSON: ${e.message}")
            }
        }

        when (method) {
            "GET" -> reqBuilder.get()
            "DELETE" -> reqBuilder.delete()
            else -> {
                val mt = "application/json; charset=utf-8".toMediaTypeOrNull()
                reqBuilder.method(method, (body ?: "").toRequestBody(mt))
            }
        }

        runCatching {
            reqTimeoutClient.newCall(reqBuilder.build()).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                val truncated = if (respBody.length > 16_000) {
                    respBody.take(16_000) + "\n…[truncated, ${respBody.length} chars total]"
                } else respBody
                val out = "HTTP ${resp.code} ${resp.message}\n\n$truncated"
                ToolResult(resp.isSuccessful, out, meta = mapOf("url" to url, "status" to resp.code.toString()))
            }
        }.getOrElse { e ->
            ToolResult(false, "Request failed: ${e.message}", meta = mapOf("url" to url))
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
