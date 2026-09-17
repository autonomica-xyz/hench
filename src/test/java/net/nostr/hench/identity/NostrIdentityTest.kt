package net.nostr.hench.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrIdentityTest {
    @Test
    fun importsHexPrivateKeyAndDerivesNostrPublicKey() {
        val identity = NostrIdentity.fromPrivateKeyHex("0".repeat(63) + "1")

        assertEquals("0".repeat(63) + "1", identity.privateKeyHex)
        assertEquals(
            "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            identity.publicKeyHex,
        )
    }

    @Test
    fun exportsAndImportsNsecBackup() {
        val original = NostrIdentity.fromPrivateKeyHex("0".repeat(63) + "2")

        val backup = original.toNsec()
        val restored = NostrIdentity.fromPrivateKey(backup)

        assertTrue(backup.startsWith("nsec1"))
        assertEquals(original.privateKeyHex, restored.privateKeyHex)
        assertEquals(original.publicKeyHex, restored.publicKeyHex)
    }

    @Test
    fun generatesNewRandomIdentity() {
        val first = NostrIdentity.generate()
        val second = NostrIdentity.generate()

        assertEquals(64, first.privateKeyHex.length)
        assertEquals(64, first.publicKeyHex.length)
        assertNotEquals(first.privateKeyHex, second.privateKeyHex)
    }
}
