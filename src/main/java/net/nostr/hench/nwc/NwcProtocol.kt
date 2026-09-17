package net.nostr.hench.nwc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

object NwcProtocol {
    const val KIND_INFO = 13194
    const val KIND_REQUEST = 23194
    const val KIND_RESPONSE = 23195
    const val KIND_NOTIFICATION = 23197
    const val KIND_LEGACY_NOTIFICATION = 23196

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
