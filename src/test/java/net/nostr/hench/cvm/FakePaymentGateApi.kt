package net.nostr.hench.cvm

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Scripted fake of the CEP-8 payment gate for unit tests. Callers can enqueue
 * [GateRequest] events, and assertions can inspect the submitted invoices,
 * settlement/failure/replay calls made by the consumer.
 */
class FakePaymentGateApi : PaymentGateApi {
    private val events = mutableListOf<GateRequest>()
    val submitted = CopyOnWriteArrayList<SubmittedInvoice>()
    val settled = CopyOnWriteArrayList<String>()
    val failed = CopyOnWriteArrayList<String>()
    val replayed = CopyOnWriteArrayList<String>()

    /** Force the next [submitInvoice] call(s) to return false. */
    var failSubmitInvoice: Boolean = false

    /** Force the next [markPaymentSettled] call(s) to return false. */
    var failMarkSettled: Boolean = false

    /** Force the next [markPaymentFailed] call(s) to return false. */
    var failMarkFailed: Boolean = false

    fun enqueue(event: GateRequest) { events.add(event) }

    override suspend fun recvGateRequest(timeoutSecs: ULong): GateRequest? {
        return if (events.isEmpty()) null else events.removeAt(0)
    }

    override suspend fun submitInvoice(
        requestEventId: String,
        amountSats: Long,
        payReq: String,
        pmi: String,
        ttlSecs: ULong,
        description: String?,
    ): Boolean {
        if (failSubmitInvoice) return false
        submitted.add(SubmittedInvoice(requestEventId, amountSats, payReq, pmi, ttlSecs, description))
        return true
    }

    override suspend fun markPaymentSettled(payReq: String, metaJson: String?): Boolean {
        if (failMarkSettled) return false
        settled.add(payReq)
        return true
    }

    override suspend fun markPaymentFailed(payReq: String, message: String?): Boolean {
        if (failMarkFailed) return false
        failed.add(payReq)
        return true
    }

    override suspend fun markReplayed(requestEventId: String): Boolean {
        replayed.add(requestEventId)
        return true
    }

    data class SubmittedInvoice(
        val requestEventId: String,
        val amountSats: Long,
        val payReq: String,
        val pmi: String,
        val ttlSecs: ULong,
        val description: String?,
    )
}
