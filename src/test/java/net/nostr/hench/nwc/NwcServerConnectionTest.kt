package net.nostr.hench.nwc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NwcServerConnectionTest {
    @Test
    fun buildsNostrWalletConnectUriForExternalApps() {
        val connection = NwcServerConnection(
            walletSecretHex = "11".repeat(32),
            clientSecretHex = "22".repeat(32),
            relays = listOf("wss://relay.example.com", "ws://127.0.0.1:10563"),
        )

        val uri = connection.toUri()

        assertTrue(uri.startsWith("nostr+walletconnect://${connection.walletPubkeyHex}"))
        assertTrue(uri.contains("relay=wss%3A%2F%2Frelay.example.com"))
        assertTrue(uri.contains("relay=ws%3A%2F%2F127.0.0.1%3A10563"))
        assertTrue(uri.contains("secret=${connection.clientSecretHex}"))
    }

    @Test
    fun rejectsEmptyRelayListSoNwcServerUsesConfiguredCommunicationRelaysOnly() {
        val error = runCatching {
            NwcServerConnection(
                walletSecretHex = "11".repeat(32),
                clientSecretHex = "22".repeat(32),
                relays = emptyList(),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("at least one relay"))
    }

    @Test
    fun infoListsMinimalSupportedWalletMethods() {
        val info = NwcServerConnection.supportedMethodsCsv()

        assertEquals("get_info,get_balance,make_invoice,pay_invoice,lookup_invoice", info)
    }
}
