package net.nostr.hench.tools.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.nostr.hench.worker.InMemoryPaymentSessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for web_search's pure contract: SERP payload parsing (filter,
 * dedupe, cap), input schema validity, and preflight query validation.
 * Actual Google rendering is covered on-device (WebView required).
 */
class WebSearchToolTest {

    private fun tool() = WebSearchTool(context = null, store = InMemoryPaymentSessionStore())

    private fun serp(vararg entries: String) = Json.parseToJsonElement("[${entries.joinToString(",")}]")

    private fun entry(t: String?, u: String?, s: String? = "snip") = buildString {
        append("{")
        if (t != null) append("\"t\":\"$t\",") else append("\"t\":null,")
        if (u != null) append("\"u\":\"$u\",") else append("\"u\":null,")
        if (s != null) append("\"s\":\"$s\"") else append("\"s\":null")
        append("}")
    }

    @Test
    fun parseResultsFiltersDedupesAndCaps() {
        val extracted = serp(
            entry("Result A", "https://a.example/x", "first"),
            entry("  ", "https://blank.example/"),                       // blank title
            entry("No URL", "ftp://files.example/x"),                      // non-http
            entry("Google self", "https://www.google.com/search?q=x"),     // google host
            entry("Dup", "https://a.example/x", "second"),                 // duplicate url
            entry("Result B", "https://b.example/y", "second result"),
            entry("Result C", "https://c.example/z"),
            "\"not-an-object\"",
            "42",
        )
        val out = WebSearchTool.parseResults(extracted, limit = 10)
        assertEquals(3, out.size)
        assertEquals("Result A", out[0].title)
        assertEquals("https://a.example/x", out[0].url)
        assertEquals("first", out[0].snippet)
        assertEquals("Result B", out[1].title)
        assertEquals("Result C", out[2].title)
    }

    @Test
    fun parseResultsRespectsLimit() {
        val extracted = serp(
            entry("R1", "https://1.example/"),
            entry("R2", "https://2.example/"),
            entry("R3", "https://3.example/"),
            entry("R4", "https://4.example/"),
        )
        assertEquals(2, WebSearchTool.parseResults(extracted, limit = 2).size)
    }

    @Test
    fun parseResultsUnwrapsStringEncodedJson() {
        // SERP_SCRIPT returns JSON.stringify(...); evaluateJavascript delivers it
        // as a JSON string primitive — parseResults must unwrap one level.
        val inner = "[{\"t\":\"Wrapped\",\"u\":\"https://w.example/\",\"s\":\"ok\"}]"
        val out = WebSearchTool.parseResults(JsonPrimitive(inner), limit = 10)
        assertEquals(1, out.size)
        assertEquals("Wrapped", out[0].title)
    }

    @Test
    fun parseResultsHandlesNullAndEmpty() {
        assertTrue(WebSearchTool.parseResults(null, limit = 10).isEmpty())
        assertTrue(WebSearchTool.parseResults(Json.parseToJsonElement("[]"), limit = 10).isEmpty())
        // Non-array payloads also yield empty.
        assertTrue(WebSearchTool.parseResults(Json.parseToJsonElement("{\"t\":\"x\"}"), limit = 10).isEmpty())
    }

    @Test
    fun parseResultsSkipsGoogleusercontentAndSubdomains() {
        val extracted = serp(
            entry("Gstatic", "https://googleusercontent.com/img.png"),
            entry("Maps", "https://maps.google.com/place/x"),
            entry("Real", "https://real.example/"),
        )
        val out = WebSearchTool.parseResults(extracted, limit = 10)
        assertEquals(1, out.size)
        assertEquals("Real", out[0].title)
    }

    @Test
    fun inputSchemaIsValidJson() {
        Json.parseToJsonElement(tool().inputSchema().toString())
    }

    @Test
    fun preflightRejectsMissingAndBlankQuery() {
        assertNotNull(tool().preflight(buildJsonObject {}))
        assertNotNull(tool().preflight(buildJsonObject { put("query", "   ") }))
    }

    @Test
    fun preflightAcceptsQuery() {
        assertNull(tool().preflight(buildJsonObject { put("query", "rust async") }))
    }

    @Test
    fun quotesFlatPriceWithinRange() {
        val t = tool()
        val args: JsonObject = buildJsonObject { put("query", "x") }
        assertEquals(WebSearchTool.PRICE_SATS, t.quoteSats(args))
        assertEquals(WebSearchTool.PRICE_SATS, t.priceRangeSats!!.first)
        assertEquals(WebSearchTool.PRICE_SATS, t.priceRangeSats!!.last)
    }
}
