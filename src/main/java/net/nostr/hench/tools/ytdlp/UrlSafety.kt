package net.nostr.hench.tools.ytdlp

import net.nostr.hench.auth.Platform
import java.net.InetAddress
import java.net.URI

/**
 * Network-safety validation for a public worker (PLAN §4). Deny-by-default:
 * the source URL and the Blossom server must resolve to public addresses, and
 * the source must map to a known extractor. v1 limitation: secondary fetches
 * yt-dlp makes itself (manifests, segments, CDN) are NOT filtered — see PLAN.
 */
object UrlSafety {

    /** Reject these address ranges after DNS resolution (loopback / private / link-local). */
    private fun InetAddress.isPrivateOrLoopback(): Boolean = when {
        isLoopbackAddress -> true
        isAnyLocalAddress -> true
        isLinkLocalAddress -> true
        isSiteLocalAddress -> true // RFC1918 10/8, 172.16/12, 192.168/16
        isMulticastAddress -> true
        else -> {
            val ip = hostAddress ?: return@isPrivateOrLoopback false
            // CGNAT 100.64.0.0/10 and IPv6 ULA fc00::/7 not always caught above.
            when {
                ip.contains(".") -> inIpv4Range(ip, "100.64.0.0", 10)
                ip.contains(":") -> ip.lowercase().let {
                    it.startsWith("fc") || it.startsWith("fd") || it.startsWith("fe80")
                }
                else -> false
            }
        }
    }

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
