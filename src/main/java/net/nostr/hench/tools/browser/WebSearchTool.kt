package net.nostr.hench.tools.browser

import android.content.Context
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import net.nostr.hench.cvm.ToolCallContext
import net.nostr.hench.tools.Tool
import net.nostr.hench.tools.ToolResult
import net.nostr.hench.tools.ytdlp.UrlSafety
import net.nostr.hench.worker.JobRecord
import net.nostr.hench.worker.PaymentSessionStoreI
import java.net.URLEncoder

/**
 * The web_search MCP tool: run a Google search in the phone's real mobile
 * browser (isolated unauthenticated profile, residential IP) and return the
 * parsed SERP as {title,url,snippet} JSON for an agent. Same session-isolation
 * and URL-policy guarantees as web_fetch — see BrowserRenderer.
 */
class WebSearchTool(
    private val context: Context?,
    private val store: PaymentSessionStoreI,
    private val priceSats: Long = PRICE_SATS,
) : Tool {

    override val name: String = "web_search"
    override val description: String =
        "Google search rendered in the phone's real mobile browser (isolated unauthenticated profile, residential IP). Returns a JSON list of {title,url,snippet} results for agent use."
    override val priceRangeSats: LongRange = priceSats..priceSats
    override fun quoteSats(args: JsonObject): Long = priceSats

    override fun preflight(args: JsonObject): UrlSafety.Result.Reject? {
        val query = args.str("query")
        if (query.isNullOrBlank()) {
            return UrlSafety.Result.Reject("INVALID_PARAMS", "missing query")
        }
        return null
    }

    override fun inputSchema(): JsonElement = Json.parseToJsonElement(
        """
        {"type":"object","required":["query"],"properties":{
          "query":{"type":"string","description":"search query"},
          "limit":{"type":"integer","description":"max results to return, 1-30 (default 10)"}
        }}
        """.trimIndent()
    )

    override suspend fun execute(
        ctx: ToolCallContext,
        onProgress: (progressJson: String) -> Unit,
    ): ToolResult {
        preflight(ctx.args)?.let { return it.toToolError() }
        val query = ctx.args.str("query") ?: return ToolResult.errorJson("INVALID_PARAMS", "missing query")
        val limit = (ctx.args["limit"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(1, 30) ?: 10

        return runCatching {
            val url = "https://www.google.com/search?q=" +
                URLEncoder.encode(query, "UTF-8") + "&num=$limit&hl=en"
            val page = BrowserRenderer(requireNotNull(context) { "context required" }).load(
                url = url,
                timeoutMs = 45_000,
                extractScript = SERP_SCRIPT,
            )
            val result = if (page.loadFailed) {
                ToolResult.errorJson("DOWNLOAD", page.errorMessage ?: "page load failed")
            } else {
                val results = parseResults(page.extracted, limit)
                if (results.isEmpty()) {
                    ToolResult.errorJson("EMPTY", "no results parsed (possible consent page or block)")
                } else {
                    val body = buildJsonObject {
                        put("query", query)
                        put(
                            "results",
                            JsonArray(
                                results.map { r ->
                                    buildJsonObject {
                                        put("title", r.title)
                                        put("url", r.url)
                                        put("snippet", r.snippet)
                                    }
                                },
                            ),
                        )
                    }
                    ToolResult.text(JSON.encodeToString(JsonObject.serializer(), body))
                }
            }
            store.put(
                JobRecord(
                    idempotencyKey = ctx.idempotencyKey,
                    canonicalInvocationId = ctx.canonicalInvocationId,
                    jobRequestJson = JSON.encodeToString(JsonObject.serializer(), ctx.args),
                    argsJson = JSON.encodeToString(JsonObject.serializer(), ctx.args),
                    status = JobRecord.STATUS_COMPLETED,
                    terminalResultJson = JSON.encodeToString(result),
                )
            )
            result
        }.getOrElse { e ->
            when (e) {
                is UrlPolicyRejected -> ToolResult.errorJson(e.code, e.message ?: "destination blocked")
                is BrowserIsolationUnsupported ->
                    ToolResult.errorJson("BROWSER_UNAVAILABLE", e.message ?: "WebView profile isolation unsupported")
                is IllegalArgumentException -> ToolResult.errorJson("INVALID_PARAMS", e.message ?: "bad argument")
                is TimeoutCancellationException ->
                    ToolResult.errorJson("TIMEOUT", "search page did not finish loading within 45s")
                is kotlinx.coroutines.CancellationException -> throw e
                else -> ToolResult.errorJson("DOWNLOAD", e.message ?: "search failed")
            }
        }
    }

    private fun UrlSafety.Result.Reject.toToolError(): ToolResult =
        ToolResult.errorJson(code, reason)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    companion object {
        const val PRICE_SATS = 3L

        /** One parsed SERP row: {title,url,snippet}. */
        data class SerpResult(val title: String, val url: String, val snippet: String)

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * Parse the SERP_SCRIPT payload: array elements that are objects with
         * non-blank `t` and http(s) `u`; Google self-links are dropped, URLs
         * deduped, capped at [limit]. Pure — unit-tested without Android.
         */
        fun parseResults(extracted: JsonElement?, limit: Int): List<SerpResult> {
            // SERP_SCRIPT returns JSON.stringify(...): evaluateJavascript hands
            // that back as a JSON string primitive, so unwrap one level first.
            val root = (extracted as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull
                ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
                ?: extracted
            val arr = root as? JsonArray ?: return emptyList()
            val seen = mutableSetOf<String>()
            val out = mutableListOf<SerpResult>()
            for (el in arr) {
                if (out.size >= limit) break
                val obj = el as? JsonObject ?: continue
                val t = (obj["t"] as? JsonPrimitive)?.contentOrNull?.trim() ?: continue
                if (t.isEmpty()) continue
                val u = (obj["u"] as? JsonPrimitive)?.contentOrNull?.trim() ?: continue
                if (!u.startsWith("http://") && !u.startsWith("https://")) continue
                val host = runCatching { java.net.URI(u).host?.lowercase() }.getOrNull() ?: continue
                if (host == "google.com" || host == "www.google.com" || host == "m.google.com" ||
                    host == "googleusercontent.com" || host.endsWith(".google.com") ||
                    host.endsWith(".googleusercontent.com")
                ) continue
                if (!seen.add(u)) continue
                val s = (obj["s"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                out += SerpResult(t, u, s)
            }
            return out
        }
        /**
         * Bounded SERP extraction: at most 30 result anchors (those containing
         * an h3 or role=heading descendant), snippet from the anchor's closest
         * container div with the heading text removed, sliced to 300 chars.
         * `/url?q=` redirect wrappers are unwrapped via URL searchParams.
         * Returns JSON.stringify of [{t,u,s}] — small payload only.
         */
        private const val SERP_SCRIPT = """
(function(){
  var out = [];
  var anchors = document.querySelectorAll('a[href]');
  for (var i = 0; i < anchors.length && out.length < 30; i++) {
    var a = anchors[i];
    var h = a.querySelector('h3') || a.querySelector('div[role="heading"]');
    if (!h) continue;
    var u = a.href;
    try {
      var url = new URL(u, location.origin);
      if (url.pathname === '/url' || url.pathname === '/url/') {
        var q = url.searchParams.get('q');
        if (q) u = q;
      }
    } catch (e) {}
    var title = (h.innerText || h.textContent || '').trim();
    if (!title) continue;
    var s = '';
    var box = a.closest('div');
    if (box) {
      s = (box.innerText || box.textContent || '');
      if (title) s = s.replace(title, '');
      s = s.replace(/^\s+|\s+$/g, '');
      if (s.length > 300) s = s.slice(0, 300);
    }
    out.push({t: title, u: u, s: s});
  }
  return JSON.stringify(out);
})()
"""

        // shortcut: EU IPs can hit Google's consent wall (CONSENT=YES+ cookie);
        // not handled — if search calls fail with EMPTY on EU exits, seed the
        // isolated profile's cookie jar before load.
    }
}
