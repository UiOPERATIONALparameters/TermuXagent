package com.termuxagent.data.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Web search with multiple providers:
 * - DuckDuckGo (free, no API key — scrapes HTML results)
 * - Exa (requires API key — proper REST API, returns content snippets)
 * - Firecrawl (requires API key — REST API)
 *
 * Returns formatted text: "Search results for 'QUERY':\n1. Title\n   URL\n   Snippet\n..."
 * The AI can then use web_read to fetch full page content for any result.
 */
class WebSearchTool(
    private val provider: String = "duckduckgo",
    private val exaApiKey: String = "",
    private val firecrawlApiKey: String = "",
    private val client: OkHttpClient = defaultClient()
) : AgentTool {

    override val name = "web_search"
    override val description = """Search the web for current information. Returns titles, URLs, and snippets for the top results. Use web_read to fetch the full content of any result.
Provider: $provider ${if (provider == "duckduckgo") "(free, no key needed)" else "(API key required)"}."""
    override val parametersSchema = objSchema(
        properties = mapOf(
            "query" to strProp("The search query."),
            "num_results" to intProp("Number of results to return.", min = 1, max = 10)
        ),
        required = listOf("query")
    )

    override suspend fun invoke(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val query = args["query"]?.jsonPrimitive?.content
            ?: return@withContext ToolResult(false, "Missing 'query'.")
        val numResults = args["num_results"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 10) ?: 5

        when (provider.lowercase()) {
            "duckduckgo", "ddg" -> searchDuckDuckGo(query, numResults)
            "exa" -> {
                if (exaApiKey.isBlank()) ToolResult(false, "Exa API key not set. Add it in Settings → Web Search.")
                else searchExa(query, numResults, exaApiKey)
            }
            "firecrawl" -> {
                if (firecrawlApiKey.isBlank()) ToolResult(false, "Firecrawl API key not set. Add it in Settings → Web Search.")
                else searchFirecrawl(query, numResults, firecrawlApiKey)
            }
            else -> ToolResult(false, "Unknown search provider: $provider")
        }
    }

    // ── DuckDuckGo (free, HTML scraping) ─────────────────────────────────────

    private fun searchDuckDuckGo(query: String, numResults: Int): ToolResult {
        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://html.duckduckgo.com/html/?q=$encoded"
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@runCatching ToolResult(false, "DuckDuckGo returned HTTP ${resp.code}")
                }
                val html = resp.body?.string() ?: ""
                parseDuckDuckGoResults(html, query, numResults)
            }
        }.getOrElse { e ->
            ToolResult(false, "DuckDuckGo search failed: ${e.message}")
        }
    }

    private fun parseDuckDuckGoResults(html: String, query: String, numResults: Int): ToolResult {
        val results = mutableListOf<Triple<String, String, String>>() // title, url, snippet

        // DDG HTML results have:
        // <a class="result__a" href="//duckduckgo.com/l/?uddg=ENCODED_URL&...">TITLE</a>
        // <a class="result__snippet" href="...">SNIPPET</a>
        val linkPattern = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetPattern = Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

        val links = linkPattern.findAll(html).toList()
        val snippets = snippetPattern.findAll(html).toList()

        for ((i, linkMatch) in links.withIndex()) {
            if (i >= numResults) break
            val rawUrl = linkMatch.groupValues[1]
            // Decode the actual URL from DDG redirect
            val actualUrl = decodeDdgUrl(rawUrl)
            val title = stripHtml(linkMatch.groupValues[2]).trim()
            val snippet = if (i < snippets.size) stripHtml(snippets[i].groupValues[1]).trim() else ""
            if (title.isNotBlank() && actualUrl.isNotBlank()) {
                results.add(Triple(title, actualUrl, snippet))
            }
        }

        return formatResults(query, results)
    }

    private fun decodeDdgUrl(rawUrl: String): String {
        // DDG links look like: //duckduckgo.com/l/?uddg=ENCODED_URL&rut=...
        val uddgMatch = Regex("""uddg=([^&]+)""").find(rawUrl)
        if (uddgMatch != null) {
            return java.net.URLDecoder.decode(uddgMatch.groupValues[1], "UTF-8")
        }
        // If it's already a direct URL
        if (rawUrl.startsWith("http")) return rawUrl
        if (rawUrl.startsWith("//")) return "https:$rawUrl"
        return rawUrl
    }

    // ── Exa (requires API key) ───────────────────────────────────────────────

    private fun searchExa(query: String, numResults: Int, apiKey: String): ToolResult {
        return runCatching {
            val bodyStr = Json.encodeToString(
                kotlinx.serialization.json.buildJsonObject {
                    put("query", query)
                    put("numResults", numResults)
                    put("contents", kotlinx.serialization.json.buildJsonObject {
                        put("text", kotlinx.serialization.json.buildJsonObject {
                            put("maxCharacters", 500)
                        })
                    })
                }
            )
            val req = Request.Builder()
                .url("https://api.exa.ai/search")
                .header("x-api-key", apiKey)
                .header("Content-Type", "application/json")
                .post(bodyStr.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string()?.take(500) ?: ""
                    return@runCatching ToolResult(false, "Exa returned HTTP ${resp.code}: $errBody")
                }
                val respBody = resp.body?.string() ?: ""
                val respJson = json.parseToJsonElement(respBody).jsonObject
                val resultsArr = respJson["results"]?.jsonArray ?: emptyList()
                val results = resultsArr.mapNotNull { el ->
                    val obj = el.jsonObject
                    val title = obj["title"]?.jsonPrimitive?.content ?: ""
                    val url = obj["url"]?.jsonPrimitive?.content ?: ""
                    val text = obj["text"]?.jsonPrimitive?.content ?: ""
                    if (title.isNotBlank() || url.isNotBlank()) Triple(title, url, text.take(300)) else null
                }
                formatResults(query, results)
            }
        }.getOrElse { e ->
            ToolResult(false, "Exa search failed: ${e.message}")
        }
    }

    // ── Firecrawl (requires API key) ────────────────────────────────────────

    private fun searchFirecrawl(query: String, numResults: Int, apiKey: String): ToolResult {
        return runCatching {
            val bodyStr = Json.encodeToString(
                kotlinx.serialization.json.buildJsonObject {
                    put("query", query)
                    put("limit", numResults)
                }
            )
            val req = Request.Builder()
                .url("https://api.firecrawl.dev/v1/search")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(bodyStr.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string()?.take(500) ?: ""
                    return@runCatching ToolResult(false, "Firecrawl returned HTTP ${resp.code}: $errBody")
                }
                val respBody = resp.body?.string() ?: ""
                val respJson = json.parseToJsonElement(respBody).jsonObject
                val dataArr = respJson["data"]?.jsonArray ?: emptyList()
                val results = dataArr.mapNotNull { el ->
                    val obj = el.jsonObject
                    val title = obj["title"]?.jsonPrimitive?.content ?: ""
                    val url = obj["url"]?.jsonPrimitive?.content ?: ""
                    val desc = obj["description"]?.jsonPrimitive?.content ?: ""
                    if (title.isNotBlank() || url.isNotBlank()) Triple(title, url, desc) else null
                }
                formatResults(query, results)
            }
        }.getOrElse { e ->
            ToolResult(false, "Firecrawl search failed: ${e.message}")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun formatResults(query: String, results: List<Triple<String, String, String>>): ToolResult {
        if (results.isEmpty()) {
            return ToolResult(true, "No results found for '$query'.", meta = mapOf("query" to query, "count" to "0"))
        }
        val sb = StringBuilder()
        sb.appendLine("Search results for '$query' (${results.size} results):")
        sb.appendLine()
        for ((i, r) in results.withIndex()) {
            sb.appendLine("${i + 1}. ${r.first}")
            sb.appendLine("   ${r.second}")
            if (r.third.isNotBlank()) {
                sb.appendLine("   ${r.third}")
            }
            sb.appendLine()
        }
        sb.appendLine("Use web_read to fetch the full content of any URL above.")
        return ToolResult(true, sb.toString(), meta = mapOf("query" to query, "count" to results.size.toString()))
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]*>"), "")
         .replace("&amp;", "&")
         .replace("&lt;", "<")
         .replace("&gt;", ">")
         .replace("&quot;", "\"")
         .replace("&#39;", "'")
         .replace("&nbsp;", " ")
         .replace(Regex("\\s+"), " ")
         .trim()

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Fetch a URL and extract clean readable text (strips HTML tags, scripts, styles).
 * Returns up to ~12KB of text — enough for the AI to understand the page content.
 */
class WebReadTool(
    private val client: OkHttpClient = WebSearchTool.defaultClient()
) : AgentTool {

    override val name = "web_read"
    override val description = "Fetch a web page URL and return clean readable text (HTML stripped). Use after web_search to read a specific result, or for any URL you need to understand. Returns up to ~12KB of text."
    override val parametersSchema = objSchema(
        properties = mapOf(
            "url" to strProp("Absolute URL of the page to read."),
            "max_chars" to intProp("Maximum characters to return.", min = 1000, max = 20_000)
        ),
        required = listOf("url")
    )

    override suspend fun invoke(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val url = args["url"]?.jsonPrimitive?.content
            ?: return@withContext ToolResult(false, "Missing 'url'.")
        val maxChars = args["max_chars"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1000, 20_000) ?: 12_000

        runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@runCatching ToolResult(false, "HTTP ${resp.code} ${resp.message}", meta = mapOf("url" to url))
                }
                val html = resp.body?.string() ?: ""
                val text = htmlToText(html, maxChars)
                val title = extractTitle(html)
                val out = buildString {
                    if (title.isNotBlank()) {
                        appendLine("Title: $title")
                        appendLine()
                    }
                    appendLine(text)
                }
                ToolResult(true, out, meta = mapOf("url" to url, "chars" to text.length.toString()))
            }
        }.getOrElse { e ->
            ToolResult(false, "Failed to read $url: ${e.message}", meta = mapOf("url" to url))
        }
    }

    private fun htmlToText(html: String, maxChars: Int): String {
        var s = html
        // Remove script, style, noscript, svg, nav, footer, header blocks
        s = s.replace(Regex("<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        // Replace block-level closings with newlines
        s = s.replace(Regex("</(p|div|br|h[1-6]|li|tr|blockquote)>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        // Remove all remaining tags
        s = s.replace(Regex("<[^>]*>"), "")
        // Decode HTML entities
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
             .replace("&#x27;", "'").replace("&mdash;", "—").replace("&ndash;", "–")
        // Collapse whitespace
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        s = s.trim()
        // Truncate
        if (s.length > maxChars) {
            s = s.take(maxChars) + "\n…[truncated at $maxChars chars]"
        }
        return s
    }

    private fun extractTitle(html: String): String {
        val match = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(html)
        return match?.groupValues?.get(1)?.trim()?.take(200) ?: ""
    }
}
