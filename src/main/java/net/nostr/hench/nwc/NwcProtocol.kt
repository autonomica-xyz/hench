package net.nostr.hench.nwc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import net.nostr.hench.identity.Bip340Schnorr
import net.nostr.hench.identity.hexToBytes
import net.nostr.hench.nostr.NostrEventSigner
import net.nostr.hench.nostr.NostrSignedEvent

object NwcProtocol {
    const val KIND_INFO = 13194
    const val KIND_REQUEST = 23194
    const val KIND_RESPONSE = 23195
    const val KIND_NOTIFICATION = 23197
    const val KIND_LEGACY_NOTIFICATION = 23196

    /** Canonical Nostr event ids are 64 chars of lowercase hex. Verifiers must
     *  compare ids exactly — a case variant is a different key for ledgers. */
    fun isCanonicalEventId(id: String): Boolean =
        id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * Full authentication for a kind-23195 wallet response: canonical id,
     * recomputed event id, BIP-340 signature, expected wallet author, the p tag
     * targeting [clientPubkeyHex], and the e tag correlating [requestId].
     * Clients MUST run this before trusting decrypted content — NIP-04
     * ciphertext replayed under a signed event otherwise authenticates.
     */
    fun verifyResponseEvent(
        event: NostrSignedEvent,
        walletPubkeyHex: String,
        clientPubkeyHex: String,
        requestId: String,
    ): Boolean {
        if (event.kind != KIND_RESPONSE) return false
        if (event.pubkey != walletPubkeyHex) return false
        if (!event.tags.any { it.size >= 2 && it[0] == "p" && it[1] == clientPubkeyHex }) return false
        if (!event.tags.any { it.size >= 2 && it[0] == "e" && it[1] == requestId }) return false
        if (!isCanonicalEventId(event.id)) return false
        val expectedId = runCatching {
            NostrEventSigner.eventId(event.pubkey, event.createdAt, event.kind, event.tags, event.content)
        }.getOrNull() ?: return false
        if (expectedId != event.id) return false
        return runCatching {
            Bip340Schnorr.verify(expectedId.hexToBytes(), event.pubkey.hexToBytes(), event.sig.hexToBytes())
        }.getOrDefault(false)
    }

    const val METHOD_GET_INFO = "get_info"
    const val METHOD_GET_BALANCE = "get_balance"
    const val METHOD_MAKE_INVOICE = "make_invoice"
    const val METHOD_PAY_INVOICE = "pay_invoice"
    const val METHOD_LOOKUP_INVOICE = "lookup_invoice"

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun makeInvoiceRequest(amountMsat: Long, description: String, expirySeconds: Long? = null): JsonObject {
        require(amountMsat > 0L) { "amountMsat must be positive" }
        return request(METHOD_MAKE_INVOICE, buildJsonObject {
            put("amount", amountMsat)
            put("description", description)
            expirySeconds?.let { put("expiry", it) }
        })
    }

    fun lookupInvoiceRequest(paymentHash: String): JsonObject {
        require(paymentHash.isNotBlank()) { "paymentHash is required" }
        return request(METHOD_LOOKUP_INVOICE, buildJsonObject { put("payment_hash", paymentHash) })
    }

    fun request(method: String, params: JsonObject): JsonObject = buildJsonObject {
        put("method", method)
        put("params", params)
    }

    fun responseResultType(rawDecryptedContent: String): String? = runCatching {
        json.parseToJsonElement(rawDecryptedContent).jsonObject["result_type"]?.toString()?.trim('"')
    }.getOrNull()

    fun requestEventTags(walletPubkeyHex: String, encryption: String = "nip04"): List<List<String>> {
        require(walletPubkeyHex.matches(Regex("[0-9a-fA-F]{64}"))) { "wallet pubkey must be 32-byte hex" }
        return listOf(listOf("p", walletPubkeyHex.lowercase()), listOf("encryption", encryption))
    }

    fun responseSubscriptionFilter(clientPubkeyHex: String): JsonElement = buildJsonObject {
        put("kinds", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(KIND_RESPONSE))))
        put("#p", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(clientPubkeyHex))))
    }
}
