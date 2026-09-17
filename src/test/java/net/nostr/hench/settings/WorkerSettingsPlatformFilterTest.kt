package net.nostr.hench.settings

import net.nostr.hench.auth.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerSettingsPlatformFilterTest {

    @Test
    fun acceptsAllUrlsByDefault() {
        val settings = WorkerSettings()
        assertTrue(settings.acceptsUrl("https://www.youtube.com/watch?v=abc"))
        assertTrue(settings.acceptsUrl("https://www.tiktok.com/@user/video/123"))
        assertTrue(settings.acceptsUrl("https://www.instagram.com/reel/abc/"))
        assertTrue(settings.acceptsUrl("https://x.com/user/status/123"))
        assertTrue(settings.acceptsUrl("https://vimeo.com/123"))
        assertTrue(settings.acceptsUrl("https://soundcloud.com/artist/track"))
    }

    @Test
    fun acceptsUnknownPlatformWhenAllowedListIsEmpty() {
        val settings = WorkerSettings()
        assertTrue(settings.acceptsUrl("https://peertube.social/w/abc"))
        assertTrue(settings.acceptsUrl("https://odysee.com/@channel/video"))
    }

    @Test
    fun rejectsUnknownPlatformWhenAllowedListIsSpecified() {
        val settings = WorkerSettings(allowedPlatforms = listOf(Platform.YOUTUBE, Platform.TIKTOK))
        assertFalse(settings.acceptsUrl("https://peertube.social/w/abc"))
        assertTrue(settings.acceptsUrl("https://www.youtube.com/watch?v=abc"))
        assertTrue(settings.acceptsUrl("https://www.tiktok.com/@user/video/123"))
    }

    @Test
    fun blockedPlatformsOverrideAllowedList() {
        val settings = WorkerSettings(
            allowedPlatforms = listOf(Platform.YOUTUBE, Platform.TIKTOK),
            blockedPlatforms = listOf(Platform.TIKTOK),
        )
        assertTrue(settings.acceptsUrl("https://www.youtube.com/watch?v=abc"))
        assertFalse(settings.acceptsUrl("https://www.tiktok.com/@user/video/123"))
    }

    @Test
    fun blockedPlatformsWorkWithEmptyAllowedList() {
        val settings = WorkerSettings(blockedPlatforms = listOf(Platform.INSTAGRAM))
        assertTrue(settings.acceptsUrl("https://www.youtube.com/watch?v=abc"))
        assertFalse(settings.acceptsUrl("https://www.instagram.com/reel/abc/"))
        // Unknown platforms still accepted
        assertTrue(settings.acceptsUrl("https://peertube.social/w/abc"))
    }

    @Test
    fun authenticatedPlatformsReturnsAllWhenEmpty() {
        val settings = WorkerSettings()
        assertTrue(settings.authenticatedPlatforms().containsAll(Platform.entries))
    }

    @Test
    fun authenticatedPlatformsReturnsOnlyAllowed() {
        val settings = WorkerSettings(allowedPlatforms = listOf(Platform.YOUTUBE, Platform.TIKTOK))
        assertEquals(listOf(Platform.YOUTUBE, Platform.TIKTOK), settings.authenticatedPlatforms())
    }
}
