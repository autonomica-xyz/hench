package net.nostr.hench.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Bip39RoundTripTest {

    @Test
    fun roundTripKnownVectors() {
        for (hex in listOf(
            "a36b0ef1a52088f9863da954fc3a3741",   // the original phone wallet
            "ea7145994fdebb65d4513e2be801b7d7",
            "00000000000000000000000000000000",
            "ffffffffffffffffffffffffffffffff",
            "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f", // 32 bytes
        )) {
            val words = entropyToBip39Mnemonic(hex.hexToBytes())
            assertEquals(hex, bip39MnemonicToEntropyHex(words))
        }
    }

    @Test
    fun normalizeAcceptsHexAndWords() {
        val words = sparkSeedBackupWords("a36b0ef1a52088f9863da954fc3a3741")
        assertEquals("a36b0ef1a52088f9863da954fc3a3741", normalizeSparkSeedInput(words))
        assertEquals("a36b0ef1a52088f9863da954fc3a3741", normalizeSparkSeedInput("A36B0EF1A52088F9863DA954FC3A3741"))
        assertEquals("a36b0ef1a52088f9863da954fc3a3741", normalizeSparkSeedInput("  $words  "))
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
