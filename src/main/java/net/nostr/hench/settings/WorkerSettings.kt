package net.nostr.hench.settings

import net.nostr.hench.auth.Platform
import net.nostr.hench.nwc.NwcConnectionUri
import net.nostr.hench.worker.DeliveryMode

enum class WalletMode {
    BUILT_IN,
    NWC,
    TEST,
}

enum class WorkerSettingsIssue {
    MISSING_RELAY_URL,
    MISSING_BLOSSOM_URL,
    MISSING_WORKER_SECRET_KEY,
    MISSING_AUDIO_PRICE,
    MISSING_VIDEO_PRICE,
    NO_SUPPORTED_MEDIA_MODES,
    INVALID_BASE_PRICE,
    INVALID_MIN_BILLABLE_SECONDS,
    MISSING_SPARK_API_KEY,
    MISSING_SPARK_SEED,
    INVALID_SPARK_SEED,
    MISSING_NWC_CONNECTION_URI,
    INVALID_NWC_CONNECTION_URI,
}

data class WorkerSettings(
    val enabled: Boolean = false,
    val relayUrls: List<String> = emptyList(),
    val blossomUrls: List<String> = emptyList(),
    val workerSecretKeyHex: String? = null,
    val tempDownloadDirectory: String? = null,
    val acceptAudio: Boolean = true,
    val acceptVideo: Boolean = false,
    val basePriceMsat: Long = 0L,
    val audioPriceMsatPerHour: Long = 0L,
    val videoPriceMsatPerHour: Long = 0L,
    val minBillableSeconds: Long = 60L,
    val cleanupAfterUpload: Boolean = true,
    val hoursOfOperation: HoursOfOperation = HoursOfOperation.always(),
    val walletMode: WalletMode = WalletMode.BUILT_IN,
    val sparkApiKey: String? = null,
    val sparkSeedHex: String? = null,
    val nwcConnectionUri: String? = null,
    /** Platforms the worker should accept jobs for. Empty = accept all. */
    val allowedPlatforms: List<Platform> = emptyList(),
    /** Platforms the worker should reject even with a valid session. */
    val blockedPlatforms: List<Platform> = emptyList(),
    /** Whether authenticated (cookie-based) job execution is enabled. */
    val useCookies: Boolean = true,

    /** Public worker name (announced). Null = keep generated default. */
    val workerName: String? = null,

    /** Built-in tools the user turned OFF (empty = all built-ins advertised). */
    val disabledTools: Set<String> = emptySet(),

    /** User-installed script tools turned ON (strictly opt-in; empty = none). */
    val enabledScriptTools: Set<String> = emptySet(),
) {
    fun supports(mode: DeliveryMode): Boolean {
        return when (mode) {
            DeliveryMode.AUDIO_ONLY -> acceptAudio
            DeliveryMode.FULL -> acceptVideo
        }
    }

    /** Returns true if this worker should accept jobs for the given URL's platform. */
    fun acceptsUrl(url: String): Boolean {
        val platform = Platform.forUrl(url)
        // If no known platform, accept only when allowedPlatforms is empty (i.e. accept all)
        if (platform == null) return allowedPlatforms.isEmpty()
        if (platform in blockedPlatforms) return false
        if (allowedPlatforms.isNotEmpty() && platform !in allowedPlatforms) return false
        return true
    }

    /** Returns the list of platforms that have active sessions and are usable. */
    fun authenticatedPlatforms(): List<Platform> = allowedPlatforms.ifEmpty { Platform.entries }


    fun validate(): List<WorkerSettingsIssue> {
        val issues = mutableListOf<WorkerSettingsIssue>()
        if (enabled) {
            if (relayUrls.none { it.isNotBlank() }) issues += WorkerSettingsIssue.MISSING_RELAY_URL
            if (blossomUrls.none { it.isNotBlank() }) issues += WorkerSettingsIssue.MISSING_BLOSSOM_URL
            if (workerSecretKeyHex.isNullOrBlank()) issues += WorkerSettingsIssue.MISSING_WORKER_SECRET_KEY
        }
        if (!acceptAudio && !acceptVideo) issues += WorkerSettingsIssue.NO_SUPPORTED_MEDIA_MODES
        if (acceptAudio && audioPriceMsatPerHour <= 0L) issues += WorkerSettingsIssue.MISSING_AUDIO_PRICE
        if (acceptVideo && videoPriceMsatPerHour <= 0L) issues += WorkerSettingsIssue.MISSING_VIDEO_PRICE
        if (basePriceMsat < 0L) issues += WorkerSettingsIssue.INVALID_BASE_PRICE
        if (minBillableSeconds < 1L) issues += WorkerSettingsIssue.INVALID_MIN_BILLABLE_SECONDS
        if (walletMode == WalletMode.BUILT_IN) {
            if (sparkApiKey.isNullOrBlank()) issues += WorkerSettingsIssue.MISSING_SPARK_API_KEY
            val seed = sparkSeedHex.orEmpty()
            if (seed.isBlank()) {
                issues += WorkerSettingsIssue.MISSING_SPARK_SEED
            } else if (!seed.matches(Regex("^[0-9a-fA-F]{32,128}$")) || seed.length % 2 != 0) {
                issues += WorkerSettingsIssue.INVALID_SPARK_SEED
            }
        }
        if (walletMode == WalletMode.NWC) {
            if (nwcConnectionUri.isNullOrBlank()) {
                issues += WorkerSettingsIssue.MISSING_NWC_CONNECTION_URI
            } else if (NwcConnectionUri.parse(nwcConnectionUri).isFailure) {
                issues += WorkerSettingsIssue.INVALID_NWC_CONNECTION_URI
            }
        }
        return issues
    }
}

fun WorkerSettings.withBuiltInSparkDefaults(
    bundledApiKey: String,
    seedFactory: () -> String,
): WorkerSettings {
    if (walletMode != WalletMode.BUILT_IN) return this
    val normalizedBundledApiKey = bundledApiKey.trim().takeIf { it.isNotBlank() }
    return copy(
        sparkApiKey = sparkApiKey?.takeIf { it.isNotBlank() } ?: normalizedBundledApiKey,
        sparkSeedHex = sparkSeedHex?.takeIf { it.isNotBlank() } ?: seedFactory(),
    )
}
