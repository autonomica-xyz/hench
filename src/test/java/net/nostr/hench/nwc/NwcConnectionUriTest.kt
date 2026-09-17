package net.nostr.hench.nwc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NwcConnectionUriTest {
    @Test
    fun parsesNostrWalletConnectUri() {
        val uri = "nostr+walletconnect://" + "a".repeat(64) +
            "?relay=wss%3A%2F%2Frelay.example.com&relay=wss%3A%2F%2Frelay2.example.com&secret=" + "b".repeat(64) +
            "&lud16=alice%40example.com"

        val parsed = NwcConnectionUri.parse(uri).getOrThrow()

        assertEquals("a".repeat(64), parsed.walletPubkeyHex)
        assertEquals("b".repeat(64), parsed.clientSecretHex)
        assertEquals(listOf("wss://relay.example.com", "wss://relay2.example.com"), parsed.relays)
        assertEquals("alice@example.com", parsed.lud16)
    }

    @Test
    fun rejectsMissingSecret() {
        val uri = "nostr+walletconnect://${"a".repeat(64)}?relay=wss%3A%2F%2Frelay.example.com"

        val parsed = NwcConnectionUri.parse(uri)

        assertTrue(parsed.exceptionOrNull() is InvalidNwcConnectionUriException)
    }
}
