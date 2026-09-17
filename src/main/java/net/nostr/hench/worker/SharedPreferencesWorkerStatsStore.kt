package net.nostr.hench.worker

import android.content.Context
import androidx.core.content.edit

class SharedPreferencesWorkerStatsStore(context: Context) : WorkerStatsStore {
    private val prefs = context.getSharedPreferences("worker_stats", Context.MODE_PRIVATE)

    override fun load(): WorkerStats = WorkerStats(
        jobsExecuted = prefs.getLong(KEY_JOBS_EXECUTED, 0L),
        moneyEarnedMsat = prefs.getLong(KEY_MONEY_EARNED_MSAT, 0L),
    )

    override fun recordCompletedJob(amountMsat: Long) {
        val current = load()
        prefs.edit {
            putLong(KEY_JOBS_EXECUTED, current.jobsExecuted + 1)
            putLong(KEY_MONEY_EARNED_MSAT, current.moneyEarnedMsat + amountMsat.coerceAtLeast(0))
        }
    }

    companion object {
        const val KEY_JOBS_EXECUTED = "jobs_executed"
        const val KEY_MONEY_EARNED_MSAT = "money_earned_msat"
    }
}
