package net.nostr.hench.cvm

import android.util.Log
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.nostr.hench.auth.Platform
import net.nostr.hench.pricing.QuoteCalculator
import net.nostr.hench.settings.WorkerSettings
import net.nostr.hench.tools.ToolResult
import net.nostr.hench.tools.ytdlp.YtdlpPreflight
import net.nostr.hench.wallet.InvoiceStatus
import net.nostr.hench.wallet.LightningWallet
import net.nostr.hench.worker.DeliveryMode
import net.nostr.hench.worker.JobRecord
import net.nostr.hench.worker.JobRequest
import net.nostr.hench.worker.PaymentRecord
import net.nostr.hench.worker.PaymentSessionStoreI

/**
 * CEP-8 payment-gate event handler. Drains one [GateRequest] at a time (the
 * [CvmServer] runs the drain loop), creates invoices through the consumer's
 * wallet, and drives the durable-before-submit ordering required by the restart
 * model.
 *
 * The handler is intentionally independent of the FFI Server so it can be unit
 * tested with scripted [PaymentGateApi] fakes.
 */
class PaymentGateHandler(
    private val paymentGate: PaymentGateApi,
    private val wallet: LightningWallet,
    private val registry: net.nostr.hench.tools.ToolRegistry,
    private val store: PaymentSessionStoreI,
    private val settings: WorkerSettings,
    private val paymentTtlCapSecs: ULong = 300UL,
    private val onJobUpdate: (JobUpdate) -> Unit = {},
    private val isExecutionInFlight: (String) -> Boolean = { false },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Handle a single gate event. Returns once the invoice has been submitted
     * and a settle-poll coroutine has been launched in [scope]. The returned
     * [kotlinx.coroutines.Job] can be used by tests to cancel or join the poll.
     */
    suspend fun handle(event: GateRequest, scope: CoroutineScope): kotlinx.coroutines.Job? {
        val terminal = store.getByCanonicalId(event.canonicalInvocationId)
        val terminalResult = terminal?.terminalResultJson?.let { decodeToolResult(it) }
        if (terminal != null &&
            (terminal.status == JobRecord.STATUS_COMPLETED || terminal.status == JobRecord.STATUS_FAILED) &&
            terminalResult != null
        ) {
            val status = if (terminal.status == JobRecord.STATUS_COMPLETED) JobStatus.COMPLETED else JobStatus.FAILED
            val amountMsat = if (terminal.amountMsat > 0) {
                terminal.amountMsat
            } else {
                store.getPayment(event.canonicalInvocationId)?.amountSats?.times(1_000L) ?: 0L
            }
            onJobUpdate(JobUpdate(event.canonicalInvocationId, status, amountMsat = amountMsat, detail = terminal.terminalResultJson))
            if (!paymentGate.markReplayed(event.requestEventId)) {
                logw( "markReplayed failed for ${event.requestEventId}")
            }
            return null
        }

        val existing = store.getPayment(event.canonicalInvocationId)
        when {
            existing != null && existing.status == PaymentRecord.STATUS_PENDING -> {
                onJobUpdate(JobUpdate(event.canonicalInvocationId, JobStatus.WAITING_FOR_PAYMENT, amountMsat = existing.amountSats * 1_000L, detail = existing.payReq))
                val ttl = existing.ttlSecs.coerceIn(1, paymentTtlCapSecs.toLong()).toULong()
                if (!paymentGate.submitInvoice(
                        event.requestEventId,
                        existing.amountSats,
                        existing.payReq,
                        existing.pmi,
                        ttl,
                        null,
                    )
                ) {
                    logw( "submitInvoice failed for ${event.requestEventId}; payment record stays PENDING")
                    onJobUpdate(JobUpdate(event.canonicalInvocationId, JobStatus.FAILED, detail = "payment gate rejected invoice rebind"))
                    return null
                }
                return launchSettlePoll(scope, existing.copy(ttlSecs = ttl.toLong()))
            }
            existing != null && (existing.status == PaymentRecord.STATUS_DISPATCHED || existing.status == PaymentRecord.STATUS_SETTLED) -> {
                if (isExecutionInFlight(event.canonicalInvocationId)) {
                    onJobUpdate(JobUpdate(event.canonicalInvocationId, JobStatus.PROCESSING, amountMsat = existing.amountSats * 1_000L, detail = "execution already in progress"))
                    return null
                }
                val ttl = existing.ttlSecs.coerceIn(1, paymentTtlCapSecs.toLong()).toULong()
                if (!paymentGate.submitInvoice(
                        event.requestEventId,
                        existing.amountSats,
                        existing.payReq,
                        existing.pmi,
                        ttl,
                        null,
                    )
                ) {
                    logw( "submitInvoice failed for ${event.requestEventId} on crash recovery")
                    onJobUpdate(JobUpdate(event.canonicalInvocationId, JobStatus.FAILED, detail = "payment gate rejected invoice rebind"))
                    return null
                }
                if (!markSettledWithRetry(existing.payReq, null)) {
                    loge( "markPaymentSettled failed on crash recovery for ${existing.payReq}; starting settle poll")
                    return launchSettlePoll(scope, existing.copy(ttlSecs = ttl.toLong(), status = PaymentRecord.STATUS_SETTLED))
                }
                onJobUpdate(JobUpdate(event.canonicalInvocationId, JobStatus.PROCESSING, amountMsat = existing.amountSats * 1_000L, detail = "payment settled"))
                return null
            }
        }

        // Preflight + quote are TOOL-owned now (Tool.preflight / Tool.quoteSats):
        // the gate is generic over registered tools; it never names a tool itself.
        val params = runCatching { json.parseToJsonElement(event.paramsJson).jsonObject }.getOrNull()
        val toolName = params?.get("name")?.let { (it as? JsonPrimitive)?.content }
        val tool = toolName?.let { registry.get(it) }
        val args = params?.get("arguments")?.let { runCatching { it.jsonObject }.getOrNull() }
        if (tool == null || tool.priceRangeSats == null || args == null) {
            logw("no priced tool '${toolName}' for ${event.canonicalInvocationId}")
            onJobUpdate(JobUpdate(event.canonicalInvocationId, JobStatus.FAILED, detail = "unknown or unpriced tool"))
            return null
        }
        tool.preflight(args)?.let { reject ->
            logw("preflight rejected for ${event.canonicalInvocationId}: ${reject.reason}")
            onJobUpdate(JobUpdate(event.canonicalInvocationId, JobStatus.FAILED, detail = reject.reason))
            return null
        }
        val quoteSats = tool.quoteSats(args)
        val range = tool.priceRangeSats!!
        if (quoteSats == null || quoteSats < range.first || quoteSats > range.last) {
            logw("could not quote gate request for ${event.canonicalInvocationId}")
            onJobUpdate(JobUpdate(event.canonicalInvocationId, JobStatus.FAILED, detail = "could not quote request"))
            return null
        }

        val invoice = wallet.createInvoice(quoteSats * 1_000L, "${tool.name} ${event.canonicalInvocationId.take(8)}")
        val submittedSats = (invoice.amountMsat + 999L) / 1_000L
        if (submittedSats < range.first || submittedSats > range.last) {
            logw("invoice amount $submittedSats sats outside advertised cap [$range.first, ${range.last}]")
            onJobUpdate(JobUpdate(event.canonicalInvocationId, JobStatus.FAILED, detail = "invoice amount outside advertised range"))
            return null
        }

        val payment = PaymentRecord(
            canonicalInvocationId = event.canonicalInvocationId,
            payReq = invoice.bolt11,
            amountSats = submittedSats,
            pmi = "bitcoin-lightning-bolt11",
            status = PaymentRecord.STATUS_PENDING,
            paymentId = invoice.id,
            ttlSecs = paymentTtlCapSecs.toLong(),
        )

        // Durable-before-submit: the payment record MUST be committed before
        // the invoice is advertised to the gate. If the commit fails, we abort
        // and the next gate event will mint a fresh invoice.
        if (!store.putPayment(payment)) {
            loge( "putPayment failed for ${event.canonicalInvocationId}, aborting before submitInvoice")
            onJobUpdate(JobUpdate(event.canonicalInvocationId, JobStatus.FAILED, detail = "failed to persist payment record"))
            return null
        }

        val description = "${tool.name} (paid task)"
        if (!paymentGate.submitInvoice(
                event.requestEventId,
                submittedSats,
                invoice.bolt11,
                "bitcoin-lightning-bolt11",
                paymentTtlCapSecs,
                description,
            )
        ) {
            logw( "submitInvoice failed for ${event.requestEventId}; payment record stays PENDING")
            onJobUpdate(JobUpdate(event.canonicalInvocationId, JobStatus.FAILED, detail = "payment gate rejected invoice"))
            return null
        }
        onJobUpdate(JobUpdate(event.canonicalInvocationId, JobStatus.WAITING_FOR_PAYMENT, amountMsat = submittedSats * 1_000L, detail = invoice.bolt11))
        return launchSettlePoll(scope, payment)
    }

    private fun parseArgs(paramsJson: String): JsonObject? {
        val params = runCatching { json.parseToJsonElement(paramsJson).jsonObject }.getOrNull() ?: return null
        return params["arguments"]?.let { runCatching { it.jsonObject }.getOrNull() }
    }


    private fun launchSettlePoll(scope: CoroutineScope, payment: PaymentRecord): kotlinx.coroutines.Job {
        return scope.launch { settlePoll(payment) }
    }

    private suspend fun settlePoll(payment: PaymentRecord) {
        val paymentId = payment.paymentId ?: payment.payReq
        // Spark's listPayments only records an invoice once it settles — an
        // unpaid invoice is simply absent ("Unknown"). During an active poll
        // for an invoice THIS process created, Unknown == "not paid yet":
        // keep polling until TTL. (Unknown→FAILED stays valid for startup
        // reconciliation, where Unknown means the wallet never knew it.)
        try {
            withTimeout(payment.ttlSecs * 1_000L) {
                while (coroutineContext.isActive) {
                    val status = try {
                        withTimeout(LOOKUP_TIMEOUT_MS) { wallet.lookupInvoice(paymentId) }
                    } catch (_: TimeoutCancellationException) {
                        InvoiceStatus.Pending
                    }
                    when (status) {
                        InvoiceStatus.Settled -> {
                            if (markSettledWithRetry(payment.payReq, null)) {
                                if (!store.putPayment(payment.copy(status = PaymentRecord.STATUS_SETTLED))) {
                                    // Non-fatal: gate already accepted the mark; the record heals on a
                                    // later gate event for this canonical id (reconcile does not scan payments).
                                    loge( "putPayment failed for settled ${payment.canonicalInvocationId}; record will heal on next gate event")
                                    onJobUpdate(JobUpdate(payment.canonicalInvocationId, JobStatus.FAILED, detail = "payment settled, but local record write failed"))
                                    return@withTimeout
                                }
                                onJobUpdate(JobUpdate(payment.canonicalInvocationId, JobStatus.PROCESSING, amountMsat = payment.amountSats * 1_000L, detail = "payment settled"))
                                return@withTimeout
                            }
                            // Grant lives in Rust; a lost mark means the paid
                            // request never forwards. Keep polling until the
                            // gate accepts it or the settle deadline expires.
                            loge( "markPaymentSettled failed after retry for ${payment.payReq}; keeping poll")
                            onJobUpdate(
                                JobUpdate(
                                    payment.canonicalInvocationId,
                                    JobStatus.PROCESSING,
                                    amountMsat = payment.amountSats * 1_000L,
                                    detail = "payment settled, gate confirmation pending",
                                ),
                            )
                        }
                        InvoiceStatus.Expired -> {
                            markFailedWithRetry(payment.payReq, "invoice expired")
                            store.putPayment(payment.copy(status = PaymentRecord.STATUS_FAILED))
                            onJobUpdate(JobUpdate(payment.canonicalInvocationId, JobStatus.FAILED, detail = "payment expired"))
                            return@withTimeout
                        }
                        InvoiceStatus.Unknown,
                        InvoiceStatus.Pending -> {
                            // Not paid yet — keep polling (see comment above).
                        }
                        InvoiceStatus.Pending -> {
                            // fall through to delay
                        }
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }
        } catch (_: TimeoutCancellationException) {
            markFailedWithRetry(payment.payReq, "payment timed out")
            store.putPayment(payment.copy(status = PaymentRecord.STATUS_FAILED))
            onJobUpdate(JobUpdate(payment.canonicalInvocationId, JobStatus.FAILED, detail = "payment timed out"))
        }
    }

    private suspend fun markSettledWithRetry(payReq: String, metaJson: String?): Boolean {
        if (paymentGate.markPaymentSettled(payReq, metaJson)) return true
        logw( "markPaymentSettled failed for $payReq, retrying")
        if (paymentGate.markPaymentSettled(payReq, metaJson)) return true
        loge( "markPaymentSettled failed after retry for $payReq")
        return false
    }

    private suspend fun markFailedWithRetry(payReq: String, message: String?) {
        if (paymentGate.markPaymentFailed(payReq, message)) return
        logw( "markPaymentFailed failed for $payReq, retrying")
        if (paymentGate.markPaymentFailed(payReq, message)) return
        loge( "markPaymentFailed failed after retry for $payReq")
    }

    private fun decodeToolResult(s: String): ToolResult? =
        runCatching { json.decodeFromString<ToolResult>(s) }.getOrNull()

    /**
     * Build the priced-capabilities JSON used by `setPricedCapabilitiesJson`:
     * one capability row per registered tool that declares a price range.
     * Currency unit is always whole sats.
     */
    internal fun buildPricedCapabilitiesJson(): String {
        val rows = registry.all.mapNotNull { tool ->
            val range = tool.priceRangeSats ?: return@mapNotNull null
            buildJsonObject {
                put("method", "tools/call")
                put("name", tool.name)
                put("amount", range.first)
                put("maxAmount", range.last)
                put("currencyUnit", "sats")
                put("description", tool.description)
            }
        }
        return json.encodeToString(JsonArray.serializer(), JsonArray(rows))
    }

    /** Logging wrappers that tolerate the Android stub throwing in JVM unit tests. */
    private fun logw(msg: String) {
        runCatching { Log.w(TAG, msg) }
    }

    private fun loge(msg: String) {
        runCatching { Log.e(TAG, msg) }
    }

    companion object {
        private const val TAG = "PaymentGateHandler"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val LOOKUP_TIMEOUT_MS = 10_000L
    }
}
