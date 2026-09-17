package net.nostr.hench.cvm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.nostr.hench.auth.Platform
import net.nostr.hench.pricing.QuoteCalculator
import net.nostr.hench.settings.WorkerSettings
import net.nostr.hench.tools.ToolResult
import net.nostr.hench.wallet.FakeLightningWallet
import net.nostr.hench.wallet.InvoiceStatus
import net.nostr.hench.worker.DeliveryMode
import net.nostr.hench.worker.InMemoryPaymentSessionStore
import net.nostr.hench.worker.JobRecord
import net.nostr.hench.worker.JobRequest
import net.nostr.hench.worker.PaymentRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentGateHandlerTest {

    @Test
    fun newGateEventCreatesInvoiceAndSubmits() = runTest {
        val (gate, wallet, handler, store) = buildHandler(this)

        val event = sampleEvent("evt-1", "clientA:abc123", mode = "full")
        val job = handler.handle(event, this)

        assertEquals(1, gate.submitted.size)
        val submitted = gate.submitted.single()
        assertEquals("evt-1", submitted.requestEventId)
        assertTrue(submitted.amountSats > 0)
        assertEquals("bitcoin-lightning-bolt11", submitted.pmi)
        assertEquals("tools/call", event.method)

        val payment = store.getPayment("clientA:abc123")
        assertNotNull(payment)
        assertEquals(PaymentRecord.STATUS_PENDING, payment!!.status)
        assertEquals(submitted.payReq, payment.payReq)
        job?.cancel()
    }

    @Test
    fun durableBeforeSubmitOrder() = runTest {
        val (gate, wallet, handler, store) = buildHandler(this)

        // We cannot observe the exact pre/post ordering without intercepting
        // wallet.createInvoice, but we can verify the PaymentRecord is written
        // to the store before submitInvoice returns (synchronous store, no
        // suspension between the two operations).
        val event = sampleEvent("evt-1", "clientA:abc123", mode = "full")
        val job = handler.handle(event, this)

        val payment = store.getPayment("clientA:abc123")
        assertNotNull(payment)
        assertEquals(gate.submitted.single().payReq, payment!!.payReq)
        job?.cancel()
    }

    @Test
    fun paymentSettledMarksGateSettledAndMovesToProcessing() = runTest {
        val (gate, wallet, handler, store) = buildHandler(this)

        val event = sampleEvent("evt-1", "clientA:abc123", mode = "full")
        val job = handler.handle(event, this)

        val payment = store.getPayment("clientA:abc123")!!
        wallet.markSettled(payment.paymentId!!)

        advanceTimeBy(5_000)
        job?.join()

        assertEquals(1, gate.settled.size)
        assertEquals(payment.payReq, gate.settled.single())
        assertEquals(PaymentRecord.STATUS_SETTLED, store.getPayment("clientA:abc123")?.status)
    }

    @Test
    fun paymentExpiredMarksGateFailed() = runTest {
        val (gate, wallet, handler, store) = buildHandler(this)

        val event = sampleEvent("evt-1", "clientA:abc123", mode = "full")
        val job = handler.handle(event, this)

        val payment = store.getPayment("clientA:abc123")!!
        wallet.setStatus(payment.paymentId!!, InvoiceStatus.Expired)

        advanceTimeBy(5_000)
        job?.join()

        assertEquals(1, gate.failed.size)
        assertEquals(payment.payReq, gate.failed.single())
        assertEquals(PaymentRecord.STATUS_FAILED, store.getPayment("clientA:abc123")?.status)
    }

    @Test
    fun crashAfterPutReusesSameInvoice() = runTest {
        val (gate, wallet, handler, store) = buildHandler(this)

        val canonical = "clientA:abc123"
        val event1 = sampleEvent("evt-1", canonical, mode = "full")
        val job1 = handler.handle(event1, this)

        val payment = store.getPayment(canonical)!!
        val createdCountBefore = wallet.createCount

        // Simulate a second gate event (restart/re-delivery) for the same
        // canonical id; the handler must re-bind and not mint a new invoice.
        val event2 = sampleEvent("evt-2", canonical, mode = "full")
        val job2 = handler.handle(event2, this)

        assertEquals(createdCountBefore, wallet.createCount)
        assertEquals(2, gate.submitted.size)
        assertEquals(payment.payReq, store.getPayment(canonical)!!.payReq)
        job1?.cancel()
        job2?.cancel()
    }

    @Test
    fun terminalRecordTriggersReplayWithoutNewInvoice() = runTest {
        val (gate, wallet, handler, store) = buildHandler(this)

        val canonical = "clientA:abc123"
        store.put(
            JobRecord(
                idempotencyKey = "idk-1",
                canonicalInvocationId = canonical,
                jobRequestJson = "",
                argsJson = "",
                quoteJson = null,
                invoiceJson = null,
                status = JobRecord.STATUS_COMPLETED,
                terminalResultJson = "{\"ok\":true}",
            ),
        )

        val event = sampleEvent("evt-1", canonical, mode = "full")
        val job = handler.handle(event, this)

        assertEquals(0, wallet.createCount)
        assertEquals(0, gate.submitted.size)
        assertEquals(1, gate.replayed.size)
        assertEquals("evt-1", gate.replayed.single())
        assertNull(job)
    }

    @Test
    fun quoteIsCeiledToSats() = runTest {
        val (gate, wallet, handler, store) = buildHandler(this)

        // The configured price is 60,000 msat/hour with a 3600 second min billable
        // budget, so the quote is 60,000 msat, which is exactly 60 sats.
        val event = sampleEvent("evt-1", "clientA:abc123", mode = "full")
        val job = handler.handle(event, this)

        assertEquals(60L, gate.submitted.single().amountSats)
        job?.cancel()
    }

    @Test
    fun buildPricedCapabilitiesContainsSatsAndCurrencyUnit() {
        val settings = sampleSettings()
        val registry = net.nostr.hench.tools.ToolRegistry(
            net.nostr.hench.tools.ytdlp.YtdlpTool(null, settings, null, InMemoryPaymentSessionStore())
        )
        val gate = FakePaymentGateApi()
        val wallet = FakeLightningWallet()
        val store = InMemoryPaymentSessionStore()
        val handler = PaymentGateHandler(
            paymentGate = gate,
            wallet = wallet,
            registry = registry,
            store = store,
            settings = settings,
        )

        val json = handler.buildPricedCapabilitiesJson()
        assertTrue(json.contains("\"currencyUnit\""))
        assertTrue(json.contains("\"sats\""))
        assertTrue(json.contains("\"method\""))
        assertTrue(json.contains("\"download_media\""))
        assertTrue(json.contains("\"amount\""))
        assertTrue(json.contains("\"maxAmount\""))
    }

    @Test
    fun putPaymentFailureAbortsBeforeSubmit() = runTest {
        val (gate, wallet, handler, store) = buildHandler(this)
        store.failPutPayment = true

        val event = sampleEvent("evt-1", "clientA:abc123", mode = "full")
        val job = handler.handle(event, this)

        // Invoice may be created, but the gate must not have seen it.
        assertEquals(0, gate.submitted.size)
        assertNull(store.getPayment("clientA:abc123"))
        assertNull(job)
    }

    @Test
    fun submitInvoiceFailureKeepsPaymentPending() = runTest {
        val (gate, wallet, handler, store) = buildHandler(this)
        gate.failSubmitInvoice = true

        val event = sampleEvent("evt-1", "clientA:abc123", mode = "full")
        val job = handler.handle(event, this)

        val payment = store.getPayment("clientA:abc123")
        assertNotNull(payment)
        assertEquals(PaymentRecord.STATUS_PENDING, payment!!.status)
        assertEquals(0, gate.submitted.size)
        assertNull(job)
    }

    @Test
    fun terminalRecordWithPaymentEmitsAmountMsatAndReplays() = runTest {
        val (gate, wallet, handler, store, updates) = buildHandler(this)

        val canonical = "clientA:abc123"
        val amountSats = 123L
        store.putPayment(
            PaymentRecord(
                canonicalInvocationId = canonical,
                payReq = "lnbc$amountSats",
                amountSats = amountSats,
                pmi = "bitcoin-lightning-bolt11",
                status = PaymentRecord.STATUS_SETTLED,
            ),
        )
        store.put(
            JobRecord(
                idempotencyKey = "idk-1",
                canonicalInvocationId = canonical,
                jobRequestJson = "",
                argsJson = "",
                quoteJson = null,
                invoiceJson = null,
                amountMsat = amountSats * 1_000L,
                status = JobRecord.STATUS_COMPLETED,
                terminalResultJson = ToolResult.text("done").let { Json.encodeToString(it) },
            ),
        )

        val event = sampleEvent("evt-1", canonical, mode = "full")
        val job = handler.handle(event, this)

        assertNull(job)
        assertEquals(0, wallet.createCount) // test buildHandler uses new wallet; wallet not in harness
        assertEquals(1, gate.replayed.size)
        assertEquals(1, updates.size)
        assertEquals(JobStatus.COMPLETED, updates.single().status)
        assertEquals(amountSats * 1_000L, updates.single().amountMsat)
    }

    @Test
    fun terminalRecordWithoutResultFallsThroughToNewInvoice() = runTest {
        val (gate, _, handler, store) = buildHandler(this)

        val canonical = "clientA:abc123"
        store.put(
            JobRecord(
                idempotencyKey = "idk-1",
                canonicalInvocationId = canonical,
                jobRequestJson = "",
                argsJson = "",
                quoteJson = null,
                invoiceJson = null,
                status = JobRecord.STATUS_FAILED,
                terminalResultJson = null,
            ),
        )

        val event = sampleEvent("evt-1", canonical, mode = "full")
        val job = handler.handle(event, this)

        // A new invoice must be created because no decodable result exists.
        assertEquals(1, gate.submitted.size)
        assertEquals(0, gate.replayed.size)
        job?.cancel()
    }

    @Test
    fun preflightRejectsUnsupportedUrlBeforeInvoice() = runTest {
        val (gate, wallet, handler, store) = buildHandler(this)

        val event = sampleEvent("evt-1", "clientA:abc123", mode = "full")
            .copy(paramsJson = buildJsonObject {
                put("name", "download_media")
                put("arguments", buildJsonObject {
                    put("url", "https://example.com/private")
                    put("mode", "full")
                })
            }.toString())
        val job = handler.handle(event, this)

        assertEquals(0, wallet.createCount)
        assertEquals(0, gate.submitted.size)
        assertNull(store.getPayment("clientA:abc123"))
        assertNull(job)
    }

    @Test
    fun preflightRejectionLogsWithoutStackOverflow() = runTest {
        val (gate, wallet, handler, store, updates) = buildHandler(this)

        val event = sampleEvent("evt-1", "clientA:abc123", mode = "full")
            .copy(paramsJson = buildJsonObject {
                put("name", "download_media")
                put("arguments", buildJsonObject {
                    put("url", "https://example.com/private")
                    put("mode", "full")
                })
            }.toString())

        // The log wrappers were once recursively calling themselves, which
        // would StackOverflowError on this exact preflight-rejection path.
        try {
            val job = handler.handle(event, this)
            assertNull(job)
        } catch (e: StackOverflowError) {
            org.junit.Assert.fail("preflight rejection must not recurse: $e")
        }

        assertEquals(0, wallet.createCount)
        assertEquals(0, gate.submitted.size)
        assertNull(store.getPayment("clientA:abc123"))
        assertTrue(updates.any { it.status == JobStatus.FAILED })
    }

    @Test
    fun submittedSatsComeFromInvoiceAmount() = runTest {
        val (gate, _, handler, store) = buildHandler(this)

        val event = sampleEvent("evt-1", "clientA:abc123", mode = "full")
        val job = handler.handle(event, this)

        // Quote is 60_000 msat. Fake wallet invoice preserves msat, so
        // submitted sats must be ceil(60_000 / 1_000) = 60.
        assertEquals(60L, gate.submitted.single().amountSats)
        assertEquals(60L, store.getPayment("clientA:abc123")!!.amountSats)
        job?.cancel()
    }

    @Test
    fun markSettledGateFailureKeepsPollingAndEmitsUpdate() = runTest {
        val (gate, wallet, handler, store, updates) = buildHandler(this)
        gate.failMarkSettled = true

        val event = sampleEvent("evt-1", "clientA:abc123", mode = "full")
        val job = handler.handle(event, this)

        val payment = store.getPayment("clientA:abc123")!!
        wallet.markSettled(payment.paymentId!!)

        // Let the first poll see Settled, fail the gate mark, and emit the
        // "gate confirmation pending" update before the next delay starts.
        advanceTimeBy(100)
        job?.cancel()

        // The local record must stay PENDING and polling must continue; the
        // gate mark must not have been accepted.
        assertEquals(PaymentRecord.STATUS_PENDING, store.getPayment("clientA:abc123")?.status)
        assertEquals(0, gate.settled.size)
        assertTrue(
            "expected a PROCESSING update while waiting for gate confirmation",
            updates.any { it.status == JobStatus.PROCESSING && it.detail == "payment settled, gate confirmation pending" },
        )
    }

    @Test
    fun markFailedGateFailureStillUpdatesStore() = runTest {
        val (gate, wallet, handler, store) = buildHandler(this)
        gate.failMarkFailed = true

        val event = sampleEvent("evt-1", "clientA:abc123", mode = "full")
        val job = handler.handle(event, this)

        val payment = store.getPayment("clientA:abc123")!!
        wallet.setStatus(payment.paymentId!!, InvoiceStatus.Expired)

        advanceTimeBy(5_000)
        job?.join()

        assertEquals(PaymentRecord.STATUS_FAILED, store.getPayment("clientA:abc123")?.status)
        assertEquals(0, gate.failed.size)
    }

    @Test
    fun settlePollTimesOutAfterTtl() = runTest {
        val settings = sampleSettings()
        val registry = net.nostr.hench.tools.ToolRegistry(
            net.nostr.hench.tools.ytdlp.YtdlpTool(null, settings, null, InMemoryPaymentSessionStore())
        )
        val gate = FakePaymentGateApi()
        val wallet = FakeLightningWallet()
        val store = InMemoryPaymentSessionStore()
        val handler = PaymentGateHandler(
            paymentGate = gate,
            wallet = wallet,
            registry = registry,
            store = store,
            settings = settings,
            paymentTtlCapSecs = 1UL,
        )

        val event = sampleEvent("evt-1", "clientA:abc123", mode = "full")
        val job = handler.handle(event, this)

        advanceTimeBy(2_000)
        job?.join()

        assertEquals(PaymentRecord.STATUS_FAILED, store.getPayment("clientA:abc123")?.status)
        assertEquals(1, gate.failed.size)
    }

    @Test
    fun inFlightSettledRecordDoesNotRebind() = runTest {
        val (gate, wallet, handler, store, updates, inFlight) = buildHandler(this)
        val canonical = "clientA:abc123"
        val payReq = "lnbc60n1fakeinflight"
        store.putPayment(
            PaymentRecord(
                canonicalInvocationId = canonical,
                payReq = payReq,
                amountSats = 60L,
                pmi = "bitcoin-lightning-bolt11",
                status = PaymentRecord.STATUS_SETTLED,
                paymentId = "pid-inflight",
            ),
        )
        inFlight += canonical

        val event = sampleEvent("evt-1", canonical, mode = "full")
        val job = handler.handle(event, this)

        assertNull(job)
        assertEquals(0, wallet.createCount)
        assertEquals(0, gate.submitted.size)
        assertEquals(0, gate.settled.size)
        assertEquals(1, updates.size)
        assertEquals(JobStatus.PROCESSING, updates.single().status)
        assertEquals("execution already in progress", updates.single().detail)
    }

    @Test
    fun freshProcessRecoveryRebindsAndMarksSettledOnce() = runTest {
        val (gate, wallet, handler, store, updates, _) = buildHandler(this)
        val canonical = "clientA:abc123"
        val payReq = "lnbc60n1fakerecovery"
        store.putPayment(
            PaymentRecord(
                canonicalInvocationId = canonical,
                payReq = payReq,
                amountSats = 60L,
                pmi = "bitcoin-lightning-bolt11",
                status = PaymentRecord.STATUS_SETTLED,
                paymentId = "pid-recovery",
            ),
        )

        val event = sampleEvent("evt-1", canonical, mode = "full")
        val job = handler.handle(event, this)

        assertNull(job)
        assertEquals(0, wallet.createCount)
        assertEquals(1, gate.submitted.size)
        assertEquals(payReq, gate.submitted.single().payReq)
        assertEquals(1, gate.settled.size)
        assertEquals(payReq, gate.settled.single())
        assertEquals(1, updates.size)
        assertEquals(JobStatus.PROCESSING, updates.single().status)
        assertEquals("payment settled", updates.single().detail)
    }

    @Test
    fun terminalRecordWithDispatchedPaymentIsReplayed() = runTest {
        val (gate, _, handler, store) = buildHandler(this)
        val canonical = "clientA:abc123"
        store.putPayment(
            PaymentRecord(
                canonicalInvocationId = canonical,
                payReq = "lnbc60n1fakereplay",
                amountSats = 60L,
                pmi = "bitcoin-lightning-bolt11",
                status = PaymentRecord.STATUS_DISPATCHED,
            ),
        )
        store.put(
            JobRecord(
                idempotencyKey = "idk-1",
                canonicalInvocationId = canonical,
                jobRequestJson = "",
                argsJson = "",
                quoteJson = null,
                invoiceJson = null,
                status = JobRecord.STATUS_COMPLETED,
                terminalResultJson = "{\"ok\":true}",
            ),
        )

        val event = sampleEvent("evt-1", canonical, mode = "full")
        val job = handler.handle(event, this)

        assertNull(job)
        assertEquals(0, gate.submitted.size)
        assertEquals(0, gate.settled.size)
        assertEquals(1, gate.replayed.size)
        assertEquals("evt-1", gate.replayed.single())
    }

    // --- helpers ------------------------------------------------------------

    private fun buildHandler(scope: TestScope, inFlight: MutableSet<String> = mutableSetOf()): TestHarness {
        val settings = sampleSettings()
        val registry = net.nostr.hench.tools.ToolRegistry(
            net.nostr.hench.tools.ytdlp.YtdlpTool(null, settings, null, InMemoryPaymentSessionStore())
        )
        val gate = FakePaymentGateApi()
        val wallet = FakeLightningWallet()
        val store = InMemoryPaymentSessionStore()
        val updates = mutableListOf<JobUpdate>()
        val handler = PaymentGateHandler(
            paymentGate = gate,
            wallet = wallet,
            registry = registry,
            store = store,
            settings = settings,
            paymentTtlCapSecs = 300UL,
            onJobUpdate = { updates.add(it) },
            isExecutionInFlight = { canonical -> canonical in inFlight },
        )
        return TestHarness(gate, wallet, handler, store, updates, inFlight)
    }

    private data class TestHarness(
        val gate: FakePaymentGateApi,
        val wallet: FakeLightningWallet,
        val handler: PaymentGateHandler,
        val store: InMemoryPaymentSessionStore,
        val updates: MutableList<JobUpdate>,
        val inFlight: MutableSet<String>,
    )

    private fun sampleSettings() = WorkerSettings(
        enabled = true,
        relayUrls = listOf("wss://relay.test"),
        blossomUrls = listOf("https://blossom.test"),
        workerSecretKeyHex = "a".repeat(64),
        acceptAudio = true,
        acceptVideo = true,
        audioPriceMsatPerHour = 60_000,
        videoPriceMsatPerHour = 60_000,
        minBillableSeconds = 3600L,
        allowedPlatforms = listOf(Platform.YOUTUBE),
    )

    private fun sampleEvent(requestEventId: String, canonical: String, mode: String): GateRequest {
        val params = buildJsonObject {
            put("name", "download_media")
            put("arguments", buildJsonObject {
                put("url", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                put("mode", mode)
            })
        }
        return GateRequest(
            requestEventId = requestEventId,
            clientPubkey = "clientA",
            method = "tools/call",
            paramsJson = params.toString(),
            capabilityName = "download_media",
            canonicalInvocationId = canonical,
        )
    }
}
