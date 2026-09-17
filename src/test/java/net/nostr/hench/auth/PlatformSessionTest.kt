package net.nostr.hench.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformSessionTest {

    @Test
    fun unknownHealthIsUsable() {
        val session = session(health = SessionHealth.UNKNOWN)
        assertTrue(session.isUsable)
    }

    @Test
    fun healthyIsUsable() {
        val session = session(health = SessionHealth.HEALTHY)
        assertTrue(session.isUsable)
    }

    @Test
    fun bannedIsNotUsable() {
        val session = session(health = SessionHealth.BANNED)
        assertFalse(session.isUsable)
    }

    @Test
    fun expiredIsNotUsable() {
        val session = session(health = SessionHealth.EXPIRED)
        assertFalse(session.isUsable)
    }

    @Test
    fun rateLimitedIsNotUsable() {
        val session = session(health = SessionHealth.RATE_LIMITED)
        assertFalse(session.isUsable)
    }

    @Test
    fun withHealthUpdatesHealthAndTimestamp() {
        val original = session(health = SessionHealth.UNKNOWN)
        val updated = original.withHealth(SessionHealth.HEALTHY)
        assertEquals(SessionHealth.HEALTHY, updated.health)
        assertTrue(updated.lastHealthCheckMs > 0)
        assertEquals(SessionHealth.UNKNOWN, original.health) // original unchanged
    }

    @Test
    fun withJobCompletedIncrementsCount() {
        val original = session(jobsProcessed = 5)
        val updated = original.withJobCompleted()
        assertEquals(6, updated.jobsProcessed)
        assertTrue(updated.lastJobProcessedMs > 0)
        assertEquals(5, original.jobsProcessed) // original unchanged
    }

    @Test
    fun riskPremiumReturnsPlatformDefault() {
        val instagram = session(platform = Platform.INSTAGRAM)
        assertEquals(5L, instagram.riskPremiumMultiplier)

        val youtube = session(platform = Platform.YOUTUBE)
        assertEquals(1L, youtube.riskPremiumMultiplier) // minimum is 1x

        val twitter = session(platform = Platform.TWITTER)
        assertEquals(3L, twitter.riskPremiumMultiplier)
    }

    private fun session(
        platform: Platform = Platform.YOUTUBE,
        health: SessionHealth = SessionHealth.UNKNOWN,
        jobsProcessed: Int = 0,
    ) = PlatformSession(
        platform = platform,
        cookiesNetscape = ".${platform.domains.first()}\tTRUE\t/\tTRUE\t0\tsid\ttest123",
        loggedInAtMs = System.currentTimeMillis(),
        health = health,
        jobsProcessed = jobsProcessed,
    )
}
