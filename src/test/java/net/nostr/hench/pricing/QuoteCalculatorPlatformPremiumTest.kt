package net.nostr.hench.pricing

import net.nostr.hench.settings.WorkerSettings
import net.nostr.hench.worker.DeliveryMode
import net.nostr.hench.worker.JobRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class QuoteCalculatorPlatformPremiumTest {

    @Test
    fun noPremiumWhenCookiesDisabled() {
        val settings = settings(useCookies = false)
        val request = instagramRequest()
        val quote = QuoteCalculator(settings).quote(request)
        // base(1000) + usage(60,000) = 61,000, no premium since cookies disabled
        assertEquals(61_000L, quote.getOrThrow().amountMsat)
    }

    @Test
    fun addsPremiumForInstagram() {
        // Instagram risk premium is 5L, so usage * 4 extra
        val settings = settings(useCookies = true, audioPerHour = 3_600_000)
        val request = instagramRequest(durationSeconds = 3600)
        val quote = QuoteCalculator(settings).quote(request)
        // base(1000) + usage(3,600,000) + premium(3,600,000 * 4) = 1000 + 3,600,000 + 14,400,000 = 18,001,000
        assertEquals(18_001_000L, quote.getOrThrow().amountMsat)
    }

    @Test
    fun noPremiumForYouTube() {
        // YouTube risk premium is 0L, so multiplier=1, no extra
        val settings = settings(useCookies = true, audioPerHour = 3_600_000)
        val request = JobRequest(
            id = "yt1",
            url = "https://www.youtube.com/watch?v=abc",
            mode = DeliveryMode.AUDIO_ONLY,
            durationSeconds = 3600,
        )
        val quote = QuoteCalculator(settings).quote(request)
        assertEquals(3_601_000L, quote.getOrThrow().amountMsat)
    }

    @Test
    fun noPremiumForUnknownPlatform() {
        val settings = settings(useCookies = true, audioPerHour = 3_600_000)
        val request = JobRequest(
            id = "unk1",
            url = "https://peertube.social/w/abc",
            mode = DeliveryMode.AUDIO_ONLY,
            durationSeconds = 3600,
        )
        val quote = QuoteCalculator(settings).quote(request)
        assertEquals(3_601_000L, quote.getOrThrow().amountMsat)
    }

    @Test
    fun premiumIsProportionalToUsage() {
        val settings = settings(useCookies = true, audioPerHour = 3_600_000)
        // 30-second Instagram reel
        val request = instagramRequest(durationSeconds = 30)
        val quote = QuoteCalculator(settings).quote(request)
        // min billable = 60s. usage = 3,600,000 * 60/3600 = 60,000
        // Instagram premium = 60,000 * 4 = 240,000
        // total = 1000 + 60,000 + 240,000 = 301,000
        assertEquals(301_000L, quote.getOrThrow().amountMsat)
    }

    private fun settings(
        useCookies: Boolean = false,
        audioPerHour: Long = 3_600_000,
    ) = WorkerSettings(
        enabled = true,
        relayUrls = listOf("wss://relay.example.com"),
        blossomUrls = listOf("https://blossom.example.com"),
        workerSecretKeyHex = "0".repeat(63) + "1",
        acceptAudio = true,
        acceptVideo = false,
        basePriceMsat = 1_000,
        audioPriceMsatPerHour = audioPerHour,
        useCookies = useCookies,
    )

    private fun instagramRequest(durationSeconds: Long = 60) = JobRequest(
        id = "ig1",
        url = "https://www.instagram.com/reel/abc123/",
        mode = DeliveryMode.AUDIO_ONLY,
        durationSeconds = durationSeconds,
    )
}
