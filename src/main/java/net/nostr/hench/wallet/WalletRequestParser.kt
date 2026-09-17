package net.nostr.hench.wallet

import java.util.Locale

enum class WalletPaymentKind {
    BOLT11_INVOICE,
    LIGHTNING_ADDRESS,
}

data class WalletPaymentTarget(
    val kind: WalletPaymentKind,
    val value: String,
)

object WalletRequestParser {
    private val lightningAddress = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun parse(raw: String): WalletPaymentTarget {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "Paste an invoice or lightning address" }
        val normalized = trimmed.removePrefixIgnoreCase("lightning:").trim()
        val lower = normalized.lowercase(Locale.US)
        return when {
            lower.startsWith("lnbc") || lower.startsWith("lntb") || lower.startsWith("lnbcrt") -> {
                WalletPaymentTarget(WalletPaymentKind.BOLT11_INVOICE, normalized)
            }
            lightningAddress.matches(normalized) -> WalletPaymentTarget(WalletPaymentKind.LIGHTNING_ADDRESS, normalized)
            else -> throw IllegalArgumentException("Unsupported payment request. Paste a BOLT11 invoice or lightning address.")
        }
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) substring(prefix.length) else this
}
