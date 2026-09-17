package net.nostr.hench.wallet

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SparkLightningWalletTest {
    @Test
    fun createsRealSparkInvoiceAndUsesPaymentIdWhenSdkListsIt() = runTest {
        val client = FakeSparkSdkClient()
        val wallet = SparkLightningWallet(client)

        val invoice = wallet.createInvoice(21_001, "download job abc")

        assertEquals("payment-1", invoice.id)
        assertEquals(22_000, invoice.amountMsat)
        assertEquals("download job abc", invoice.memo)
        assertEquals("lnbc22n1spark", invoice.bolt11)
        assertEquals(22uL, client.lastAmountSats)
    }

    @Test
    fun lookupInvoiceMapsCompletedSdkPaymentToSettled() = runTest {
        val client = FakeSparkSdkClient(status = InvoiceStatus.Settled)
        val wallet = SparkLightningWallet(client)
        val invoice = wallet.createInvoice(2_000, "paid")

        assertEquals(InvoiceStatus.Settled, wallet.lookupInvoice(invoice.id))
    }

    @Test
    fun lookupInvoiceSyncsWalletBeforeListingPayments() = runTest {
        val client = FakeSparkSdkClient(status = InvoiceStatus.Settled)
        val wallet = SparkLightningWallet(client)
        val invoice = wallet.createInvoice(2_000, "paid")

        wallet.lookupInvoice(invoice.id)

        assertEquals(true, client.lastEnsureSynced)
    }

    @Test
    fun generatedSeedIsHexEntropy() {
        val seed = generateSparkSeedHex()

        assertEquals(32, seed.length)
        assertTrue(seed.matches(Regex("^[0-9a-f]+$")))
        assertEquals(16, seed.hexToBytes().size)
    }

    @Test
    fun sparkSeedBackupWordsExposeBip39MnemonicForEntropy() {
        val words = sparkSeedBackupWords("00000000000000000000000000000000")

        assertEquals(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            words,
        )
    }

    @Test
    fun sparkSeedBackupWordsTrimAndNormalizeHexInput() {
        val words = sparkSeedBackupWords("  00000000000000000000000000000000  ")

        assertEquals(12, words.split(" ").size)
        assertEquals("about", words.substringAfterLast(" "))
    }

    private class FakeSparkSdkClient(
        private val status: InvoiceStatus = InvoiceStatus.Pending,
    ) : SparkSdkClient {
        var lastAmountSats: ULong = 0uL
        var lastEnsureSynced: Boolean? = null
        private var invoice: String? = null

        override suspend fun receiveBolt11Invoice(amountSats: ULong, memo: String, expirySecs: UInt): String {
            lastAmountSats = amountSats
            invoice = "lnbc${amountSats}n1spark"
            return invoice!!
        }

        override suspend fun listPayments(limit: UInt): List<SparkPaymentSnapshot> {
            return listOf(SparkPaymentSnapshot("payment-1", invoice, status))
        }

        override suspend fun balanceSats(ensureSynced: Boolean): ULong {
            lastEnsureSynced = ensureSynced
            return 0uL
        }

        override suspend fun payBolt11Invoice(invoice: String): WalletSendResult =
            WalletSendResult(paymentId = "send-1", amountSats = 21)

        override suspend fun payLightningAddress(address: String, amountSats: ULong, comment: String): WalletSendResult =
            WalletSendResult(paymentId = "lnaddr-1", amountSats = amountSats.toLong())
    }
}
