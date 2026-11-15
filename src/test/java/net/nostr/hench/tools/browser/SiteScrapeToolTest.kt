package net.nostr.hench.tools.browser

import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.nostr.hench.blossom.BlossomUploadResult
import net.nostr.hench.cvm.IncomingContext
import net.nostr.hench.cvm.ToolCallContext
import net.nostr.hench.settings.WorkerSettings
import net.nostr.hench.tools.ContentBlock
import okhttp3.HttpUrl.Companion.toHttpUrl
import net.nostr.hench.worker.InMemoryPaymentSessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crawl-loop contract of site_scrape against a scripted site: host scoping,
 * dedupe, non-page skipping, per-page pricing, and delivery. Real WebView
 * rendering is covered on-device by BrowserIsolationInstrumentedTest.
 */
class SiteScrapeToolTest {

    private fun page(url: String, vararg links: String, failed: Boolean = false) = BrowserRenderer.Page(
        finalUrl = url,
        title = "title of $url",
        text = "text of $url",
        loadFailed = failed,
        errorMessage = if (failed) "net::ERR_FAILED" else null,
        links = links.toList(),
    )

    private class Fixture(val tool: SiteScrapeTool, val loaded: MutableList<String>, val uploads: MutableList<String>, val peak: () -> Int)

    /** Scripted site; each render takes [renderMs] of virtual time so renders overlap. */
    private fun fixture(
        site: Map<String, BrowserRenderer.Page>,
        renderMs: Long = 0,
        fetchSeeds: (suspend (okhttp3.HttpUrl) -> List<okhttp3.HttpUrl>)? = null,
    ): Fixture {
        val loaded = mutableListOf<String>()
        val uploads = mutableListOf<String>()
        var active = 0
        var peak = 0
        val tool = SiteScrapeTool(
            context = null,
            settings = WorkerSettings(blossomUrls = listOf("https://blossom.test")),
            store = InMemoryPaymentSessionStore(),
            loadPage = { url, _ ->
                loaded += url
                peak = maxOf(peak, ++active)
                delay(renderMs)
                active--
                site.getValue(url)
            },
            fetchSeeds = fetchSeeds,
            upload = { file ->
                uploads += file.readText()
                BlossomUploadResult("https://blossom.test/abc.bin", "ab".repeat(32), file.length(), "application/octet-stream")
            },
        )
        return Fixture(tool, loaded, uploads) { peak }
    }

    private fun ctx(args: JsonObject) = ToolCallContext(IncomingContext("e", "c", "1", "tools/call"), "site_scrape", args, "h")

    private fun meta(result: net.nostr.hench.tools.ToolResult) =
        Json.parseToJsonElement((result.content.first() as ContentBlock.Text).text).jsonObject

    @Test
    fun crawlStaysOnLandingHostAndDeliversEveryPageAsJsonl() = runTest {
        val run = fixture(
            mapOf(
                // Start URL redirects to www; its home link must not re-crawl it.
                "https://example.com/" to page(
                    "https://www.example.com/",
                    "https://www.example.com/",
                    "https://www.example.com/a#top",
                    "https://www.example.com/a",
                    "https://www.example.com/report.PDF",
                    "https://other.example/x",
                    "mailto:hi@example.com",
                    "https://www.example.com/b",
                ),
                "https://www.example.com/a" to page("https://www.example.com/a", "https://www.example.com/b", "https://www.example.com/c"),
                "https://www.example.com/b" to page("https://www.example.com/b", failed = true),
                "https://www.example.com/c" to page("https://www.example.com/c"),
            ),
        )

        val result = run.tool.execute(ctx(buildJsonObject { put("url", "https://example.com") })) {}

        assertFalse(result.isError)
        assertEquals(
            listOf("https://example.com/", "https://www.example.com/a", "https://www.example.com/b", "https://www.example.com/c"),
            run.loaded,
        )
        val meta = meta(result)
        assertEquals("3", meta.getValue("pages").jsonPrimitive.content)
        assertEquals("1", meta.getValue("failed").jsonPrimitive.content)
        assertEquals("true", meta.getValue("complete").jsonPrimitive.content)
        val lines = run.uploads.single().lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(4, lines.size)
        assertEquals("text of https://www.example.com/a", lines[1].getValue("text").jsonPrimitive.content)
        assertEquals("net::ERR_FAILED", lines[2].getValue("error").jsonPrimitive.content)
        assertNull(lines[2]["text"])
    }

    @Test
    fun maxPagesBoundsBothThePriceAndTheCrawl() = runTest {
        val site = (0..5).associate { i ->
            "https://example.com/$i" to page("https://example.com/$i", "https://example.com/${i + 1}")
        }
        val run = fixture(site)
        val tool = run.tool
        fun quote(max: String?) = tool.quoteSats(buildJsonObject { put("url", "https://example.com/0"); max?.let { put("max_pages", it) } })

        assertEquals(2 * SiteScrapeTool.PRICE_PER_PAGE_SATS, quote("2"))
        assertEquals(SiteScrapeTool.PRICE_PER_PAGE_SATS, quote("0"))
        assertEquals(tool.priceRangeSats.last, quote("100000"))
        assertEquals(tool.priceRangeSats.last, quote(null))

        val result = tool.execute(ctx(buildJsonObject { put("url", "https://example.com/0"); put("max_pages", 2) })) {}

        assertEquals(listOf("https://example.com/0", "https://example.com/1"), run.loaded)
        assertEquals("false", meta(result).getValue("complete").jsonPrimitive.content)
    }

    @Test
    fun unreachableStartPageIsAnErrorWithNothingUploaded() = runTest {
        val run = fixture(mapOf("https://example.com/" to page("https://example.com/", failed = true)))

        val result = run.tool.execute(ctx(buildJsonObject { put("url", "https://example.com/") })) {}

        assertTrue(result.isError)
        assertTrue(run.uploads.isEmpty())
    }

    @Test
    fun rendersThreePagesAtOnceWithoutOvershootingMaxPages() = runTest {
        val pages = (1..10).map { "https://example.com/$it" }
        val site = (listOf("https://example.com/") + pages).associateWith { page(it, *pages.toTypedArray()) }
        val run = fixture(site, renderMs = 1_000)

        val result = run.tool.execute(ctx(buildJsonObject { put("url", "https://example.com/"); put("max_pages", 5) })) {}

        assertFalse(result.isError)
        assertEquals(5, run.loaded.size)
        assertEquals(3, run.peak())
        assertEquals(5, run.uploads.single().lines().count { it.isNotBlank() })
    }

    @Test
    fun sitemapSeedsAreCrawledAlongsideDiscoveredLinks() = runTest {
        val run = fixture(
            mapOf(
                "https://example.com/" to page("https://example.com/", "https://example.com/a"),
                "https://example.com/a" to page("https://example.com/a", "https://example.com/b"),
                "https://example.com/b" to page("https://example.com/b"),
                "https://example.com/seed" to page("https://example.com/seed"),
                "https://example.com/a#frag" to page("https://example.com/a#frag"), // dedupes to /a
            ),
            fetchSeeds = { listOf("https://example.com/seed".toHttpUrl(), "https://example.com/a#frag".toHttpUrl()) },
        )

        val result = run.tool.execute(ctx(buildJsonObject { put("url", "https://example.com/") })) {}

        assertFalse(result.isError)
        // /seed comes from the sitemap; /a#frag normalizes onto the already-seen /a.
        assertEquals(
            listOf("https://example.com/", "https://example.com/a", "https://example.com/seed", "https://example.com/b"),
            run.loaded,
        )
        assertEquals(4, run.uploads.single().lines().count { it.isNotBlank() })
    }

    @Test
    fun offHostAndNonPageSeedsAreSkipped() = runTest {
        val run = fixture(
            mapOf(
                "https://example.com/" to page("https://example.com/", "https://example.com/a"),
                "https://example.com/a" to page("https://example.com/a"),
                "https://example.com/ok" to page("https://example.com/ok"),
            ),
            fetchSeeds = {
                listOf(
                    "https://other.example/x".toHttpUrl(),
                    "https://example.com/doc.pdf".toHttpUrl(),
                    "https://example.com/ok".toHttpUrl(),
                )
            },
        )

        val result = run.tool.execute(ctx(buildJsonObject { put("url", "https://example.com/") })) {}

        assertFalse(result.isError)
        assertEquals(
            listOf("https://example.com/", "https://example.com/a", "https://example.com/ok"),
            run.loaded,
        )
    }

    @Test
    fun throwingFetchSeedsLeavesTheCrawlUnchanged() = runTest {
        val run = fixture(
            mapOf(
                "https://example.com/" to page("https://example.com/", "https://example.com/a"),
                "https://example.com/a" to page("https://example.com/a"),
            ),
            fetchSeeds = { throw RuntimeException("no network in test") },
        )

        val result = run.tool.execute(ctx(buildJsonObject { put("url", "https://example.com/") })) {}

        assertFalse(result.isError)
        assertEquals(listOf("https://example.com/", "https://example.com/a"), run.loaded)
        assertEquals("true", meta(result).getValue("complete").jsonPrimitive.content)
    }

    @Test
    fun parseRobotsSitemapsReadsOnlyHttpSitemapLines() {
        val body = """
            User-agent: *
            Disallow: /private
            Sitemap: https://example.com/sitemap.xml
            sitemap:https://example.com/news-sitemap.xml
            Sitemap: ftp://example.com/bad.xml
            Sitemap: not-a-url
        """.trimIndent()

        assertEquals(
            listOf("https://example.com/sitemap.xml", "https://example.com/news-sitemap.xml"),
            SiteScrapeTool.parseRobotsSitemaps(body),
        )
    }

    @Test
    fun parseSitemapSplitsUrlsetFromIndex() {
        val urlset = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>https://example.com/a</loc></url>
              <url><loc> https://example.com/b </loc></url>
            </urlset>
        """.trimIndent()
        val (pages, children) = SiteScrapeTool.parseSitemap(urlset)
        assertEquals(listOf("https://example.com/a", "https://example.com/b"), pages)
        assertTrue(children.isEmpty())

        val index = """
            <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <sitemap><loc>https://example.com/sitemap-1.xml</loc></sitemap>
              <sitemap><loc>https://example.com/sitemap-2.xml</loc></sitemap>
            </sitemapindex>
        """.trimIndent()
        val (indexPages, indexChildren) = SiteScrapeTool.parseSitemap(index)
        assertTrue(indexPages.isEmpty())
        assertEquals(listOf("https://example.com/sitemap-1.xml", "https://example.com/sitemap-2.xml"), indexChildren)
    }

    @Test
    fun readCappedStopsAtCapAndPreservesShortInput() {
        val big = ByteArrayInputStream(ByteArray(200) { 'x'.code.toByte() })
        assertEquals(64, SiteScrapeTool.readCapped(big, cap = 64).size)

        val small = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        assertTrue(SiteScrapeTool.readCapped(small, cap = 64).contentEquals(byteArrayOf(1, 2, 3)))
    }
}
