package net.nostr.hench.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import net.nostr.hench.BuildConfig
import net.nostr.hench.auth.Platform
import net.nostr.hench.auth.PlatformHealthChecker
import net.nostr.hench.wallet.generateSparkSeedHex
import java.time.LocalTime

class SharedPreferencesSettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("worker", Context.MODE_PRIVATE)

    fun isOnboardingComplete(): Boolean = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)

    fun completeOnboarding(settings: WorkerSettings) {
        save(settings)
        prefs.edit { putBoolean(KEY_ONBOARDING_COMPLETE, true) }
    }

    fun ensureFirstRunConfigured(
        bundledSparkApiKey: String = BuildConfig.BREEZ_SPARK_API_KEY,
    ): WorkerSettings {
        if (isOnboardingComplete()) return load()
        val settings = OnboardingDefaults.createFirstRunWorkerSettings(bundledSparkApiKey)
        completeOnboarding(settings)
        return settings
    }

    fun load(): WorkerSettings {
        val loaded = WorkerSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            relayUrls = prefs.getString(KEY_RELAY_URLS, "")!!.split(',').map { it.trim() }.filter { it.isNotBlank() },
            blossomUrls = prefs.getString(KEY_BLOSSOM_URLS, "")!!.split(',').map { it.trim() }.filter { it.isNotBlank() },
            workerSecretKeyHex = prefs.getString(KEY_WORKER_SECRET_KEY_HEX, null)?.takeIf { it.isNotBlank() },
            tempDownloadDirectory = prefs.getString(KEY_TEMP_DOWNLOAD_DIRECTORY, null)?.takeIf { it.isNotBlank() },
            acceptAudio = prefs.getBoolean(KEY_ACCEPT_AUDIO, true),
            acceptVideo = prefs.getBoolean(KEY_ACCEPT_VIDEO, false),
            basePriceMsat = prefs.getLong(KEY_BASE_PRICE_MSAT, 0L),
            audioPriceMsatPerHour = prefs.getLong(KEY_AUDIO_PRICE_MSAT_PER_HOUR, 60_000L),
            videoPriceMsatPerHour = prefs.getLong(KEY_VIDEO_PRICE_MSAT_PER_HOUR, 120_000L),
            minBillableSeconds = prefs.getLong(KEY_MIN_BILLABLE_SECONDS, 60L),
            cleanupAfterUpload = prefs.getBoolean(KEY_CLEANUP_AFTER_UPLOAD, true),
            hoursOfOperation = loadHoursOfOperation(),
            walletMode = runCatching { WalletMode.valueOf(prefs.getString(KEY_WALLET_MODE, WalletMode.BUILT_IN.name)!!) }.getOrDefault(WalletMode.BUILT_IN),
            sparkApiKey = prefs.getString(KEY_SPARK_API_KEY, null)?.takeIf { it.isNotBlank() },
            sparkSeedHex = prefs.getString(KEY_SPARK_SEED_HEX, null)?.takeIf { it.isNotBlank() },
            nwcConnectionUri = prefs.getString(KEY_NWC_URI, null)?.takeIf { it.isNotBlank() },
            allowedPlatforms = loadPlatformList(KEY_ALLOWED_PLATFORMS),
            blockedPlatforms = loadPlatformList(KEY_BLOCKED_PLATFORMS),
            useCookies = prefs.getBoolean(KEY_USE_COOKIES, true),
            workerName = prefs.getString(KEY_WORKER_NAME, null)?.takeIf { it.isNotBlank() },
            disabledTools = (prefs.getStringSet(KEY_DISABLED_TOOLS, emptySet()) ?: emptySet()).toSet(),
            enabledScriptTools = (prefs.getStringSet(KEY_ENABLED_SCRIPT_TOOLS, emptySet()) ?: emptySet()).toSet(),
            healthCheckSettings = PlatformHealthChecker.PlatformHealthSettings(
                enabled = prefs.getBoolean(KEY_HEALTH_CHECK_ENABLED, true),
                checkIntervalMinutes = prefs.getLong(KEY_HEALTH_CHECK_INTERVAL_MINUTES, 30),
                maxJobsPerSession = prefs.getInt(KEY_HEALTH_MAX_JOBS, 100),
                cooldownMinutes = prefs.getLong(KEY_HEALTH_COOLDOWN_MINUTES, 60),
            ),
        ).withBuiltInSparkDefaults(
            bundledApiKey = BuildConfig.BREEZ_SPARK_API_KEY,
            seedFactory = ::generateSparkSeedHex,
        ).let {
            // One-seed identity, enforced: in built-in mode the seed is the
            // single source of truth — the Nostr key is ALWAYS re-derived and
            // any stray stored value (stale save, partial restore) is corrected.
            // The identity only changes when the seed changes, by design.
            if (it.walletMode == WalletMode.BUILT_IN && !it.sparkSeedHex.isNullOrBlank()) {
                val derived = net.nostr.hench.wallet.deriveNostrSecretKeyHex(it.sparkSeedHex!!)
                if (it.workerSecretKeyHex != derived) it.copy(workerSecretKeyHex = derived) else it
            } else {
                it
            }
        }
        if (loaded.sparkApiKey != prefs.getString(KEY_SPARK_API_KEY, null) || loaded.sparkSeedHex != prefs.getString(KEY_SPARK_SEED_HEX, null) || loaded.workerSecretKeyHex != prefs.getString(KEY_WORKER_SECRET_KEY_HEX, null)) {
            save(loaded)
        }
        return loaded
    }

    fun save(settings: WorkerSettings) {
        prefs.edit {
            putBoolean(KEY_ENABLED, settings.enabled)
            putString(KEY_RELAY_URLS, settings.relayUrls.joinToString(","))
            putString(KEY_BLOSSOM_URLS, settings.blossomUrls.joinToString(","))
            putString(KEY_WORKER_SECRET_KEY_HEX, settings.workerSecretKeyHex.orEmpty())
            putString(KEY_TEMP_DOWNLOAD_DIRECTORY, settings.tempDownloadDirectory.orEmpty())
            putBoolean(KEY_ACCEPT_AUDIO, settings.acceptAudio)
            putBoolean(KEY_ACCEPT_VIDEO, settings.acceptVideo)
            putLong(KEY_BASE_PRICE_MSAT, settings.basePriceMsat)
            putLong(KEY_AUDIO_PRICE_MSAT_PER_HOUR, settings.audioPriceMsatPerHour)
            putLong(KEY_VIDEO_PRICE_MSAT_PER_HOUR, settings.videoPriceMsatPerHour)
            putLong(KEY_MIN_BILLABLE_SECONDS, settings.minBillableSeconds)
            putBoolean(KEY_CLEANUP_AFTER_UPLOAD, settings.cleanupAfterUpload)
            putString(KEY_WALLET_MODE, settings.walletMode.name)
            putString(KEY_SPARK_API_KEY, settings.sparkApiKey.orEmpty())
            putString(KEY_SPARK_SEED_HEX, settings.sparkSeedHex.orEmpty())
            putString(KEY_NWC_URI, settings.nwcConnectionUri.orEmpty())
            putString(KEY_ALLOWED_PLATFORMS, settings.allowedPlatforms.joinToString(",") { it.name })
            putString(KEY_BLOCKED_PLATFORMS, settings.blockedPlatforms.joinToString(",") { it.name })
            putBoolean(KEY_USE_COOKIES, settings.useCookies)
            putString(KEY_WORKER_NAME, settings.workerName.orEmpty())
            putStringSet(KEY_DISABLED_TOOLS, settings.disabledTools)
            putStringSet(KEY_ENABLED_SCRIPT_TOOLS, settings.enabledScriptTools)
            putBoolean(KEY_HEALTH_CHECK_ENABLED, settings.healthCheckSettings.enabled)
            putLong(KEY_HEALTH_CHECK_INTERVAL_MINUTES, settings.healthCheckSettings.checkIntervalMinutes)
            putInt(KEY_HEALTH_MAX_JOBS, settings.healthCheckSettings.maxJobsPerSession)
            putLong(KEY_HEALTH_COOLDOWN_MINUTES, settings.healthCheckSettings.cooldownMinutes)
            when (val hours = settings.hoursOfOperation) {
                HoursOfOperation.Always -> putBoolean(KEY_HOURS_ALWAYS, true)
                is HoursOfOperation.Daily -> {
                    putBoolean(KEY_HOURS_ALWAYS, false)
                    putString(KEY_HOURS_START, hours.start.toString())
                    putString(KEY_HOURS_END, hours.end.toString())
                }
            }
        }
    }

    private fun loadHoursOfOperation(): HoursOfOperation {
        if (prefs.getBoolean(KEY_HOURS_ALWAYS, true)) return HoursOfOperation.always()
        val start = prefs.getString(KEY_HOURS_START, "00:00") ?: "00:00"
        val end = prefs.getString(KEY_HOURS_END, "00:00") ?: "00:00"
        return runCatching { HoursOfOperation.daily(LocalTime.parse(start), LocalTime.parse(end)) }
            .getOrDefault(HoursOfOperation.always())
    }

    companion object {
        const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        const val KEY_ENABLED = "enabled"
        const val KEY_RELAY_URLS = "relay_urls"
        const val KEY_BLOSSOM_URLS = "blossom_urls"
        const val KEY_WORKER_SECRET_KEY_HEX = "worker_secret_key_hex"
        const val KEY_TEMP_DOWNLOAD_DIRECTORY = "temp_download_directory"
        const val KEY_ACCEPT_AUDIO = "accept_audio"
        const val KEY_ACCEPT_VIDEO = "accept_video"
        const val KEY_BASE_PRICE_MSAT = "base_price_msat"
        const val KEY_AUDIO_PRICE_MSAT_PER_HOUR = "audio_price_msat_per_hour"
        const val KEY_VIDEO_PRICE_MSAT_PER_HOUR = "video_price_msat_per_hour"
        const val KEY_MIN_BILLABLE_SECONDS = "min_billable_seconds"
        const val KEY_CLEANUP_AFTER_UPLOAD = "cleanup_after_upload"
        const val KEY_HOURS_ALWAYS = "hours_always"
        const val KEY_HOURS_START = "hours_start"
        const val KEY_HOURS_END = "hours_end"
        const val KEY_WALLET_MODE = "wallet_mode"
        const val KEY_SPARK_API_KEY = "spark_api_key"
        const val KEY_SPARK_SEED_HEX = "spark_seed_hex"
        const val KEY_NWC_URI = "nwc_uri"
        const val KEY_ALLOWED_PLATFORMS = "allowed_platforms"
        const val KEY_BLOCKED_PLATFORMS = "blocked_platforms"
        const val KEY_USE_COOKIES = "use_cookies"
        const val KEY_WORKER_NAME = "worker_name"
        const val KEY_DISABLED_TOOLS = "disabled_tools"
        const val KEY_ENABLED_SCRIPT_TOOLS = "enabled_script_tools"
        const val KEY_HEALTH_CHECK_ENABLED = "health_check_enabled"
        const val KEY_HEALTH_CHECK_INTERVAL_MINUTES = "health_check_interval_minutes"
        const val KEY_HEALTH_MAX_JOBS = "health_max_jobs"
        const val KEY_HEALTH_COOLDOWN_MINUTES = "health_cooldown_minutes"
    }

    private fun loadPlatformList(key: String): List<Platform> {
        return prefs.getString(key, "")!!
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { Platform.valueOf(it) }.getOrNull() }
    }
}
