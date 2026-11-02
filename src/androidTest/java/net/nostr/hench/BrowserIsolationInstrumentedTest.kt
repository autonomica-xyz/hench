package net.nostr.hench

import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.nostr.hench.tools.browser.BrowserRenderer
import net.nostr.hench.tools.ytdlp.UrlSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/**
 * On-device regression for the browser tools' isolation and request policy:
 * unauthenticated WebView profile (no operator cookies), redirect/subframe/
 * subresource-redirect blocking, and wait_for selector injection.
 *
 * Everything targets a loopback ServerSocket — no relays, no Blossom, no
 * wallet, no destructive actions. Safe on any install: the only cookie
 * touched is one this test creates and expires itself.
 */
class BrowserIsolationInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Test policy: allow loopback, deny any URL containing "denied". */
    private val denyDenied: (String) -> UrlSafety.Result.Reject? = { url ->
        if (url.contains("denied")) UrlSafety.Result.Reject("TEST_DENY", "denied by test policy") else null
    }

    /**
     * Renderer under test: both policy seams point at [denyDenied] and the
     * prefetch client is plain OkHttp — the production private-DNS guard is
     * not weakened, only bypassed so loopback fixtures can run.
     */
    private fun renderer(): BrowserRenderer = BrowserRenderer(
        context,
        urlPolicy = denyDenied,
        cheapUrlPolicy = denyDenied,
        prefetchClient = okhttp3.OkHttpClient(),
    )

    @Test
    fun rendersSimplePage() = runBlocking {
        assumeMultiProfile()
        TestServer().use { server ->
            server.respond(mapOf("/hello" to textResponse("<html><title>t</title><body>hello hench</body>")))
            val page = renderer().load("$server/hello", timeoutMs = 20_000, extraWaitMs = 100)
            assertFalse(page.errorMessage ?: "", page.loadFailed)
            assertTrue(page.text.contains("hello hench"))
        }
    }

    @Test
    fun loadCollectsAbsoluteLinksAndDecodesText() = runBlocking {
        assumeMultiProfile()
        TestServer().use { server ->
            server.respond(
                mapOf(
                    "/links" to textResponse(
                        "<html><body><p>a &lt; b \"q\" back\\slash</p>" +
                            "<table><tr><td>c1</td><td>c2</td></tr></table>" +
                            "<a href=\"/next#frag\">n</a><a href=\"https://other.example/x\">o</a></body>",
                    ),
                ),
            )
            val page = renderer().load("$server/links", timeoutMs = 20_000, extraWaitMs = 100, collectLinks = true)
            assertFalse(page.errorMessage ?: "", page.loadFailed)
            // evaluateJavascript JSON-escapes `<`, tabs, quotes and backslashes; text must come back decoded.
            assertTrue(page.text, page.text.contains("a < b \"q\" back\\slash"))
            assertTrue(page.text, page.text.contains("c1\tc2"))
            assertEquals(listOf("$server/next#frag", "https://other.example/x"), page.links)
        }
    }

    @Test
    fun redirectToDeniedDestinationIsBlocked() = runBlocking {
        assumeMultiProfile()
        TestServer().use { server ->
            server.respond(mapOf(
                "/start" to redirectResponse("/denied"),
            ))
            // Either the checked prefetch rejects the redirect target, or the
            // in-WebView policy blocks it — both fail closed.
            val outcome = runCatching {
                renderer().load("$server/start", timeoutMs = 20_000, extraWaitMs = 0)
            }
            val rejected = outcome.exceptionOrNull() is net.nostr.hench.tools.browser.UrlPolicyRejected ||
                outcome.getOrNull()?.loadFailed == true
            assertTrue("redirect to denied destination must fail closed: $outcome", rejected)
            assertTrue("denied target must never be requested: ${server.hits}",
                server.hits.none { it.contains("denied") })
        }
    }

    @Test
    fun deniedSubframeIsBlockedButPageStillLoads() = runBlocking {
        assumeMultiProfile()
        TestServer().use { server ->
            server.respond(mapOf(
                "/frame" to textResponse(
                    "<html><body>top<iframe src=\"/denied-frame\"></iframe></body></html>",
                ),
            ))
            val page = renderer().load("$server/frame", timeoutMs = 20_000, extraWaitMs = 1_500)
            assertFalse(page.errorMessage ?: "", page.loadFailed)
            assertTrue(page.text.contains("top"))
            assertTrue("subframe target must never be fetched: ${server.hits}",
                server.hits.none { it.contains("denied-frame") })
        }
    }

    @Test
    fun allowedSubresourceRedirectingToDeniedIsBlocked() = runBlocking {
        assumeMultiProfile()
        TestServer().use { server ->
            server.respond(mapOf(
                // A script, not an image: text renders never fetch images.
                "/sub-page" to textResponse(
                    "<html><body>top<script src=\"/sub-redir\"></script></body></html>",
                ),
                // The subresource URL itself is allowed; its 302 target is not.
                "/sub-redir" to redirectResponse("/denied-sub"),
            ))
            val page = renderer().load("$server/sub-page", timeoutMs = 20_000, extraWaitMs = 1_500)
            assertFalse(page.errorMessage ?: "", page.loadFailed)
            assertTrue(page.text.contains("top"))
            assertTrue("allowed subresource was fetched: ${server.hits}",
                server.hits.any { it.contains("sub-redir") })
            assertTrue("redirect target must never be fetched: ${server.hits}",
                server.hits.none { it.contains("denied-sub") })
        }
    }

    @Test
    fun injectedWaitForSelectorCannotRunScript() = runBlocking {
        assumeMultiProfile()
        TestServer().use { server ->
            server.respond(mapOf(
                "/clean" to textResponse("<html><title>clean</title><body>ok</body></html>"),
            ))
            // HIGH-3 breakout: the leading backslash defeats the old
            // single-quote escape (' -> \'), closing the JS string. Under the
            // fix the whole payload is one JSON literal: querySelector throws
            // SyntaxError and the renderer surfaces INVALID_PARAMS.
            try {
                renderer().load(
                    "$server/clean",
                    timeoutMs = 20_000,
                    extraWaitMs = 0,
                    waitForSelector = "x\\');document.title='PWNED';//",
                )
                fail("injection selector should throw IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().contains("invalid CSS selector"))
            }
        }
    }

    @Test
    fun rendererProfileHasNoOperatorCookies() = runBlocking {
        assumeMultiProfile()
        TestServer().use { server ->
            server.respond(mapOf(
                "/echo" to textResponse(
                    "<html><body><script>document.body.textContent = document.cookie;</script></body></html>",
                ),
            ))
            val url = "$server/echo"
            // CookieManager wants a Looper thread; getInstance/setAcceptCookie
            // and the write all run on Main.
            val cm = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.webkit.CookieManager.getInstance().apply { setAcceptCookie(true) }
            }
            // Plant a cookie in the operator's default profile, awaiting the
            // async write before loading; the render must not see it.
            cm.setCookieAwaited(url, "operator_secret=SHARED_MARKER; path=/")
            try {
                val page = renderer().load(url, timeoutMs = 20_000, extraWaitMs = 300)
                assertFalse(page.errorMessage ?: "", page.loadFailed)
                assertFalse(
                    "operator cookie leaked into render: ${page.text}",
                    page.text.contains("SHARED_MARKER"),
                )
            } finally {
                // Expire ONLY the cookie this test set — never wipe other
                // sessions on a shared QA install.
                cm.setCookieAwaited(url, "operator_secret=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/")
            }
        }
    }

    @Test
    fun rendererDeathFailsTheRenderInsteadOfKillingTheApp() = runBlocking {
        assumeMultiProfile()
        assumeTrue(
            WebViewFeature.isFeatureSupported(WebViewFeature.GET_WEB_VIEW_RENDERER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_TERMINATE),
        )
        TestServer().use { server ->
            // The stalled script keeps the page loading until the renderer dies
            // (not an image: text renders never fetch those).
            server.respond(mapOf("/page" to textResponse("<html><body>p<script src=\"/stall\"></script></body>"), "/stall" to TestServer.STALL))
            val render = async { renderer().load("$server/page", timeoutMs = 20_000, extraWaitMs = 0) }
            withTimeout(10_000) { while (server.hits.none { it.startsWith("GET /stall") }) delay(50) }
            withContext(Dispatchers.Main) {
                // A profile's WebViews share one renderer: killing the sibling's kills the render's.
                val sibling = WebView(context)
                WebViewCompat.setProfile(sibling, BrowserRenderer.PROFILE_NAME)
                val loaded = CompletableDeferred<Unit>()
                sibling.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { loaded.complete(Unit) }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        view.destroy()
                        return true
                    }
                }
                sibling.loadDataWithBaseURL(null, "<p>sibling</p>", "text/html", "utf-8", null)
                loaded.await()
                assertTrue(WebViewCompat.getWebViewRenderProcess(sibling)!!.terminate())
            }
            // Unhandled, the renderer's death would have killed this process before here.
            val page = render.await()
            assertTrue(page.loadFailed)
            assertTrue(page.errorMessage.orEmpty(), page.errorMessage.orEmpty().contains("renderer"))
        }
    }

    private suspend fun android.webkit.CookieManager.setCookieAwaited(url: String, value: String) =
        // CookieManager wants a Looper thread; runBlocking has none — dispatch to Main.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                setCookie(url, value) { cont.resume(Unit) }
            }
        }

    private fun assumeMultiProfile() {
        assumeTrue(
            "WebView lacks MULTI_PROFILE; renderer fails closed by design",
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE),
        )
    }

    private fun textResponse(body: String): String =
        "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"

    private fun redirectResponse(location: String): String =
        "HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

    /**
     * Minimal sequential HTTP responder on loopback. Records the request line
     * of every connection it actually sees in [hits].
     */
    private class TestServer : AutoCloseable {
        val hits = CopyOnWriteArrayList<String>()
        private val socket = ServerSocket(0)
        private val routes = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val thread = Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { conn ->
                        conn.soTimeout = 3_000
                        val reader = BufferedReader(InputStreamReader(conn.getInputStream()))
                        val requestLine = reader.readLine() ?: return@use
                        hits += requestLine
                        val path = requestLine.split(' ').getOrNull(1) ?: "/"
                        val route = routes[path]
                        if (route == STALL) Thread.sleep(6_000)
                        val reply = route?.takeIf { it != STALL }
                            ?: "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        conn.getOutputStream().apply { write(reply.toByteArray()); flush() }
                    }
                } catch (_: java.net.SocketException) {
                    break
                } catch (_: Exception) {
                    // keep serving
                }
            }
        }

        init { thread.start() }

        fun respond(routes: Map<String, String>) { this.routes.putAll(routes) }

        override fun toString(): String = "http://127.0.0.1:${socket.localPort}"

        override fun close() {
            runCatching { socket.close() }
            runCatching { thread.join(2_000) }
        }

        companion object {
            /** Route value: stall the (sequential) server 6s, then 404 — keeps a page loading. */
            const val STALL = "STALL"
        }
    }
}
