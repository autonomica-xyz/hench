package net.nostr.hench.wallet

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

@Serializable
data class PaymentInvoice(
    val id: String,
    val amountMsat: Long,
    val memo: String,
    val bolt11: String,
)

enum class InvoiceStatus {
    Pending,
    Settled,
    Expired,
    Unknown,
}

interface LightningWallet {
    suspend fun createInvoice(amountMsat: Long, memo: String): PaymentInvoice
    suspend fun lookupInvoice(invoiceId: String): InvoiceStatus
}

data class WalletSendResult(
    val paymentId: String,
    val amountSats: Long,
    val feeSats: Long? = null,
    /** Real 32-byte-hex Lightning preimage when the backend exposes one.
     *  NWC `pay_invoice` requires it; null means "unknown", never a fallback id. */
    val preimage: String? = null,
)

interface SpendableLightningWallet : LightningWallet {
    suspend fun balanceSats(): Long
    suspend fun payInvoice(bolt11: String): WalletSendResult
    suspend fun payLightningAddress(address: String, amountSats: Long, comment: String = ""): WalletSendResult
}

class FakeLightningWallet(
    private val autoSettle: Boolean = false,
) : LightningWallet {
    private val invoices = ConcurrentHashMap<String, InvoiceStatus>()
    var createCount = 0
        private set

    override suspend fun createInvoice(amountMsat: Long, memo: String): PaymentInvoice {
        require(amountMsat > 0) { "amountMsat must be positive" }
        createCount++
        val id = UUID.randomUUID().toString()
        val amountSats = (amountMsat + 999L) / 1_000L
        val bolt11 = "lnbc${amountSats}n1fake${id.replace("-", "")}"
        invoices[id] = if (autoSettle) InvoiceStatus.Settled else InvoiceStatus.Pending
        invoices[bolt11] = invoices[id]!!
        return PaymentInvoice(
            id = id,
            amountMsat = amountSats * 1_000L,
            memo = memo,
            bolt11 = bolt11,
        )
    }

    override suspend fun lookupInvoice(invoiceId: String): InvoiceStatus {
        return invoices[invoiceId] ?: InvoiceStatus.Unknown
    }

    fun markSettled(invoiceId: String) {
        invoices[invoiceId] = InvoiceStatus.Settled
    }

    fun setStatus(invoiceId: String, status: InvoiceStatus) {
        invoices[invoiceId] = status
    }
}
