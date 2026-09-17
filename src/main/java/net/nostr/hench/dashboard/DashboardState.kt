package net.nostr.hench.dashboard

import net.nostr.hench.auth.PlatformSession
import net.nostr.hench.settings.WorkerSettings
import net.nostr.hench.worker.WorkerStats

data class SessionSummary(
    val platformName: String,
    val health: String,
    val jobsProcessed: Int,
)

data class DashboardState(
    val workerEnabled: Boolean,
    val jobsExecuted: Long,
    val moneyEarnedMsat: Long,
    val cachedWalletBalanceSats: Long?,
    val validationIssueCount: Int,
    val sessions: List<SessionSummary>,
    val useCookies: Boolean,
) {
    val moneyEarnedSats: Long get() = cachedWalletBalanceSats ?: (moneyEarnedMsat / 1_000L)
    val moneyEarnedSubtitle: String get() = if (cachedWalletBalanceSats != null) "wallet balance" else "$moneyEarnedMsat msat"
    val authenticatedSessionCount: Int get() = sessions.count { it.health == "HEALTHY" || it.health == "UNKNOWN" }
    val statusLabel: String get() = when {
        workerEnabled && validationIssueCount == 0 -> "Worker online"
        workerEnabled -> "Needs configuration"
        else -> "Worker offline"
    }

    companion object {
        fun from(
            settings: WorkerSettings,
            stats: WorkerStats,
            platformSessions: List<PlatformSession> = emptyList(),
            cachedWalletBalanceSats: Long? = null,
        ): DashboardState = DashboardState(
            workerEnabled = settings.enabled,
            jobsExecuted = stats.jobsExecuted,
            moneyEarnedMsat = stats.moneyEarnedMsat,
            cachedWalletBalanceSats = cachedWalletBalanceSats,
            validationIssueCount = settings.validate().size,
            sessions = platformSessions.map { SessionSummary(
                platformName = it.platform.displayName,
                health = it.health.name,
                jobsProcessed = it.jobsProcessed,
            ) },
            useCookies = settings.useCookies,
        )
    }
}
