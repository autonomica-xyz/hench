package net.nostr.hench.wallet

import android.content.Context
import breez_sdk_spark.ConnectRequest
import breez_sdk_spark.GetInfoRequest
import breez_sdk_spark.FeePolicy
import breez_sdk_spark.InputType
import breez_sdk_spark.LnurlPayRequest
import breez_sdk_spark.ListPaymentsRequest
import breez_sdk_spark.Network
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentRequest
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PaymentType
import breez_sdk_spark.PrepareLnurlPayRequest
import breez_sdk_spark.PrepareSendPaymentRequest
import breez_sdk_spark.ReceivePaymentMethod
import breez_sdk_spark.ReceivePaymentRequest
import breez_sdk_spark.Seed
import breez_sdk_spark.SendPaymentOptions
import breez_sdk_spark.SendPaymentRequest
import breez_sdk_spark.BreezSdk
import breez_sdk_spark.connect
import breez_sdk_spark.defaultConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.math.BigInteger
import java.security.SecureRandom
import java.util.UUID
import java.util.Locale

interface SparkSdkClient {
    suspend fun receiveBolt11Invoice(amountSats: ULong, memo: String, expirySecs: UInt = 3_600u): String
    /** Page through payment history; [offset] of null starts at the newest. */
    suspend fun listPayments(limit: UInt = 50u, offset: UInt? = null): List<SparkPaymentSnapshot>
    /** Direct lookup by SDK payment id; null when the id is unknown. */
    suspend fun getPaymentOptional(paymentId: String): SparkPaymentSnapshot?
    suspend fun balanceSats(ensureSynced: Boolean = false): ULong
    suspend fun payBolt11Invoice(invoice: String): WalletSendResult
    suspend fun payLightningAddress(address: String, amountSats: ULong, comment: String = ""): WalletSendResult
}

data class SparkPaymentSnapshot(
    val id: String,
    val invoice: String?,
    val status: InvoiceStatus,
    /** false = outgoing payment. Defaults keep legacy constructors (receive-only) working. */
    val isReceive: Boolean = true,
    val amountSats: Long = 0,
    val feeSats: Long = 0,
    val timestampSecs: Long = 0,
    val description: String? = null,
    /** Lightning/HTLC preimage once revealed; null before settle or for non-HTLC payments. */
    val preimage: String? = null,
    /** Payment hash of the HTLC, when known. */
    val paymentHash: String? = null,
)

class BreezSparkSdkClient(
    private val context: Context,
    private val apiKey: String,
    private val seedHex: String,
    private val storageName: String = "breez-spark-wallet",
) : SparkSdkClient {
    private val mutex = Mutex()
    private var sdk: BreezSdk? = null

    override suspend fun receiveBolt11Invoice(amountSats: ULong, memo: String, expirySecs: UInt): String {
        val response = sdk().receivePayment(
            ReceivePaymentRequest(
                ReceivePaymentMethod.Bolt11Invoice(
                    description = memo,
                    amountSats = amountSats,
                    expirySecs = expirySecs,
                    paymentHash = null,
                    receiverIdentityPublicKey = null,
                ),
            ),
        )
        return response.paymentRequest
    }

    override suspend fun listPayments(limit: UInt, offset: UInt?): List<SparkPaymentSnapshot> {
        val response = sdk().listPayments(
            ListPaymentsRequest(
                typeFilter = null, // receives AND sends — the wallet UI shows both
                statusFilter = null,
                assetFilter = null,
                paymentDetailsFilter = null,
                fromTimestamp = null,
                toTimestamp = null,
                offset = offset,
                limit = limit,
                sortAscending = false,
            ),
        )
        return response.payments.map(::toSnapshot)
    }

    /** Map an SDK payment to the wallet's snapshot, keeping the HTLC details
     *  (preimage/payment hash) that NWC callers need. */
    private fun toSnapshot(payment: breez_sdk_spark.Payment): SparkPaymentSnapshot {
        val lightning = payment.details as? PaymentDetails.Lightning
        val spark = payment.details as? PaymentDetails.Spark
        return SparkPaymentSnapshot(
            id = payment.id,
            invoice = lightning?.invoice,
            status = when (payment.status) {
                PaymentStatus.COMPLETED -> InvoiceStatus.Settled
                PaymentStatus.PENDING -> InvoiceStatus.Pending
                PaymentStatus.FAILED -> InvoiceStatus.Expired
                else -> InvoiceStatus.Unknown
            },
            isReceive = payment.paymentType == PaymentType.RECEIVE,
            amountSats = payment.amount.toLong(),
            feeSats = payment.fees.toLong(),
            timestampSecs = payment.timestamp.toLong(),
            description = lightning?.description,
            preimage = (lightning?.htlcDetails?.preimage ?: spark?.htlcDetails?.preimage)
                ?.takeIf { it.isNotBlank() },
            paymentHash = lightning?.htlcDetails?.paymentHash ?: spark?.htlcDetails?.paymentHash,
        )
    }

    override suspend fun getPaymentOptional(paymentId: String): SparkPaymentSnapshot? = runCatching {
        sdk().getPayment(breez_sdk_spark.GetPaymentRequest(paymentId)).payment
    }.getOrNull()?.let { payment ->
        toSnapshot(payment)
    }

    override suspend fun balanceSats(ensureSynced: Boolean): ULong {
        return sdk().getInfo(GetInfoRequest(ensureSynced)).balanceSats
    }

    override suspend fun payBolt11Invoice(invoice: String): WalletSendResult {
        val prepare = sdk().prepareSendPayment(
            PrepareSendPaymentRequest(
                paymentRequest = PaymentRequest.Input(invoice),
                amount = null,
                tokenIdentifier = null,
                conversionOptions = null,
                feePolicy = FeePolicy.FEES_EXCLUDED,
            ),
        )
        val response = sdk().sendPayment(
            SendPaymentRequest(
                prepareResponse = prepare,
                options = SendPaymentOptions.Bolt11Invoice(preferSpark = true, completionTimeoutSecs = 60u),
                idempotencyKey = UUID.randomUUID().toString(),
            ),
        )
        val snapshot = toSnapshot(response.payment)
        return WalletSendResult(
            paymentId = snapshot.id,
            amountSats = snapshot.amountSats,
            feeSats = snapshot.feeSats,
            preimage = snapshot.preimage,
        )
    }

    override suspend fun payLightningAddress(address: String, amountSats: ULong, comment: String): WalletSendResult {
        val input = sdk().parse(address)
        val payRequest = when (input) {
            is InputType.LightningAddress -> input.v1.payRequest
            is InputType.LnurlPay -> input.v1
            else -> error("Not a lightning address or LNURL-pay request")
        }
        val prepare = sdk().prepareLnurlPay(
            PrepareLnurlPayRequest(
                amount = amountSats.toBigInteger() * BigInteger.valueOf(1_000L),
                payRequest = payRequest,
                comment = comment.takeIf { it.isNotBlank() },
                validateSuccessActionUrl = true,
                tokenIdentifier = null,
                conversionOptions = null,
                feePolicy = FeePolicy.FEES_EXCLUDED,
            ),
        )
        val response = sdk().lnurlPay(LnurlPayRequest(prepare, UUID.randomUUID().toString()))
        return WalletSendResult(
            paymentId = response.payment.id,
            amountSats = response.payment.amount.toLong(),
        )
    }

    private suspend fun sdk(): BreezSdk = mutex.withLock {
        sdk ?: run {
            val config = defaultConfig(Network.MAINNET).apply {
                this.apiKey = this@BreezSparkSdkClient.apiKey
            }
            val storageDir = File(context.filesDir, storageName).apply { mkdirs() }.absolutePath
            connect(
                ConnectRequest(
                    config = config,
                    seed = Seed.Entropy(seedHex.hexToBytes()),
                    storageDir = storageDir,
                ),
            ).also { sdk = it }
        }
    }
}

class SparkLightningWallet(
    private val client: SparkSdkClient,
) : SpendableLightningWallet {
    override suspend fun createInvoice(amountMsat: Long, memo: String): PaymentInvoice {
        require(amountMsat > 0 && amountMsat <= Long.MAX_VALUE - 999L) { "amountMsat out of range" }
        val amountSats = ((amountMsat + 999L) / 1_000L).toULong()
        val invoice = client.receiveBolt11Invoice(amountSats, memo)
        val paymentId = client.listPayments().firstOrNull { it.invoice == invoice }?.id ?: invoice
        return PaymentInvoice(
            id = paymentId,
            amountMsat = amountSats.toLong() * 1_000L,
            memo = memo,
            bolt11 = invoice,
        )
    }

    override suspend fun lookupInvoice(invoiceId: String): InvoiceStatus {
        runCatching { client.balanceSats(ensureSynced = true) }
        // Try the cheap direct lookup first (invoiceId is usually our own
        // payment id), then fall back to a full-history scan. The previous
        // code listed only the newest 50 payments — older invoices silently
        // reported Unknown, which failed paid work.
        client.getPaymentOptional(invoiceId)?.let { return it.status }
        val isBolt11 = invoiceId.startsWith("ln")
        var offset: UInt? = null
        while (true) {
            val page = client.listPayments(limit = LOOKUP_PAGE_SIZE, offset = offset)
            val match = page.firstOrNull {
                it.id == invoiceId ||
                    it.invoice?.equals(invoiceId, ignoreCase = true) == true ||
                    (!isBolt11 && it.paymentHash.equals(invoiceId, ignoreCase = true))
            }
            if (match != null) return match.status
            if (page.size < LOOKUP_PAGE_SIZE.toInt()) return InvoiceStatus.Unknown
            offset = (offset ?: 0u) + page.size.toUInt()
        }
    }

    override suspend fun balanceSats(): Long = client.balanceSats(ensureSynced = true).toLong()

    /** Recent wallet transactions (newest first), receives and sends. */
    suspend fun recentTransactions(limit: UInt = 20u): List<SparkPaymentSnapshot> =
        client.listPayments(limit)

    override suspend fun payInvoice(bolt11: String): WalletSendResult = client.payBolt11Invoice(bolt11)

    override suspend fun payLightningAddress(address: String, amountSats: Long, comment: String): WalletSendResult {
        require(amountSats > 0) { "amountSats must be positive" }
        return client.payLightningAddress(address, amountSats.toULong(), comment)
    }
}

fun generateSparkSeedHex(byteCount: Int = 16): String {
    require(byteCount in 16..64) { "byteCount must be between 16 and 64" }
    val bytes = ByteArray(byteCount)
    SecureRandom().nextBytes(bytes)
    return bytes.toHex()
}

private const val LOOKUP_PAGE_SIZE = 100u

fun String.hexToBytes(): ByteArray {
    val clean = trim().lowercase(Locale.US)
    require(clean.length % 2 == 0) { "hex string must have an even length" }
    require(clean.matches(Regex("^[0-9a-f]+$"))) { "hex string contains non-hex characters" }
    return ByteArray(clean.length / 2) { index ->
        clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
