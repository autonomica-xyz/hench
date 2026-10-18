package net.nostr.hench

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import net.nostr.hench.settings.SharedPreferencesSettingsRepository
import net.nostr.hench.settings.WalletMode
import net.nostr.hench.settings.OnboardingDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

/**
 * Verifies the real first-run contract: launching MainActivity on a fresh
 * install auto-creates the Spark wallet, derives the Nostr identity, marks
 * onboarding complete, and enables the worker — with NO manual
 * "Create wallet & turn worker on" step.
 *
 * Runs against an isolated QA applicationId. This test NEVER clears shared
 * preferences: a debug install carrying a real wallet would be wiped.
 */
class FirstRunOnboardingInstrumentedTest {
    @Test
    fun firstLaunchAutoConfiguresNativeWalletAndTurnsWorkerOn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = SharedPreferencesSettingsRepository(context)
        assumeFalse(
            "onboarding already complete — run on a fresh QA install, never on a device with a real wallet",
            repository.isOnboardingComplete(),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val button = findButton(activity.window.decorView, "Create wallet & turn worker on")
                assertNull("fresh launch should auto-configure, not offer manual onboarding", button)
            }
        }

        // First-run configuration happens on an IO coroutine; poll instead of racing it.
        val deadline = System.currentTimeMillis() + 30_000
        while (!repository.isOnboardingComplete() && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
        }
        assertTrue("first-run configuration did not complete within 30s", repository.isOnboardingComplete())

        val settings = repository.load()
        assertTrue(settings.enabled)
        assertEquals(WalletMode.BUILT_IN, settings.walletMode)
        assertTrue(settings.sparkApiKey.orEmpty().isNotBlank())
        assertTrue(settings.sparkSeedHex.orEmpty().matches(Regex("^[0-9a-f]{32}$")))
        assertTrue(settings.workerSecretKeyHex.orEmpty().isNotBlank())
        assertEquals(OnboardingDefaults.DEFAULT_RELAY_URLS, settings.relayUrls)
        assertEquals(OnboardingDefaults.DEFAULT_BLOSSOM_URLS, settings.blossomUrls)
        assertTrue(settings.validate().isEmpty())
        Log.i(TAG, "ONBOARDING_COMPLETE enabled=${settings.enabled} wallet=${settings.walletMode} relay=${settings.relayUrls.first()} blossom=${settings.blossomUrls.first()}")
    }

    private fun findButton(root: View, text: String): Button? {
        if (root is Button && root.text?.toString() == text) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findButton(root.getChildAt(i), text)
                if (found != null) return found
            }
        }
        return null
    }

    companion object { private const val TAG = "HenchOnboardingE2E" }
}
