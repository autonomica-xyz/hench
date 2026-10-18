package net.nostr.hench.nwc

import net.nostr.hench.nostr.NostrEventEnvelope
import net.nostr.hench.nostr.NostrEventSigner
import net.nostr.hench.nostr.NostrSignedEvent
import net.nostr.hench.wallet.InvoiceStatus
import net.nostr.hench.wallet.PaymentInvoice
import net.nostr.hench.wallet.SpendableLightningWallet
import net.nostr.hench.wallet.WalletSendResult
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay/auth firewall for the NWC server: request events must be signed by
 * the paired client key, and each event id may execute at most once.
 */
class NwcServerRuntimeTest {

    private val walletSecret = "11".repeat(32)
    private val clientSecret = "22".repeat(32)
    private val attackerSecret = "33".repeat(32)
    private val connection = NwcServerConnection(
        walletSecretHex = walletSecret,
        clientSecretHex = clientSecret,
        relays = listOf("wss://relay.test"),
    )
    private val runtime = NwcServerRuntime(connection, FakeSpendableWallet(), ledger = InMemoryNwcRequestLedger())

    private fun signedRequest(secret: String, content: String = "{}", pTag: String = connection.walletPubkeyHex): NostrSignedEvent =
        NostrEventSigner.sign(
            NostrEventEnvelope(
                kind = NwcProtocol.KIND_REQUEST,
                content = content,
                tags = NwcProtocol.requestEventTags(pTag),
            ),
            secret,
        )

    @Test
    fun acceptsSignedRequestFromPairedClient() {
        assertTrue(runtime.isAuthorizedRequest(signedRequest(clientSecret)))
    }

    @Test
    fun rejectsSignedRequestFromUnpairedKey() {
        // A stranger holding only the public wallet pubkey (the info event is
        // published openly) must not reach pay_invoice.
        assertFalse(runtime.isAuthorizedRequest(signedRequest(attackerSecret)))
    }

    @Test
    fun rejectsEventWithMismatchedId() {
        assertFalse(runtime.isAuthorizedRequest(signedRequest(clientSecret).copy(id = "0".repeat(64))))
    }

    @Test
    fun rejectsEventWithBadSignature() {
        assertFalse(runtime.isAuthorizedRequest(signedRequest(clientSecret).copy(sig = "0".repeat(128))))
    }

    @Test
    fun rejectsRequestPTaggingADifferentWallet() {
        assertFalse(runtime.isAuthorizedRequest(signedRequest(clientSecret, pTag = "ff".repeat(32))))
    }

    // HIGH-2 regression: an event id differing only by hex letter case would
    // previously pass equals(ignoreCase=true) yet claim a different ledger
    // row, reaching payInvoice twice. Only the canonical lowercase id may pass.
    @Test
    fun rejectsCaseVariantEventId() {
        val signed = signedRequest(clientSecret)
        assertTrue("fixture must contain a letter so uppercase differs", signed.id.any { it in 'a'..'f' })
        val upperId = signed.copy(id = signed.id.uppercase())
        assertFalse("uppercased event id must not authenticate", runtime.isAuthorizedRequest(upperId))
        val upperPub = signed.copy(pubkey = signed.pubkey.uppercase())
        assertFalse("uppercased pubkey must not authenticate", runtime.isAuthorizedRequest(upperPub))
        val upperTag = signed.copy(tags = signed.tags.map { tag ->
            if (tag.firstOrNull() == "p") listOf(tag[0], tag[1].uppercase()) else tag
        })
        assertFalse("uppercased p tag must not authenticate", runtime.isAuthorizedRequest(upperTag))
        // Ledger treats ids as exact strings — the canonical gate above is
        // what keeps a case variant from claiming a second ledger row.
        val ledger = InMemoryNwcRequestLedger()
        assertEquals(NwcRequestClaim.Execute, ledger.claim(signed.id))
        assertEquals(NwcRequestClaim.InFlight, ledger.claim(signed.id))
    }

    @Test
    fun payInvoiceReturnsRealPreimage() = kotlinx.coroutines.runBlocking {
        val wallet = FakeSpendableWallet()
        val rt = NwcServerRuntime(connection, wallet, ledger = InMemoryNwcRequestLedger())
        val result = rt.handlePlainRequest(
            """{"method":"pay_invoice","params":{"invoice":"lnbc1fake"}}"""
        )
        val payload = result["result"]!!.jsonObject
        assertEquals(wallet.preimage, payload["preimage"]!!.jsonPrimitive.content)
        assertNotEquals(wallet.lastPaidPaymentId, payload["preimage"]!!.jsonPrimitive.content)
    }

    @Test
    fun payInvoiceWithoutPreimageFails() = kotlinx.coroutines.runBlocking {
        val wallet = FakeSpendableWallet(preimage = null)
        val rt = NwcServerRuntime(connection, wallet, ledger = InMemoryNwcRequestLedger())
        // Called directly, the handler must throw rather than fabricate a
        // preimage; the websocket path wraps the same failure as an error event.
        val outcome = runCatching {
            rt.handlePlainRequest("""{"method":"pay_invoice","params":{"invoice":"lnbc1fake"}}""")
        }
        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull()!!.message!!.contains("preimage"))
    }

    @Test
    fun ledgerClaimExecutesOnceThenReplaysStoredResponse() {
        val ledger = InMemoryNwcRequestLedger()

        assertEquals(NwcRequestClaim.Execute, ledger.claim("evt-1"))
        // A concurrent duplicate of the same event id must not re-execute.
        assertEquals(NwcRequestClaim.InFlight, ledger.claim("evt-1"))

        ledger.complete("evt-1", "[\"EVENT\",{}]")
        val replay = ledger.claim("evt-1")
        assertTrue(replay is NwcRequestClaim.Completed)
        assertEquals("[\"EVENT\",{}]", (replay as NwcRequestClaim.Completed).responseJson)
    }

    private class FakeSpendableWallet(val preimage: String? = "0123456789abcdef".repeat(4)) : SpendableLightningWallet {
        var lastPaidPaymentId = "pid-1"
        override suspend fun createInvoice(amountMsat: Long, memo: String) =
            PaymentInvoice("id", amountMsat, memo, "lnbc1fake")
        override suspend fun lookupInvoice(invoiceId: String) = InvoiceStatus.Pending
        override suspend fun balanceSats() = 0L
        override suspend fun payInvoice(bolt11: String) =
            WalletSendResult(lastPaidPaymentId, 0L, preimage = preimage)
        override suspend fun payLightningAddress(address: String, amountSats: Long, comment: String) =
            WalletSendResult("p", amountSats)
    }
}
