package net.nostr.hench

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import net.nostr.hench.service.WorkerForegroundService
import net.nostr.hench.service.WorkerForegroundServiceContract
import net.nostr.hench.settings.SharedPreferencesSettingsRepository
import net.nostr.hench.settings.WalletMode
import net.nostr.hench.settings.WorkerSettings
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the worker foreground service actually comes up and stays running
 * with a valid NWC-mode configuration. Seeded settings point at loopback
 * endpoints — no real relays, no real money.
 *
 * Settings are snapshotted and restored in `finally`, so this is safe to run
 * on an isolated QA install; it must still not run against a production
 * install that has a real wallet.
 */
class WorkerServiceStartInstrumentedTest {
    @Test
    fun startsWorkerForegroundServiceFromAppContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = SharedPreferencesSettingsRepository(context)
        val previous = repository.load()
        try {
            repository.save(seedNwcWorkerSettings(previous))
            val intent = Intent(context, WorkerForegroundService::class.java).apply {
                action = WorkerForegroundServiceContract.ACTION_RELOAD_SETTINGS
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            val deadline = System.currentTimeMillis() + 20_000
            var running = serviceRunning(context)
            while (!running && System.currentTimeMillis() < deadline) {
                Thread.sleep(250)
                running = serviceRunning(context)
            }
            assertTrue("WorkerForegroundService was not running 20s after start", running)
        } finally {
            repository.save(previous)
            context.startService(
                Intent(context, WorkerForegroundService::class.java).apply {
                    action = WorkerForegroundServiceContract.ACTION_STOP
                },
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // For our own package getRunningServices still reports running services.
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == WorkerForegroundService::class.java.name }
    }

    /** Loopback-only endpoints + a dummy NWC wallet URI; starts the service without real traffic. */
    private fun seedNwcWorkerSettings(previous: WorkerSettings): WorkerSettings {
        val walletPubkey = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
        val testNwcUri = "nostr+walletconnect://$walletPubkey?relay=ws%3A%2F%2F127.0.0.1%3A10563&secret=0000000000000000000000000000000000000000000000000000000000000004"
        return previous.copy(
            enabled = true,
            relayUrls = listOf("ws://127.0.0.1:10563"),
            blossomUrls = listOf("http://127.0.0.1:18080"),
            workerSecretKeyHex = "0000000000000000000000000000000000000000000000000000000000000001",
            acceptAudio = true,
            acceptVideo = false,
            basePriceMsat = 1_000L,
            audioPriceMsatPerHour = 60_000L,
            videoPriceMsatPerHour = 120_000L,
            minBillableSeconds = 60L,
            cleanupAfterUpload = true,
            walletMode = WalletMode.NWC,
            nwcConnectionUri = testNwcUri,
        )
    }
}
