package net.nostr.hench.worker

import kotlinx.serialization.Serializable
import net.nostr.hench.auth.Platform

@Serializable
enum class DeliveryMode {
    AUDIO_ONLY,
    FULL,
}

@Serializable
data class JobRequest(
    val id: String,
    val url: String,
    val mode: DeliveryMode,
    val durationSeconds: Long? = null,
    val blossomServerUrl: String? = null,
    /** Hard socket timeout passed to yt-dlp's --socket-timeout (seconds). */
    val socketTimeoutSecs: Long? = null,
) {
    /** The platform detected from the URL, or null if unknown. */
    val platform: Platform? get() = Platform.forUrl(url)
}
