package net.nostr.hench.dashboard

import net.nostr.hench.auth.Platform
import net.nostr.hench.auth.PlatformSession
import net.nostr.hench.auth.SessionHealth
import net.nostr.hench.settings.WorkerSettings
import net.nostr.hench.worker.WorkerStats
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardStateTest {
    @Test
    fun summarizesOfflineWorkerStats() {
        val state = DashboardState.from(
            WorkerSettings(enabled = false),
            WorkerStats(jobsExecuted = 7, moneyEarnedMsat = 12_345_000),
        )

        assertEquals("Worker offline", state.statusLabel)
        assertEquals(7, state.jobsExecuted)
        assertEquals(12_345, state.moneyEarnedSats)
        assertEquals(0, state.authenticatedSessionCount)
    }

    @Test
    fun cachedWalletBalanceOverridesCompletedJobEarningsForMoneyCard() {
        val state = DashboardState.from(
            WorkerSettings(enabled = true),
            WorkerStats(jobsExecuted = 1, moneyEarnedMsat = 9_000),
            cachedWalletBalanceSats = 10,
        )

        assertEquals(10, state.moneyEarnedSats)
        assertEquals("wallet balance", state.moneyEarnedSubtitle)
    }

    @Test
    fun enabledButInvalidSettingsNeedConfiguration() {
        val state = DashboardState.from(
            WorkerSettings(enabled = true),
            WorkerStats(),
        )

        assertEquals("Needs configuration", state.statusLabel)
    }

    @Test
    fun countsAuthenticatedSessions() {
        val sessions = listOf(
            testSession(Platform.YOUTUBE, SessionHealth.HEALTHY),
            testSession(Platform.TIKTOK, SessionHealth.UNKNOWN),
            testSession(Platform.INSTAGRAM, SessionHealth.BANNED),
            testSession(Platform.TWITTER, SessionHealth.RATE_LIMITED),
        )
        val state = DashboardState.from(
            WorkerSettings(enabled = true, useCookies = true),
            WorkerStats(),
            sessions,
        )

        assertEquals(2, state.authenticatedSessionCount)
        assertEquals(4, state.sessions.size)
        assertEquals("HEALTHY", state.sessions[0].health)
        assertEquals("BANNED", state.sessions[2].health)
    }

    private fun testSession(platform: Platform, health: SessionHealth) = PlatformSession(
        platform = platform,
        cookiesNetscape = "test",
        loggedInAtMs = System.currentTimeMillis(),
        health = health,
    )
}
