package net.nostr.hench.identity

import java.security.SecureRandom

data class NostrIdentity(
    val privateKeyHex: String,
    val publicKeyHex: String,
) {
    fun toNsec(): String = Bech32.encode("nsec", privateKeyHex.hexToBytes())

    companion object {
        private val random = SecureRandom()

        fun generate(): NostrIdentity {
            while (true) {
                val bytes = ByteArray(32)
                random.nextBytes(bytes)
                runCatching { return fromPrivateKeyHex(bytes.toHex()) }
            }
        }

        fun fromPrivateKey(key: String): NostrIdentity {
            val trimmed = key.trim()
            return if (trimmed.startsWith("nsec1", ignoreCase = true)) {
                fromPrivateKeyHex(Bech32.decode("nsec", trimmed).toHex())
            } else {
                fromPrivateKeyHex(trimmed)
            }
        }

        fun fromPrivateKeyHex(hex: String): NostrIdentity {
            val normalized = hex.trim().removePrefix("0x").lowercase()
            require(normalized.matches(Regex("[0-9a-f]{64}"))) { "Nostr private key must be 32-byte hex" }
            val privateKey = normalized.hexToBytes()
            val publicKey = Bip340Schnorr.publicKey(privateKey).toHex()
            return NostrIdentity(privateKeyHex = normalized, publicKeyHex = publicKey)
        }
    }
}
