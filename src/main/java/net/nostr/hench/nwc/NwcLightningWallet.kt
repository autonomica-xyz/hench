package net.nostr.hench.nwc

import net.nostr.hench.identity.Bip340Schnorr
import net.nostr.hench.identity.hexToBytes
import net.nostr.hench.identity.toHex
import net.nostr.hench.nostr.NostrEventEnvelope
import net.nostr.hench.nostr.NostrEventSigner
import net.nostr.hench.nostr.NostrRelayMessageCodec
import net.nostr.hench.nostr.NostrSignedEvent
import net.nostr.hench.wallet.InvoiceStatus
import net.nostr.hench.wallet.LightningWallet
import net.nostr.hench.wallet.PaymentInvoice
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
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

interface NwcRelayTransport {
    suspend fun request(connection: NwcConnectionUri, request: NostrSignedEvent): NostrSignedEvent
}

class NwcLightningWallet(
    private val connection: NwcConnectionUri,
    private val transport: NwcRelayTransport = WebSocketNwcRelayTransport(),
    private val timeoutMillis: Long = 30_000L,
) : LightningWallet {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val clientPubkey = Bip340Schnorr.publicKey(connection.clientSecretHex.hexToBytes()).toHex()

    override suspend fun createInvoice(amountMsat: Long, memo: String): PaymentInvoice {
        val response = sendEncryptedRequest(NwcProtocol.makeInvoiceRequest(amountMsat, memo, expirySeconds = 600))
        val result = parseSuccess(response, NwcProtocol.METHOD_MAKE_INVOICE)
        val invoice = result["invoice"]?.jsonPrimitive?.contentOrNull
            ?: result["bolt11"]?.jsonPrimitive?.contentOrNull
            ?: error("NWC make_invoice response missing invoice")
        val paymentHash = result["payment_hash"]?.jsonPrimitive?.contentOrNull
            ?: result["paymentHash"]?.jsonPrimitive?.contentOrNull
            ?: UUID.nameUUIDFromBytes(invoice.toByteArray()).toString()
        return PaymentInvoice(
            id = paymentHash,
            amountMsat = amountMsat,
            memo = memo,
            bolt11 = invoice,
        )
    }

    override suspend fun lookupInvoice(invoiceId: String): InvoiceStatus {
        val response = sendEncryptedRequest(NwcProtocol.lookupInvoiceRequest(invoiceId))
        val result = parseSuccess(response, NwcProtocol.METHOD_LOOKUP_INVOICE)
        return when {
            result["settled"]?.jsonPrimitive?.booleanOrNull == true -> InvoiceStatus.Settled
            result["preimage"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true -> InvoiceStatus.Settled
            result["state"]?.jsonPrimitive?.contentOrNull.equals("settled", ignoreCase = true) -> InvoiceStatus.Settled
            result["status"]?.jsonPrimitive?.contentOrNull.equals("settled", ignoreCase = true) -> InvoiceStatus.Settled
            result["expires_at"]?.jsonPrimitive?.longOrNull?.let { it < System.currentTimeMillis() / 1000L } == true -> InvoiceStatus.Expired
            else -> InvoiceStatus.Pending
        }
    }

    private suspend fun sendEncryptedRequest(payload: JsonObject): JsonObject = withTimeout(timeoutMillis) {
        val encrypted = Nip04.encrypt(
            senderSecretKeyHex = connection.clientSecretHex,
            recipientPubkeyHex = connection.walletPubkeyHex,
            plainText = json.encodeToString(JsonObject.serializer(), payload),
        )
        val request = NostrEventSigner.sign(
            NostrEventEnvelope(
                kind = NwcProtocol.KIND_REQUEST,
                content = encrypted,
                tags = NwcProtocol.requestEventTags(connection.walletPubkeyHex, encryption = "nip04"),
            ),
            connection.clientSecretHex,
        )
        val response = transport.request(connection, request)
        require(response.kind == NwcProtocol.KIND_RESPONSE) { "unexpected NWC response kind ${response.kind}" }
        require(response.pubkey.equals(connection.walletPubkeyHex, ignoreCase = true)) { "NWC response from unexpected pubkey" }
        require(response.tags.any { it.size >= 2 && it[0] == "e" && it[1] == request.id }) { "NWC response missing request event tag" }
        val decrypted = Nip04.decrypt(connection.clientSecretHex, connection.walletPubkeyHex, response.content)
        json.parseToJsonElement(decrypted).jsonObject
    }

    private fun parseSuccess(response: JsonObject, expectedType: String): JsonObject {
        val error = response["error"]
        if (error != null && error !is kotlinx.serialization.json.JsonNull) {
            val message = runCatching { error.jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                ?: error.toString()
            throw IOException("NWC $expectedType failed: $message")
        }
        val resultType = response["result_type"]?.jsonPrimitive?.contentOrNull
        require(resultType == null || resultType == expectedType) { "unexpected NWC result_type $resultType" }
        return response["result"]?.jsonObject ?: error("NWC $expectedType response missing result")
    }
}

class WebSocketNwcRelayTransport(
    private val http: OkHttpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build(),
    private val timeoutMillis: Long = 30_000L,
) : NwcRelayTransport {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun request(connection: NwcConnectionUri, request: NostrSignedEvent): NostrSignedEvent = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (relay in connection.relays) {
            try {
                return@withContext requestOne(relay, connection, request)
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IOException("all NWC relays failed", lastError)
    }

    private suspend fun requestOne(relayUrl: String, connection: NwcConnectionUri, requestEvent: NostrSignedEvent): NostrSignedEvent {
        val deferred = CompletableDeferred<NostrSignedEvent>()
        var socket: WebSocket? = null
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val clientPubkey = requestEvent.pubkey
                webSocket.send(subscriptionMessage(requestEvent.id, connection.walletPubkeyHex, clientPubkey))
                webSocket.send(NostrRelayMessageCodec.eventMessage(requestEvent))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { parseRelayEvent(text) }.getOrNull()?.let { event ->
                    if (event.kind == NwcProtocol.KIND_RESPONSE &&
                        event.pubkey.equals(connection.walletPubkeyHex, ignoreCase = true) &&
                        event.tags.any { it.size >= 2 && it[0] == "e" && it[1] == requestEvent.id }
                    ) {
                        deferred.complete(event)
                        webSocket.close(1000, "nwc response received")
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                deferred.completeExceptionally(t)
            }
        }
        socket = http.newWebSocket(Request.Builder().url(relayUrl).build(), listener)
        return try {
            withTimeout(timeoutMillis) { deferred.await() }
        } finally {
            socket.close(1000, "done")
        }
    }

    private fun subscriptionMessage(requestId: String, walletPubkey: String, clientPubkey: String): String {
        val filter = buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(NwcProtocol.KIND_RESPONSE))))
            put("authors", JsonArray(listOf(JsonPrimitive(walletPubkey))))
            put("#p", JsonArray(listOf(JsonPrimitive(clientPubkey))))
            put("#e", JsonArray(listOf(JsonPrimitive(requestId))))
            put("limit", 1)
        }
        return json.encodeToString(
            JsonArray.serializer(),
            buildJsonArray {
                add(JsonPrimitive("REQ"))
                add(JsonPrimitive("nwc-${requestId.take(8)}"))
                add(filter)
            },
        )
    }

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
