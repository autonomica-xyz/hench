package net.nostr.hench.tools.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.nostr.hench.tools.ytdlp.UrlSafety
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** Thrown when the installed WebView cannot run an isolated unauthenticated profile. */
class BrowserIsolationUnsupported(message: String) : Exception(message)

/** Thrown when a URL or redirect hop fails the browser URL policy. */
class UrlPolicyRejected(val code: String, reason: String) : Exception(reason)

/**
 * Shared offscreen WebView runner for the browser tools (web_fetch,
 * page_screenshot). Renders on the main thread via a dedicated handler;
 * never touches any UI the user can see.
 *
 * Session isolation: every render runs in a dedicated, unauthenticated WebView
 * [Profile] ([PROFILE_NAME]) — its own cookie jar and web storage, completely
 * separate from the operator's login sessions used by Settings → platform
 * logins (which live in the default profile). A paying client therefore never
 * reaches operator-authenticated pages; there is no host denylist to bypass.
 * Where the installed WebView lacks multi-profile support the tools fail
 * closed ([BrowserIsolationUnsupported]) rather than exposing real sessions.
 *
 * URL policy / SSRF: the initial URL's redirect chain is walked explicitly —
 * policy applied BEFORE each hop — over an OkHttp client whose DNS refuses
 * private answers. Inside the WebView, EVERY http(s) GET/HEAD request (main
 * frame, subframes, subresources) is served by the same checked fetch: each
 * hop of every redirect chain is policy-checked, so an attacker-controlled
 * page cannot reach private/internal destinations through an <img>/<iframe>
 * redirect either. Main-frame navigations additionally get a cheap
 * main-thread policy check in shouldOverrideUrlLoading (DNS can't run there).
 * A restrictive Content-Security-Policy connect-src is merged into every
 * served HTML document, closing channels shouldInterceptRequest can't see
 * (WebSocket/http connect-src), with 'self' + https: left working.
 *
 * Known tradeoff, deliberate: non-GET/HEAD requests (POST forms, XHR POST) are
 * refused because the interception API exposes no request body — they can't
 * be replayed through the checked fetch, and letting them go native would
 * reopen uncontrolled redirect hops. Read-only browsing is the product.
 *
 * Residual limits: WebView does its own DNS after our checks (rebinding TOCTOU
 * remains), and WebSocket traffic is governed by the injected CSP rather than
 * per-request policy.
 *
 * ponytail: viewport-only screenshots (no full-page stitching); add scroll-
 * and-stitch if an agent ever asks for full_page.
 */
class BrowserRenderer(
    private val context: Context,
    /** Full URL policy incl. DNS — call off the main thread only. */
    private val urlPolicy: (String) -> UrlSafety.Result.Reject? = BrowserUrlPolicy::check,
    /** DNS-free policy safe for WebView main-thread callbacks. */
    private val cheapUrlPolicy: (String) -> UrlSafety.Result.Reject? = BrowserUrlPolicy::checkCheap,
    /** Base client for checked fetches; redirects are walked manually. */
    prefetchClient: okhttp3.OkHttpClient = defaultPrefetchClient(),
) {

    data class Page(
        val finalUrl: String,
        val title: String,
        val text: String,
        val html: String,
        val loadFailed: Boolean,
        val errorMessage: String?,
    )

    private class Session(
        val view: WebView,
        val profile: Profile?,
        /** Live OkHttp responses handed to the WebView; closed on teardown. */
        val openResponses: ConcurrentLinkedQueue<okhttp3.Response> = ConcurrentLinkedQueue(),
    )

    private val walkClient = prefetchClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Load [url], wait for finish + optional CSS selector, return page content. */
    suspend fun load(
        url: String,
        timeoutMs: Long = 45_000,
        waitForSelector: String? = null,
        extraWaitMs: Long = 1_200,
    ): Page = withTimeout(timeoutMs) {
        withContext(mainContext) {
            val session = newSession()
            val view = session.view
            try {
                // Resolve redirects before the WebView commits; every hop is
                // policy-checked so a 302 to a denied destination fails here.
                val finalUrl = walkToFinalUrl(url, view.settings.userAgentString)
                val finished = awaitPageFinished(view, finalUrl, session)
                var title = ""
                var text = ""
                if (finished.ok) {
                    if (extraWaitMs > 0) kotlinx.coroutines.delay(extraWaitMs)
                    if (waitForSelector != null) {
                        awaitSelector(view, waitForSelector, remainingOf(timeoutMs))
                    }
                    title = runCatching { view.title.orEmpty() }.getOrDefault("")
                    // innerText only: extracting outerHTML on JS-heavy SPAs builds a
                    // string so large it wedges the main thread inside native code,
                    // past any coroutine timeout (found on-device vs m.youtube.com).
                    // innerText is layout-dependent and can be blank for pages whose
                    // custom elements have not laid out yet (m.youtube.com); fall
                    // back to textContent (DOM-only) rather than returning nothing.
                    text = evaluateScript(
                        view,
                        "(function(){ var b = document.body; if (!b) return ''; " +
                            "var t = b.innerText; if (!t || !t.trim()) t = b.textContent; return t; })()",
                    ).orEmpty()
                }
                Page(
                    finalUrl = view.url ?: finalUrl,
                    title = title,
                    text = text,
                    html = "",
                    loadFailed = !finished.ok,
                    errorMessage = finished.error,
                )
            } finally {
                session.cleanup()
            }
        }
    }

    /** Render [url] at the given viewport and capture a PNG bitmap. */
    suspend fun screenshot(
        url: String,
        width: Int = 1280,
        height: Int = 800,
        timeoutMs: Long = 45_000,
        waitForSelector: String? = null,
        extraWaitMs: Long = 1_000,
    ): Pair<Page, Bitmap> = withTimeout(timeoutMs) {
        withContext(mainContext) {
            val session = newSession()
            val view = session.view
            try {
                view.layout(0, 0, width, height)
                val finalUrl = walkToFinalUrl(url, view.settings.userAgentString)
                val finished = awaitPageFinished(view, finalUrl, session)
                if (!finished.ok && finished.error?.startsWith("blocked by URL policy") == true) {
                    // Policy-blocked destination: no paid upload of a dead render.
                    throw UrlPolicyRejected("UNSUPPORTED_URL", finished.error)
                }
                var title = ""
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                if (finished.ok) {
                    if (extraWaitMs > 0) kotlinx.coroutines.delay(extraWaitMs)
                    if (waitForSelector != null) {
                        awaitSelector(view, waitForSelector, remainingOf(timeoutMs))
                    }
                    view.draw(canvas)
                    title = runCatching { view.title.orEmpty() }.getOrDefault("")
                }
                Pair(
                    Page(
                        finalUrl = view.url ?: finalUrl,
                        title = title,
                        text = "",
                        html = "",
                        loadFailed = !finished.ok,
                        errorMessage = finished.error,
                    ),
                    bitmap,
                )
            } finally {
                session.cleanup()
            }
        }
    }

    /**
     * Walks the redirect chain for [url], applying the FULL url policy before
     * every request, and returns the final URL. Fails closed on policy
     * rejection, private/loopback DNS answers, cancellation, and any hop that
     * isn't http(s). Plain network failures fall back to the original URL —
     * the in-WebView checked fetch still enforces policy on every request.
     */
    private suspend fun walkToFinalUrl(url: String, userAgent: String?): String =
        withContext(Dispatchers.IO) {
            try {
                fetchChecked(url, uaHeaders(userAgent)).use { it.request.url.toString() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: UrlPolicyRejected) {
                throw e
            } catch (e: java.net.UnknownHostException) {
                throw UrlPolicyRejected("UNSUPPORTED_URL", e.message ?: "private/unresolvable host")
            } catch (e: java.io.IOException) {
                url // transient network failure — checked fetch still guards every load
            }
        }

    private fun uaHeaders(userAgent: String?): Map<String, String> =
        if (userAgent.isNullOrBlank()) emptyMap() else mapOf("User-Agent" to userAgent)

    /**
     * One checked fetch: policy-checks [startUrl], issues the request, then
     * follows redirect hops manually — re-applying the full policy (incl. DNS)
     * to every hop and refusing non-http(s) targets — up to [MAX_REDIRECTS].
     * Caller owns the returned [okhttp3.Response] body.
     *
     * Blocking; only call on a background thread.
     */
    private fun fetchChecked(startUrl: String, headers: Map<String, String>): okhttp3.Response {
        var current = startUrl
        repeat(MAX_REDIRECTS + 1) {
            urlPolicy(current)?.let { throw UrlPolicyRejected(it.code, it.reason) }
            val request = okhttp3.Request.Builder().url(current).get().apply {
                headers.forEach { (k, v) ->
                    if (k.lowercase() !in SKIP_REQUEST_HEADERS) header(k, v)
                }
            }.build()
            val response = walkClient.newCall(request).execute()
            val location = response.header("Location")
            if (response.code in 300..399 && location != null) {
                val next = response.request.url.resolve(location)
                    ?: throw UrlPolicyRejected("UNSUPPORTED_URL", "unresolvable redirect target: $location")
                response.close()
                if (next.scheme != "http" && next.scheme != "https") {
                    throw UrlPolicyRejected("UNSUPPORTED_URL", "redirect to non-http(s): ${next.scheme}")
                }
                current = next.toString()
                return@repeat
            }
            return response
        }
        throw UrlPolicyRejected("UNSUPPORTED_URL", "redirect chain exceeded $MAX_REDIRECTS hops")
    }

    /** Streams a checked OkHttp response into a WebView-servable resource. */
    private fun okhttp3.Response.toWebResourceResponse(): WebResourceResponse {
        val contentType = header("Content-Type").orEmpty()
        val mime = contentType.substringBefore(';').trim()
            .ifEmpty { "application/octet-stream" }
        val encoding = contentType.substringAfter("charset=", "")
            .substringBefore(';').trim().ifEmpty { "UTF-8" }
        val outHeaders = mutableMapOf<String, String>()
        for (name in headers.names()) {
            if (name.lowercase() !in SKIP_RESPONSE_HEADERS) {
                outHeaders[name] = headers.values(name).joinToString(", ")
            }
        }
        if (mime.equals("text/html", ignoreCase = true)) {
            // Merge (comma = additional policy, both enforced): pages may only
            // open connections to their own origin or https: — no ws:// or
            // http: connects that per-request policy cannot see.
            outHeaders["Content-Security-Policy"] =
                (outHeaders["Content-Security-Policy"]?.let { "$it, " } ?: "") + CONNECT_CSP
        }
        val stream = body?.byteStream() ?: "".byteInputStream()
        return WebResourceResponse(mime, encoding, code, message.ifBlank { "OK" }, outHeaders, stream)
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun newSession(): Session {
        val view = WebView(context)
        var profile: Profile? = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            // Fresh isolated profile: cookies/storage are scoped to it and stay
            // empty for the operator's real sessions. Must be set before the
            // WebView loads anything.
            profile = ProfileStore.getInstance().getOrCreateProfile(PROFILE_NAME)
            WebViewCompat.setProfile(view, PROFILE_NAME)
        } else {
            // Never silently expose operator cookies on an old WebView.
            view.destroy()
            throw BrowserIsolationUnsupported(
                "WebView does not support isolated profiles (MULTI_PROFILE); refusing to run browser tools with shared sessions",
            )
        }
        view.apply {
            layout(0, 0, 1280, 800)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            runCatching {
                profile!!.cookieManager.setAcceptThirdPartyCookies(this, true)
            }
        }
        return Session(view, profile)
    }

    /** Tear down a render session: abort in-flight fetches, blank, destroy, wipe profile state. */
    private fun Session.cleanup() {
        runCatching { view.stopLoading() }
        runCatching { view.loadUrl("about:blank") }
        runCatching { view.destroy() }
        var pending = openResponses.poll()
        while (pending != null) {
            runCatching { pending.close() }
            pending = openResponses.poll()
        }
        runCatching { profile?.cookieManager?.removeAllCookies(null) }
        runCatching { profile?.webStorage?.deleteAllData() }
    }

    /** Bridges WebViewClient callbacks into coroutine continuation. */
    private suspend fun awaitPageFinished(view: WebView, url: String, session: Session): LoadResult =
        suspendCancellableCoroutine { cont ->
            val result = AtomicReference<LoadResult?>(null)

            fun blocked(what: String) {
                val r = LoadResult(ok = false, error = "blocked by URL policy: $what")
                if (result.compareAndSet(null, r)) {
                    runCatching { view.stopLoading() }
                    cont.resume(r)
                }
            }

            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // Fail closed on the final landed URL (cheap check only —
                    // this runs on the main thread, so no DNS here). Blank/
                    // error pages aren't destinations; skip the check.
                    val scheme = runCatching { java.net.URI(url).scheme }.getOrNull()
                    if (scheme == "http" || scheme == "https") {
                        cheapUrlPolicy(url)?.let { blocked(url); return }
                    }
                    val r = LoadResult(ok = true, error = null)
                    if (result.compareAndSet(null, r)) cont.resume(r)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    // Main-frame errors only; subresource failures are normal page noise.
                    val r = LoadResult(ok = false, error = error.description?.toString())
                    if (request.isForMainFrame && result.compareAndSet(null, r)) cont.resume(r)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Called on the main thread for every main-frame navigation,
                    // including HTTP redirect hops (API 24+). Only the cheap
                    // policy can run here — DNS checks live in the checked
                    // fetch below, which every actual request must pass anyway.
                    val requestUrl = request.url?.toString() ?: return false
                    val scheme = request.url?.scheme?.lowercase()
                    if (scheme != "http" && scheme != "https") return true
                    if (cheapUrlPolicy(requestUrl) != null) {
                        blocked(requestUrl)
                        return true
                    }
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    // Runs on a WebView background thread — blocking and DNS
                    // are both legal here. EVERY http(s) request (main frame,
                    // subframes, subresources) is served by the checked fetch,
                    // so remote-controlled pages cannot reach denied
                    // destinations — even through redirect hops we walk.
                    val requestUrl = request.url?.toString() ?: return null
                    val scheme = request.url?.scheme?.lowercase()
                    if (scheme != "http" && scheme != "https") return null // data:/blob:/about:
                    if (cheapUrlPolicy(requestUrl) != null || urlPolicy(requestUrl) != null) {
                        if (request.isForMainFrame) blocked(requestUrl)
                        return BLOCKED
                    }
                    val method = request.method?.uppercase() ?: "GET"
                    if (method !in SAFE_METHODS) {
                        // No request body is exposed to this callback, so the
                        // request can't be replayed through the checked fetch;
                        // letting it go native would reopen unchecked redirects.
                        if (request.isForMainFrame) blocked(requestUrl)
                        return METHOD_BLOCKED
                    }
                    val response = runCatching {
                        fetchChecked(requestUrl, request.requestHeaders ?: emptyMap())
                    }.getOrElse { e ->
                        return when {
                            e is UrlPolicyRejected || e is java.net.UnknownHostException -> {
                                if (request.isForMainFrame) blocked(requestUrl)
                                BLOCKED
                            }
                            // Main-frame transient failure: let the WebView
                            // retry natively — policy callbacks still guard it.
                            request.isForMainFrame -> null
                            else -> BLOCKED
                        }
                    }
                    // Only 2xx bodies are replayed; error responses go native
                    // (any redirect they carry lands back here re-checked).
                    if (response.code !in 200..299) {
                        response.close()
                        return null
                    }
                    session.openResponses.add(response)
                    return response.toWebResourceResponse()
                }
            }
            cont.invokeOnCancellation { runCatching { view.stopLoading() } }
            view.loadUrl(url)
        }

    /** Poll `document.querySelector(selector)` until non-null or timeout. */
    private suspend fun awaitSelector(view: WebView, selector: String, timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            when (evaluateScript(view, selectorProbeScript(selector))) {
                "found" -> return
                "invalid" -> throw IllegalArgumentException("invalid CSS selector: $selector")
            }
            kotlinx.coroutines.delay(POLL_MS)
        }
    }

    private suspend fun evaluateScript(view: WebView, script: String): String? =
        suspendCancellableCoroutine { cont ->
            view.evaluateJavascript(script) { value ->
                cont.resume(value?.trim('"')?.replace("\\n", "\n")?.replace("\\\"", "\""))
            }
        }

    private fun remainingOf(timeoutMs: Long): Long = timeoutMs / 2

    private data class LoadResult(val ok: Boolean, val error: String?)

    companion object {
        private const val POLL_MS = 250L
        private const val MAX_REDIRECTS = 10

        /** Dedicated WebView profile for paid renders; never sees operator logins. */
        internal const val PROFILE_NAME = "hench-reader"

        /** Connect-src merged into served HTML: same-origin + https only. */
        private const val CONNECT_CSP = "connect-src 'self' https:"

        /** Requests we can faithfully replay through the checked fetch (no body). */
        private val SAFE_METHODS = setOf("GET", "HEAD")

        /** Hop-by-hop / framing headers we must not forward or echo. */
        private val SKIP_REQUEST_HEADERS = setOf("host", "connection", "content-length", "accept-encoding")
        private val SKIP_RESPONSE_HEADERS = setOf("connection", "transfer-encoding", "content-length")

        /** Checked-fetch base client: DNS refuses private/loopback answers at connect time. */
        private fun defaultPrefetchClient(): okhttp3.OkHttpClient =
            okhttp3.OkHttpClient.Builder()
                .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(false)
                .dns(UrlSafety.publicOnlyDns())
                .build()

        private fun deniedResponse(reason: String) = WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            reason,
            emptyMap(),
            "".byteInputStream(),
        )

        /** Empty 403 used to deny policy-rejected requests. */
        private val BLOCKED = deniedResponse("Blocked by hench URL policy")

        /** 403 for methods that can't be replayed through the checked fetch. */
        private val METHOD_BLOCKED = deniedResponse("Blocked: only GET/HEAD are supported")

        /**
         * Quote [raw] as a JavaScript string literal (double-quoted, JSON-style).
         * Quotes, backslashes, control chars, and the JS line terminators
         * U+2028/U+2029 (legal in JSON, illegal raw in JS strings) are escaped —
         * an attacker-supplied selector can never break out of the literal.
         */
        internal fun jsStringLiteral(raw: String): String = buildString(raw.length + 2) {
            append('"')
            for (c in raw) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\u2028' -> append("\\u2028")
                    '\u2029' -> append("\\u2029")
                    else -> if (c < ' ' || c == '\u007f') {
                        append("\\u")
                        append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        append(c)
                    }
                }
            }
            append('"')
        }

        /** JS probe for [awaitSelector]; returns "found" | "missing" | "invalid". */
        internal fun selectorProbeScript(selector: String): String =
            "(function(){ try { return document.querySelector(" + jsStringLiteral(selector) +
                ") !== null ? 'found' : 'missing'; } catch (e) { return 'invalid'; } })()"

        /** WebView must be created/touched on the main thread only. */
        private val mainContext get() = Dispatchers.Main
    }
}
