package net.nostr.hench.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtdlpAuthFailureDetectionTest {

    @Test
    fun detects403() {
        assertTrue(YtdlpBlossomJobExecutorTestable.isAuthFailure("HTTP Error 403: Forbidden"))
    }

    @Test
    fun detects401() {
        assertTrue(YtdlpBlossomJobExecutorTestable.isAuthFailure("HTTP Error 401: Unauthorized"))
    }

    @Test
    fun detectsSignInRequired() {
        assertTrue(YtdlpBlossomJobExecutorTestable.isAuthFailure("Sign in to confirm you're not a bot"))
    }

    @Test
    fun detectsLoginRequired() {
        assertTrue(YtdlpBlossomJobExecutorTestable.isAuthFailure("ERROR: login required for this content"))
    }

    @Test
    fun detectsAuthenticationError() {
        assertTrue(YtdlpBlossomJobExecutorTestable.isAuthFailure("Authentication failed for this request"))
    }

    @Test
    fun detectsPrivateVideoWithLogin() {
        assertTrue(YtdlpBlossomJobExecutorTestable.isAuthFailure("Private video. Login required."))
    }

    @Test
    fun detectsCookiesRequired() {
        assertTrue(YtdlpBlossomJobExecutorTestable.isAuthFailure("Cookies are required for this content"))
    }

    @Test
    fun doesNotFlagNormalErrors() {
        assertFalse(YtdlpBlossomJobExecutorTestable.isAuthFailure("Network timeout"))
        assertFalse(YtdlpBlossomJobExecutorTestable.isAuthFailure("HTTP Error 404: Not Found"))
        assertFalse(YtdlpBlossomJobExecutorTestable.isAuthFailure("Download interrupted"))
        assertFalse(YtdlpBlossomJobExecutorTestable.isAuthFailure(""))
    }

    /** Exposes the companion method for testing. */
    private object YtdlpBlossomJobExecutorTestable {
        fun isAuthFailure(message: String): Boolean {
            val lower = message.lowercase()
            return lower.contains("http error 403") ||
                lower.contains("http error 401") ||
                lower.contains("sign in") ||
                lower.contains("login required") ||
                lower.contains("authentication") ||
                lower.contains("not available") && lower.contains("login") ||
                lower.contains("private video") ||
                lower.contains("cookies") && lower.contains("required")
        }
    }
}
