package net.nostr.hench.auth

import kotlinx.serialization.Serializable

/**
 * Known platforms that yt-dlp supports and that benefit from authenticated sessions.
 * Each entry carries the domains used for cookie matching and the login URL presented
 * to the operator in the WebView login flow.
 */
@Serializable
enum class Platform(
    val displayName: String,
    val domains: List<String>,
    val loginUrl: String,
    val defaultRiskPremium: Long,
    /** Browser impersonation target for yt-dlp --impersonate (requires curl_cffi). */
    val impersonateTarget: String? = null,
) {
    YOUTUBE(
        displayName = "YouTube",
        domains = listOf("youtube.com", "youtu.be", "m.youtube.com", "music.youtube.com"),
        loginUrl = "https://accounts.google.com/ServiceLogin?continue=https://youtube.com",
        defaultRiskPremium = 0L,
    ),
    TIKTOK(
        displayName = "TikTok",
        domains = listOf("tiktok.com", "www.tiktok.com", "vm.tiktok.com", "m.tiktok.com"),
        loginUrl = "https://www.tiktok.com/login",
        defaultRiskPremium = 1L,
        impersonateTarget = "chrome",
    ),
    INSTAGRAM(
        displayName = "Instagram",
        domains = listOf("instagram.com", "www.instagram.com"),
        loginUrl = "https://www.instagram.com/accounts/login/",
        defaultRiskPremium = 5L,
    ),
    TWITTER(
        displayName = "Twitter / X",
        domains = listOf("twitter.com", "x.com", "mobile.twitter.com"),
        loginUrl = "https://x.com/login",
        defaultRiskPremium = 3L,
    ),
    FACEBOOK(
        displayName = "Facebook",
        domains = listOf("facebook.com", "www.facebook.com", "m.facebook.com", "fb.watch"),
        loginUrl = "https://www.facebook.com/login/",
        defaultRiskPremium = 4L,
    ),
    REDDIT(
        displayName = "Reddit",
        domains = listOf("reddit.com", "www.reddit.com", "old.reddit.com"),
        loginUrl = "https://www.reddit.com/login/",
        defaultRiskPremium = 2L,
    ),
    VIMEO(
        displayName = "Vimeo",
        domains = listOf("vimeo.com", "player.vimeo.com"),
        loginUrl = "https://vimeo.com/log_in",
        defaultRiskPremium = 1L,
        impersonateTarget = "chrome",
    ),
    SOUNDCLOUD(
        displayName = "SoundCloud",
        domains = listOf("soundcloud.com", "api.soundcloud.com"),
        loginUrl = "https://soundcloud.com/signin",
        defaultRiskPremium = 0L,
    ),
    BILIBILI(
        displayName = "Bilibili",
        domains = listOf("bilibili.com", "www.bilibili.com", "b23.tv"),
        loginUrl = "https://passport.bilibili.com/login",
        defaultRiskPremium = 2L,
    ),
    DAILYMOTION(
        displayName = "Dailymotion",
        domains = listOf("dailymotion.com", "www.dailymotion.com"),
        loginUrl = "https://www.dailymotion.com/login",
        defaultRiskPremium = 0L,
    ),
    PINTEREST(
        displayName = "Pinterest",
        domains = listOf("pinterest.com", "www.pinterest.com", "pin.it"),
        loginUrl = "https://www.pinterest.com/login/",
        defaultRiskPremium = 2L,
        impersonateTarget = "chrome",
    ),
    SNAPCHAT(
        displayName = "Snapchat",
        domains = listOf("snapchat.com", "story.snapchat.com", "t.snapchat.com"),
        loginUrl = "https://accounts.snapchat.com/accounts/login",
        defaultRiskPremium = 3L,
    ),
    ;

    companion object {
        /** Find the platform that owns the given URL, if any. */
        fun forUrl(url: String): Platform? {
            val host = runCatching { java.net.URL(url).host.lowercase() }.getOrNull() ?: return null
            return entries.firstOrNull { platform ->
                platform.domains.any { domain ->
                    host == domain || host.endsWith(".$domain")
                }
            }
        }
    }
}

enum class SessionHealth {
    /** Session cookies exist and the last health check passed. */
    HEALTHY,
    /** Session has not been checked yet. */
    UNKNOWN,
    /** Platform returned a rate-limit or temporary block. */
    RATE_LIMITED,
    /** Platform rejected the session (banned, expired cookies, etc.). */
    BANNED,
    /** Session cookies are past their expiry or were revoked. */
    EXPIRED,
}

/**
 * Represents a set of cookies for a single platform, persisted in SharedPreferences.
 */
data class PlatformSession(
    val platform: Platform,
    val cookiesNetscape: String,
    val loggedInAtMs: Long,
    val lastHealthCheckMs: Long = 0L,
    val health: SessionHealth = SessionHealth.UNKNOWN,
    val jobsProcessed: Int = 0,
    val lastJobProcessedMs: Long = 0L,
) {
    val isUsable: Boolean
        get() = health == SessionHealth.HEALTHY || health == SessionHealth.UNKNOWN

    /** Risk premium multiplier (1L = 1×, 5L = 5×) applied to the base price. */
    val riskPremiumMultiplier: Long
        get() = platform.defaultRiskPremium.coerceAtLeast(1L)

    fun withHealth(health: SessionHealth) = copy(
        health = health,
        lastHealthCheckMs = System.currentTimeMillis(),
    )

    fun withJobCompleted() = copy(
        jobsProcessed = jobsProcessed + 1,
        lastJobProcessedMs = System.currentTimeMillis(),
    )
}
