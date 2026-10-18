package net.nostr.hench.identity

import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Bip340SchnorrTest {

    @Test
    fun signThenVerifyRoundTrips() {
        val secret = "0".repeat(63) + "1"
        val pubkey = Bip340Schnorr.publicKey(secret.hexToBytes())
        val message = MessageDigest.getInstance("SHA-256").digest("hello".toByteArray())

        val sig = Bip340Schnorr.sign(message, secret.hexToBytes())

        assertTrue(Bip340Schnorr.verify(message, pubkey, sig))
    }

    @Test
    fun rejectsWrongMessageWrongKeyAndTamperedSig() {
        val secret = "0".repeat(63) + "1"
        val otherSecret = "0".repeat(63) + "2"
        val pubkey = Bip340Schnorr.publicKey(secret.hexToBytes())
        val otherPubkey = Bip340Schnorr.publicKey(otherSecret.hexToBytes())
        val message = MessageDigest.getInstance("SHA-256").digest("a".toByteArray())
        val otherMessage = MessageDigest.getInstance("SHA-256").digest("b".toByteArray())
        val sig = Bip340Schnorr.sign(message, secret.hexToBytes())

        assertFalse(Bip340Schnorr.verify(otherMessage, pubkey, sig))
        assertFalse(Bip340Schnorr.verify(message, otherPubkey, sig))
        val tampered = sig.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFalse(Bip340Schnorr.verify(message, pubkey, tampered))
    }

    @Test
    fun verifyNeverThrowsOnMalformedInput() {
        val pubkey = Bip340Schnorr.publicKey(("0".repeat(63) + "1").hexToBytes())
        assertFalse(Bip340Schnorr.verify(ByteArray(0), pubkey, ByteArray(64)))
        assertFalse(Bip340Schnorr.verify(ByteArray(32), ByteArray(0), ByteArray(64)))
        assertFalse(Bip340Schnorr.verify(ByteArray(32), pubkey, ByteArray(64)))
        // pubkey that is not on the curve
        assertFalse(Bip340Schnorr.verify(ByteArray(32), ByteArray(32) { 0x55 }, ByteArray(64) { 1 }))
    }
}
