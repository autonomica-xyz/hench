package net.nostr.hench.nwc

import net.nostr.hench.identity.Bip340Schnorr
import net.nostr.hench.identity.hexToBytes
import net.nostr.hench.identity.toHex
import net.nostr.hench.nostr.NostrEventEnvelope
import net.nostr.hench.nostr.NostrEventSigner
import net.nostr.hench.nostr.NostrSignedEvent
import net.nostr.hench.wallet.InvoiceStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NwcWalletClientTest {
    private val clientSecret = "0".repeat(63) + "1"
    private val walletSecret = "0".repeat(63) + "2"
    private val walletPubkey = Bip340Schnorr.publicKey(walletSecret.hexToBytes()).toHex()
    private val clientPubkey = Bip340Schnorr.publicKey(clientSecret.hexToBytes()).toHex()
    private val connection = NwcConnectionUri(
        walletPubkeyHex = walletPubkey,
        clientSecretHex = clientSecret,
        relays = listOf("ws://relay.test"),
        lud16 = null,
    )

    @Test
    fun createInvoicePublishesEncryptedNwcMakeInvoiceRequestAndParsesResponse() = runBlocking {
        val transport = FakeNwcTransport { request ->
            assertEquals(NwcProtocol.KIND_REQUEST, request.kind)
            assertEquals(clientPubkey, request.pubkey)
            assertTrue(request.tags.contains(listOf("p", walletPubkey)))
            val decrypted = Nip04.decrypt(walletSecret, clientPubkey, request.content)
            val payload = Json.parseToJsonElement(decrypted).jsonObject
            assertEquals("make_invoice", payload["method"]?.jsonPrimitive?.content)
            assertEquals("2500", payload["params"]!!.jsonObject["amount"]?.jsonPrimitive?.content)
            assertEquals("download job abc", payload["params"]!!.jsonObject["description"]?.jsonPrimitive?.content)

            walletResponse(
                requestId = request.id,
                content = """{"result_type":"make_invoice","result":{"invoice":"lnbc2500n1real","payment_hash":"hash2500"}}""",
            )
        }
        val wallet = NwcLightningWallet(connection, transport)

        val invoice = wallet.createInvoice(2500, "download job abc")

        assertEquals("hash2500", invoice.id)
        assertEquals(2500, invoice.amountMsat)
        assertEquals("download job abc", invoice.memo)
        assertEquals("lnbc2500n1real", invoice.bolt11)
    }

    @Test
    fun lookupInvoiceReturnsSettledWhenNwcResponseReportsSettled() = runBlocking {
        val transport = FakeNwcTransport { request ->
            val decrypted = Nip04.decrypt(walletSecret, clientPubkey, request.content)
            val payload = Json.parseToJsonElement(decrypted).jsonObject
            assertEquals("lookup_invoice", payload["method"]?.jsonPrimitive?.content)
            assertEquals("hash2500", payload["params"]!!.jsonObject["payment_hash"]?.jsonPrimitive?.content)
            walletResponse(
                requestId = request.id,
                content = """{"result_type":"lookup_invoice","result":{"settled":true}}""",
            )
        }
        val wallet = NwcLightningWallet(connection, transport)

        assertEquals(InvoiceStatus.Settled, wallet.lookupInvoice("hash2500"))
    }

    // HIGH-1 regression: the client must verify event id + signature before
    // trusting the decrypted payload. These events carry authentic signed
    // vectors, then mutated — each must be rejected.

    @Test
    fun rejectsResponseSignedByWrongKey() = runBlocking {
        val attackerSecret = "0".repeat(63) + "9"
        val transport = FakeNwcTransport { request ->
            // Attacker-signed event carrying replayable-looking ciphertext.
            NostrEventSigner.sign(
                NostrEventEnvelope(
                    kind = NwcProtocol.KIND_RESPONSE,
                    content = Nip04.encrypt(walletSecret, clientPubkey, """{"result_type":"lookup_invoice","result":{"settled":true}}""", ByteArray(16) { 7 }),
                    tags = listOf(listOf("p", clientPubkey), listOf("e", request.id)),
                ),
                attackerSecret,
            )
        }
        val wallet = NwcLightningWallet(connection, transport)
        val outcome = runCatching { wallet.lookupInvoice("hash2500") }
        assertTrue(outcome.isFailure)
    }

    @Test
    fun rejectsRetaggedHistoricalSignedResponse() = runBlocking {
        // The replay attack from the review: a real wallet-signed response for
        // an older request is re-delivered with its e-tag swapped to the new
        // request id. The signature no longer covers the mutated event, so the
        // recomputed id must not match.
        val transport = FakeNwcTransport { request ->
            val historical = walletResponse(
                requestId = "old-request-id",
                content = """{"result_type":"lookup_invoice","result":{"settled":true}}""",
            )
            // Malicious relay retags it to correlate with the new request.
            historical.copy(tags = listOf(listOf("p", clientPubkey), listOf("e", request.id)))
        }
        val wallet = NwcLightningWallet(connection, transport)
        val outcome = runCatching { wallet.lookupInvoice("hash2500") }
        assertTrue("retagged historical response must be rejected", outcome.isFailure)
    }

    @Test
    fun rejectsResponseWithForgedId() = runBlocking {
        val transport = FakeNwcTransport { request ->
            walletResponse(
                requestId = request.id,
                content = """{"result_type":"lookup_invoice","result":{"settled":true}}""",
            ).copy(id = "ff".repeat(32))
        }
        val wallet = NwcLightningWallet(connection, transport)
        assertTrue(runCatching { wallet.lookupInvoice("hash2500") }.isFailure)
    }

    @Test
    fun rejectsResponseWithBadSignature() = runBlocking {
        // Signature from an attacker key over the mutated content is still not
        // the wallet's signature — the pubkey check catches key swaps, this
        // catches sig-forgery attempts under the wallet's own pubkey.
        val transport = FakeNwcTransport { request ->
            walletResponse(
                requestId = request.id,
                content = """{"result_type":"lookup_invoice","result":{"settled":true}}""",
            ).copy(sig = "00".repeat(64))
        }
        val wallet = NwcLightningWallet(connection, transport)
        assertTrue(runCatching { wallet.lookupInvoice("hash2500") }.isFailure)
    }

    @Test
    fun lookupInvoiceRejectsResponseForDifferentInvoice() = runBlocking {
        // Defense in depth: even a properly signed wallet response must name
        // the transaction we queried.
        val transport = FakeNwcTransport { request ->
            walletResponse(
                requestId = request.id,
                content = """{"result_type":"lookup_invoice","result":{"settled":true,"payment_hash":"other-hash"}}""",
            )
        }
        val wallet = NwcLightningWallet(connection, transport)
        assertTrue(runCatching { wallet.lookupInvoice("hash2500") }.isFailure)
    }

    private fun walletResponse(requestId: String, content: String): NostrSignedEvent {
        return NostrEventSigner.sign(
            NostrEventEnvelope(
                kind = NwcProtocol.KIND_RESPONSE,
                content = Nip04.encrypt(walletSecret, clientPubkey, content, ByteArray(16) { 7 }),
                tags = listOf(listOf("p", clientPubkey), listOf("e", requestId)),
            ),
            walletSecret,
            createdAt = 1_700_000_000L,
        )
    }

    private class FakeNwcTransport(
        val handler: (NostrSignedEvent) -> NostrSignedEvent,
    ) : NwcRelayTransport {
        override suspend fun request(connection: NwcConnectionUri, request: NostrSignedEvent): NostrSignedEvent = handler(request)
    }
}
