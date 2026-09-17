package net.nostr.hench.pricing

import net.nostr.hench.settings.WorkerSettings
import net.nostr.hench.worker.DeliveryMode
import net.nostr.hench.worker.JobRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteCalculatorTest {
    @Test
    fun audioQuoteUsesBasePricePlusProratedHourlyPrice() {
        val settings = settings(audioPerHour = 3_600_000, base = 1_000)
        val request = JobRequest(
            id = "job1",
            url = "https://youtube.test/video",
            mode = DeliveryMode.AUDIO_ONLY,
            durationSeconds = 180,
        )

        val quote = QuoteCalculator(settings).quote(request)

        assertEquals(181_000, quote.getOrThrow().amountMsat)
    }

    @Test
    fun videoQuoteUsesVideoRateAndMinimumBillableDuration() {
        val settings = settings(
            acceptVideo = true,
            videoPerHour = 7_200_000,
            base = 500,
            minBillableSeconds = 60,
        )
        val request = JobRequest(
            id = "job2",
            url = "https://youtube.test/video",
            mode = DeliveryMode.FULL,
            durationSeconds = 5,
        )

        val quote = QuoteCalculator(settings).quote(request)

        assertEquals(120_500, quote.getOrThrow().amountMsat)
    }

    @Test
    fun rejectsVideoWhenUserOnlyAcceptsAudio() {
        val settings = settings(acceptVideo = false)
        val request = JobRequest(
            id = "job3",
            url = "https://youtube.test/video",
            mode = DeliveryMode.FULL,
            durationSeconds = 600,
        )

        val quote = QuoteCalculator(settings).quote(request)

        assertTrue(quote.exceptionOrNull() is UnsupportedDeliveryModeException)
    }

    private fun settings(
        acceptVideo: Boolean = false,
        audioPerHour: Long = 3_600_000,
        videoPerHour: Long = 7_200_000,
        base: Long = 0,
        minBillableSeconds: Long = 60,
    ) = WorkerSettings(
        enabled = true,
        relayUrls = listOf("wss://relay.example.com"),
        blossomUrls = listOf("https://blossom.example.com"),
        workerSecretKeyHex = "0".repeat(63) + "1",
        acceptAudio = true,
        acceptVideo = acceptVideo,
        basePriceMsat = base,
        audioPriceMsatPerHour = audioPerHour,
        videoPriceMsatPerHour = videoPerHour,
        minBillableSeconds = minBillableSeconds,
    )
}
