package net.nostr.hench.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the platform impersonate-target table consumed by
 * YtdlpBlossomJobExecutor + PythonPackageManager. Install paths and version
 * constants need an Android Context; device coverage is manual.
 */
class PythonPackageManagerTest {

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
