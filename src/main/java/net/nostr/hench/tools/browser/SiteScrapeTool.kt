package net.nostr.hench.tools.browser

import android.content.Context
import java.io.File
import java.io.Writer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.nostr.hench.blossom.BlossomServerSelector
import net.nostr.hench.blossom.BlossomUploadResult
import net.nostr.hench.blossom.HttpBlossomClient
import net.nostr.hench.blossom.uploadVerified
import net.nostr.hench.cvm.ToolCallContext
import net.nostr.hench.settings.WorkerSettings
import net.nostr.hench.tools.ContentBlock
import net.nostr.hench.tools.Tool
import net.nostr.hench.tools.ToolResult
import net.nostr.hench.tools.ytdlp.UrlSafety
import net.nostr.hench.worker.DeliveryMode
import net.nostr.hench.worker.JobRecord
import net.nostr.hench.worker.JobRequest
import net.nostr.hench.worker.PaymentSessionStoreI
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The site_scrape MCP tool: crawl a website breadth-first in the on-device
 * browser and deliver every page's title + text as one JSONL file on Blossom.
 * Each page is a web_fetch render (isolated unauthenticated profile, URL policy
 * on every request); the crawl follows links on the host the start URL lands
 * on, minus fragments and links to obvious non-page files.
 *
 * Billed up front per requested page (CEP-8 invoices before execution), so a
 * site smaller than max_pages costs the same. The crawl stops at max_pages or
 * [CRAWL_BUDGET_MS] and delivers what it has: it must finish inside CvmServer's
 * 600s execution budget with time left for the upload.
 */
class SiteScrapeTool(
    private val context: Context?,
    private val settings: WorkerSettings,
    private val store: PaymentSessionStoreI,
    /** Test seam; null renders each page in [renderer]. */
    private val loadPage: (suspend (url: String, timeoutMs: Long) -> BrowserRenderer.Page)? = null,
    private val blossomServerSelector: BlossomServerSelector = BlossomServerSelector(settings.blossomUrls),
    private val upload: suspend (File) -> BlossomUploadResult = { file ->
        uploadVerified(file, blossomServerSelector.serverCandidatesFor(JobRequest("site_scrape", "", DeliveryMode.FULL))) {
            HttpBlossomClient(it, authSecretKeyHex = settings.workerSecretKeyHex, callTimeoutSecs = 60L)
        }
    },
) : Tool {

    /** Shared across pages so the checked-fetch OkHttp client keeps its connections. */
    private val renderer by lazy { BrowserRenderer(requireNotNull(context) { "context required" }) }

    override val name: String = "site_scrape"
    override val description: String =
        "Crawl a website in a real mobile browser (full JS, unauthenticated session): follows same-host links breadth-first and delivers every page's title and text as a hosted JSONL file. Billed per page of max_pages."
    override val priceRangeSats: LongRange = PRICE_PER_PAGE_SATS..PRICE_PER_PAGE_SATS * MAX_PAGES
    override fun quoteSats(args: JsonObject): Long = PRICE_PER_PAGE_SATS * maxPages(args)

    override fun preflight(args: JsonObject): UrlSafety.Result.Reject? {
        val url = args.str("url") ?: return UrlSafety.Result.Reject("INVALID_PARAMS", "missing url")
        return BrowserUrlPolicy.check(url)
    }

    override fun inputSchema(): JsonElement = Json.parseToJsonElement(
        """
        {"type":"object","required":["url"],"properties":{
          "url":{"type":"string","description":"http(s) start URL; the crawl stays on the host it lands on"},
          "max_pages":{"type":"integer","description":"pages to crawl, 1-$MAX_PAGES (default $MAX_PAGES), billed up front at $PRICE_PER_PAGE_SATS sats each"}
        }}
        """.trimIndent()
    )

    override suspend fun execute(
        ctx: ToolCallContext,
        onProgress: (progressJson: String) -> Unit,
    ): ToolResult {
        preflight(ctx.args)?.let { return ToolResult.errorJson(it.code, it.reason) }
        val start = ctx.args.str("url")?.let { normalize(it) }
            ?: return ToolResult.errorJson("INVALID_PARAMS", "url is not an http(s) URL")
        val maxPages = maxPages(ctx.args)

        val file = File.createTempFile("cvm-scrape-", ".jsonl", context?.cacheDir)
        val result = try {
            val stats = file.bufferedWriter().use { out -> crawl(start, maxPages, out, onProgress) }
            if (stats.scraped == 0) {
                ToolResult.errorJson("DOWNLOAD", "no page could be loaded: ${stats.firstError}")
            } else {
                val uploaded = upload(file)
                val meta = buildJsonObject {
                    put("url", start.toString())
                    put("pages", stats.scraped)
                    put("failed", stats.failed)
                    put("complete", stats.complete)
                    put("url_result", uploaded.url)
                    put("sha256", uploaded.sha256)
                    put("sizeBytes", uploaded.sizeBytes)
                    put("mimeType", MIME)
                }
                ToolResult(
                    content = listOf(
                        ContentBlock.Text(JSON.encodeToString(JsonObject.serializer(), meta)),
                        ContentBlock.ResourceLink(
                            uri = uploaded.url,
                            name = uploaded.sha256.take(16) + ".jsonl",
                            mimeType = MIME,
                            description = "Crawled by hench site_scrape",
                        ),
                    ),
                )
            }
        } catch (e: BrowserIsolationUnsupported) {
            ToolResult.errorJson("BROWSER_UNAVAILABLE", e.message ?: "WebView profile isolation unsupported")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.errorJson("UPLOAD", e.message ?: "scrape upload failed")
        } finally {
            file.delete()
        }
        store.put(
            JobRecord(
                idempotencyKey = ctx.idempotencyKey,
                canonicalInvocationId = ctx.canonicalInvocationId,
                jobRequestJson = JSON.encodeToString(JsonObject.serializer(), ctx.args),
                argsJson = JSON.encodeToString(JsonObject.serializer(), ctx.args),
                status = JobRecord.STATUS_COMPLETED,
                terminalResultJson = JSON.encodeToString(result),
            ),
        )
        return result
    }

    private class Crawl(val scraped: Int, val failed: Int, val complete: Boolean, val firstError: String?)

    /**
     * Breadth-first from [start] with up to [CONCURRENCY] renders in flight; writes
     * one JSONL line per page attempted, in completion order. Only this coroutine
     * touches the queue, the seen set and [out]; renders just report back.
     */
    private suspend fun crawl(start: HttpUrl, maxPages: Int, out: Writer, onProgress: (String) -> Unit): Crawl = coroutineScope {
        val deadline = System.nanoTime() + CRAWL_BUDGET_MS * 1_000_000
        val queue = ArrayDeque(listOf(start))
        val seen = mutableSetOf(start.toString())
        val rendered = Channel<Triple<HttpUrl, BrowserRenderer.Page?, String?>>(Channel.UNLIMITED)
        var host: String? = null
        var started = 0
        var inFlight = 0
        var scraped = 0
        var failed = 0
        var firstError: String? = null
        while (true) {
            // The start page renders alone (it pins the host); its links fill the queue.
            while (inFlight < CONCURRENCY && queue.isNotEmpty() && started < maxPages) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                if (remainingMs <= 0) break
                val next = queue.removeFirst()
                started++
                inFlight++
                launch {
                    val (page, error) = render(next.toString(), minOf(PAGE_TIMEOUT_MS, remainingMs))
                    rendered.send(Triple(next, page, error))
                }
            }
            if (inFlight == 0) break
            val (url, page, error) = rendered.receive()
            inFlight--
            val line = buildJsonObject {
                put("url", page?.finalUrl ?: url.toString())
                if (page == null) {
                    put("error", error)
                } else {
                    put("title", page.title)
                    put("text", page.text.take(WebFetchTool.MAX_TEXT_CHARS))
                    if (page.text.length > WebFetchTool.MAX_TEXT_CHARS) put("truncated", true)
                }
            }
            out.write(JSON.encodeToString(JsonObject.serializer(), line))
            out.write("\n")
            if (page == null) {
                failed++
                if (firstError == null) firstError = error
            } else {
                scraped++
                // Scope is where the start URL landed (example.com -> www.example.com);
                // marking the landing URL seen stops its home link re-crawling it.
                val landed = normalize(page.finalUrl) ?: url
                if (host == null) host = landed.host
                seen += landed.toString()
                for (href in page.links) {
                    val link = normalize(href) ?: continue
                    if (link.host == host && !NON_PAGE.containsMatchIn(link.encodedPath) && seen.add(link.toString())) {
                        queue.addLast(link)
                    }
                }
            }
            onProgress(progress(scraped + failed, maxPages))
        }
        Crawl(scraped, failed, complete = queue.isEmpty(), firstError)
    }

    /** One page render; a failure becomes that page's error line instead of ending the crawl. */
    private suspend fun render(url: String, timeoutMs: Long): Pair<BrowserRenderer.Page?, String?> = try {
        val page = loadPage?.invoke(url, timeoutMs) ?: renderer.load(url, timeoutMs, collectLinks = true)
        if (page.loadFailed) null to (page.errorMessage ?: "page failed to load") else page to null
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive() // rethrow when the whole call was cancelled
        null to "page did not finish loading within ${timeoutMs / 1_000}s"
    } catch (e: BrowserIsolationUnsupported) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null to (e.message ?: e::class.java.simpleName)
    }

    private fun maxPages(args: JsonObject): Int =
        args.str("max_pages")?.toIntOrNull()?.coerceIn(1, MAX_PAGES) ?: MAX_PAGES

    private fun progress(done: Int, total: Int): String = JSON.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("status", "PROCESSING")
            put("progress", done)
            put("total", total)
            put("detail", "page $done/$total")
        },
    )

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    companion object {
        /** A page costs what a single web_fetch render does. */
        const val PRICE_PER_PAGE_SATS = WebFetchTool.PRICE_SATS

        /** 50 pages took 30-37 s on test phones (3 renders at a time), far inside [CRAWL_BUDGET_MS]. */
        const val MAX_PAGES = 50

        /** Renders in flight; each is a WebView in the profile's one shared renderer process, so this also caps memory. */
        private const val CONCURRENCY = 3

        /** CvmServer kills the call at 600s; the rest is upload headroom. */
        private const val CRAWL_BUDGET_MS = 420_000L
        private const val PAGE_TIMEOUT_MS = 30_000L
        private const val MIME = "application/x-ndjson"

        /** Links a page render can't read; skipped instead of burning a paid page. */
        private val NON_PAGE = Regex(
            """\.(pdf|zip|gz|tgz|rar|7z|exe|dmg|apk|iso|mp3|mp4|m4a|mov|avi|mkv|webm|wav|ogg|jpe?g|png|gif|webp|svg|ico|css|js|json|xml|rss|woff2?|ttf)$""",
            RegexOption.IGNORE_CASE,
        )
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** http(s) URL minus its fragment; null for mailto:/javascript:/junk. */
        private fun normalize(url: String): HttpUrl? =
            url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()
    }
}
