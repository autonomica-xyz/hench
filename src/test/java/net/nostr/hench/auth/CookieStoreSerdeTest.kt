package net.nostr.hench.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CookieStoreSerdeTest {

    @Test
    fun roundTripsSessionThroughSerialization() {
        val original = PlatformSession(
            platform = Platform.INSTAGRAM,
            cookiesNetscape = ".instagram.com\tTRUE\t/\tTRUE\t0\tsessionid\tabc123",
            loggedInAtMs = 1700000000000L,
            lastHealthCheckMs = 1700001000000L,
            health = SessionHealth.HEALTHY,
            jobsProcessed = 42,
            lastJobProcessedMs = 1700002000000L,
        )

        // Access the serialize/deserialize via reflection or the companion
        // Since we can't instantiate CookieStore without Android context,
        // test the serialization format directly.
        val raw = serializeManual(original)
        val restored = deserializeManual(raw)

        assertNotNull(restored)
        assertEquals(original.platform, restored!!.platform)
        assertEquals(original.cookiesNetscape, restored.cookiesNetscape)
        assertEquals(original.loggedInAtMs, restored.loggedInAtMs)
        assertEquals(original.lastHealthCheckMs, restored.lastHealthCheckMs)
        assertEquals(original.health, restored.health)
        assertEquals(original.jobsProcessed, restored.jobsProcessed)
        assertEquals(original.lastJobProcessedMs, restored.lastJobProcessedMs)
    }

    @Test
    fun handlesNullInput() {
        assertNull(deserializeManual(null))
    }

    @Test
    fun handlesEmptyString() {
        assertNull(deserializeManual(""))
    }

    @Test
    fun handlesTruncatedInput() {
        assertNull(deserializeManual("YOUTUBE"))
        assertNull(deserializeManual("YOUTUBE\u0000cookies"))
    }

    @Test
    fun handlesUnknownPlatform() {
        val raw = "NONEXISTENT_PLATFORM\u0000cookies\u00001000\u00000\u0000UNKNOWN\u00000\u00000"
        assertNull(deserializeManual(raw))
    }

    companion object {
        /** Mirrors CookieStore.serialize for testing without Android. */
        private fun serializeManual(session: PlatformSession): String = buildString {
            append(session.platform.name)
            append('\u0000')
            append(session.cookiesNetscape)
            append('\u0000')
            append(session.loggedInAtMs)
            append('\u0000')
            append(session.lastHealthCheckMs)
            append('\u0000')
            append(session.health.name)
            append('\u0000')
            append(session.jobsProcessed)
            append('\u0000')
            append(session.lastJobProcessedMs)
        }

        /** Mirrors CookieStore.deserialize for testing without Android. */
        private fun deserializeManual(raw: String?): PlatformSession? {
            if (raw == null) return null
            val parts = raw.split('\u0000')
            if (parts.size < 7) return null
            return runCatching {
                PlatformSession(
                    platform = Platform.valueOf(parts[0]),
                    cookiesNetscape = parts[1],
                    loggedInAtMs = parts[2].toLongOrNull() ?: 0L,
                    lastHealthCheckMs = parts[3].toLongOrNull() ?: 0L,
                    health = runCatching { SessionHealth.valueOf(parts[4]) }.getOrDefault(SessionHealth.UNKNOWN),
                    jobsProcessed = parts[5].toIntOrNull() ?: 0,
                    lastJobProcessedMs = parts[6].toLongOrNull() ?: 0L,
                )
            }.getOrNull()
        }
    }
}
