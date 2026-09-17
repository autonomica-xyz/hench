package net.nostr.hench.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Opens a WebView to the platform's login page. After the operator logs in,
 * cookies are extracted from the WebView's Chromium cookie database and
 * stored in [CookieStore] in Netscape format for yt-dlp.
 */
class WebViewLoginActivity : Activity() {

    private lateinit var cookieStore: CookieStore
    private lateinit var platform: Platform
    private lateinit var statusText: TextView
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cookieStore = CookieStore(applicationContext)

        val platformName = intent.getStringExtra(EXTRA_PLATFORM)
            ?: run { finish(); return }
        platform = runCatching { Platform.valueOf(platformName) }
            .getOrDefault(Platform.YOUTUBE)

        render()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun render() {
        val bg = Color.rgb(8, 9, 10)
        val surface = Color.rgb(25, 26, 27)
        val border = Color.rgb(36, 40, 44)
        val primaryText = Color.rgb(247, 248, 248)
        val muted = Color.rgb(138, 143, 152)
        val accent = Color.rgb(94, 106, 210)
        val success = Color.rgb(16, 185, 129)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(56), dp(22), dp(28))
            setBackgroundColor(bg)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); addView(root) }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Login to ${platform.displayName}"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(primaryText)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "Done"
            textSize = 13f
            setTextColor(primaryText)
            background = rounded(surface, dp(10), border)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { finish() }
        })
        root.addView(header)

        // Explanation
        root.addView(TextView(this).apply {
            text = "Log into your ${platform.displayName} account below. " +
                "The worker will use your session to download content that requires authentication. " +
                "Consider using a burner account."
            textSize = 14f
            setTextColor(muted)
            setLineSpacing(4f, 1.0f)
        }, matchTop(dp(12)))

        // Existing session info
        val existing = cookieStore.get(platform)
        if (existing != null) {
            root.addView(TextView(this).apply {
                text = "⚠️  Existing session: ${existing.health.name}, " +
                    "${existing.jobsProcessed} jobs processed. " +
                    "Logging in again will replace the session."
                textSize = 13f
                setTextColor(Color.rgb(255, 191, 87))
                setLineSpacing(3f, 1.0f)
            }, matchTop(dp(8)))
        }

        // Status
        statusText = TextView(this).apply {
            text = "Browse to log in, then tap 'Save session' below."
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        root.addView(statusText, matchTop(dp(12)))

        // WebView — use fixed pixel height since ScrollView doesn't support weight
        val webViewHeight = dp(800)

        webView = WebView(this).apply {
            settings.run {
                javaScriptEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                setGeolocationEnabled(false)
                if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val cookies = cookieManager.getCookie(url)
                    if (!cookies.isNullOrBlank()) {
                        statusText.text = "Cookies captured from ${url?.takeLast(40)}. Tap 'Save session' when logged in."
                        statusText.setTextColor(success)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    // Allow all navigation within the WebView
                    return false
                }
            }
            webChromeClient = WebChromeClient()

            loadUrl(platform.loginUrl)
        }

        root.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            webViewHeight,
        ).apply { topMargin = dp(12) })

        // Action buttons
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        actions.addView(Button(this).apply {
            text = "Save session"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(accent, dp(12), null)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { saveSession() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(8) })

        actions.addView(Button(this).apply {
            text = "Delete session"
            textSize = 14f
            setTextColor(Color.rgb(247, 100, 100))
            background = rounded(surface, dp(12), border)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener {
                cookieStore.remove(platform)
                statusText.text = "Session deleted for ${platform.displayName}."
                statusText.setTextColor(Color.rgb(247, 100, 100))
                toast("Session deleted")
            }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(8) })

        root.addView(actions, matchTop(dp(12)))

        setContentView(scroll)
    }

    private fun saveSession() {
        val cookies = extractCookiesFromWebView()
        if (cookies.isNullOrBlank()) {
            statusText.text = "No cookies found. Browse to the login page and log in first."
            statusText.setTextColor(Color.rgb(247, 100, 100))
            return
        }

        val session = PlatformSession(
            platform = platform,
            cookiesNetscape = cookies,
            loggedInAtMs = System.currentTimeMillis(),
            health = SessionHealth.UNKNOWN,
        )
        cookieStore.save(session)
        cookieStore.writeCookieFile(platform)

        statusText.text = "✓ Session saved for ${platform.displayName}. The worker can now process authenticated jobs."
        statusText.setTextColor(Color.rgb(16, 185, 129))
        toast("Session saved for ${platform.displayName}")
    }

    /**
     * Extracts cookies from the WebView's Chromium cookie database and
     * converts them to Netscape format for yt-dlp --cookies.
     *
     * Ported from the main YTDLnis app's CookieViewModel.getCookiesFromDB().
     */
    @SuppressLint("SdCardPath")
    private fun extractCookiesFromWebView(): String? {
        val cookieManager = CookieManager.getInstance()
        val allCookies = StringBuilder()

        // Collect cookies from all platform domains
        val domainsToCheck = platform.domains.toMutableList()

        // Also collect cookies from the URL the WebView is currently on
        webView?.url?.let { currentUrl ->
            runCatching {
                val host = java.net.URL(currentUrl).host.lowercase()
                if (host.isNotBlank() && domainsToCheck.none { host == it || host.endsWith(".$it") }) {
                    domainsToCheck.add(host)
                }
            }
        }

        // Also collect from the login URL domain
        runCatching {
            val loginHost = java.net.URL(platform.loginUrl).host.lowercase()
            if (loginHost.isNotBlank() && domainsToCheck.none { loginHost == it || loginHost.endsWith(".$it") }) {
                domainsToCheck.add(loginHost)
            }
        }.getOrNull()

        for (domain in domainsToCheck) {
            val url = "https://$domain"
            val raw = cookieManager.getCookie(url) ?: continue
            for (pair in raw.split(";")) {
                val trimmed = pair.trim()
                if (trimmed.isBlank()) continue
                val eq = trimmed.indexOf('=')
                if (eq < 0) continue
                val name = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                allCookies.appendLine(toNetscapeLine(domain, name, value))
            }
        }

        if (allCookies.isNotBlank()) {
            return allCookies.toString()
        }

        // Fallback: try reading the Chromium cookie database directly,
        // similar to the main app's CookieViewModel approach.
        return tryReadChromiumCookieDb()
    }

    /**
     * Attempts to read cookies directly from the WebView's Chromium cookie
     * database file, as done in the main YTDLnis app.
     */
    @SuppressLint("SdCardPath")
    private fun tryReadChromiumCookieDb(): String? {
        return runCatching {
            // The WebView cookie database is at /data/data/<pkg>/app_webview/Default/Cookies
            val dbPath = File("/data/data/$packageName/").walkTopDown()
                .find { it.name == "Cookies" && it.isFile }
                ?: return null

            val db = SQLiteDatabase.openDatabase(dbPath.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val result = StringBuilder()

            val platformDomainSet = platform.domains.toSet()

            db.query(
                "cookies",
                arrayOf("host_key", "name", "value", "path", "is_secure", "expires_utc"),
                null, null, null, null, null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val hostKey = cursor.getString(0)
                    val name = cursor.getString(1)
                    val value = cursor.getString(2)
                    val path = cursor.getString(3)
                    val secure = cursor.getLong(4) == 1L
                    val expiry = cursor.getLong(5)

                    // Filter to cookies matching our platform domains
                    val hostMatch = platformDomainSet.any { domain ->
                        hostKey.equals(domain, ignoreCase = true) ||
                            hostKey.endsWith(".$domain", ignoreCase = true)
                    }
                    if (!hostMatch) continue

                    val host = if (hostKey.startsWith(".")) hostKey else ".$hostKey"
                    result.appendLine(
                        listOf(
                            host,
                            true.toString().uppercase(),
                            path,
                            secure.toString().uppercase(),
                            expiry.toString(),
                            name,
                            value
                        ).joinToString("\t")
                    )
                }
            }
            db.close()
            result.toString().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun toNetscapeLine(domain: String, name: String, value: String): String {
        val host = if (domain.startsWith(".")) domain else ".$domain"
        return listOf(
            host,
            "TRUE",
            "/",
            "TRUE",
            "0",
            name,
            value
        ).joinToString("\t")
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun matchTop(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top }

    private fun rounded(color: Int, radius: Int, strokeColor: Int?): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PLATFORM = "platform"
        private const val TAG = "WebViewLogin"
    }
}
