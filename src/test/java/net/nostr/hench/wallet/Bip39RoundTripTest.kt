package net.nostr.hench.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round-trip coverage over synthetic, well-known public entropy values only.
 * Fixtures that once held real device seeds were replaced — test entropy must
 * never correspond to a funded wallet.
 */
class Bip39RoundTripTest {

    @Test
    fun roundTripKnownVectors() {
        for (hex in listOf(
            "00000000000000000000000000000000",   // BIP-39 reference vector (all zeros)
            "ffffffffffffffffffffffffffffffff",   // all ones
            "80808080808080808080808080808080",   // BIP-39 reference vector
            "0102030405060708090a0b0c0d0e0f10",   // sequential synthetic
            "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f", // 32 bytes
        )) {
            val words = entropyToBip39Mnemonic(hex.hexToBytes())
            assertEquals(hex, bip39MnemonicToEntropyHex(words))
        }
    }

    @Test
    fun normalizeAcceptsHexAndWords() {
        val hex = "0102030405060708090a0b0c0d0e0f10"
        val words = sparkSeedBackupWords(hex)
        assertEquals(hex, normalizeSparkSeedInput(words))
        assertEquals(hex, normalizeSparkSeedInput(hex.uppercase()))
        assertEquals(hex, normalizeSparkSeedInput("  $words  "))
    }

    @Test
    fun rejectsGarbageAndBadChecksum() {
        assertNull(normalizeSparkSeedInput("not a seed"))
        assertNull(normalizeSparkSeedInput("zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo notaword")) // truly unknown word
        // valid words, deliberately broken checksum: the classic all-zeros
        // vector is "abandon x11 about"; swap the checksum word to 'abandon'
        assertNull(bip39MnemonicToEntropyHex("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"))
    }
}
