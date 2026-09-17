package net.nostr.hench.cvm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IdempotencyKeyTest {

    private fun ctx(client: String, rpcId: String, tool: String, argsJson: String): ToolCallContext {
        val args = Json.parseToJsonElement(argsJson) as JsonObject
        val incoming = IncomingContext("event", client, rpcId, "tools/call")
        return ToolCallContext(incoming, tool, args, sha256Hex(canonical(args)))
    }

    @Test
    fun sameInputsProduceSameKey() {
        val a = ctx("clientA", "1", "download_media", """{"url":"https://y/watch?v=x","mode":"audio"}""")
        val b = ctx("clientA", "1", "download_media", """{"url":"https://y/watch?v=x","mode":"audio"}""")
        assertEquals(a.idempotencyKey, b.idempotencyKey)
    }

    @Test
    fun differentClientsCollideToDifferentKeys() {
        val a = ctx("clientA", "1", "download_media", """{"url":"u","mode":"audio"}""")
        val b = ctx("clientB", "1", "download_media", """{"url":"u","mode":"audio"}""")
        assertNotEquals("same JSON-RPC id from different clients must not collide", a.idempotencyKey, b.idempotencyKey)
    }

    @Test
    fun differentArgsProduceDifferentKeys() {
        val a = ctx("c", "1", "download_media", """{"url":"u1","mode":"audio"}""")
        val b = ctx("c", "1", "download_media", """{"url":"u2","mode":"audio"}""")
        assertNotEquals("same id reused with different args must not collide", a.idempotencyKey, b.idempotencyKey)
    }

    @Test
    fun jsonRpcIdWithDelimitersDoesNotCorruptKey() {
        // A JSON-RPC id containing ':' and quotes must not break the key
        // (the reason we hash a canonical array, not concatenate with ':').
        val weird = "1:2\"3\n4"
        val a = ctx("c", weird, "download_media", """{"url":"u","mode":"audio"}""")
        val b = ctx("c", weird, "download_media", """{"url":"u","mode":"audio"}""")
        assertEquals(a.idempotencyKey, b.idempotencyKey)
    }
}
