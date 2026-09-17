package net.nostr.hench.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.nostr.hench.cvm.ToolCallContext
import net.nostr.hench.tools.ytdlp.UrlSafety

/**
 * A single MCP tool. Implementations live under tools/ (e.g. ytdlp/YtdlpTool,
 * browser/WebFetchTool). The CVM layer routes `tools/call` to a Tool by name
 * and hands parsed args. Tools know nothing about CVM/DVM transport — they
 * take args, return a result, and own their pricing + preflight contract so
 * the payment gate can invoice before execution without tool-specific code.
 */
interface Tool {
    val name: String
    val description: String

    /** MCP inputSchema (JSON Schema) published in tools/list. */
    fun inputSchema(): JsonElement

    /**
     * Run the tool for a fresh (uncached) call. [ctx] carries the idempotency
     * key the tool uses to persist payment/download state. [onProgress] emits
     * a notifications/progress payload string the CVM layer wraps and sends.
     * The dispatcher handles replays (cached results / in-flight busy) before
     * this is ever called.
     */
    suspend fun execute(ctx: ToolCallContext, onProgress: (progressJson: String) -> Unit): ToolResult

    /**
     * Advertised flat price range in whole sats (capability `cap` tags +
     * validation bounds). Null = this tool is not offered as a paid capability.
     */
    val priceRangeSats: LongRange? get() = null

    /**
     * Actual price in whole sats for these specific args; must lie within
     * [priceRangeSats]. Null = cannot quote (reject before invoicing).
     */
    fun quoteSats(args: JsonObject): Long? = priceRangeSats?.first

    /**
     * Cheap validation run before any invoice is created (the payment gate)
     * and again at execution time (defense in depth). Null = ok.
     */
    fun preflight(args: JsonObject): UrlSafety.Result.Reject? = null
}

/** One MCP content block: text | resource_link | image. */
@Serializable
sealed class ContentBlock {
    @Serializable
    data class Text(val text: String) : ContentBlock()

    @Serializable
    data class ResourceLink(
        val uri: String,
        val name: String,
        val mimeType: String,
        val description: String? = null,
    ) : ContentBlock()
}

/** MCP CallToolResult: content blocks + isError flag. */
@Serializable
data class ToolResult(
    val content: List<ContentBlock> = emptyList(),
    val isError: Boolean = false,
) {
    companion object {
        fun text(body: String, isError: Boolean = false) =
            ToolResult(listOf(ContentBlock.Text(body)), isError)

        fun errorJson(code: String, message: String) = ToolResult(
            listOf(ContentBlock.Text("""{"code":"$code","message":${quote(message)}}""")),
            isError = true,
        )

        private fun quote(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
