package net.nostr.hench.nwc

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Durable per-event-id ledger for inbound NIP-47 requests. This is the replay
 * firewall for `pay_invoice` and friends: an event id may be executed at most
 * once across process restarts, and a completed request replays its stored
 * response instead of re-executing.
 *
 * Distinct from `net.nostr.hench.worker.ProcessedJobStore` on purpose: the
 * worker ledger tracks job dedupe, this one tracks wire-level request state
 * (in-flight vs completed) so a crash mid-payment fails closed instead of
 * re-paying or silently swallowing a retry.
 */
sealed class NwcRequestClaim {
    /** First time this event id is seen — caller may execute. */
    data object Execute : NwcRequestClaim()

    /** Same event id currently in flight — drop silently. */
    data object InFlight : NwcRequestClaim()

    /**
     * Event id was interrupted (crash between execute and complete). For
     * pay_invoice the safe answer is "state unknown" — the caller must NOT
     * re-execute, because the payment may already have left the wallet.
     */
    data object Interrupted : NwcRequestClaim()

    /**
     * Event id completed earlier; [responseJson] is the signed response event
     * ready to resend (null when no response was ever emitted).
     */
    data class Completed(val responseJson: String?) : NwcRequestClaim()

    /** Ledger write failed — caller must not execute (fail closed). */
    data object Unavailable : NwcRequestClaim()
}

interface NwcRequestLedger {
    /**
     * Atomically claim `eventId` for execution. MUST be called before any
     * side effect; implementations must make the claim durable (SharedPreferences
     * `commit()`), so a crash after claiming never re-executes.
     */
    fun claim(eventId: String): NwcRequestClaim

    /**
     * Record the terminal outcome. `responseJson` is the verbatim relay
     * message that was sent (null when the request produced no response).
     * Callers should invoke this even for "no response" outcomes so a replay
     * gets a deterministic answer.
     */
    fun complete(eventId: String, responseJson: String?)
}

@Serializable
private data class NwcRequestEntry(
    val status: String,          // "P" processing | "C" completed
    val atMs: Long,
    val responseJson: String? = null,
)

/** Test/fake implementation — NOT durable, never use in production paths. */
class InMemoryNwcRequestLedger : NwcRequestLedger {
    private val entries = java.util.concurrent.ConcurrentHashMap<String, NwcRequestEntry>()

    override fun claim(eventId: String): NwcRequestClaim {
        val fresh = NwcRequestEntry("P", System.currentTimeMillis())
        val existing = entries.putIfAbsent(eventId, fresh) ?: return NwcRequestClaim.Execute
        return when (existing.status) {
            "C" -> NwcRequestClaim.Completed(existing.responseJson)
            else -> NwcRequestClaim.InFlight
        }
    }

    override fun complete(eventId: String, responseJson: String?) {
        entries[eventId] = NwcRequestEntry("C", System.currentTimeMillis(), responseJson)
    }
}

/**
 * SharedPreferences-backed ledger (own file `nwc_request_ledger`, so it never
 * collides with the worker's `processed_jobs` idempotency ledger). Claims and
 * completions use `commit()`; a failed claim commit reports
 * [NwcRequestClaim.Unavailable] and the caller must not execute.
 */
class SharedPreferencesNwcRequestLedger(context: Context) : NwcRequestLedger {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun claim(eventId: String): NwcRequestClaim {
        val existing = decode(prefs.getString(eventId, null))
        if (existing != null) {
            return when (existing.status) {
                "C" -> NwcRequestClaim.Completed(existing.responseJson)
                // A stale PROCESSING row means a previous process died between
                // claim and complete — the payment outcome is unknown.
                else -> if (System.currentTimeMillis() - existing.atMs < IN_FLIGHT_WINDOW_MS) {
                    NwcRequestClaim.InFlight
                } else {
                    NwcRequestClaim.Interrupted
                }
            }
        }
        val entry = NwcRequestEntry("P", System.currentTimeMillis())
        val committed = prefs.edit().putString(eventId, json.encodeToString(NwcRequestEntry.serializer(), entry)).commit()
        return if (committed) NwcRequestClaim.Execute else NwcRequestClaim.Unavailable
    }

    @Synchronized
    override fun complete(eventId: String, responseJson: String?) {
        prefs.edit()
            .putString(eventId, json.encodeToString(NwcRequestEntry.serializer(),
                NwcRequestEntry("C", System.currentTimeMillis(), responseJson)))
            .commit()
    }

    private fun decode(raw: String?): NwcRequestEntry? =
        raw?.let { runCatching { json.decodeFromString(NwcRequestEntry.serializer(), it) }.getOrNull() }

    companion object {
        private const val PREFS_FILE = "nwc_request_ledger"
        /** PROCESSING rows younger than this are live duplicates, not crashes. */
        private const val IN_FLIGHT_WINDOW_MS = 120_000L
    }
}
