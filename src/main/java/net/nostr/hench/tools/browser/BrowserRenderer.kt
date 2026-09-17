package net.nostr.hench.tools.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Shared offscreen WebView runner for the browser tools (web_fetch,
 * page_screenshot). Renders on the main thread via a dedicated handler;
 * never touches any UI the user can see.
 *
 * Cookies come from the app's WebView CookieManager — i.e. the SAME real
 * sessions the user created via Settings → platform logins (WebViewLogin).
 * Sensitive hosts are blocked by [BrowserUrlPolicy] before we ever load.
 *
 * ponytail: viewport-only screenshots (no full-page stitching); add scroll-
 * and-stitch if an agent ever asks for full_page.
 */
class BrowserRenderer(private val context: Context) {

    data class Page(
        val finalUrl: String,
        val title: String,
        val text: String,
        val html: String,
        val loadFailed: Boolean,
        val errorMessage: String?,
    )

    /** Load [url], wait for finish + optional CSS selector, return page content. */
    suspend fun load(
        url: String,
        timeoutMs: Long = 45_000,
        waitForSelector: String? = null,
        extraWaitMs: Long = 1_200,
    ): Page = withTimeout(timeoutMs) {
        withContext(mainContext) {
            val view = newWebView()
            try {
                val finished = awaitPageFinished(view, url, timeoutMs)
                if (extraWaitMs > 0) kotlinx.coroutines.delay(extraWaitMs)
                if (waitForSelector != null) {
                    awaitSelector(view, waitForSelector, remainingOf(timeoutMs))
                }
                val title = runCatching { view.title.orEmpty() }.getOrDefault("")
                // innerText only: extracting outerHTML on JS-heavy SPAs builds a
                // string so large it wedges the main thread inside native code,
                // past any coroutine timeout (found on-device vs m.youtube.com).
                // ponytail: agents get innerText; add a Blossom-hosted HTML dump
                // via a main-thread-safe path if a client ever needs full HTML.
                // innerText is layout-dependent and can be blank for pages whose
                // custom elements have not laid out yet (m.youtube.com); fall
                // back to textContent (DOM-only) rather than returning nothing.
                val text = evaluateScript(
                    view,
                    "(function(){ var b = document.body; if (!b) return ''; " +
                        "var t = b.innerText; if (!t || !t.trim()) t = b.textContent; return t; })()",
                ).orEmpty()
                Page(
                    finalUrl = view.url ?: url,
                    title = title,
                    text = text,
                    html = "",
                    loadFailed = !finished.ok,
                    errorMessage = finished.error,
                )
            } finally {
                runCatching {
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    view.destroy()
                }
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
            val view = newWebView()
            try {
                view.layout(0, 0, width, height)
                val finished = awaitPageFinished(view, url, timeoutMs)
                if (extraWaitMs > 0) kotlinx.coroutines.delay(extraWaitMs)
                if (waitForSelector != null) {
                    awaitSelector(view, waitForSelector, remainingOf(timeoutMs))
                }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                view.draw(canvas)
                val title = runCatching { view.title.orEmpty() }.getOrDefault("")
                Pair(
                    Page(
                        finalUrl = view.url ?: url,
                        title = title,
                        text = "",
                        html = "",
                        loadFailed = !finished.ok,
                        errorMessage = finished.error,
                    ),
                    bitmap,
                )
            } finally {
                runCatching {
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    view.destroy()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView = WebView(context).apply {
        layout(0, 0, 1280, 800)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.userAgentString = null // real WebView UA — the whole point
        CookieManagerSync.acceptCookies(this)
    }

    /** Bridges WebViewClient callbacks into coroutine continuation. */
    private suspend fun awaitPageFinished(view: WebView, url: String, timeoutMs: Long): LoadResult =
        suspendCancellableCoroutine { cont ->
            val result = AtomicReference<LoadResult?>(null)
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val r = LoadResult(ok = true, error = null)
                    if (result.compareAndSet(null, r)) cont.resume(r)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    // Main-frame errors only; subresource failures are normal page noise.
                    val r = LoadResult(ok = false, error = error.description?.toString())
                    if (request.isForMainFrame && result.compareAndSet(null, r)) cont.resume(r)
                }
            }
            cont.invokeOnCancellation { runCatching { view.stopLoading() } }
            view.loadUrl(url)
        }

    /** Poll `document.querySelector(selector)` until non-null or timeout. */
    private suspend fun awaitSelector(view: WebView, selector: String, timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val safeSelector = selector.replace("'", "\\'")
        while (System.nanoTime() < deadline) {
            val found = evaluateScript(view, "(function(){ try { return document.querySelector('$safeSelector') !== null; } catch (e) { return false; } })()")
            if (found == "true") return
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

    private object CookieManagerSync {
        fun acceptCookies(view: WebView) {
            runCatching {
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            }
        }
    }

    companion object {
        private const val POLL_MS = 250L

        /** WebView must be created/touched on the main thread only. */
        private val mainContext get() = Dispatchers.Main
    }
}
