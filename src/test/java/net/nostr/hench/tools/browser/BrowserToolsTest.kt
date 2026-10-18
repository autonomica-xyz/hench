package net.nostr.hench.tools.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.nostr.hench.settings.WorkerSettings
import net.nostr.hench.worker.InMemoryPaymentSessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the browser tools' pure contract: URL policy (scheme, SSRF),
 * JS-literal quoting for wait_for selectors, preflight, and registry-driven
 * capability rows. Rendering, redirect, and session-isolation behavior are
 * covered by the on-device tests in androidTest (WebView required).
 */
class BrowserToolsTest {

    private fun args(url: String?, extra: Map<String, String> = emptyMap()) = buildJsonObject {
        url?.let { put("url", it) }
        extra.forEach { (k, v) -> put(k, v) }
    }

    private fun webFetch() = WebFetchTool(context = null, store = InMemoryPaymentSessionStore())
    private fun screenshot() = PageScreenshotTool(
        context = null,
        settings = WorkerSettings(),
        store = InMemoryPaymentSessionStore(),
    )

    @Test
    fun webFetchQuotesFlatPriceWithinRange() {
        val tool = webFetch()
        assertEquals(WebFetchTool.PRICE_SATS, tool.quoteSats(args("https://example.com")))
        assertEquals(WebFetchTool.PRICE_SATS, tool.priceRangeSats.first)
        assertEquals(WebFetchTool.PRICE_SATS, tool.priceRangeSats.last)
    }

    @Test
    fun screenshotQuotesFlatPriceWithinRange() {
        val tool = screenshot()
        assertEquals(PageScreenshotTool.PRICE_SATS, tool.quoteSats(args("https://example.com")))
        assertTrue(tool.priceRangeSats.first <= PageScreenshotTool.PRICE_SATS)
        assertTrue(tool.priceRangeSats.last >= PageScreenshotTool.PRICE_SATS)
    }

    @Test
    fun preflightRejectsMissingUrl() {
        assertNotNull(webFetch().preflight(args(null)))
        assertNotNull(screenshot().preflight(args(null)))
    }

    @Test
    fun preflightRejectsNonHttpSchemes() {
        for (url in listOf("file:///etc/passwd", "ftp://example.com/x", "content://media/external")) {
            assertNotNull("should reject $url", webFetch().preflight(args(url)))
            assertNotNull("should reject $url", screenshot().preflight(args(url)))
        }
    }

    @Test
    fun preflightRejectsLoopbackAndPrivateHosts() {
        for (url in listOf(
            "http://127.0.0.1/x",
            "http://localhost/x",
            "http://10.0.0.1/x",
            "http://192.168.1.10/x",
            "http://[::1]/x",
        )) {
            assertNotNull("should reject $url", webFetch().preflight(args(url)))
        }
    }


    @Test
    fun jsStringLiteralRoundTripsThroughQuotes() {
        assertEquals("\"a\\\\'b\\\"c\"", BrowserRenderer.jsStringLiteral("a\\'b\"c"))
        assertEquals("\"plain\"", BrowserRenderer.jsStringLiteral("plain"))
    }

    @Test
    fun preflightAllowsFormerDenylistHostsUnderIsolatedProfile() {
        // Session safety comes from the unauthenticated WebView profile, not a
        // host denylist — these pass the public-URL policy like any other host.
        for (url in listOf(
            "https://mail.google.com/mail/u/0/#inbox",
            "https://accounts.google.com/signin",
            "https://web.whatsapp.com/",
        )) {
            assertNull("should allow $url", BrowserUrlPolicy.check(url))
        }
    }

    @Test
    fun preflightAllowsPublicHttpUrls() {
        for (url in listOf(
            "https://example.com/page?a=1",
            "http://example.org/",
            "https://www.youtube.com/watch?v=x",
        )) {
            assertNull("should allow $url", webFetch().preflight(args(url)))
        }
    }

    @Test
    fun inputSchemasAreValidJson() {
        Json.parseToJsonElement(webFetch().inputSchema().toString())
        Json.parseToJsonElement(screenshot().inputSchema().toString())
    }

    @Test
    fun registryPublishesBothBrowserTools() {
        val registry = net.nostr.hench.tools.ToolRegistry(webFetch(), screenshot())
        val published = registry.toolsPublishJson()
        for (name in listOf("web_fetch", "page_screenshot")) {
            assertTrue(published.contains(name))
        }
    }
}
