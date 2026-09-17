package net.nostr.hench.wallet

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import net.nostr.hench.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RealSparkWalletInstrumentedTest {
    @Test
    fun createsInvoiceAndWaitsForRealSettlement() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val apiKey = args.getString("sparkApiKey")?.takeIf { it.isNotBlank() } ?: BuildConfig.BREEZ_SPARK_API_KEY
        val seedHex = args.getString("sparkSeedHex").orEmpty()
        val amountSats = args.getString("amountSats")?.toULongOrNull() ?: 1uL
        val waitSeconds = args.getString("waitSeconds")?.toLongOrNull() ?: 180L
        assumeTrue("sparkApiKey instrumentation arg is required", apiKey.isNotBlank())
        assumeTrue("sparkSeedHex instrumentation arg is required", seedHex.isNotBlank())

        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = BreezSparkSdkClient(
            context = context,
            apiKey = apiKey,
            seedHex = seedHex,
            storageName = "breez-spark-wallet-e2e-${seedHex.take(8)}",
        )
        val wallet = SparkLightningWallet(client)
        val invoice = wallet.createInvoice(amountSats.toLong() * 1_000L, "hench real payment E2E")
        Log.i(TAG, "REAL_SPARK_INVOICE:${invoice.bolt11}")
        Log.i(TAG, "REAL_SPARK_PAYMENT_ID:${invoice.id}")
        Log.i(TAG, "REAL_SPARK_AMOUNT_SATS:$amountSats")

        val deadline = System.currentTimeMillis() + waitSeconds * 1_000L
        var status = wallet.lookupInvoice(invoice.id)
        while (status != InvoiceStatus.Settled && System.currentTimeMillis() < deadline) {
            delay(3_000L)
            status = wallet.lookupInvoice(invoice.id)
            Log.i(TAG, "REAL_SPARK_STATUS:$status")
        }

        val balance = client.balanceSats(ensureSynced = false)
        Log.i(TAG, "REAL_SPARK_BALANCE_SATS:$balance")
        assertEquals(InvoiceStatus.Settled, status)
        assertTrue("wallet balance should include received sats", balance >= amountSats)
    }

    companion object {
        private const val TAG = "RealSparkWalletE2E"
    }
}
