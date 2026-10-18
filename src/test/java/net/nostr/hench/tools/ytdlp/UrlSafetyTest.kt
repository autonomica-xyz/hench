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
    fun rejectsIpv4EmbeddedInIpv6Literal() {
        // ::10.0.0.1 parses as an Inet6Address whose hostAddress ends in a
        // dotted v4 tail; a prefix-only fc/fd check would miss it.
        val compat = UrlSafety.checkAnyHttpUrl("http://[::10.0.0.1]/x")
        assertTrue("IPv6-compatible private literal must be rejected", compat is UrlSafety.Result.Reject)
        val mapped = UrlSafety.checkAnyHttpUrl("http://[::ffff:10.0.0.1]/x")
        assertTrue("IPv4-mapped private literal must be rejected", mapped is UrlSafety.Result.Reject)
    }

    @Test
    fun rejectsBlossomServerNotInAllowlist() {
        val r = UrlSafety.checkBlossom("https://evil.example/upload", listOf("https://blossom.test"))
        assertTrue(r is UrlSafety.Result.Reject)
    }
}
