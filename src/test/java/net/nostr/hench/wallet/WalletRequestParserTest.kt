package net.nostr.hench.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletRequestParserTest {
    @Test
    fun parsesBolt11InvoiceWithLightningPrefix() {
        val target = WalletRequestParser.parse("lightning:lnbc2500n1test")

        assertEquals(WalletPaymentKind.BOLT11_INVOICE, target.kind)
        assertEquals("lnbc2500n1test", target.value)
    }

    @Test
    fun parsesLightningAddress() {
        val target = WalletRequestParser.parse("alice@example.com")

        assertEquals(WalletPaymentKind.LIGHTNING_ADDRESS, target.kind)
        assertEquals("alice@example.com", target.value)
    }

    @Test
    fun rejectsUnsupportedInput() {
        val result = runCatching { WalletRequestParser.parse("not a payment request") }

        assertTrue(result.isFailure)
    }
}
