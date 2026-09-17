package net.nostr.hench.cvm

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentGateApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun noopRecvGateRequestReturnsNullOnTimeout() = runTest {
        val gate = NoopPaymentGateApi()
        assertNull(gate.recvGateRequest(timeoutSecs = 1UL))
    }

    @Test
    fun noopMarkMethodsReturnTrue() = runTest {
        val gate = NoopPaymentGateApi()
        assertTrue(gate.submitInvoice("evt", 1000L, "lnbc1", "bitcoin-lightning-bolt11", 300UL, null))
        assertTrue(gate.markPaymentSettled("lnbc1", null))
        assertTrue(gate.markPaymentFailed("lnbc1", "msg"))
        assertTrue(gate.markReplayed("evt"))
    }

    @Test
    fun gateRequestSerializesToExpectedShape() {
        val req = GateRequest(
            requestEventId = "evt-1",
            clientPubkey = "npub1abc",
            method = "tools/call",
            paramsJson = """{"name":"download_media","arguments":{}}""",
            capabilityName = "download_media",
            canonicalInvocationId = "canon-1",
        )
        val s = json.encodeToString(req)
        val obj = json.parseToJsonElement(s).jsonObject
        assertEquals("evt-1", obj["requestEventId"]?.jsonPrimitive?.content)
        assertEquals("npub1abc", obj["clientPubkey"]?.jsonPrimitive?.content)
        assertEquals("tools/call", obj["method"]?.jsonPrimitive?.content)
        assertEquals("canon-1", obj["canonicalInvocationId"]?.jsonPrimitive?.content)
        assertTrue(obj["paramsJson"]?.jsonPrimitive?.content?.contains("download_media") == true)
    }
}
