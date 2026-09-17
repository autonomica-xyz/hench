package net.nostr.hench.tools.ytdlp

import android.content.Context
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.nostr.hench.auth.CookieStore
import net.nostr.hench.blossom.BlossomServerSelector
import net.nostr.hench.blossom.HttpBlossomClient
import net.nostr.hench.cvm.ToolCallContext
import net.nostr.hench.runtime.YtdlpBlossomJobExecutor
import net.nostr.hench.settings.WorkerSettings
import net.nostr.hench.tools.ContentBlock
import net.nostr.hench.tools.Tool
import net.nostr.hench.tools.ToolResult
import net.nostr.hench.worker.DeliveryMode
import net.nostr.hench.worker.JobArtifact
import net.nostr.hench.worker.JobRecord
import net.nostr.hench.worker.JobRequest
import net.nostr.hench.worker.PaymentSessionStoreI

/**
 * The download_media MCP tool. Validates the URL (network safety contract),
 * runs yt-dlp via the ported executor under a hard execution timeout, and
 * returns a Blossom-hosted artifact as text + resource_link.
 *
 * Payment gating has moved out of the tool into the CEP-8 gate. This class only
 * writes terminal JobRecords
 * (COMPLETED/FAILED + result cache) to PaymentSessionStoreI; it never creates an
 * invoice or calls a wallet.
 */
class YtdlpTool(
    private val context: Context?,
    private val settings: WorkerSettings,
    private val cookieStore: CookieStore?,
    private val store: PaymentSessionStoreI,
    private val executorProvider: (() -> net.nostr.hench.worker.JobExecutor)? = null,
    private val executionBudgetSecs: Long = EXECUTION_BUDGET_SECS,
    private val quoteCalculator: net.nostr.hench.pricing.QuoteCalculator = net.nostr.hench.pricing.QuoteCalculator(settings),
) : Tool {

    override val name: String = "download_media"
    override val description: String =
        "Download audio or video from a URL (YouTube and other platforms) via yt-dlp. Returns a hosted file URL."

    /** Advertised price range: cheapest accepted mode/platform floor to most expensive ceiling. */
    override val priceRangeSats: LongRange by lazy { computePriceRange() }

    override fun quoteSats(args: JsonObject): Long? {
        val url = args.str("url") ?: return null
        val modeStr = args.str("mode") ?: return null
        val mode = when (modeStr.lowercase()) {
            "audio" -> DeliveryMode.AUDIO_ONLY
            "full" -> DeliveryMode.FULL
            else -> return null
        }
        return quoteCalculator.quote(JobRequest("quote", url, mode)).getOrNull()?.let { ceilSats(it.amountMsat) }
    }

    override fun preflight(args: JsonObject): UrlSafety.Result.Reject? = YtdlpPreflight.validate(args, settings)

    /** Cheapest/most-expensive accepted quote over modes × platforms, in sats. */
    private fun computePriceRange(): LongRange = runCatching {
        val modes = buildList {
            if (settings.acceptAudio) add(DeliveryMode.AUDIO_ONLY)
            if (settings.acceptVideo) add(DeliveryMode.FULL)
        }.ifEmpty { return LongRange(0, 0) }
        val platforms = (settings.allowedPlatforms.ifEmpty { net.nostr.hench.auth.Platform.entries })
            .filter { it !in settings.blockedPlatforms }
        val floor = platforms.minByOrNull { it.defaultRiskPremium } ?: net.nostr.hench.auth.Platform.YOUTUBE
        val ceiling = platforms.maxByOrNull { it.defaultRiskPremium } ?: net.nostr.hench.auth.Platform.INSTAGRAM
        val sats = modes.flatMap { mode ->
            listOfNotNull(
                quoteCalculator.quote(JobRequest("cap-floor", "https://${floor.domains[0]}/x", mode)).getOrNull(),
                quoteCalculator.quote(JobRequest("cap-ceiling", "https://${ceiling.domains[0]}/x", mode)).getOrNull(),
            ).map { ceilSats(it.amountMsat) }
        }
        if (sats.isEmpty()) LongRange(0, 0) else LongRange(sats.min(), sats.max())
    }.getOrDefault(LongRange(0, 0))

    override fun inputSchema(): JsonElement = JSON.parseToJsonElement(
        """
        {"type":"object","required":["url","mode"],"properties":{
          "url":{"type":"string","description":"http(s) URL of the media page"},
          "mode":{"type":"string","enum":["audio","full"],"description":"audio = mp3 extract; full = best mp4"},
          "blossom_server":{"type":"string","description":"optional Blossom server override (must be in worker allowlist)"}
        }}
        """.trimIndent()
    )

    override suspend fun execute(
        ctx: ToolCallContext,
        onProgress: (progressJson: String) -> Unit,
    ): ToolResult {
        val args = ctx.args

        YtdlpPreflight.validate(args, settings)?.let { return it.toToolError() }

        val url = args.str("url") ?: return ToolResult.errorJson("INVALID_PARAMS", "missing url")
        val modeStr = args.str("mode") ?: return ToolResult.errorJson("INVALID_PARAMS", "missing mode")
        val mode = when (modeStr.lowercase()) {
            "audio" -> DeliveryMode.AUDIO_ONLY
            "full" -> DeliveryMode.FULL
            else -> return ToolResult.errorJson("INVALID_PARAMS", "mode must be 'audio' or 'full'")
        }
        val blossomOverride = args.str("blossom_server")

        val request = JobRequest(
            id = ctx.idempotencyKey,
            url = url,
            mode = mode,
            durationSeconds = null,
            blossomServerUrl = blossomOverride,
            socketTimeoutSecs = executionBudgetSecs,
        )
        val executor = executorProvider?.invoke() ?: defaultExecutor()

        onProgress(progress("PROCESSING"))
        return try {
            val artifact = withTimeout(executionBudgetSecs * 1_000L) { executor.execute(request) }
            val ok = artifact.toToolResult(mode)
            store.put(record(ctx, request, JobRecord.STATUS_COMPLETED, ok))
            ok
        } catch (e: TimeoutCancellationException) {
            val err = ToolResult.errorJson("TIMEOUT", "execution timed out after ${executionBudgetSecs}s")
            store.put(record(ctx, request, JobRecord.STATUS_FAILED, err))
            err
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val code = if (isAuthFailure(e.message)) "AUTH_FAILURE" else "DOWNLOAD"
            val err = ToolResult.errorJson(code, e.message ?: "download failed")
            store.put(record(ctx, request, JobRecord.STATUS_FAILED, err))
            err
        }
    }

    private fun defaultExecutor(): YtdlpBlossomJobExecutor = YtdlpBlossomJobExecutor(
        context = requireNotNull(context) { "context required when no executorProvider is set" },
        blossomServerSelector = BlossomServerSelector(settings.blossomUrls),
        cookieStore = cookieStore,
        useCookies = settings.useCookies,
        blossomClientFactory = { server, timeout ->
            HttpBlossomClient(server, authSecretKeyHex = settings.workerSecretKeyHex, callTimeoutSecs = timeout)
        },
    )

    private fun record(
        ctx: ToolCallContext,
        request: JobRequest,
        status: String,
        result: ToolResult? = null,
    ) = JobRecord(
        idempotencyKey = ctx.idempotencyKey,
        canonicalInvocationId = ctx.canonicalInvocationId,
        jobRequestJson = JSON.encodeToString(request),
        argsJson = JSON.encodeToString(JsonObject.serializer(), ctx.args),
        status = status,
        terminalResultJson = result?.let { JSON.encodeToString(it) },
    )

    private fun progress(status: String, extra: Map<String, Any> = emptyMap()): String =
        encode(buildJsonObject {
            put("status", status)
            extra.forEach { (k, v) ->
                when (v) {
                    is String -> put(k, v)
                    is Number -> put(k, v.toLong())
                    else -> put(k, v.toString())
                }
            }
        })

    private fun JobArtifact.toToolResult(mode: DeliveryMode): ToolResult {
        val mime = if (mode == DeliveryMode.AUDIO_ONLY) "audio/mpeg" else "video/mp4"
        val body = buildJsonObject {
            put("url", url)
            put("sha256", sha256)
            put("sizeBytes", sizeBytes)
            put("mimeType", mime)
        }
        val name = url.substringAfterLast('/').ifBlank { "media" } +
            if (mode == DeliveryMode.AUDIO_ONLY) ".mp3" else ".mp4"
        return ToolResult(
            content = listOf(
                ContentBlock.Text(encode(body)),
                ContentBlock.ResourceLink(uri = url, name = name, mimeType = mime, description = "Downloaded by hench download_media"),
            ),
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    companion object {
        const val EXECUTION_BUDGET_SECS = 600L
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private fun encode(el: JsonElement) = JSON.encodeToString(JsonElement.serializer(), el)

        private fun ceilSats(msat: Long): Long = (msat + 999L) / 1_000L

        private fun isAuthFailure(message: String?): Boolean {
            val l = message.orEmpty().lowercase()
            return l.contains("http error 403") || l.contains("http error 401") ||
                l.contains("sign in") || l.contains("login required") ||
                l.contains("authentication") || l.contains("private video") ||
                (l.contains("cookies") && l.contains("required"))
        }

        private fun UrlSafety.Result.reject(): UrlSafety.Result.Reject? = this as? UrlSafety.Result.Reject
        private fun UrlSafety.Result.Reject.toToolError(): ToolResult = ToolResult.errorJson(code, reason)
    }
}
