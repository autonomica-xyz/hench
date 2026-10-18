package net.nostr.hench.nwc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.nostr.hench.identity.Bip340Schnorr
import net.nostr.hench.identity.hexToBytes
import net.nostr.hench.identity.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NwcProtocolTest {
    @Test
    fun nip04MatchesIndependentAesVectorWithBase64IvCharacters() {
        val senderSecret = "0".repeat(63) + "1"
        val recipientSecret = "0".repeat(63) + "2"
        val recipientPubkey = "c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"
        val senderPubkey = Bip340Schnorr.publicKey(senderSecret.hexToBytes()).toHex()
        // Node crypto AES-256-CBC + noble secp256k1, following NIP-04's reference.
        val expected = "IDdDCvyqtsm2frEcERLMnw2Ey+lHeJtXI8xnjV80Ryk=?iv=+/v7+/v7+/v7+/v7+/v7+w=="
        val plain = "Hench NWC interoperability"
        assertEquals(expected, Nip04.encrypt(senderSecret, recipientPubkey, plain, ByteArray(16) { 0xfb.toByte() }))
        assertEquals(plain, Nip04.decrypt(recipientSecret, senderPubkey, expected))
    }

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

    @Test
    fun canonicalEventIdRequiresLowerHex() {
        val lower = "a".repeat(64)
        assertTrue(NwcProtocol.isCanonicalEventId(lower))
        assertTrue(NwcProtocol.isCanonicalEventId("0".repeat(64)))
        // Case variants and malformed ids must not reach ledger keys.
        assertTrue(!NwcProtocol.isCanonicalEventId("A".repeat(64)))
        assertTrue(!NwcProtocol.isCanonicalEventId("a".repeat(63)))
        assertTrue(!NwcProtocol.isCanonicalEventId("g".repeat(64)))
    }

    @Test
    fun verifyResponseEventAcceptsAuthenticSignedVector() {
        val walletSecret = "0".repeat(63) + "2"
        val clientSecret = "0".repeat(63) + "1"
        val walletPubkey = Bip340Schnorr.publicKey(walletSecret.hexToBytes()).toHex()
        val clientPubkey = Bip340Schnorr.publicKey(clientSecret.hexToBytes()).toHex()
        val requestId = "b".repeat(64)
        val event = net.nostr.hench.nostr.NostrEventSigner.sign(
            net.nostr.hench.nostr.NostrEventEnvelope(
                kind = NwcProtocol.KIND_RESPONSE,
                content = "encrypted",
                tags = listOf(listOf("p", clientPubkey), listOf("e", requestId)),
            ),
            walletSecret,
        )
        assertTrue(NwcProtocol.verifyResponseEvent(event, walletPubkey, clientPubkey, requestId))
        // Mutated correlations and ids are rejected.
        assertTrue(!NwcProtocol.verifyResponseEvent(
            event.copy(id = event.id.uppercase()), walletPubkey, clientPubkey, requestId))
        assertTrue(!NwcProtocol.verifyResponseEvent(event, walletPubkey, clientPubkey, "c".repeat(64)))
        assertTrue(!NwcProtocol.verifyResponseEvent(
            event.copy(tags = listOf(listOf("p", clientPubkey), listOf("e", "d".repeat(64)))),
            walletPubkey, clientPubkey, requestId))
    }
}
