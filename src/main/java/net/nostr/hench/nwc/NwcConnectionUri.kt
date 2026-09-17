package net.nostr.hench.nwc

import java.net.URI
import java.net.URLDecoder

class InvalidNwcConnectionUriException(message: String) : IllegalArgumentException(message)

data class NwcConnectionUri(
    val walletPubkeyHex: String,
    val clientSecretHex: String,
    val relays: List<String>,
    val lud16: String?,
) {
    companion object {
        fun parse(raw: String): Result<NwcConnectionUri> = runCatching {
            val uri = URI(raw)
            requireNwc(uri.scheme == "nostr+walletconnect", "URI scheme must be nostr+walletconnect")
            val pubkey = uri.host ?: uri.schemeSpecificPart.substringBefore('?').removePrefix("//")
            requireNwc(pubkey.matches(HEX_32), "wallet pubkey must be 32-byte hex")

            val params = parseQuery(uri.rawQuery.orEmpty())
            val secret = params["secret"]?.firstOrNull().orEmpty()
            requireNwc(secret.matches(HEX_32), "secret must be 32-byte hex")
            val relays = params["relay"].orEmpty().filter { it.isNotBlank() }
            requireNwc(relays.isNotEmpty(), "at least one relay is required")

            NwcConnectionUri(
                walletPubkeyHex = pubkey.lowercase(),
                clientSecretHex = secret.lowercase(),
                relays = relays,
                lud16 = params["lud16"]?.firstOrNull(),
            )
        }.recoverCatching { error ->
            if (error is InvalidNwcConnectionUriException) throw error
            throw InvalidNwcConnectionUriException(error.message ?: "invalid NWC URI")
        }

        private val HEX_32 = Regex("[0-9a-fA-F]{64}")

        private fun requireNwc(condition: Boolean, message: String) {
            if (!condition) throw InvalidNwcConnectionUriException(message)
        }

        private fun parseQuery(query: String): Map<String, List<String>> {
            if (query.isBlank()) return emptyMap()
            return query.split('&')
                .filter { it.isNotBlank() }
                .map { pair ->
                    val key = pair.substringBefore('=')
                    val value = pair.substringAfter('=', "")
                    decode(key) to decode(value)
                }
                .groupBy({ it.first }, { it.second })
        }

        private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
    }
}
