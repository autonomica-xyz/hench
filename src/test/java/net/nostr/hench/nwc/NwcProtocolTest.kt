package net.nostr.hench.nwc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NwcProtocolTest {
    @Test
    fun makeInvoiceRequestMatchesNip47Shape() {
        val request = NwcProtocol.makeInvoiceRequest(amountMsat = 12_345, description = "yt-dlp job", expirySeconds = 600)

        assertEquals("make_invoice", request["method"]?.jsonPrimitive?.content)
        val params = request["params"]!!.jsonObject
        assertEquals("12345", params["amount"]?.jsonPrimitive?.content)
        assertEquals("yt-dlp job", params["description"]?.jsonPrimitive?.content)
        assertEquals("600", params["expiry"]?.jsonPrimitive?.content)
    }

    @Test
    fun lookupInvoiceRequestUsesPaymentHash() {
        val request = NwcProtocol.lookupInvoiceRequest("abc123")

        assertEquals("lookup_invoice", request["method"]?.jsonPrimitive?.content)
        assertEquals("abc123", request["params"]!!.jsonObject["payment_hash"]?.jsonPrimitive?.content)
    }

    @Test
    fun requestEventTagsIncludeWalletPubkeyAndEncryption() {
        val walletPubkey = "a".repeat(64)
        val tags = NwcProtocol.requestEventTags(walletPubkey)

        assertTrue(tags.contains(listOf("p", walletPubkey)))
        assertTrue(tags.contains(listOf("encryption", "nip04")))
    }

    @Test
    fun parsesResponseResultType() {
        val raw = Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("result_type", kotlinx.serialization.json.JsonPrimitive("make_invoice"))
                put("error", kotlinx.serialization.json.JsonNull)
            },
        )

        assertEquals("make_invoice", NwcProtocol.responseResultType(raw))
    }
}
