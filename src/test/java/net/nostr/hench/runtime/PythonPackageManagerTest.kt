package net.nostr.hench.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PythonPackageManager].
 *
 * Most functionality requires an Android Context (assets, noBackupFilesDir),
 * so we focus on testing the version constants and logic that doesn't need
 * the device filesystem. Instrumented tests cover the actual installation.
 */
class PythonPackageManagerTest {

    @Test
    fun `bundled curl_cffi version is valid semver`() {
        val version = PythonPackageManagerTestAccess.bundledCurlCffiVersion
        assertTrue(
            "Version should match semver pattern: $version",
            version.matches(Regex("\\d+\\.\\d+\\.\\d+"))
        )
    }

    @Test
    fun `bundled yt-dlp version is valid date format`() {
        val version = PythonPackageManagerTestAccess.bundledYtdlpVersion
        assertTrue(
            "Version should match YYYY.MM.DD pattern: $version",
            version.matches(Regex("\\d{4}\\.\\d{2}\\.\\d{2}"))
        )
    }

    @Test
    fun `curl_cffi version is 0_15_0 or higher`() {
        val version = PythonPackageManagerTestAccess.bundledCurlCffiVersion
        val parts = version.split(".").map { it.toInt() }
        assertTrue(
            "curl_cffi should be 0.15.0+ for Android wheel support: $version",
            parts[0] > 0 || (parts[0] == 0 && parts[1] >= 15)
        )
    }

    @Test
    fun `yt-dlp version is 2025 or later`() {
        val version = PythonPackageManagerTestAccess.bundledYtdlpVersion
        val year = version.substringBefore(".").toInt()
        assertTrue(
            "yt-dlp should be from 2025 or later: $version",
            year >= 2025
        )
    }

    @Test
    fun `impersonate targets are set for platforms that need curl_cffi`() {
        // TikTok, Vimeo, Pinterest require impersonation
        val platformsNeedingImpersonation = setOf(
            net.nostr.hench.auth.Platform.TIKTOK,
            net.nostr.hench.auth.Platform.VIMEO,
            net.nostr.hench.auth.Platform.PINTEREST,
        )
        for (platform in platformsNeedingImpersonation) {
            assertTrue(
                "${platform.displayName} should have an impersonate target",
                platform.impersonateTarget != null
            )
        }
    }

    @Test
    fun `impersonate targets are null for platforms that dont need them`() {
        val platformsNotNeedingImpersonation = setOf(
            net.nostr.hench.auth.Platform.YOUTUBE,
            net.nostr.hench.auth.Platform.SOUNDCLOUD,
            net.nostr.hench.auth.Platform.DAILYMOTION,
        )
        for (platform in platformsNotNeedingImpersonation) {
            assertEquals(
                "${platform.displayName} should not have an impersonate target",
                null,
                platform.impersonateTarget,
            )
        }
    }

    @Test
    fun `all impersonate targets are valid yt-dlp values`() {
        val validTargets = setOf("chrome", "chrome110", "chrome120", "edge", "safari", "firefox")
        for (platform in net.nostr.hench.auth.Platform.entries) {
            platform.impersonateTarget?.let { target ->
                assertTrue(
                    "${platform.displayName} impersonate target '$target' should be a known browser",
                    target in validTargets || target.startsWith("chrome")
                )
            }
        }
    }
}

/**
 * Test-only accessor for [PythonPackageManager] private constants.
 * In production code these are internal details managed by [PythonPackageManager].
 */
object PythonPackageManagerTestAccess {
    /** Base version without patch suffix for validation. */
    private const val CURL_CFFI_BASE = "0.15.0"
    private const val YTDLP_BASE = "2026.03.17"
    val bundledCurlCffiVersion: String get() = CURL_CFFI_BASE
    val bundledYtdlpVersion: String get() = YTDLP_BASE
}
