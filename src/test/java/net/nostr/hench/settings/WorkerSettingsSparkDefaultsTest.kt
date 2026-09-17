package net.nostr.hench.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WorkerSettingsSparkDefaultsTest {
    @Test
    fun fillsBuiltInSparkApiKeyAndSeedWhenBundledKeyExists() {
        val settings = WorkerSettings(walletMode = WalletMode.BUILT_IN)
            .withBuiltInSparkDefaults(
                bundledApiKey = "bundled-key",
                seedFactory = { "11".repeat(16) },
            )

        assertEquals("bundled-key", settings.sparkApiKey)
        assertEquals("11".repeat(16), settings.sparkSeedHex)
        assertFalse(settings.validate().contains(WorkerSettingsIssue.MISSING_SPARK_API_KEY))
        assertFalse(settings.validate().contains(WorkerSettingsIssue.MISSING_SPARK_SEED))
    }

    @Test
    fun doesNotOverrideUserProvidedSparkCredentials() {
        val settings = WorkerSettings(
            walletMode = WalletMode.BUILT_IN,
            sparkApiKey = "custom-key",
            sparkSeedHex = "22".repeat(16),
        ).withBuiltInSparkDefaults(
            bundledApiKey = "bundled-key",
            seedFactory = { "11".repeat(16) },
        )

        assertEquals("custom-key", settings.sparkApiKey)
        assertEquals("22".repeat(16), settings.sparkSeedHex)
    }

    @Test
    fun doesNotGenerateSparkDefaultsForOtherWalletModes() {
        val settings = WorkerSettings(walletMode = WalletMode.TEST)
            .withBuiltInSparkDefaults(
                bundledApiKey = "bundled-key",
                seedFactory = { "11".repeat(16) },
            )

        assertEquals(null, settings.sparkApiKey)
        assertEquals(null, settings.sparkSeedHex)
    }
}
