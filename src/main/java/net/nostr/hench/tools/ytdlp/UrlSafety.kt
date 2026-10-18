package net.nostr.hench.tools.ytdlp

import net.nostr.hench.auth.Platform
import java.net.InetAddress
import java.net.URI

/**
 * Network-safety validation for a public worker. Deny-by-default:
 * the source URL and the Blossom server must resolve to public addresses, and
 * the source must map to a known extractor.
 *
 * Precise coverage (do not overstate):
 * - The DNS check runs once at preflight; it does NOT pin later resolutions,
 *   so a fast-rebinding host could still race it. For the browser tools,
 *   [net.nostr.hench.tools.browser.BrowserUrlPolicy] is re-applied to every
 *   WebView request (main frame, subframes, subresources) plus redirect hops
 *   are resolved up front over [publicOnlyDns].
 * - Secondary fetches yt-dlp makes itself (manifests, segments, CDN hosts) are
 *   NOT filtered — only the top-level source URL is checked.
 * - Blossom uploads go over OkHttp with [publicOnlyDns] and redirects
 *   DISABLED — the DNS hook never sees literal-IP redirect targets, so
 *   followed 3xx hops would bypass it. The server URL and the
 *   server-returned blob URL are both validated before requests.
 */
object UrlSafety {

    /** Reject these address ranges after DNS resolution (loopback / private / link-local). */
    private fun InetAddress.isPrivateOrLoopback(): Boolean = when {
        isLoopbackAddress -> true
        isAnyLocalAddress -> true
        isLinkLocalAddress -> true
        isSiteLocalAddress -> true // RFC1918 10/8, 172.16/12, 192.168/16
        isMulticastAddress -> true
        this is java.net.Inet6Address -> {
            val b = address ?: return true // fail closed
            // Byte-level checks, not string forms: Java renders ::10.0.0.1 in
            // hex (no dotted tail) and maps ::ffff:a.b.c.d to Inet4Address.
            when {
                // v4-mapped ::ffff:a.b.c.d
                b.size == 16 && b.copyOfRange(0, 10).all { it == 0.toByte() } &&
                    b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte() ->
                    privateV4Bytes(b.copyOfRange(12, 16))
                // v4-compatible ::a.b.c.d (nonzero v4 tail)
                b.size == 16 && b.copyOfRange(0, 12).all { it == 0.toByte() } &&
                    !b.copyOfRange(12, 16).all { it == 0.toByte() } ->
                    privateV4Bytes(b.copyOfRange(12, 16))
                else -> {
                    val first = b[0].toInt() and 0xff
                    val second = b[1].toInt() and 0xff
                    first == 0xfc || first == 0xfd || (first == 0xfe && (second and 0xc0) == 0x80)
                }
            }
        }
        else -> {
            val ip = hostAddress ?: return true // fail closed
            // CGNAT 100.64.0.0/10 not always caught above.
            if (ip.contains(".")) inIpv4RangeOrMalformed(ip, "100.64.0.0", 10) else false
        }
    }
    /** All unroutable v4 ranges on a 4-byte address: loopback, any-local, link-local, RFC1918, CGNAT. */
    private fun privateV4Bytes(b: ByteArray): Boolean {
        if (b.size != 4) return true // fail closed
        val v = b.fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xff) }
        fun inRange(base: Long, prefixBits: Int): Boolean {
            val mask = (-1L shl (32 - prefixBits)) and 0xFFFFFFFFL
            return (v and mask) == (base and mask)
        }
        return inRange(0x7F000000, 8) || inRange(0x00000000, 8) ||
            inRange(0xA9FE0000, 16) || inRange(0x0A000000, 8) ||
            inRange(0xAC100000, 12) || inRange(0xC0A80000, 16) ||
            inRange(0x64400000, 10)
    }


    private fun inIpv4RangeOrMalformed(ip: String, base: String, prefixBits: Int): Boolean =
        runCatching { inIpv4Range(ip, base, prefixBits) }.getOrDefault(true)

    private fun inIpv4Range(ip: String, base: String, prefixBits: Int): Boolean {
        val toLong: (String) -> Long = { s -> s.split('.').fold(0L) { acc, o -> (acc shl 8) or o.toLong() } }
        val mask = (-1L shl (32 - prefixBits)) and 0xFFFFFFFFL
        return (toLong(ip) and mask) == (toLong(base) and mask)
    }

    sealed class Result {
        data object Ok : Result()
        data class Reject(val code: String, val reason: String) : Result()
    }

    /**
     * Validate a source URL. [allowedPlatforms] is the worker's extractor
     * allowlist; empty = accept any known Platform.forUrl result (reject unknown).
     */
    fun checkSource(url: String, allowedPlatforms: List<Platform>): Result {
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return Result.Reject("UNSUPPORTED_URL", "unparseable URL")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.Reject("UNSUPPORTED_URL", "scheme '$scheme' not allowed")
        }
        val host = uri.host
            ?: return Result.Reject("UNSUPPORTED_URL", "no host")
        // Extractor/domain allowlist (PLAN §4): must map to a known platform.
        val platform = Platform.forUrl(url)
            ?: return Result.Reject("UNSUPPORTED_URL", "no extractor for host $host")
        if (allowedPlatforms.isNotEmpty() && platform !in allowedPlatforms) {
            return Result.Reject("UNSUPPORTED_URL", "platform ${platform.name} not allowed")
        }
        // DNS-rebinding defense: resolve and reject any private record.
        val addrs = runCatching { InetAddress.getAllByName(host) }.getOrElse {
            return Result.Reject("UNSUPPORTED_URL", "DNS resolution failed for $host")
        }
        if (addrs.any { it.isPrivateOrLoopback() }) {
            return Result.Reject("UNSUPPORTED_URL", "host $host resolves to a private/loopback address")
        }
        return Result.Ok
    }

    /**
     * Platform-agnostic HTTP(S) URL check for non-ytdlp tools (browser fetch,
     * screenshots): scheme, host present, DNS-rebinding defense. No platform
     * allowlist — any public host is fetchable.
     */
    fun checkAnyHttpUrl(url: String): Result {
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return Result.Reject("UNSUPPORTED_URL", "unparseable URL")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.Reject("UNSUPPORTED_URL", "scheme '$scheme' not allowed")
        }
        val host = uri.host ?: return Result.Reject("UNSUPPORTED_URL", "no host")
        val addrs = runCatching { InetAddress.getAllByName(host) }.getOrElse {
            return Result.Reject("UNSUPPORTED_URL", "DNS resolution failed for $host")
        }
        if (addrs.any { it.isPrivateOrLoopback() }) {
            return Result.Reject("UNSUPPORTED_URL", "host $host resolves to a private/loopback address")
        }
        return Result.Ok
    }

    /**
     * DNS-free sibling of [checkAnyHttpUrl] for callers on threads that must
     * never block on name resolution (WebViewClient main-thread callbacks).
     * Validates scheme and rejects literal-IP private/loopback destinations;
     * named hosts pass and must be covered by a [checkAnyHttpUrl] call on a
     * background thread before their bytes are trusted.
     */
    fun checkHttpUrlCheap(url: String): Result {
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return Result.Reject("UNSUPPORTED_URL", "unparseable URL")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.Reject("UNSUPPORTED_URL", "scheme '$scheme' not allowed")
        }
        val host = uri.host ?: return Result.Reject("UNSUPPORTED_URL", "no host")
        // Numeric literals parse without touching DNS (InetAddress only does
        // a lookup for real names); names are left for the full check on a
        // background thread.
        val literal = if (host.contains(':') || host.all { it.isDigit() || it == '.' }) {
            runCatching { InetAddress.getByName(host) }.getOrNull()
        } else {
            null
        }
        if (literal != null && literal.isPrivateOrLoopback()) {
            return Result.Reject("UNSUPPORTED_URL", "host $host is a private/loopback address")
        }
        return Result.Ok
    }

    /**
     * okhttp3 [okhttp3.Dns] that refuses private/loopback answers at request
     * time. NOTE: the hook only sees hostname lookups — literal-IP redirect
     * targets never reach DNS, so clients using this MUST also disable
     * redirect following (as HttpBlossomClient does) to stay safe.
     */
    fun publicOnlyDns(): okhttp3.Dns = okhttp3.Dns { hostname ->
        val addrs = InetAddress.getAllByName(hostname)
        if (addrs.any { it.isPrivateOrLoopback() }) {
            throw java.net.UnknownHostException("$hostname resolves to a private/loopback address")
        }
        addrs.toList()
    }

    /** Validate a Blossom server URL against an allowlist + private-range rule. */
    fun checkBlossom(serverUrl: String, allowlist: List<String>): Result {
        if (allowlist.none { it.trimEnd('/').equals(serverUrl.trimEnd('/'), ignoreCase = true) }) {
            return Result.Reject("UNSUPPORTED_URL", "blossom server not in allowlist")
        }
        val host = runCatching { URI(serverUrl).host }.getOrNull()
            ?: return Result.Reject("UNSUPPORTED_URL", "blossom server has no host")
        val addrs = runCatching { InetAddress.getAllByName(host) }.getOrElse {
            return Result.Reject("UNSUPPORTED_URL", "blossom DNS resolution failed")
        }
        if (addrs.any { it.isPrivateOrLoopback() }) {
            return Result.Reject("UNSUPPORTED_URL", "blossom host resolves to a private/loopback address")
        }
        return Result.Ok
    }
}
