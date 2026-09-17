package net.nostr.hench.worker

data class WorkerStats(
    val jobsExecuted: Long = 0,
    val moneyEarnedMsat: Long = 0,
) {
    val moneyEarnedSats: Long get() = moneyEarnedMsat / 1_000L
}

interface WorkerStatsStore {
    fun load(): WorkerStats
    fun recordCompletedJob(amountMsat: Long)
}

class InMemoryWorkerStatsStore : WorkerStatsStore {
    private var stats = WorkerStats()
    override fun load(): WorkerStats = stats
    override fun recordCompletedJob(amountMsat: Long) {
        stats = stats.copy(
            jobsExecuted = stats.jobsExecuted + 1,
            moneyEarnedMsat = stats.moneyEarnedMsat + amountMsat.coerceAtLeast(0),
        )
    }
}
