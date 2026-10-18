package net.nostr.hench

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import net.nostr.hench.service.WorkerForegroundServiceContract
import net.nostr.hench.settings.SharedPreferencesSettingsRepository
import net.nostr.hench.wallet.normalizeSparkSeedInput

/**
 * Debug-only deterministic settings restore (adb-driven). Debug builds only;
 * release builds refuse and finish. This exists because the Settings UI cannot
 * be reliably automated and ops/restore needs a scriptable path. The activity
 * is non-exported, so `am start` works on debug builds only (shell may launch
 * non-exported components of debuggable packages):
 *
 *   adb shell am start -n net.nostr.hench/.DebugRestoreActivity \
 *     --es worker_secret_hex "<hex>" \
 *     --es spark_seed "<hex or 12/24 words>" \
 *     --es enable_script_tools "tool_a,tool_b"
 */
class DebugRestoreActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            Toast.makeText(this, "restore is debug-only", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val result = runCatching {
            val repo = SharedPreferencesSettingsRepository(this)
            val current = repo.load()
            val workerSecret = intent.getStringExtra(EXTRA_WORKER_SECRET_HEX)?.trim()
            val sparkSeedRaw = intent.getStringExtra(EXTRA_SPARK_SEED)?.trim()
            val scriptTools = intent.getStringExtra(EXTRA_ENABLE_SCRIPT_TOOLS)
                ?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet()

            val seed = sparkSeedRaw?.let {
                normalizeSparkSeedInput(it) ?: error("invalid spark seed (hex or 12/24 words)")
            }
            // One-seed restore: no explicit worker secret -> derive from the seed.
            val workerSecretFinal = workerSecret
                ?: seed?.let { net.nostr.hench.wallet.deriveNostrSecretKeyHex(it) }
                ?: current.workerSecretKeyHex
            val updated = current.copy(
                enabled = true, // restoring implies the worker should run
                relayUrls = current.relayUrls.ifEmpty { net.nostr.hench.settings.OnboardingDefaults.DEFAULT_RELAY_URLS },
                blossomUrls = current.blossomUrls.ifEmpty { net.nostr.hench.settings.OnboardingDefaults.DEFAULT_BLOSSOM_URLS },
                workerSecretKeyHex = workerSecretFinal,
                sparkSeedHex = seed ?: current.sparkSeedHex,
                enabledScriptTools = scriptTools ?: current.enabledScriptTools,
            )
            repo.save(updated)
            // Mark onboarding complete so the first-run flow cannot overwrite
            // the restored settings with fresh defaults.
            repo.completeOnboarding(updated)
            startService(
                Intent(this, Class.forName("net.nostr.hench.service.WorkerForegroundService")).apply {
                    action = WorkerForegroundServiceContract.ACTION_RELOAD_SETTINGS
                },
            )
            "restored: secret=${workerSecret != null} seed=${seed != null} scripts=$scriptTools"
        }
        result.onFailure { android.util.Log.e("HenchRestore", "restore failed", it) }
        Toast.makeText(this, result.getOrElse { "restore failed: ${it.message}" }, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val EXTRA_WORKER_SECRET_HEX = "worker_secret_hex"
        const val EXTRA_SPARK_SEED = "spark_seed"
        const val EXTRA_ENABLE_SCRIPT_TOOLS = "enable_script_tools"
    }
}
