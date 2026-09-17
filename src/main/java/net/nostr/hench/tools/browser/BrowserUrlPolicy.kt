package net.nostr.hench.tools.browser

import net.nostr.hench.tools.ytdlp.UrlSafety

/**
 * URL policy for the browser tools. Generic http(s) + private-IP checks come
 * from [UrlSafety.checkAnyHttpUrl]; on top of that, hosts where the user's
 * real WebView cookies must NEVER be handed to a paying client are denied.
 *
 * ponytail: hardcoded denylist — the exfiltration targets that matter (mail,
 * banking, payments, account/SSO endpoints). Move to a settings-editable list
 * the day a user asks for one.
 */
object BrowserUrlPolicy {

    // Exact hosts (port/scheme-insensitive) or suffix matches on registrable domains.
    private val DENIED_HOSTS = setOf(
        "mail.google.com", "accounts.google.com", "myaccount.google.com",
        "outlook.live.com", "login.microsoftonline.com", "login.live.com",
        "paypal.com", "www.paypal.com",
        "secure.chase.com", "online.citi.com", "www.bankofamerica.com",
        "wellsfargo.com", "www.wellsfargo.com", "secure.wellsfargo.com",
        "appleid.apple.com", "signin.apple.com",
        "login.yahoo.com", "mail.yahoo.com",
        "web.whatsapp.com", "web.telegram.org", "discord.com/app",
        "m.facebook.com", "mbasic.facebook.com",
    )

    private val DENIED_SUFFIXES = listOf(
        ".mail.google.com", ".accounts.google.com",
        ".paypal.com",
        ".wellsfargo.com",
    )

    /** Returns a Reject if the URL must not be fetched with the user's session. */
    fun check(url: String): UrlSafety.Result.Reject? {
        UrlSafety.checkAnyHttpUrl(url).let { if (it is UrlSafety.Result.Reject) return it }
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return null
        if (host in DENIED_HOSTS) {
            return UrlSafety.Result.Reject("FORBIDDEN_HOST", "host $host is on the browser-tools denylist (sensitive session)")
        }
        if (DENIED_SUFFIXES.any { host.endsWith(it) }) {
            return UrlSafety.Result.Reject("FORBIDDEN_HOST", "host $host is on the browser-tools denylist (sensitive session)")
        }
        return null
    }
}
