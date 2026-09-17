package net.nostr.hench.worker

interface ProcessedJobStore {
    fun hasProcessed(requestId: String): Boolean
    fun markProcessed(requestId: String)
}

class InMemoryProcessedJobStore : ProcessedJobStore {
    private val ids = java.util.Collections.synchronizedSet(linkedSetOf<String>())
    override fun hasProcessed(requestId: String): Boolean = ids.contains(requestId)
    override fun markProcessed(requestId: String) { ids.add(requestId) }
}
