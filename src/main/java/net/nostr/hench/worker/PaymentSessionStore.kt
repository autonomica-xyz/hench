package net.nostr.hench.worker

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.nostr.hench.pricing.PriceQuote
import net.nostr.hench.tools.ToolResult
import net.nostr.hench.wallet.InvoiceStatus
import net.nostr.hench.wallet.LightningWallet
import net.nostr.hench.wallet.PaymentInvoice

/**
 * Durable record of one tools/call workload. Survives app restart so a paid
 * invoice is never lost and never minted twice (PLAN §4). The status field is
 * the payment/download state machine.
 */
@Serializable
data class JobRecord(
    val idempotencyKey: String,
    val canonicalInvocationId: String? = null,  // delivered by the CEP-8 gate; null until wired
    val jobRequestJson: String,        // serialized JobRequest (url, mode, ...)
    val argsJson: String,              // canonical args, for diagnostics
    val quoteJson: String? = null,     // serialized PriceQuote when payment ran
    val invoiceJson: String? = null,   // serialized PaymentInvoice (incl. bolt11)
    val amountMsat: Long = 0L,         // actual paid amount when terminal (from PaymentRecord)
    val status: String = STATUS_WAITING,
    val terminalResultJson: String? = null,  // serialized ToolResult for terminal states (COMPLETED *or* FAILED)
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATUS_WAITING = "WAITING_FOR_PAYMENT"
        const val STATUS_PROCESSING = "PROCESSING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
    }
}

/**
 * Durable record of one CEP-8 payment attempt, keyed by the canonical invocation
 * id (which survives JSON-RPC id changes on client retry). This is the consumer's
 * side of the restart model: it is written *before* `submitInvoice` so a crash
 * after the invoice is published can re-bind the same pay_req instead of minting
 * a second invoice.
 */
@Serializable
data class PaymentRecord(
    val canonicalInvocationId: String,
    val payReq: String,
    val amountSats: Long,
    val pmi: String,
    val status: String = STATUS_PENDING,
    val submittedAt: Long = System.currentTimeMillis(),
    val paymentId: String? = null,
    val ttlSecs: Long = 0L,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_DISPATCHED = "DISPATCHED"
        const val STATUS_SETTLED = "SETTLED"
        const val STATUS_FAILED = "FAILED"
    }
}

interface PaymentSessionStoreI {
    fun get(idempotencyKey: String): JobRecord?
    fun put(record: JobRecord)
    fun all(): List<JobRecord>

    /**
     * Look up a terminal or in-flight `JobRecord` by canonical invocation id.
     * Implementations may scan the underlying store or maintain a secondary
     * index; the contract only requires equality on `canonicalInvocationId`.
     */
    fun getByCanonicalId(canonicalInvocationId: String): JobRecord?

    /**
     * Persist a payment record. Implementations MUST write this synchronously
     * (`SharedPreferences.commit()`, not `apply()`) because the consumer is
     * required to durably record the payment before calling `submitInvoice`.
     * The return value is the commit result; callers must abort if it is false.
     */
    fun putPayment(record: PaymentRecord): Boolean

    fun getPayment(canonicalInvocationId: String): PaymentRecord?
    fun allPayments(): List<PaymentRecord>

    /**
     * Remove a payment record. Callers MUST only use this for records the
     * wallet has just confirmed are NOT settled — settled/dispatched records
     * are replay protection and must never be deleted.
     */
    fun removePayment(canonicalInvocationId: String)
}

class InMemoryPaymentSessionStore : PaymentSessionStoreI {
    private val records = java.util.concurrent.ConcurrentHashMap<String, JobRecord>()
    private val payments = java.util.concurrent.ConcurrentHashMap<String, PaymentRecord>()

    /**
     * Test-only flag: when true [putPayment] returns false without writing,
     * letting tests exercise the durable-before-submit abort path.
     */
    var failPutPayment: Boolean = false

    override fun get(idempotencyKey: String) = records[idempotencyKey]
    override fun put(record: JobRecord) { records[record.idempotencyKey] = record }
    override fun all(): List<JobRecord> = records.values.toList()

    override fun getByCanonicalId(canonicalInvocationId: String): JobRecord? =
        records.values.find { it.canonicalInvocationId == canonicalInvocationId }

    override fun putPayment(record: PaymentRecord): Boolean {
        if (failPutPayment) return false
        // Monotonic: a racing writer cannot downgrade the persisted state —
        // FAILED ranks below SETTLED so confirmed settlement heals a failed
        // record, but a stale FAILED write never erases SETTLED/DISPATCHED.
        payments.merge(record.canonicalInvocationId, record) { old, new ->
            if (statusRank(new.status) >= statusRank(old.status)) new else old
        }
        return true
    }

    /**
     * FAILED ranks BELOW SETTLED: a record proven settled (by a late poll or
     * a pruning wallet check) must heal a failed record, while a stale
     * timeout's FAILED write must never erase a settled one.
     */
    private fun statusRank(status: String) = when (status) {
        PaymentRecord.STATUS_PENDING -> 0
        PaymentRecord.STATUS_FAILED -> 1
        PaymentRecord.STATUS_SETTLED -> 2
        PaymentRecord.STATUS_DISPATCHED -> 3
        else -> 0
    }

    override fun removePayment(canonicalInvocationId: String) {
        payments.remove(canonicalInvocationId)
    }

    override fun getPayment(canonicalInvocationId: String) = payments[canonicalInvocationId]
    override fun allPayments(): List<PaymentRecord> = payments.values.toList()
}

/**
 * Persistent map of idempotencyKey → JobRecord, backed by SharedPreferences
 * (one JSON blob per record). This is the PaymentSessionStore: the wallet
 * gate's durability layer (PLAN §5) and the idempotency cache for replays.
 *
 * Payment records live in a separate SharedPreferences file and are committed
 * synchronously (`commit()`) to satisfy durable-before-submit.
 */
class PaymentSessionStore(context: Context) : PaymentSessionStoreI {
    private val recordPrefs: SharedPreferences =
        context.getSharedPreferences("cvm_payment_sessions", Context.MODE_PRIVATE)
    private val paymentPrefs: SharedPreferences =
        context.getSharedPreferences("cvm_payment_records", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun get(idempotencyKey: String): JobRecord? =
        recordPrefs.getString(idempotencyKey, null)?.let {
            runCatching { json.decodeFromString<JobRecord>(it) }.getOrNull()
        }

    override fun put(record: JobRecord) {
        recordPrefs.edit {
            putString(
                record.idempotencyKey,
                json.encodeToString(record.copy(updatedAt = System.currentTimeMillis())),
            )
        }
    }

    override fun all(): List<JobRecord> = recordPrefs.all.values.mapNotNull {
        runCatching { json.decodeFromString<JobRecord>(it as String) }.getOrNull()
    }

    override fun getByCanonicalId(canonicalInvocationId: String): JobRecord? =
        all().find { it.canonicalInvocationId == canonicalInvocationId }

    /**
     * Synchronous, durable write of a payment record. The caller MUST have this
     * on disk before it calls `PaymentGateApi.submitInvoice`. Returns the commit
     * result.
    * Monotonic: a racing writer cannot downgrade the persisted status
    * (PENDING < FAILED < SETTLED < DISPATCHED — settled/dispatched are money
    * states; FAILED is an unpaid terminal state, so a stale timeout write must
    * not overwrite SETTLED while a proven settlement may heal FAILED).
    */
    override fun putPayment(record: PaymentRecord): Boolean {
        @Synchronized
        fun commitMonotonic(): Boolean {
            val existing = getPayment(record.canonicalInvocationId)
            val toWrite = if (existing != null && statusRank(existing.status) > statusRank(record.status)) {
                existing // keep the higher state; report success (the durable state is the newer one)
            } else {
                record
            }
            return paymentPrefs
                .edit()
                .putString(record.canonicalInvocationId, json.encodeToString(toWrite))
                .commit()
        }
        return commitMonotonic()
    }

    private fun statusRank(status: String) = when (status) {
        PaymentRecord.STATUS_PENDING -> 0
        PaymentRecord.STATUS_FAILED -> 1
        PaymentRecord.STATUS_SETTLED -> 2
        PaymentRecord.STATUS_DISPATCHED -> 3
        else -> 0
    }

    /**
     * Removes a payment record via `commit()`. Only for records verified
     * unpaid — never drop settled/dispatched replay protection.
     */
    @Synchronized
    override fun removePayment(canonicalInvocationId: String) {
        paymentPrefs.edit().remove(canonicalInvocationId).commit()
    }

    override fun getPayment(canonicalInvocationId: String): PaymentRecord? =
        paymentPrefs.getString(canonicalInvocationId, null)?.let {
            runCatching { json.decodeFromString<PaymentRecord>(it) }.getOrNull()
        }

    override fun allPayments(): List<PaymentRecord> = paymentPrefs.all.values.mapNotNull {
        runCatching { json.decodeFromString<PaymentRecord>(it as String) }.getOrNull()
    }

    /**
     * Startup reconciliation.
     * In the CEP-8 interim the gate is not bound yet, so this only updates
     * the consumer's own records: WAITING invoices already settled by the wallet
     * become PROCESSING, Expired/Unknown become FAILED, and terminal records are
     * left as-is. It no longer returns a re-run list — the gate owns replay once
     * wired.
     */
    suspend fun reconcile(wallet: LightningWallet) {
        for (record in all()) {
            when (record.status) {
                JobRecord.STATUS_WAITING -> {
                    val invoice = record.invoiceJson?.let { decodeInvoice(it) } ?: continue
                    when (wallet.lookupInvoice(invoice.id)) {
                        InvoiceStatus.Settled ->
                            put(record.copy(status = JobRecord.STATUS_PROCESSING))
                        InvoiceStatus.Expired,
                        InvoiceStatus.Unknown ->
                            put(record.copy(status = JobRecord.STATUS_FAILED))
                        InvoiceStatus.Pending -> Unit // keep; next replay reuses the same invoice
                    }
                }
                else -> Unit // PROCESSING/COMPLETED/FAILED left as-is; gate owns re-run dispatch
            }
        }
    }

    private fun decodeInvoice(s: String): PaymentInvoice? =
        runCatching { json.decodeFromString<PaymentInvoice>(s) }.getOrNull()

    companion object {
        // Aliases so callers write Store.STATUS_* without reaching into JobRecord.
        const val STATUS_WAITING = JobRecord.STATUS_WAITING
        const val STATUS_PROCESSING = JobRecord.STATUS_PROCESSING
        const val STATUS_COMPLETED = JobRecord.STATUS_COMPLETED
        const val STATUS_FAILED = JobRecord.STATUS_FAILED
    }
}
