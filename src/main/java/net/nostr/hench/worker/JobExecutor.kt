package net.nostr.hench.worker

/**
 * Pluggable download executor. Production implementation is
 * [net.nostr.hench.runtime.YtdlpBlossomJobExecutor]; tests inject fakes.
 */
interface JobExecutor {
    suspend fun execute(request: JobRequest): JobArtifact
}
