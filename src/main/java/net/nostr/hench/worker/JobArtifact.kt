package net.nostr.hench.worker

/**
 * A successfully downloaded and uploaded media artifact.
 */
data class JobArtifact(
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)
