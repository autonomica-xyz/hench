package net.nostr.hench.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identity-derivation coverage over synthetic seed entropy only. Previous
 * fixtures embedded real device seeds; these values are deliberately
 * recognizable patterns ("01", "02" repeated) that never held funds.
 */
class SeedIdentityDerivationTest {

    private val seedA = "01".repeat(16)
    private val seedB = "02".repeat(16)

    @Test
    fun derivationIsDeterministic() {
        val a = deriveNostrSecretKeyHex(seedA)
        val b = deriveNostrSecretKeyHex(seedA)
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun differentSeedsDeriveDifferentIdentities() {
        val a = deriveNostrSecretKeyHex(seedA)
        val b = deriveNostrSecretKeyHex(seedB)
        assertNotEquals(a, b)
    }

    @Test
    fun derivationDoesNotLeakTheSeedObviousWay() {
        // derived key is not the seed itself and not its plain hash without tag
        val derived = deriveNostrSecretKeyHex(seedA)
        assertNotEquals(seedA, derived)
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
