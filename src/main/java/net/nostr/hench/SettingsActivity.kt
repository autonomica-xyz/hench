package net.nostr.hench

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import net.nostr.hench.auth.CookieStore
import net.nostr.hench.auth.Platform
import net.nostr.hench.auth.SessionHealth
import net.nostr.hench.auth.WebViewLoginActivity
import net.nostr.hench.identity.NostrIdentity
import net.nostr.hench.service.WorkerForegroundService
import net.nostr.hench.service.WorkerForegroundServiceContract
import net.nostr.hench.settings.HoursOfOperation
import net.nostr.hench.settings.SharedPreferencesSettingsRepository
import net.nostr.hench.settings.WalletMode
import net.nostr.hench.settings.WorkerSettings
import net.nostr.hench.wallet.generateSparkSeedHex
import net.nostr.hench.wallet.sparkSeedBackupWords
import java.time.LocalTime

class SettingsActivity : Activity() {
    companion object {
        private const val REQUEST_IMPORT_TOOL = 4201
    }

    private lateinit var repository: SharedPreferencesSettingsRepository
    private lateinit var cookieStore: CookieStore

    private val bg = Color.rgb(8, 9, 10)
    private val panel = Color.rgb(15, 16, 17)
    private val surface = Color.rgb(25, 26, 27)
    private val border = Color.rgb(36, 40, 44)
    private val primaryText = Color.rgb(247, 248, 248)
    private val muted = Color.rgb(138, 143, 152)
    private val subtle = Color.rgb(98, 102, 109)
    private val accent = Color.rgb(94, 106, 210)
    private val success = Color.rgb(16, 185, 129)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SharedPreferencesSettingsRepository(this)
        cookieStore = CookieStore(this)
        render(repository.load())
    }

    private fun render(settings: WorkerSettings) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(56), dp(22), dp(420))
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        }

        root.addView(header())
        root.addView(helperText("Configure relays, identity, pricing, hours, and wallet. Home stays focused on worker controls and earnings."), matchTop(dp(10)))

        val enabled = checkbox("Worker enabled", settings.enabled)
        val workerName = field(
            "Worker name",
            settings.workerName ?: net.nostr.hench.settings.OnboardingDefaults.generateWorkerName(settings.sparkSeedHex ?: ""),
        )
        root.addView(section("Worker", "Core routing and storage endpoints", listOf(
            enabled,
            labeled("Worker name", workerName, "Shown in announcements so clients can tell workers apart. Blank resets to the generated default."),
        )))

        val relayUrls = field("Relay URLs", settings.relayUrls.joinToString(","), singleLine = false)
        val blossomUrls = field("Blossom URLs", settings.blossomUrls.joinToString(","), singleLine = false)
        val tempDir = field("Temp download directory", settings.tempDownloadDirectory.orEmpty())
        root.addView(section("Network", "Comma-separated endpoints used by the worker", listOf(
            labeled("Relay URLs", relayUrls),
            labeled("Blossom URLs", blossomUrls),
            labeled("Temp directory", tempDir, "Leave blank for app cache"),
        )))

        val secret = field("Private key", settings.workerSecretKeyHex.orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val publicKey = helperText(settings.workerSecretKeyHex?.let {
            runCatching { "Public key: ${NostrIdentity.fromPrivateKey(it).publicKeyHex}" }.getOrNull()
        } ?: "Public key: not configured")
        val identityActions = horizontalActions(
            actionButton("Generate") {
                val identity = NostrIdentity.generate()
                secret.setText(identity.privateKeyHex)
                publicKey.text = "Public key: ${identity.publicKeyHex}"
            },
            actionButton("Import") {
                runCatching { NostrIdentity.fromPrivateKey(secret.text.toString()) }
                    .onSuccess {
                        secret.setText(it.privateKeyHex)
                        publicKey.text = "Public key: ${it.publicKeyHex}"
                        toast("Imported Nostr identity")
                    }
                    .onFailure { toast(it.message ?: "Invalid Nostr identity") }
            },
            actionButton("Copy nsec") {
                runCatching { NostrIdentity.fromPrivateKey(secret.text.toString()).toNsec() }
                    .onSuccess { nsec ->
                        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("worker nsec", nsec))
                        toast("Copied nsec to clipboard")
                    }
                    .onFailure { toast(it.message ?: "Invalid Nostr identity") }
            },
        )
        root.addView(section("Identity", "Nostr key used to sign worker quotes and results", listOf(
            labeled("Private key", secret, "Accepts hex or nsec. Leave blank to derive from the Spark seed — then the 12 recovery words back up wallet AND identity together."),
            publicKey,
            identityActions,
        )))

        val acceptAudio = checkbox("Accept audio jobs", settings.acceptAudio)
        val acceptVideo = checkbox("Accept video jobs", settings.acceptVideo)
        val basePrice = numberField("Base price", settings.basePriceMsat)
        val audioPrice = numberField("Audio per hour", settings.audioPriceMsatPerHour)
        val videoPrice = numberField("Video per hour", settings.videoPriceMsatPerHour)
        val minBillable = numberField("Minimum billable seconds", settings.minBillableSeconds)
        val cleanup = checkbox("Cleanup local file after upload", settings.cleanupAfterUpload)
        root.addView(section("Jobs & Pricing", "Capabilities and msat pricing rules", listOf(
            acceptAudio,
            acceptVideo,
            twoColumn(labeled("Base price", basePrice), labeled("Min seconds", minBillable)),
            twoColumn(labeled("Audio / hour", audioPrice), labeled("Video / hour", videoPrice)),
            cleanup,
        )))

        val hoursAlways = checkbox("Always open", settings.hoursOfOperation is HoursOfOperation.Always)
        val daily = settings.hoursOfOperation as? HoursOfOperation.Daily
        val hoursStart = field("Start", daily?.start?.toString() ?: "00:00")
        val hoursEnd = field("End", daily?.end?.toString() ?: "00:00")
        root.addView(section("Availability", "When this phone should accept jobs", listOf(
            hoursAlways,
            twoColumn(labeled("Daily start", hoursStart), labeled("Daily end", hoursEnd)),
        )))

        val builtInSparkMode = checkbox("Use built-in Breez Spark wallet", settings.walletMode == WalletMode.BUILT_IN)
        val testWalletMode = checkbox("Use local auto-settle test wallet", settings.walletMode == WalletMode.TEST)
        val nwcMode = checkbox("Use Nostr Wallet Connect", settings.walletMode == WalletMode.NWC)
        val sparkApiKey = field("Breez API key", settings.sparkApiKey.orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val sparkSeed = field("Spark seed (hex or recovery words)", settings.sparkSeedHex.orEmpty(), singleLine = false).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val nwcUri = field("NWC URI", settings.nwcConnectionUri.orEmpty(), singleLine = false)
        keepVisibleAboveKeyboard(scroll, root, nwcUri)
        root.addView(section("Wallet", "Built-in Breez Spark creates real Lightning invoices. Secrets are stored only in this app's local preferences.", listOf(
            builtInSparkMode,
            labeled("Breez API key", sparkApiKey, "Bundled into this APK when available; override here only if needed."),
            labeled("Spark seed", sparkSeed, "Paste entropy hex OR the 12/24 BIP-39 recovery words to restore a wallet. Use Copy recovery words for a backup other Spark wallets can restore."),
            horizontalActions(
                actionButton("Generate wallet") {
                    sparkSeed.setText(generateSparkSeedHex())
                    builtInSparkMode.isChecked = true
                    nwcMode.isChecked = false
                    testWalletMode.isChecked = false
                    toast("Generated local Spark wallet seed")
                },
                actionButton("Copy recovery words") {
                    val seed = sparkSeed.text.toString().trim()
                    if (seed.isBlank()) {
                        toast("No Spark seed to copy")
                    } else {
                        runCatching { sparkSeedBackupWords(seed) }
                            .onSuccess { words ->
                                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("spark wallet recovery words", words))
                                toast("Copied BIP-39 recovery words — store them safely")
                            }
                            .onFailure { toast(it.message ?: "Invalid Spark seed") }
                    }
                },
                actionButton("Test mode") {
                    testWalletMode.isChecked = true
                    builtInSparkMode.isChecked = false
                    nwcMode.isChecked = false
                    toast("Selected local test wallet")
                },
            ),
            nwcMode,
            labeled("NWC URI", nwcUri, "Optional alternative wallet backend for real sats."),
            testWalletMode,
        )))

        // Platform Auth section
        val useCookies = checkbox("Enable authenticated sessions (cookies)", settings.useCookies)
        val platformButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        Platform.entries.chunked(3).forEach { row ->
            platformButtons.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEach { platform ->
                    val session = cookieStore.get(platform)
                    val statusLabel = when {
                        session == null -> ""
                        session.health == SessionHealth.HEALTHY -> " ✓"
                        session.health == SessionHealth.BANNED -> " ✗"
                        session.health == SessionHealth.EXPIRED -> " ⚠"
                        session.health == SessionHealth.RATE_LIMITED -> " ⏳"
                        else -> " ?"
                    }
                    addView(Button(this@SettingsActivity).apply {
                        text = platform.displayName + statusLabel
                        textSize = 11f
                        setTextColor(primaryText)
                        background = rounded(surface, dp(8), border)
                        setPadding(dp(6), dp(4), dp(6), dp(4))
                        setOnClickListener {
                            startActivity(Intent(this@SettingsActivity, WebViewLoginActivity::class.java).apply {
                                putExtra(WebViewLoginActivity.EXTRA_PLATFORM, platform.name)
                            })
                        }
                    }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        if (row.indexOf(platform) > 0) leftMargin = dp(4)
                        if (row.indexOf(platform) < row.lastIndex) rightMargin = dp(4)
                    })
                }
            }, matchTop(dp(4)))
        }
        val sessionSummary = helperText(buildString {
            val sessions = cookieStore.all()
            if (sessions.isEmpty()) {
                append("No sessions configured. Tap a platform above to log in.")
            } else {
                sessions.forEach { s ->
                    val healthIcon = when (s.health) {
                        SessionHealth.HEALTHY -> "✓"
                        SessionHealth.BANNED -> "✗ banned"
                        SessionHealth.EXPIRED -> "⚠ expired"
                        SessionHealth.RATE_LIMITED -> "⏳ rate limited"
                        SessionHealth.UNKNOWN -> "? unchecked"
                    }
                    appendLine("${s.platform.displayName}: $healthIcon, ${s.jobsProcessed} jobs")
                }
            }
        })
        // ---- Advertised tools (built-ins + user-installed script packages) ----
        val disabledToolsState = settings.disabledTools.toMutableSet()
        val enabledScriptsState = settings.enabledScriptTools.toMutableSet()
        val toolToggles = mutableListOf<android.widget.CheckBox>()
        for (entry in net.nostr.hench.tools.ToolCatalog.all) {
            val checked = entry.name !in disabledToolsState
            toolToggles += checkbox("${entry.displayName} — ${entry.blurb}", checked).apply {
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) disabledToolsState.remove(entry.name) else disabledToolsState.add(entry.name)
                }
            }
        }
        val scriptTools = net.nostr.hench.tools.script.ToolPackageManager.discover(this)
        for (pkg in scriptTools) {
            val checked = pkg.manifest.name in enabledScriptsState
            toolToggles += checkbox("${pkg.manifest.name} (script) — ${pkg.manifest.description.take(60)}", checked).apply {
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) enabledScriptsState.add(pkg.manifest.name) else enabledScriptsState.remove(pkg.manifest.name)
                }
            }
        }
        val importStatus = helperText("Drop a zip of <dir>/manifest.json+script.py into /Android/data/net.nostr.hench/files/tools/ or import it here. Script tools run with full app access — only enable tools you trust.")
        root.addView(section("Advertised tools", "Which tools this worker publishes on Nostr (restart applies)", toolToggles + listOf(
            actionButton("Import tool (.zip)") {
                runCatching {
                    startActivityForResult(
                        android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(android.content.Intent.CATEGORY_OPENABLE)
                            type = "application/zip"
                        },
                        REQUEST_IMPORT_TOOL,
                    )
                }.onFailure { toast("No file picker available: ${it.message}") }
            },
            importStatus,
        )))

        root.addView(section("Platform Auth", "Log into platforms to download authenticated content. Use burner accounts.", listOf(
            useCookies,
            helperText("Supported platforms: YouTube, TikTok, Instagram, Twitter/X, Facebook, Reddit, Vimeo, SoundCloud, Bilibili, Dailymotion, Pinterest, Snapchat, and any site yt-dlp supports."),
            platformButtons,
            sessionSummary,
        )))

        val status = statusText()
        fun readCandidate(): WorkerSettings = WorkerSettings(
            enabled = enabled.isChecked,
            workerName = workerName.text.toString().trim().takeIf { it.isNotBlank() },
            relayUrls = splitCsv(relayUrls.text.toString()),
            blossomUrls = splitCsv(blossomUrls.text.toString()),
            workerSecretKeyHex = secret.text.toString().trim().takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { NostrIdentity.fromPrivateKey(raw).privateKeyHex }.getOrElse { raw }
            },
            tempDownloadDirectory = tempDir.text.toString().trim().takeIf { it.isNotBlank() },
            acceptAudio = acceptAudio.isChecked,
            acceptVideo = acceptVideo.isChecked,
            basePriceMsat = basePrice.longValue(),
            audioPriceMsatPerHour = audioPrice.longValue(),
            videoPriceMsatPerHour = videoPrice.longValue(),
            minBillableSeconds = minBillable.longValue(),
            cleanupAfterUpload = cleanup.isChecked,
            hoursOfOperation = if (hoursAlways.isChecked) {
                HoursOfOperation.always()
            } else {
                HoursOfOperation.daily(LocalTime.parse(hoursStart.text.toString()), LocalTime.parse(hoursEnd.text.toString()))
            },
            walletMode = when {
                nwcMode.isChecked -> WalletMode.NWC
                testWalletMode.isChecked -> WalletMode.TEST
                else -> WalletMode.BUILT_IN
            },
            sparkApiKey = sparkApiKey.text.toString().trim().takeIf { it.isNotBlank() },
            sparkSeedHex = sparkSeed.text.toString().trim().takeIf { it.isNotBlank() }?.let {
                net.nostr.hench.wallet.normalizeSparkSeedInput(it)
                    ?: throw IllegalArgumentException("Invalid Spark seed: use entropy hex or valid 12/24 recovery words")
            },
            nwcConnectionUri = nwcUri.text.toString().trim().takeIf { it.isNotBlank() },
            useCookies = useCookies.isChecked,
            disabledTools = disabledToolsState.toSet(),
            enabledScriptTools = enabledScriptsState.toSet(),
            // Platform settings persist via CookieStore, not SharedPreferences fields
            allowedPlatforms = emptyList(),
            blockedPlatforms = emptyList(),
            healthCheckSettings = net.nostr.hench.auth.PlatformHealthChecker.PlatformHealthSettings(),
        )
        fun refreshStatus(candidate: WorkerSettings) {
            val issues = candidate.validate()
            status.text = if (issues.isEmpty()) "● Settings valid" else "● ${issues.size} issue(s): ${issues.joinToString()}"
            status.setTextColor(if (issues.isEmpty()) success else Color.rgb(255, 191, 87))
        }
        refreshStatus(settings)
        root.addView(status, matchTop(dp(4)))

        root.addView(actionButton("Save and reload worker", primary = true) {
            runCatching { readCandidate() }
                .onSuccess { candidate ->
                    refreshStatus(candidate)
                    repository.save(candidate)
                    reloadService(candidate.enabled)
                    toast("Saved settings")
                    render(candidate)
                }
                .onFailure { toast(it.message ?: "Invalid settings") }
        }, matchTop(dp(18)))

        root.addView(actionButton("Stop worker service") {
            startService(Intent(this@SettingsActivity, WorkerForegroundService::class.java).apply {
                action = WorkerForegroundServiceContract.ACTION_STOP
            })
            toast("Stop requested")
        }, matchTop(dp(10)))

        setContentView(scroll)
        if (settings.enabled) reloadService(true)
    }

    private fun header(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@SettingsActivity).apply {
            text = "Settings"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(primaryText)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(actionButton("Done") { finish() })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT_TOOL && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val dirs = net.nostr.hench.tools.script.ToolPackageManager.installFromZip(this, input)
                    val ok = dirs.any { java.io.File(it, "manifest.json").isFile }
                    if (ok) toast("Tool imported: ${dirs.joinToString { it.name }}. Enable it above, then Save.")
                    else toast("Zip had no manifest.json — see the tools README")
                } ?: toast("Could not open the selected zip")
            }.onFailure { toast("Import failed: ${it.message}") }
            render(repository.load()) // refresh the script-tool checkbox list
        }
    }

    private fun section(title: String, subtitle: String, children: List<android.view.View>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = rounded(panel, dp(18), border)
        addView(TextView(this@SettingsActivity).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(primaryText)
        })
        addView(helperText(subtitle), matchTop(dp(2)))
        children.forEach { child -> addView(child, matchTop(dp(12))) }
    }.also { it.layoutParams = matchTop(dp(18)) }

    private fun labeled(label: String, view: android.view.View, hint: String? = null): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@SettingsActivity).apply {
            text = label
            textSize = 13f
            setTextColor(muted)
        })
        addView(view, matchTop(dp(6)))
        if (hint != null) addView(helperText(hint), matchTop(dp(4)))
    }

    private fun twoColumn(left: android.view.View, right: android.view.View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
    }

    private fun horizontalActions(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEachIndexed { index, button ->
            addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                if (index > 0) leftMargin = dp(6)
                if (index < buttons.lastIndex) rightMargin = dp(6)
            })
        }
    }

    private fun field(hint: String, value: String, singleLine: Boolean = true): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine(singleLine)
        minHeight = dp(if (singleLine) 48 else 72)
        setTextColor(primaryText)
        setHintTextColor(subtle)
        textSize = 14f
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = rounded(surface, dp(10), border)
        inputType = if (singleLine) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }

    private fun keepVisibleAboveKeyboard(scroll: ScrollView, root: LinearLayout, field: EditText) {
        field.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            fun scrollFocusedFieldIntoView() {
                val fieldInRoot = Rect()
                root.offsetDescendantRectToMyCoords(field, fieldInRoot)
                scroll.smoothScrollTo(0, (fieldInRoot.top - dp(180)).coerceAtLeast(0))
            }
            scroll.postDelayed({ scrollFocusedFieldIntoView() }, 250)
            scroll.postDelayed({ scrollFocusedFieldIntoView() }, 600)
        }
    }

    private fun numberField(hint: String, value: Long): EditText = field(hint, value.toString()).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
    }

    private fun checkbox(label: String, checked: Boolean): CheckBox = CheckBox(this).apply {
        text = label
        isChecked = checked
        textSize = 15f
        setTextColor(primaryText)
        buttonTintList = android.content.res.ColorStateList.valueOf(accent)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun actionButton(label: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 13f
        setTextColor(primaryText)
        background = rounded(if (primary) accent else surface, dp(12), if (primary) null else border)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { onClick() }
    }

    private fun helperText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(muted)
    }

    private fun statusText(): TextView = TextView(this).apply {
        textSize = 14f
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rounded(surface, dp(12), border)
    }

    private fun reloadService(enabled: Boolean) {
        val intent = Intent(this, WorkerForegroundService::class.java).apply {
            action = if (enabled) WorkerForegroundServiceContract.ACTION_RELOAD_SETTINGS else WorkerForegroundServiceContract.ACTION_STOP
        }
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun splitCsv(raw: String): List<String> = raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
    private fun EditText.longValue(): Long = text.toString().trim().toLongOrNull() ?: 0L
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun matchTop(top: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top }

    private fun rounded(color: Int, radius: Int, strokeColor: Int?): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius.toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
