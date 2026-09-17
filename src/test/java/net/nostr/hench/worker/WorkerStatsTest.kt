package net.nostr.hench.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkerStatsTest {
    @Test
    fun recordsCompletedJobsAndEarnings() {
        val store = InMemoryWorkerStatsStore()

        store.recordCompletedJob(2_000)
        store.recordCompletedJob(3_000)

        val stats = store.load()
        assertEquals(2, stats.jobsExecuted)
        assertEquals(5_000, stats.moneyEarnedMsat)
        assertEquals(5, stats.moneyEarnedSats)
    }
}
