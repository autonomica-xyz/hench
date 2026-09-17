package net.nostr.hench.nwc

import android.content.Context
import androidx.core.content.edit
import net.nostr.hench.identity.NostrIdentity
import net.nostr.hench.settings.WorkerSettings

object NwcServerStore {
    private const val PREFS = "worker"
    private const val WALLET_SECRET = "nwc_server_wallet_secret"
    private const val CLIENT_SECRET = "nwc_server_client_secret"

    fun loadOrCreate(context: Context, settings: WorkerSettings): NwcServerConnection {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val walletSecret = prefs.getString(WALLET_SECRET, null)?.takeIf { it.isNotBlank() }
            ?: NostrIdentity.generate().privateKeyHex
        val clientSecret = prefs.getString(CLIENT_SECRET, null)?.takeIf { it.isNotBlank() }
            ?: NostrIdentity.generate().privateKeyHex
        prefs.edit {
            putString(WALLET_SECRET, walletSecret)
            putString(CLIENT_SECRET, clientSecret)
        }
        return NwcServerConnection(
            walletSecretHex = walletSecret,
            clientSecretHex = clientSecret,
            relays = settings.relayUrls,
        )
    }
}
