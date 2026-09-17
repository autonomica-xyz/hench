package net.nostr.hench.wallet

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeLightningWalletTest {
    @Test
    fun createsInvoiceAndStartsPending() = runTest {
        val wallet = FakeLightningWallet()

        val invoice = wallet.createInvoice(amountMsat = 21_000, memo = "job job1")

        assertEquals(21_000, invoice.amountMsat)
        assertEquals("job job1", invoice.memo)
        assertTrue(invoice.bolt11.startsWith("lnbc"))
        assertEquals(InvoiceStatus.Pending, wallet.lookupInvoice(invoice.id))
    }

    @Test
    fun canMarkInvoiceSettledForLocalPaidFlowTests() = runTest {
        val wallet = FakeLightningWallet()
        val invoice = wallet.createInvoice(amountMsat = 21_000, memo = "job job1")

        wallet.markSettled(invoice.id)

        assertEquals(InvoiceStatus.Settled, wallet.lookupInvoice(invoice.id))
    }
}
