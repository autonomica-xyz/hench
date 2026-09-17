package net.nostr.hench.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformTest {
    @Test
    fun detectsYouTubeUrls() {
        assertEquals(Platform.YOUTUBE, Platform.forUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(Platform.YOUTUBE, Platform.forUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(Platform.YOUTUBE, Platform.forUrl("https://m.youtube.com/watch?v=abc"))
        assertEquals(Platform.YOUTUBE, Platform.forUrl("https://music.youtube.com/watch?v=abc"))
    }

    @Test
    fun detectsTikTokUrls() {
        assertEquals(Platform.TIKTOK, Platform.forUrl("https://www.tiktok.com/@user/video/123"))
        assertEquals(Platform.TIKTOK, Platform.forUrl("https://vm.tiktok.com/abc123"))
        assertEquals(Platform.TIKTOK, Platform.forUrl("https://m.tiktok.com/@user"))
    }

    @Test
    fun detectsInstagramUrls() {
        assertEquals(Platform.INSTAGRAM, Platform.forUrl("https://www.instagram.com/reel/abc123/"))
        assertEquals(Platform.INSTAGRAM, Platform.forUrl("https://instagram.com/p/xyz/"))
    }

    @Test
    fun detectsTwitterUrls() {
        assertEquals(Platform.TWITTER, Platform.forUrl("https://x.com/user/status/123"))
        assertEquals(Platform.TWITTER, Platform.forUrl("https://twitter.com/user/status/123"))
        assertEquals(Platform.TWITTER, Platform.forUrl("https://mobile.twitter.com/user/status/123"))
    }

    @Test
    fun detectsFacebookUrls() {
        assertEquals(Platform.FACEBOOK, Platform.forUrl("https://www.facebook.com/watch/?v=123"))
        assertEquals(Platform.FACEBOOK, Platform.forUrl("https://fb.watch/abc"))
    }

    @Test
    fun detectsRedditUrls() {
        assertEquals(Platform.REDDIT, Platform.forUrl("https://www.reddit.com/r/videos/comments/abc/"))
        assertEquals(Platform.REDDIT, Platform.forUrl("https://old.reddit.com/r/test/"))
    }

    @Test
    fun detectsOtherPlatforms() {
        assertEquals(Platform.VIMEO, Platform.forUrl("https://vimeo.com/123456789"))
        assertEquals(Platform.SOUNDCLOUD, Platform.forUrl("https://soundcloud.com/artist/track"))
        assertEquals(Platform.BILIBILI, Platform.forUrl("https://www.bilibili.com/video/BV1xx"))
        assertEquals(Platform.DAILYMOTION, Platform.forUrl("https://www.dailymotion.com/video/x123"))
        assertEquals(Platform.PINTEREST, Platform.forUrl("https://www.pinterest.com/pin/123/"))
        assertEquals(Platform.SNAPCHAT, Platform.forUrl("https://story.snapchat.com/s/abc"))
    }

    @Test
    fun returnsNullForUnknownPlatforms() {
        assertNull(Platform.forUrl("https://example.com/video"))
        assertNull(Platform.forUrl("https://peertube.social/w/abc"))
        assertNull(Platform.forUrl("https://odysee.com/@channel:123/video:456"))
    }

    @Test
    fun returnsNullForInvalidUrls() {
        assertNull(Platform.forUrl("not a url"))
        assertNull(Platform.forUrl(""))
    }

    @Test
    fun allPlatformsHaveDomains() {
        Platform.entries.forEach { platform ->
            assertTrue("${platform.name} should have at least one domain", platform.domains.isNotEmpty())
            assertTrue("${platform.name} should have a display name", platform.displayName.isNotBlank())
            assertTrue("${platform.name} should have a login URL", platform.loginUrl.isNotBlank())
        }
    }

    @Test
    fun riskPremiumsAreNonNegative() {
        Platform.entries.forEach { platform ->
            assertTrue("${platform.name} risk premium should be >= 0", platform.defaultRiskPremium >= 0)
        }
    }
}
