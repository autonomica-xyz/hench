package net.nostr.hench.pricing

import kotlinx.serialization.Serializable
import net.nostr.hench.auth.Platform
import net.nostr.hench.settings.WorkerSettings
import net.nostr.hench.worker.DeliveryMode
import net.nostr.hench.worker.JobRequest

class UnsupportedDeliveryModeException(mode: DeliveryMode) :
    IllegalArgumentException("worker does not accept delivery mode $mode")

@Serializable
data class PriceQuote(
    val requestId: String,
    val amountMsat: Long,
    val billableSeconds: Long,
)

class QuoteCalculator(
    private val settings: WorkerSettings,
) {
    fun quote(request: JobRequest): Result<PriceQuote> {
        if (!settings.supports(request.mode)) {
            return Result.failure(UnsupportedDeliveryModeException(request.mode))
        }

        val billableSeconds = maxOf(request.durationSeconds ?: settings.minBillableSeconds, settings.minBillableSeconds)
        val hourlyRate = when (request.mode) {
            DeliveryMode.AUDIO_ONLY -> settings.audioPriceMsatPerHour
            DeliveryMode.FULL -> settings.videoPriceMsatPerHour
        }
        val usagePrice = hourlyRate * billableSeconds / SECONDS_PER_HOUR

        // Platform risk premium — charge more for platforms that risk account bans
        val platformPremium = if (settings.useCookies) {
            val platform = Platform.forUrl(request.url)
            val multiplier = platform?.defaultRiskPremium?.coerceAtLeast(1L) ?: 1L
            if (multiplier > 1L) usagePrice * (multiplier - 1L) else 0L
        } else 0L

        return Result.success(
            PriceQuote(
                requestId = request.id,
                amountMsat = settings.basePriceMsat + usagePrice + platformPremium,
                billableSeconds = billableSeconds,
            ),
        )
    }

    companion object {
        private const val SECONDS_PER_HOUR = 3_600L
    }
}
