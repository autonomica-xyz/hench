package net.nostr.hench.nwc

import net.nostr.hench.identity.Bip340Schnorr
import net.nostr.hench.identity.hexToBytes
import net.nostr.hench.identity.toHex
import java.net.URLEncoder

/**
 * Connection details for this phone acting as a NIP-47/NWC wallet server.
 * The wallet secret signs responses; the client secret is embedded in the URI
 * and used by external apps to encrypt requests to this phone.
 */
data class NwcServerConnection(
    val walletSecretHex: String,
    val clientSecretHex: String,
    val relays: List<String>,
) {
    val walletPubkeyHex: String = Bip340Schnorr.publicKey(walletSecretHex.hexToBytes()).toHex()

    /**
     * Public key of the paired client (derived from the URI `secret`). Only
     * request events signed by this key may touch the wallet — see
     * `NwcServerRuntime.isAuthorizedRequest`.
     */
    val clientPubkeyHex: String = Bip340Schnorr.publicKey(clientSecretHex.hexToBytes()).toHex()

    init {
        require(walletSecretHex.matches(HEX_32)) { "wallet secret must be 32-byte hex" }
        require(clientSecretHex.matches(HEX_32)) { "client secret must be 32-byte hex" }
        require(relays.isNotEmpty()) { "at least one relay is required" }
    }

    fun toUri(): String {
        val query = buildList {
            relays.forEach { relay -> add("relay=${relay.urlEncode()}") }
            add("secret=$clientSecretHex")
        }.joinToString("&")
        return "nostr+walletconnect://$walletPubkeyHex?$query"
    }

    companion object {
        private val HEX_32 = Regex("[0-9a-fA-F]{64}")
        fun supportedMethodsCsv(): String = listOf(
            NwcProtocol.METHOD_GET_INFO,
            NwcProtocol.METHOD_GET_BALANCE,
            NwcProtocol.METHOD_MAKE_INVOICE,
            NwcProtocol.METHOD_PAY_INVOICE,
            NwcProtocol.METHOD_LOOKUP_INVOICE,
        ).joinToString(",")
    }
}

private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
