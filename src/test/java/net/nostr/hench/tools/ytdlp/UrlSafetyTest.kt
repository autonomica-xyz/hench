package net.nostr.hench.tools.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSafetyTest {

    @Test
    fun rejectsNonHttpSchemes() {
        val r = UrlSafety.checkSource("file:///etc/passwd", emptyList())
        assertTrue(r is UrlSafety.Result.Reject)
        assertEquals("UNSUPPORTED_URL", (r as UrlSafety.Result.Reject).code)
    }

    @Test
    fun rejectsLoopbackHost() {
        val r = UrlSafety.checkSource("http://localhost/path", emptyList())
        assertTrue("localhost must be rejected", r is UrlSafety.Result.Reject)
    }

    @Test
    fun rejectsLiteralPrivateIp() {
        // 10.0.0.1 is a literal IP; no DNS round-trip needed.
        val r = UrlSafety.checkSource("http://10.0.0.1/x", emptyList())
        assertTrue("private IP must be rejected", r is UrlSafety.Result.Reject)
    }

    @Test
    fun rejectsBlossomServerNotInAllowlist() {
        val r = UrlSafety.checkBlossom("https://evil.example/upload", listOf("https://blossom.test"))
        assertTrue(r is UrlSafety.Result.Reject)
    }
}
