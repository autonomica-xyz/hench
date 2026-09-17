package net.nostr.hench.nostr

import net.nostr.hench.identity.Bip340Schnorr
import net.nostr.hench.identity.hexToBytes
import net.nostr.hench.identity.toHex
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Unsigned Nostr event data. */
data class NostrEventEnvelope(
    val kind: Int,
    val content: String,
    val tags: List<List<String>> = emptyList(),
)

data class NostrSignedEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
)

object NostrEventSigner {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    fun sign(envelope: NostrEventEnvelope, secretKeyHex: String, createdAt: Long = System.currentTimeMillis() / 1000L): NostrSignedEvent {
        val secretKey = secretKeyHex.hexToBytes()
        require(secretKey.size == 32) { "Nostr secret key must be 32 bytes" }
        val pubkey = Bip340Schnorr.publicKey(secretKey).toHex()
        val id = eventId(pubkey, createdAt, envelope.kind, envelope.tags, envelope.content)
        val sig = Bip340Schnorr.sign(id.hexToBytes(), secretKey, ByteArray(32)).toHex()
        return NostrSignedEvent(id, pubkey, createdAt, envelope.kind, envelope.tags, envelope.content, sig)
    }

    fun eventId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
        val serialized = buildJsonArray {
            add(JsonPrimitive(0))
            add(JsonPrimitive(pubkey))
            add(JsonPrimitive(createdAt))
            add(JsonPrimitive(kind))
            add(JsonArray(tags.map { tag -> JsonArray(tag.map { JsonPrimitive(it) }) }))
            add(JsonPrimitive(content))
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(json.encodeToString(JsonArray.serializer(), serialized).toByteArray())
            .toHex()
    }

    fun toJson(event: NostrSignedEvent): JsonObject = buildJsonObject {
        put("id", event.id)
        put("pubkey", event.pubkey)
        put("created_at", event.createdAt)
        put("kind", event.kind)
        put("tags", JsonArray(event.tags.map { tag -> JsonArray(tag.map { JsonPrimitive(it) }) }))
        put("content", event.content)
        put("sig", event.sig)
    }
}

object NostrRelayMessageCodec {
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

    fun eventMessage(event: NostrSignedEvent): String = json.encodeToString(
        JsonArray.serializer(),
        JsonArray(listOf(JsonPrimitive("EVENT"), NostrEventSigner.toJson(event))),
    )

    fun requestMessage(
        subscriptionId: String,
        kinds: List<Int>,
        targetPubkey: String? = null,
        since: Long? = null,
    ): String = json.encodeToString(
        JsonArray.serializer(),
        JsonArray(
            listOf(
                JsonPrimitive("REQ"),
                JsonPrimitive(subscriptionId),
                buildJsonObject {
                    put("kinds", JsonArray(kinds.map { JsonPrimitive(it) }))
                    targetPubkey?.let { put("#p", JsonArray(listOf(JsonPrimitive(it)))) }
                    since?.let { put("since", JsonPrimitive(it)) }
                },
            ),
        ),
    )
}
