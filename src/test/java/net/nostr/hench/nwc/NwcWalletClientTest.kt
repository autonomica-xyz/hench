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
