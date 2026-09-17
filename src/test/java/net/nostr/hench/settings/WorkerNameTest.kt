package net.nostr.hench.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerNameTest {

    @Test
    fun derivedNameIsStablePerSeed() {
        val a = OnboardingDefaults.generateWorkerName("a36b0ef1a52088f9863da954fc3a3741")
        val b = OnboardingDefaults.generateWorkerName("a36b0ef1a52088f9863da954fc3a3741")
        assertEquals(a, b)
    }

    @Test
    fun differentSeedsGetDifferentNames() {
        // the two real device seeds must produce distinct names
        val pixel = OnboardingDefaults.generateWorkerName("a36b0ef1a52088f9863da954fc3a3741")
        val huawei = OnboardingDefaults.generateWorkerName("5700a6eee5aa2fcfe61fcb290bc97da6")
        assertNotEquals(pixel, huawei)
    }

    @Test
    fun nameShapeIsHenchAdjNoun() {
        val name = OnboardingDefaults.generateWorkerName("5700a6eee5aa2fcfe61fcb290bc97da6")
        assertTrue("got: $name", name.matches(Regex("hench-[a-z]+-[a-z]+")))
    }

    @Test
    fun onboardingCarriesTheName() {
        val settings = OnboardingDefaults.createFirstRunWorkerSettings("test-key")
        assertTrue(settings.workerName!!.startsWith("hench-"))
        assertEquals(OnboardingDefaults.generateWorkerName(settings.sparkSeedHex!!), settings.workerName)
    }
}
