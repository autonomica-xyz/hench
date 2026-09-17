package net.nostr.hench.blossom

import net.nostr.hench.worker.JobRequest
import java.util.concurrent.atomic.AtomicInteger

class BlossomServerSelector(defaultServerUrls: List<String>) {
    private val defaults = defaultServerUrls.mapNotNull { it.trim().takeIf(String::isNotBlank) }
    private val nextIndex = AtomicInteger(0)

    fun serverFor(request: JobRequest): String {
        return serverCandidatesFor(request).first()
    }

    fun serverCandidatesFor(request: JobRequest): List<String> {
        val requesterServer = request.blossomServerUrl?.trim()?.takeIf { it.isNotBlank() }
        if (requesterServer != null) {
            return (listOf(requesterServer) + defaults).distinct()
        }

        require(defaults.isNotEmpty()) { "At least one Blossom server URL is required" }
        val startIndex = Math.floorMod(nextIndex.getAndIncrement(), defaults.size)
        return defaults.indices.map { offset -> defaults[(startIndex + offset) % defaults.size] }
    }
}
