package net.nostr.hench.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class FreeCallerSettingsTest {

    @Test
    fun freeCallerPubkeysDefaultsToEmpty() {
        assertEquals(emptyList<String>(), WorkerSettings().freeCallerPubkeys)
    }

    @Test
    fun csvRoundTripPreservesEntries() {
        val entries = listOf(
            "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq",
        )
        assertEquals(entries, decodeCsvList(encodeCsvList(entries)))
    }

    @Test
    fun emptyListEncodesToEmptyStringAndBack() {
        assertEquals("", encodeCsvList(emptyList()))
        assertEquals(emptyList<String>(), decodeCsvList(""))
    }

    @Test
    fun decodeTrimsAndDropsBlanks() {
        assertEquals(
            listOf("aabb", "ccdd"),
            decodeCsvList(" aabb , ,ccdd,  "),
        )
    }
}
