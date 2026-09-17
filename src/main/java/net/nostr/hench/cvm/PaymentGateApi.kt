package net.nostr.hench.cvm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import uniffi.contextvm_ffi.Server

/**
 * Foreign seam for the CEP-8 payment gate exposed by the UniFFI Server. The
 * Kotlin side drains [recvGateRequest], creates the invoice with its own wallet,
 * and then calls the matching mark/submit methods.
 *
 * The production implementation ([FfiPaymentGateApi]) wraps the blocking FFI
 * Server methods and dispatches them on [Dispatchers.IO]. The no-op fake lives
 * in the test source set so compile-level tests can inject a scripted gate.
 */
interface PaymentGateApi {
    /** Returns the next gate event, or `null` if the timeout expires. */
    suspend fun recvGateRequest(timeoutSecs: ULong): GateRequest?

    /**
     * Tell the gate an invoice has been created for this canonical request.
     * Must only be called after the payment record has been durably committed
     * (PaymentSessionStore payment-record path uses `commit()`).
     */
    suspend fun submitInvoice(
        requestEventId: String,
        amountSats: Long,
        payReq: String,
        pmi: String,
        ttlSecs: ULong,
        description: String?,
    ): Boolean

    suspend fun markPaymentSettled(payReq: String, metaJson: String?): Boolean
    suspend fun markPaymentFailed(payReq: String, message: String?): Boolean
    suspend fun markReplayed(requestEventId: String): Boolean
}

/**
 * Inbound CEP-8 payment-gate event. The [canonicalInvocationId] is the durable
 * key for the consumer's payment/result records; it outlives the JSON-RPC id
 * that forms the idempotency key.
 */
@Serializable
data class GateRequest(
    val requestEventId: String,
    val clientPubkey: String,
    val method: String,
    val paramsJson: String,
    val capabilityName: String,
    val canonicalInvocationId: String,
)

/**
 * Production implementation over the UniFFI Server's blocking payment methods.
 * All calls are dispatched to [Dispatchers.IO] because the FFI owns its tokio
 * runtime and blocks the calling thread.
 */
class FfiPaymentGateApi(private val server: Server) : PaymentGateApi {

    override suspend fun recvGateRequest(timeoutSecs: ULong): GateRequest? = withContext(Dispatchers.IO) {
        server.recvPaymentGateRequest(timeoutSecs)?.toGateRequest()
    }

    override suspend fun submitInvoice(
        requestEventId: String,
        amountSats: Long,
        payReq: String,
        pmi: String,
        ttlSecs: ULong,
        description: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            server.submitInvoice(requestEventId, amountSats, payReq, pmi, ttlSecs, description)
            true
        }.getOrDefault(false)
    }

    override suspend fun markPaymentSettled(payReq: String, metaJson: String?): Boolean = withContext(Dispatchers.IO) {
        runCatching { server.markPaymentSettled(payReq, metaJson); true }.getOrDefault(false)
    }

    override suspend fun markPaymentFailed(payReq: String, message: String?): Boolean = withContext(Dispatchers.IO) {
        runCatching { server.markPaymentFailed(payReq, message); true }.getOrDefault(false)
    }

    override suspend fun markReplayed(requestEventId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { server.markReplayed(requestEventId); true }.getOrDefault(false)
    }

    private fun uniffi.contextvm_ffi.PaymentGateRequest.toGateRequest() = GateRequest(
        requestEventId = requestEventId,
        clientPubkey = clientPubkey,
        method = method,
        paramsJson = paramsJson,
        capabilityName = capabilityName,
        canonicalInvocationId = canonicalInvocationId,
    )
}
