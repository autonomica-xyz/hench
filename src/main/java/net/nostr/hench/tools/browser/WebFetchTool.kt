package net.nostr.hench.tools.browser

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.nostr.hench.cvm.ToolCallContext
import net.nostr.hench.tools.Tool
import net.nostr.hench.tools.ToolResult
import net.nostr.hench.tools.ytdlp.UrlSafety
import net.nostr.hench.worker.JobRecord
import net.nostr.hench.worker.PaymentSessionStoreI

/**
 * The web_fetch MCP tool: load a URL in a real on-device browser (the user's
 * WebView cookies included, minus denied sensitive hosts) and return the page
 * as title + innerText for agent consumption. This is the phone's value: real
 * fingerprint, real sessions, residential IP.
 */
class WebFetchTool(
    private val context: Context?,
    private val store: PaymentSessionStoreI,
    private val priceSats: Long = PRICE_SATS,
    private val maxTextChars: Int = MAX_TEXT_CHARS,
) : Tool {

    override val name: String = "web_fetch"
    override val description: String =
        "Fetch a URL in a real mobile browser (Chrome WebView, real fingerprint and sessions) and return the page text for an agent. Handles Cloudflare/login walls better than datacenter fetchers."
    override val priceRangeSats: LongRange = priceSats..priceSats
    override fun quoteSats(args: JsonObject): Long = priceSats

    override fun preflight(args: JsonObject): UrlSafety.Result.Reject? {
        val url = args.str("url") ?: return UrlSafety.Result.Reject("INVALID_PARAMS", "missing url")
        return BrowserUrlPolicy.check(url)
    }

    override fun inputSchema(): JsonElement = Json.parseToJsonElement(
        """
        {"type":"object","required":["url"],"properties":{
          "url":{"type":"string","description":"http(s) URL to fetch"},
          "wait_for":{"type":"string","description":"optional CSS selector to wait for before reading the page"},
          "timeout_secs":{"type":"integer","description":"optional load timeout in seconds (default 45)"}
        }}
        """.trimIndent()
    )

    override suspend fun execute(
        ctx: ToolCallContext,
        onProgress: (progressJson: String) -> Unit,
    ): ToolResult {
        preflight(ctx.args)?.let { return it.toToolError() }
        val url = ctx.args.str("url") ?: return ToolResult.errorJson("INVALID_PARAMS", "missing url")
        val waitFor = ctx.args.str("wait_for")
        val timeoutSecs = (ctx.args["timeout_secs"] as? JsonPrimitive)?.content?.toLongOrNull()?.coerceIn(5, 120) ?: 45L

        return runCatching {
            val page = BrowserRenderer(requireNotNull(context) { "context required" }).load(
                url = url,
                timeoutMs = timeoutSecs * 1_000,
                waitForSelector = waitFor,
            )
            val truncated = page.text.length > maxTextChars
            val text = if (truncated) page.text.take(maxTextChars) else page.text
            val body = buildJsonObject {
                put("url", url)
                put("finalUrl", page.finalUrl)
                put("title", page.title)
                put("loadFailed", page.loadFailed)
                page.errorMessage?.let { put("error", it) }
                put("truncated", truncated)
                put("text", text)
            }
            val ok = ToolResult.text(JSON.encodeToString(JsonObject.serializer(), body), isError = page.loadFailed)
            store.put(
                JobRecord(
                    idempotencyKey = ctx.idempotencyKey,
                    canonicalInvocationId = ctx.canonicalInvocationId,
                    jobRequestJson = JSON.encodeToString(JsonObject.serializer(), ctx.args),
                    argsJson = JSON.encodeToString(JsonObject.serializer(), ctx.args),
                    status = JobRecord.STATUS_COMPLETED,
                    terminalResultJson = JSON.encodeToString(ok),
                )
            )
            ok
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                ToolResult.errorJson("TIMEOUT", "page did not finish loading within ${timeoutSecs}s")
            } else {
                ToolResult.errorJson("DOWNLOAD", e.message ?: "page load failed")
            }
        }
    }

    private fun UrlSafety.Result.Reject.toToolError(): ToolResult =
        ToolResult.errorJson(code, reason)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    companion object {
        const val PRICE_SATS = 3L

        /** CEP-8 message-size sanity: innerText is capped, truncation flagged in the payload. */
        const val MAX_TEXT_CHARS = 100_000
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
