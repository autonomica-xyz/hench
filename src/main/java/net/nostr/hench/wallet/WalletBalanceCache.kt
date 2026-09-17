package net.nostr.hench.wallet

import android.content.Context
import androidx.core.content.edit

data class CachedWalletBalance(
    val sats: Long,
    val updatedAtMs: Long,
)

class WalletBalanceCache(context: Context) {
    private val prefs = context.getSharedPreferences("wallet_balance", Context.MODE_PRIVATE)

    fun load(): CachedWalletBalance? {
        if (!prefs.contains(KEY_BALANCE_SATS)) return null
        return CachedWalletBalance(
            sats = prefs.getLong(KEY_BALANCE_SATS, 0L),
            updatedAtMs = prefs.getLong(KEY_UPDATED_AT_MS, 0L),
        )
    }

    fun save(sats: Long, updatedAtMs: Long = System.currentTimeMillis()) {
        prefs.edit {
            putLong(KEY_BALANCE_SATS, sats.coerceAtLeast(0L))
            putLong(KEY_UPDATED_AT_MS, updatedAtMs)
        }
    }

    companion object {
        private const val KEY_BALANCE_SATS = "balance_sats"
        private const val KEY_UPDATED_AT_MS = "updated_at_ms"
    }
}
