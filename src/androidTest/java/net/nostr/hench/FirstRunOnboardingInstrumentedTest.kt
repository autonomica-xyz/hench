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
import org.junit.Test

class FirstRunOnboardingInstrumentedTest {
    @Test
    fun firstLaunchAutoConfiguresNativeWalletAndTurnsWorkerOn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("worker", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("worker_stats", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("processed_jobs", Context.MODE_PRIVATE).edit().clear().commit()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val button = findButton(activity.window.decorView, "Create wallet & turn worker on")
                assertNull("fresh launch should skip manual onboarding", button)
            }
        }

        val repository = SharedPreferencesSettingsRepository(context)
        val settings = repository.load()
        assertTrue(repository.isOnboardingComplete())
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
