package net.nostr.hench.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NormalizePubkeyTest {

    private val pubkeyHex = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

    @Test
    fun lowercaseHexPassesThrough() {
        assertEquals(pubkeyHex, normalizePubkeyToHex(pubkeyHex))
    }

    @Test
    fun uppercaseAndPrefixedHexNormalizeToLowercase() {
        assertEquals(pubkeyHex, normalizePubkeyToHex(pubkeyHex.uppercase()))
        assertEquals(pubkeyHex, normalizePubkeyToHex("0x$pubkeyHex"))
        assertEquals(pubkeyHex, normalizePubkeyToHex("  $pubkeyHex  "))
    }

    @Test
    fun npubDecodesToHex() {
        val npub = Bech32.encode("npub", pubkeyHex.hexToBytes())
        assertEquals(pubkeyHex, normalizePubkeyToHex(npub))
    }

    @Test
    fun invalidInputsReturnNull() {
        assertNull(normalizePubkeyToHex(""))
        assertNull(normalizePubkeyToHex("   "))
        assertNull(normalizePubkeyToHex("not-a-pubkey"))
        assertNull(normalizePubkeyToHex(pubkeyHex.dropLast(1)))
        assertNull(normalizePubkeyToHex("npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"))
        assertNull(normalizePubkeyToHex("nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"))
    }
}
