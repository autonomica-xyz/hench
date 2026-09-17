package net.nostr.hench.nwc

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
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = mutableListOf<WebSocket>()

    fun start() {
        stop()
        connection.relays.forEach { relay ->
            val socket = http.newWebSocket(Request.Builder().url(relay).build(), listener())
            sockets += socket
        }
    }

    fun stop() {
        sockets.forEach { it.close(1000, "nwc server stopped") }
        sockets.clear()
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
            if (request.kind != NwcProtocol.KIND_REQUEST) return
            if (!request.tags.any { it.size >= 2 && it[0] == "p" && it[1].equals(connection.walletPubkeyHex, true) }) return
            scope.launch { handleRequest(webSocket, request) }
        }
    }

    private suspend fun handleRequest(webSocket: WebSocket, request: NostrSignedEvent) {
        val plain = runCatching { Nip04.decrypt(connection.walletSecretHex, request.pubkey, request.content) }.getOrElse { return }
        val responseContent = runCatching { handlePlainRequest(plain) }.getOrElse { errorResponse(error = it.message ?: "NWC request failed") }
        val encrypted = Nip04.encrypt(connection.walletSecretHex, request.pubkey, json.encodeToString(JsonObject.serializer(), responseContent))
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

    private suspend fun handlePlainRequest(plain: String): JsonObject {
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
            NwcProtocol.METHOD_GET_BALANCE -> result(method, buildJsonObject { put("balance", wallet.balanceSats() * 1_000L) })
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
                result(method, buildJsonObject {
                    put("preimage", paid.paymentId)
                    put("fees_paid", (paid.feeSats ?: 0L) * 1_000L)
                })
            }
            NwcProtocol.METHOD_LOOKUP_INVOICE -> result(method, buildJsonObject { put("settled", false) })
            else -> error("unsupported NWC method $method")
        }
    }

    private fun result(type: String, payload: JsonObject) = buildJsonObject {
        put("result_type", type)
        put("error", kotlinx.serialization.json.JsonNull)
        put("result", payload)
    }

    private fun errorResponse(error: String) = buildJsonObject {
        put("result_type", "error")
        put("error", buildJsonObject { put("code", "INTERNAL"); put("message", error) })
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
