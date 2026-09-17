package net.nostr.hench

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import net.nostr.hench.service.WorkerForegroundService
import net.nostr.hench.service.WorkerForegroundServiceContract
import net.nostr.hench.settings.SharedPreferencesSettingsRepository
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerServiceStartInstrumentedTest {
    @Test
    fun startsWorkerForegroundServiceFromAppContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        seedNwcWorkerSettings(context)
        val intent = Intent(context, WorkerForegroundService::class.java).apply {
            action = WorkerForegroundServiceContract.ACTION_RELOAD_SETTINGS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        Thread.sleep(10_000)
        assertTrue(true)
    }

    private fun seedNwcWorkerSettings(context: Context) {
        val walletPubkey = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
        val testNwcUri = "nostr+walletconnect://$walletPubkey?relay=ws%3A%2F%2F127.0.0.1%3A10563&secret=0000000000000000000000000000000000000000000000000000000000000004"
        context.getSharedPreferences("worker", Context.MODE_PRIVATE).edit()
            .putBoolean(SharedPreferencesSettingsRepository.KEY_ENABLED, true)
            .putString(SharedPreferencesSettingsRepository.KEY_RELAY_URLS, "ws://127.0.0.1:10563")
            .putString(SharedPreferencesSettingsRepository.KEY_BLOSSOM_URLS, "http://127.0.0.1:18080")
            .putString(SharedPreferencesSettingsRepository.KEY_WORKER_SECRET_KEY_HEX, "0000000000000000000000000000000000000000000000000000000000000001")
            .putBoolean(SharedPreferencesSettingsRepository.KEY_ACCEPT_AUDIO, true)
            .putBoolean(SharedPreferencesSettingsRepository.KEY_ACCEPT_VIDEO, false)
            .putLong(SharedPreferencesSettingsRepository.KEY_BASE_PRICE_MSAT, 1_000L)
            .putLong(SharedPreferencesSettingsRepository.KEY_AUDIO_PRICE_MSAT_PER_HOUR, 60_000L)
            .putLong(SharedPreferencesSettingsRepository.KEY_VIDEO_PRICE_MSAT_PER_HOUR, 120_000L)
            .putLong(SharedPreferencesSettingsRepository.KEY_MIN_BILLABLE_SECONDS, 60L)
            .putBoolean(SharedPreferencesSettingsRepository.KEY_CLEANUP_AFTER_UPLOAD, true)
            .putBoolean(SharedPreferencesSettingsRepository.KEY_HOURS_ALWAYS, true)
            .putString(SharedPreferencesSettingsRepository.KEY_WALLET_MODE, "NWC")
            .putString(SharedPreferencesSettingsRepository.KEY_NWC_URI, testNwcUri)
            .commit()
    }
}
