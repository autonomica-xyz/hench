package net.nostr.hench.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerNameTest {

    // Synthetic seeds only — never put real device/wallet entropy in tests.
    private val seedA = "0000000000000000000000000000000000000000000000000000000000000001"
    private val seedB = "0000000000000000000000000000000000000000000000000000000000000002"

    @Test
    fun derivedNameIsStablePerSeed() {
        val a = OnboardingDefaults.generateWorkerName(seedA)
        val b = OnboardingDefaults.generateWorkerName(seedA)
        assertEquals(a, b)
    }

    @Test
    fun differentSeedsGetDifferentNames() {
        val first = OnboardingDefaults.generateWorkerName(seedA)
        val second = OnboardingDefaults.generateWorkerName(seedB)
        assertNotEquals(first, second)
    }

    @Test
    fun nameShapeIsHenchAdjNoun() {
        val name = OnboardingDefaults.generateWorkerName(seedB)
        assertTrue("got: $name", name.matches(Regex("hench-[a-z]+-[a-z]+")))
    }

    @Test
    fun onboardingCarriesTheName() {
        val settings = OnboardingDefaults.createFirstRunWorkerSettings("test-key")
        assertTrue(settings.workerName!!.startsWith("hench-"))
        assertEquals(OnboardingDefaults.generateWorkerName(settings.sparkSeedHex!!), settings.workerName)
    }
}
