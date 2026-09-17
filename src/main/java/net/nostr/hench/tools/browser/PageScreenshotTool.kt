package net.nostr.hench.tools.browser

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.nostr.hench.blossom.BlossomServerSelector
import net.nostr.hench.blossom.HttpBlossomClient
import net.nostr.hench.cvm.ToolCallContext
import net.nostr.hench.settings.WorkerSettings
import net.nostr.hench.tools.ContentBlock
import net.nostr.hench.tools.Tool
import net.nostr.hench.tools.ToolResult
import net.nostr.hench.tools.ytdlp.UrlSafety
import net.nostr.hench.worker.JobRecord
import net.nostr.hench.worker.JobRequest
import net.nostr.hench.worker.PaymentSessionStoreI

/**
 * The page_screenshot MCP tool: render a URL in a real on-device browser and
 * return a PNG hosted on Blossom. Real JS execution + real sessions — visual
 * ground truth for agents (UI checks, listing verification, page monitoring).
 */
class PageScreenshotTool(
    private val context: Context?,
    private val settings: WorkerSettings,
    private val store: PaymentSessionStoreI,
    private val priceSats: Long = PRICE_SATS,
    private val blossomClientFactory: (String, Long) -> HttpBlossomClient = { server, timeout ->
        HttpBlossomClient(server, authSecretKeyHex = settings.workerSecretKeyHex, callTimeoutSecs = timeout)
    },
    private val blossomServerSelector: BlossomServerSelector = BlossomServerSelector(settings.blossomUrls),
) : Tool {

    override val name: String = "page_screenshot"
    override val description: String =
        "Render a URL in a real mobile browser (full JS, real sessions) and return a screenshot PNG. Viewport-sized; use wait_for for JS-heavy pages."
    override val priceRangeSats: LongRange = priceSats..priceSats
    override fun quoteSats(args: JsonObject): Long = priceSats

    override fun preflight(args: JsonObject): UrlSafety.Result.Reject? {
        val url = args.str("url") ?: return UrlSafety.Result.Reject("INVALID_PARAMS", "missing url")
        return BrowserUrlPolicy.check(url)
    }

    override fun inputSchema(): JsonElement = Json.parseToJsonElement(
        """
        {"type":"object","required":["url"],"properties":{
          "url":{"type":"string","description":"http(s) URL to render"},
          "width":{"type":"integer","description":"viewport width (default 1280, max 1920)"},
          "height":{"type":"integer","description":"viewport height (default 800, max 1920)"},
          "wait_for":{"type":"string","description":"optional CSS selector to wait for before capturing"},
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
        val width = (ctx.args["width"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(320, 1920) ?: 1280
        val height = (ctx.args["height"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(240, 1920) ?: 800
        val timeoutSecs = (ctx.args["timeout_secs"] as? JsonPrimitive)?.content?.toLongOrNull()?.coerceIn(5, 120) ?: 45L

        return try {
            val (page, bitmap) = BrowserRenderer(requireNotNull(context) { "context required" }).screenshot(
                url = url,
                width = width,
                height = height,
                timeoutMs = timeoutSecs * 1_000,
                waitForSelector = waitFor,
            )

            val file = withContext(Dispatchers.IO) {
                File.createTempFile("cvm-shot-", ".png", requireNotNull(context).cacheDir).apply {
                    outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
                }
            }
            val uploaded = try {
                upload(ctx, file)
            } finally {
                file.delete()
            }

            val meta = buildJsonObject {
                put("url", url)
                put("finalUrl", page.finalUrl)
                put("title", page.title)
                put("width", width)
                put("height", height)
                put("url_result", uploaded.url)
                put("sha256", uploaded.sha256)
                put("sizeBytes", uploaded.sizeBytes)
                put("mimeType", "image/png")
            }
            val ok = ToolResult(
                content = listOf(
                    ContentBlock.Text(JSON.encodeToString(JsonObject.serializer(), meta)),
                    ContentBlock.ResourceLink(
                        uri = uploaded.url,
                        name = uploaded.sha256.take(16) + ".png",
                        mimeType = "image/png",
                        description = "Rendered by hench page_screenshot",
                    ),
                ),
                isError = page.loadFailed,
            )
            store.put(record(ctx, ok))
            ok
        } catch (e: TimeoutCancellationException) {
            val err = ToolResult.errorJson("TIMEOUT", "page did not finish loading within ${timeoutSecs}s")
            store.put(record(ctx, err))
            err
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val err = ToolResult.errorJson("UPLOAD", e.message ?: "screenshot/upload failed")
            store.put(record(ctx, err))
            err
        }
    }

    private suspend fun upload(ctx: ToolCallContext, file: File) =
        uploadWithRetry(ctx, file)

    private suspend fun uploadWithRetry(ctx: ToolCallContext, file: File): net.nostr.hench.blossom.BlossomUploadResult {
        val servers = blossomServerSelector.serverCandidatesFor(
            JobRequest(id = ctx.idempotencyKey, url = "", mode = net.nostr.hench.worker.DeliveryMode.FULL),
        )
        var lastError: Throwable? = null
        for (server in servers) {
            repeat(UPLOAD_ATTEMPTS) { attempt ->
                try {
                    val result = blossomClientFactory(server, 60L).upload(file)
                    // Verify-after-upload: some Blossom frontends ACK a PUT the
                    // storage layer never actually serves (seen on-device with
                    // an image/png). Treat an unservable blob as a failed upload
                    // and fall through to the next server.
                    if (!blobIsServed(result.url)) {
                        throw IllegalStateException("server acked upload but blob is not servable: ${result.url}")
                    }
                    return result
                } catch (t: TimeoutCancellationException) {
                    throw t
                } catch (t: Throwable) {
                    lastError = t
                    if (attempt + 1 < UPLOAD_ATTEMPTS) kotlinx.coroutines.delay(UPLOAD_RETRY_DELAY_MS)
                }
            }
        }
        throw lastError ?: IllegalStateException("no Blossom servers configured")
    }

    private fun blobIsServed(url: String): Boolean = runCatching {
        okhttp3.OkHttpClient.Builder()
            .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
            .newCall(okhttp3.Request.Builder().url(url).head().build())
            .execute()
            .use { it.isSuccessful }
    }.getOrDefault(false)

    private fun record(ctx: ToolCallContext, result: ToolResult) = JobRecord(
        idempotencyKey = ctx.idempotencyKey,
        canonicalInvocationId = ctx.canonicalInvocationId,
        jobRequestJson = JSON.encodeToString(JsonObject.serializer(), ctx.args),
        argsJson = JSON.encodeToString(JsonObject.serializer(), ctx.args),
        status = JobRecord.STATUS_COMPLETED,
        terminalResultJson = JSON.encodeToString(result),
    )

    private fun UrlSafety.Result.Reject.toToolError(): ToolResult =
        ToolResult.errorJson(code, reason)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    companion object {
        const val PRICE_SATS = 5L
        private const val UPLOAD_ATTEMPTS = 3
        private const val UPLOAD_RETRY_DELAY_MS = 1_000L
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
