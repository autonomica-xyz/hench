package net.nostr.hench.worker

import net.nostr.hench.auth.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JobRequestPlatformTest {

    @Test
    fun detectsYouTubePlatform() {
        val request = JobRequest(
            id = "yt1",
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            mode = DeliveryMode.AUDIO_ONLY,
            durationSeconds = 120,
        )
        assertEquals(Platform.YOUTUBE, request.platform)
    }

    @Test
    fun detectsInstagramPlatform() {
        val request = JobRequest(
            id = "ig1",
            url = "https://www.instagram.com/reel/abc123/",
            mode = DeliveryMode.FULL,
            durationSeconds = 30,
        )
        assertEquals(Platform.INSTAGRAM, request.platform)
    }

    @Test
    fun detectsTikTokPlatform() {
        val request = JobRequest(
            id = "tt1",
            url = "https://www.tiktok.com/@user/video/123456",
            mode = DeliveryMode.FULL,
            durationSeconds = 15,
        )
        assertEquals(Platform.TIKTOK, request.platform)
    }

    @Test
    fun returnsNullForUnknownPlatform() {
        val request = JobRequest(
            id = "unk1",
            url = "https://peertube.social/w/abc",
            mode = DeliveryMode.FULL,
            durationSeconds = 300,
        )
        assertNull(request.platform)
    }

    @Test
    fun returnsNullForInvalidUrl() {
        val request = JobRequest(
            id = "bad1",
            url = "not-a-url",
            mode = DeliveryMode.AUDIO_ONLY,
            durationSeconds = null,
        )
        assertNull(request.platform)
    }
}
