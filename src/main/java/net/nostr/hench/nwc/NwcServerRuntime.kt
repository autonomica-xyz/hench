package net.nostr.hench.nwc

import net.nostr.hench.identity.Bip340Schnorr
import net.nostr.hench.identity.hexToBytes
import net.nostr.hench.nostr.NostrEventEnvelope
import net.nostr.hench.nostr.NostrEventSigner
import net.nostr.hench.nostr.NostrRelayMessageCodec
import net.nostr.hench.nostr.NostrSignedEvent
import net.nostr.hench.wallet.SpendableLightningWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class NwcServerRuntime(
    private val connection: NwcServerConnection,
    private val wallet: SpendableLightningWallet,
    private val http: OkHttpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build(),
    /**
     * Durable request ledger — REQUIRED, deliberately has no default: the
     * in-memory implementation loses replay protection on restart and must
     * never back a wallet that can spend. Production callers pass
     * [SharedPreferencesNwcRequestLedger].
     */
    private val ledger: NwcRequestLedger,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = mutableListOf<WebSocket>()

    fun start() {
        stop()
        connection.relays.forEach { relay ->
            val socket = http.newWebSocket(Request.Builder().url(relay).build(), listener())
            synchronized(sockets) { sockets += socket }
        }
    }

    fun stop() {
        val current = synchronized(sockets) { sockets.toList().also { sockets.clear() } }
        current.forEach { it.close(1000, "nwc server stopped") }
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private fun listener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(subscriptionMessage())
            webSocket.send(infoEventMessage())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val request = runCatching { parseRelayEvent(text) }.getOrNull() ?: return
            if (!isAuthorizedRequest(request)) return
            // Contain every failure: an unhandled throw inside this coroutine
            // would crash the process; a dropped request is the safe outcome.
            scope.launch { runCatching { handleRequest(webSocket, request) } }
        }
    }

    /**
     * Authentication gate. NIP-04 decryption alone does NOT authenticate the
     * sender (anyone can encrypt to the public wallet pubkey — the info event
     * is published openly), so we require:
     *  - kind == NWC request and a p-tag targeting our wallet pubkey,
     *  - event pubkey == the paired client's pubkey (only the holder of the
     *    URI secret can produce this),
     *  - event id matching the serialized content AND a valid BIP-340 sig.
     * Exposed for tests; must stay cheap — runs per relay message.
     */
    internal fun isAuthorizedRequest(request: NostrSignedEvent): Boolean {
        if (request.kind != NwcProtocol.KIND_REQUEST) return false
        // Canonical form ONLY: a case-variant id must never reach the ledger —
        // it keys a different row yet authenticates the same signed content.
        if (!NwcProtocol.isCanonicalEventId(request.id)) return false
        if (!request.pubkey.equals(connection.clientPubkeyHex, ignoreCase = false)) return false
        if (!request.tags.any { it.size >= 2 && it[0] == "p" && it[1] == connection.walletPubkeyHex }) return false
        val expectedId = runCatching {
            NostrEventSigner.eventId(request.pubkey, request.createdAt, request.kind, request.tags, request.content)
        }.getOrNull() ?: return false
        if (expectedId != request.id) return false
        return runCatching {
            Bip340Schnorr.verify(request.id.hexToBytes(), request.pubkey.hexToBytes(), request.sig.hexToBytes())
        }.getOrDefault(false)
    }

    private suspend fun handleRequest(webSocket: WebSocket, request: NostrSignedEvent) {
        when (val claim = ledger.claim(request.id)) {
            NwcRequestClaim.InFlight -> return                      // duplicate in-flight delivery
            is NwcRequestClaim.Completed -> {                     // replay: resend stored response
                claim.responseJson?.let { webSocket.send(it) }
                return
            }
            NwcRequestClaim.Interrupted -> {                      // crash mid-request: never re-execute
                sendErrorResponse(webSocket, request, "INTERNAL", "request outcome unknown after restart; not retried")
                return
            }
            NwcRequestClaim.Unavailable -> {                      // ledger write failed: fail closed
                sendErrorResponse(webSocket, request, "INTERNAL", "request ledger unavailable")
                return
            }
            NwcRequestClaim.Execute -> Unit
        }
        val plain = runCatching { Nip04.decrypt(connection.walletSecretHex, request.pubkey, request.content) }.getOrElse {
            ledger.complete(request.id, null)
            return
        }
        val responseContent = runCatching { handlePlainRequest(plain) }
            .getOrElse { errorResponse(code = "INTERNAL", error = it.message ?: "NWC request failed") }
        val encrypted = runCatching {
            Nip04.encrypt(connection.walletSecretHex, request.pubkey, json.encodeToString(JsonObject.serializer(), responseContent))
        }.getOrElse {
            ledger.complete(request.id, null)
            return
        }
        val event = NostrEventSigner.sign(
            NostrEventEnvelope(
                kind = NwcProtocol.KIND_RESPONSE,
                content = encrypted,
                tags = listOf(listOf("p", request.pubkey), listOf("e", request.id), listOf("encryption", "nip04")),
            ),
            connection.walletSecretHex,
        )
        val message = NostrRelayMessageCodec.eventMessage(event)
        webSocket.send(message)
        ledger.complete(request.id, message)
    }

    /** Best-effort error reply for claims we refuse (never executed). */
    private fun sendErrorResponse(webSocket: WebSocket, request: NostrSignedEvent, code: String, message: String) {
        runCatching {
            val encrypted = Nip04.encrypt(
                connection.walletSecretHex,
                request.pubkey,
                json.encodeToString(JsonObject.serializer(), errorResponse(code = code, error = message)),
            )
            val event = NostrEventSigner.sign(
                NostrEventEnvelope(
                    kind = NwcProtocol.KIND_RESPONSE,
                    content = encrypted,
                    tags = listOf(listOf("p", request.pubkey), listOf("e", request.id), listOf("encryption", "nip04")),
                ),
                connection.walletSecretHex,
            )
            webSocket.send(NostrRelayMessageCodec.eventMessage(event))
        }
    }

    internal suspend fun handlePlainRequest(plain: String): JsonObject {
        val request = json.parseToJsonElement(plain).jsonObject
        val method = request["method"]?.jsonPrimitive?.contentOrNull ?: error("missing method")
        val params = request["params"]?.jsonObject ?: JsonObject(emptyMap())
        return when (method) {
            NwcProtocol.METHOD_GET_INFO -> result(method, buildJsonObject {
                put("alias", "hench wallet")
                put("color", "#5E6AD2")
                put("pubkey", connection.walletPubkeyHex)
                put("methods", NwcServerConnection.supportedMethodsCsv())
                put("notifications", "")
            })
            NwcProtocol.METHOD_GET_BALANCE -> result(method, buildJsonObject {
                put("balance", Math.multiplyExact(wallet.balanceSats(), 1_000L))
            })
            NwcProtocol.METHOD_MAKE_INVOICE -> {
                val amount = params["amount"]?.jsonPrimitive?.longOrNull ?: error("amount msat required")
                val desc = params["description"]?.jsonPrimitive?.contentOrNull ?: "NWC invoice"
                val invoice = wallet.createInvoice(amount, desc)
                result(method, buildJsonObject {
                    put("invoice", invoice.bolt11)
                    put("payment_hash", invoice.id)
                })
            }
            NwcProtocol.METHOD_PAY_INVOICE -> {
                val invoice = params["invoice"]?.jsonPrimitive?.contentOrNull ?: error("invoice required")
                val paid = wallet.payInvoice(invoice)
                // NIP-47 requires the actual 32-byte hex preimage — the payment
                // may already have left the wallet, so report an error rather
                // than return a non-preimage id that could confuse the client.
                val preimage = paid.preimage
                    ?: error("wallet did not return a preimage for payment ${paid.paymentId}")
                require(preimage.matches(Regex("[0-9a-fA-F]{64}"))) {
                    "wallet returned malformed preimage"
                }
                result(method, buildJsonObject {
                    put("preimage", preimage.lowercase())
                    put("fees_paid", (paid.feeSats ?: 0L) * 1_000L)
                })
            }
            NwcProtocol.METHOD_LOOKUP_INVOICE -> {
                val key = params["payment_hash"]?.jsonPrimitive?.contentOrNull
                    ?: params["invoice"]?.jsonPrimitive?.contentOrNull
                    ?: error("payment_hash or invoice required")
                result(method, buildJsonObject {
                    put("settled", wallet.lookupInvoice(key) == net.nostr.hench.wallet.InvoiceStatus.Settled)
                })
            }
            else -> error("unsupported NWC method $method")
        }
    }

    private fun result(type: String, payload: JsonObject) = buildJsonObject {
        put("result_type", type)
        put("error", kotlinx.serialization.json.JsonNull)
        put("result", payload)
    }

    private fun errorResponse(code: String, error: String) = buildJsonObject {
        put("result_type", "error")
        put("error", buildJsonObject { put("code", code); put("message", error) })
        put("result", JsonObject(emptyMap()))
    }

    private fun subscriptionMessage(): String {
        val filter = buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(NwcProtocol.KIND_REQUEST))))
            put("#p", JsonArray(listOf(JsonPrimitive(connection.walletPubkeyHex))))
        }
        return json.encodeToString(JsonArray.serializer(), JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive("nwc-server"), filter)))
    }

    private fun infoEventMessage(): String = NostrRelayMessageCodec.eventMessage(
        NostrEventSigner.sign(
            NostrEventEnvelope(
                kind = NwcProtocol.KIND_INFO,
                content = NwcServerConnection.supportedMethodsCsv(),
                tags = listOf(listOf("encryption", "nip04")),
            ),
            connection.walletSecretHex,
        ),
    )

    private fun parseRelayEvent(message: String): NostrSignedEvent? {
        val array = json.parseToJsonElement(message).jsonArray
        if (array.size < 3 || array[0].jsonPrimitive.content != "EVENT") return null
        val event = array[2].jsonObject
        return NostrSignedEvent(
            id = event["id"]!!.jsonPrimitive.content,
            pubkey = event["pubkey"]!!.jsonPrimitive.content,
            createdAt = event["created_at"]!!.jsonPrimitive.longOrNull ?: 0L,
            kind = event["kind"]!!.jsonPrimitive.content.toInt(),
            tags = event["tags"]!!.jsonArray.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } },
            content = event["content"]!!.jsonPrimitive.content,
            sig = event["sig"]!!.jsonPrimitive.content,
        )
    }
}
