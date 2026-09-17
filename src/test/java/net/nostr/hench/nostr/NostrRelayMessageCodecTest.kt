package net.nostr.hench.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class NostrRelayMessageCodecTest {
    @Test
    fun requestMessageCanFilterByTargetPubkeyAndSince() {
        val pubkey = "a".repeat(64)

        val message = Json.parseToJsonElement(
            NostrRelayMessageCodec.requestMessage(
                subscriptionId = "jobs",
                kinds = listOf(5662),
                targetPubkey = pubkey,
                since = 1234L,
            ),
        ).jsonArray

        assertEquals("REQ", message[0].jsonPrimitive.content)
        assertEquals("jobs", message[1].jsonPrimitive.content)
        val filter = message[2].jsonObject
        assertEquals(5662, filter["kinds"]!!.jsonArray[0].jsonPrimitive.content.toInt())
        assertEquals(pubkey, filter["#p"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(1234L, filter["since"]!!.jsonPrimitive.content.toLong())
    }
}
