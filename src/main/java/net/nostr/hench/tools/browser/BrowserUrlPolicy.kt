package net.nostr.hench.tools.browser

import net.nostr.hench.tools.ytdlp.UrlSafety

/**
 * URL policy for the browser tools: generic http(s) + private-address checks
 * from [UrlSafety.checkAnyHttpUrl], applied by [BrowserRenderer] to the
 * initial URL, every redirect hop, every navigation, and every subresource.
 *
 * There is deliberately no per-host denylist: renders run in a dedicated
 * unauthenticated WebView profile (see BrowserRenderer), so operator login
 * sessions are unreachable by construction rather than by host list.
 */
object BrowserUrlPolicy {

    /**
     * Full check: scheme + host + DNS resolution. Only call OFF the main
     * thread — [UrlSafety.checkAnyHttpUrl] resolves the host, which throws
     * NetworkOnMainThreadException (and would fail closed on every named host).
     */
    fun check(url: String): UrlSafety.Result.Reject? =
        UrlSafety.checkAnyHttpUrl(url).let { it as? UrlSafety.Result.Reject }

    /**
     * Cheap check safe for WebView callbacks on the main thread: scheme +
     * literal-IP private ranges only, no DNS. Named hosts pass here and are
     * still validated with DNS by [check] on interception/prefetch threads.
     */
    fun checkCheap(url: String): UrlSafety.Result.Reject? =
        UrlSafety.checkHttpUrlCheap(url).let { it as? UrlSafety.Result.Reject }
}
