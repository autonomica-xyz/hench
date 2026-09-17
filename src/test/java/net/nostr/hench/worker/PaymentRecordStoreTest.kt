package net.nostr.hench.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payment-record store semantics. The SharedPreferences implementation is
 * covered by code-level inspection and the `PaymentSessionStoreI` contract
 * javadoc, which requires `putPayment` to use `commit()` (not `apply()`) for
 * durable-before-submit. Robolectric is not a dependency, so this file tests the
 * in-memory implementation that implements the same interface.
 */
class PaymentRecordStoreTest {

    @Test
    fun canonicalIdRoundTrip() {
        val store = InMemoryPaymentSessionStore()
        val record = PaymentRecord(
            canonicalInvocationId = "canon-1",
            payReq = "lnbc1fake",
            amountSats = 1000L,
            pmi = "bitcoin-lightning-bolt11",
            status = PaymentRecord.STATUS_PENDING,
        )

        store.putPayment(record)

        val got = store.getPayment("canon-1")
        assertEquals(record, got)
    }

    @Test
    fun allPaymentsReflectsAllStoredRecords() {
        val store = InMemoryPaymentSessionStore()
        val a = PaymentRecord("canon-a", "lnbc-a", 1_000L, "bitcoin-lightning-bolt11", PaymentRecord.STATUS_PENDING)
        val b = PaymentRecord("canon-b", "lnbc-b", 2_000L, "bitcoin-lightning-bolt11", PaymentRecord.STATUS_SETTLED)

        store.putPayment(a)
        store.putPayment(b)

        val all = store.allPayments()
        assertEquals(2, all.size)
        assertTrue(all.any { it.canonicalInvocationId == "canon-a" && it.status == PaymentRecord.STATUS_PENDING })
        assertTrue(all.any { it.canonicalInvocationId == "canon-b" && it.status == PaymentRecord.STATUS_SETTLED })
    }

    @Test
    fun getMissingPaymentReturnsNull() {
        val store = InMemoryPaymentSessionStore()
        assertNull(store.getPayment("missing"))
    }

    @Test
    fun getByCanonicalIdFindsJobRecord() {
        val store = InMemoryPaymentSessionStore()
        val job = JobRecord(
            idempotencyKey = "idk-1",
            canonicalInvocationId = "canon-1",
            jobRequestJson = "{}",
            argsJson = "{}",
            status = JobRecord.STATUS_COMPLETED,
        )

        store.put(job)

        assertEquals(job, store.getByCanonicalId("canon-1"))
        assertNull(store.getByCanonicalId("missing"))
    }

    @Test
    fun putPaymentReturnsCommitResult() {
        val store = InMemoryPaymentSessionStore()
        val record = PaymentRecord("canon-1", "lnbc1fake", 1000L, "bitcoin-lightning-bolt11")

        assertTrue(store.putPayment(record))
        store.failPutPayment = true
        assertEquals(false, store.putPayment(record.copy(canonicalInvocationId = "canon-2")))
    }

    @Test
    fun paymentRecordAndJobRecordStoresAreIndependent() {
        val store = InMemoryPaymentSessionStore()
        val jobKey = "idempotency-1"
        val job = JobRecord(
            idempotencyKey = jobKey,
            jobRequestJson = "{}",
            argsJson = "{}",
            status = JobRecord.STATUS_COMPLETED,
        )
        val payment = PaymentRecord("canon-1", "lnbc1fake", 1000L, "bitcoin-lightning-bolt11")

        store.put(job)
        store.putPayment(payment)

        assertEquals(job, store.get(jobKey))
        assertEquals(payment, store.getPayment("canon-1"))
        assertEquals(1, store.all().size)
        assertEquals(1, store.allPayments().size)
    }

    @Test
    fun putPaymentIsMonotonicAndNeverDowngradesStatus() {
        val store = InMemoryPaymentSessionStore()
        val base = PaymentRecord("canon-m", "lnbc1fake", 1000L, "bitcoin-lightning-bolt11")

        // PENDING → SETTLED advances.
        store.putPayment(base)
        store.putPayment(base.copy(status = PaymentRecord.STATUS_SETTLED))
        assertEquals(PaymentRecord.STATUS_SETTLED, store.getPayment("canon-m")?.status)

        // SETTLED → DISPATCHED advances.
        store.putPayment(base.copy(status = PaymentRecord.STATUS_DISPATCHED))
        assertEquals(PaymentRecord.STATUS_DISPATCHED, store.getPayment("canon-m")?.status)

        // A racing DISPATCHED write must not be downgraded by a late SETTLED write.
        store.putPayment(base.copy(status = PaymentRecord.STATUS_SETTLED))
        assertEquals(PaymentRecord.STATUS_DISPATCHED, store.getPayment("canon-m")?.status)
    }
}
