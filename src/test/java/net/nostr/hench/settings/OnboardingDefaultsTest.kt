package net.nostr.hench.settings

import net.nostr.hench.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingDefaultsTest {
    @Test
    fun createsEnabledNativeSparkWorkerForFirstRun() {
        val settings = OnboardingDefaults.createFirstRunWorkerSettings("bundled-api-key")

        assertTrue(settings.enabled)
        assertEquals(WalletMode.BUILT_IN, settings.walletMode)
        assertEquals("bundled-api-key", settings.sparkApiKey)
        assertTrue(settings.sparkSeedHex.orEmpty().matches(Regex("^[0-9a-f]{32}$")))
        assertEquals(OnboardingDefaults.DEFAULT_RELAY_URLS, settings.relayUrls)
        assertTrue(settings.relayUrls.contains("wss://relay.nostr.net"))
        assertTrue(settings.relayUrls.size >= 4)
        assertEquals(OnboardingDefaults.DEFAULT_BLOSSOM_URLS, settings.blossomUrls)
        assertTrue(settings.blossomUrls.contains("https://blossom.yakihonne.com/"))
        assertTrue(settings.blossomUrls.contains("https://24242.io/"))
        assertFalse(settings.workerSecretKeyHex.isNullOrBlank())
        assertEquals(9_000, settings.basePriceMsat)
        assertEquals(60_000, settings.audioPriceMsatPerHour)
        assertTrue(settings.acceptVideo)
        assertEquals(60_000, settings.videoPriceMsatPerHour)
        assertTrue(settings.validate().isEmpty())
    }

    @Test
    fun missingBundledApiKeyStillRequiresSettingsFix() {
        val settings = OnboardingDefaults.createFirstRunWorkerSettings("")

        assertTrue(settings.validate().contains(WorkerSettingsIssue.MISSING_SPARK_API_KEY))
    }

    @Test
    fun apkBuildIncludesBundledSparkApiKey() {
        assertTrue(BuildConfig.BREEZ_SPARK_API_KEY.isNotBlank())
    }
}
