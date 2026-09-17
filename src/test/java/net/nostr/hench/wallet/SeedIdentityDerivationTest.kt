package net.nostr.hench.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedIdentityDerivationTest {

    @Test
    fun derivationIsDeterministic() {
        val a = deriveNostrSecretKeyHex("a36b0ef1a52088f9863da954fc3a3741")
        val b = deriveNostrSecretKeyHex("a36b0ef1a52088f9863da954fc3a3741")
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun differentSeedsDeriveDifferentIdentities() {
        val a = deriveNostrSecretKeyHex("a36b0ef1a52088f9863da954fc3a3741")
        val b = deriveNostrSecretKeyHex("ea7145994fdebb65d4513e2be801b7d7")
        assertNotEquals(a, b)
    }

    @Test
    fun derivationDoesNotLeakTheSeedObviousWay() {
        // derived key is not the seed itself and not its plain hash without tag
        val seed = "a36b0ef1a52088f9863da954fc3a3741"
        val derived = deriveNostrSecretKeyHex(seed)
        assertNotEquals(seed, derived)
    }

    @Test
    fun onboardingDerivesIdentityFromTheSeed() {
        val settings = net.nostr.hench.settings.OnboardingDefaults
            .createFirstRunWorkerSettings(bundledSparkApiKey = "testkey")
        assertEquals(
            deriveNostrSecretKeyHex(settings.sparkSeedHex!!),
            settings.workerSecretKeyHex,
        )
    }
}
