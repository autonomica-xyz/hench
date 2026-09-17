package net.nostr.hench.cvm

/** A no-op implementation suitable for compile-level tests and mocks. */
class NoopPaymentGateApi : PaymentGateApi {
    override suspend fun recvGateRequest(timeoutSecs: ULong): GateRequest? = null
    override suspend fun submitInvoice(
        requestEventId: String,
        amountSats: Long,
        payReq: String,
        pmi: String,
        ttlSecs: ULong,
        description: String?,
    ): Boolean = true

    override suspend fun markPaymentSettled(payReq: String, metaJson: String?): Boolean = true
    override suspend fun markPaymentFailed(payReq: String, message: String?): Boolean = true
    override suspend fun markReplayed(requestEventId: String): Boolean = true
}
