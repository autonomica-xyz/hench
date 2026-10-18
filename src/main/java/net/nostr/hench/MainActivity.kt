package net.nostr.hench

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import net.nostr.hench.auth.CookieStore
import net.nostr.hench.dashboard.DashboardState
import net.nostr.hench.service.WorkerForegroundService
import net.nostr.hench.service.WorkerForegroundServiceContract
import net.nostr.hench.settings.SharedPreferencesSettingsRepository
import net.nostr.hench.settings.WalletMode
import net.nostr.hench.wallet.BreezSparkSdkClient
import net.nostr.hench.wallet.SparkLightningWallet
import net.nostr.hench.wallet.WalletBalanceCache
import net.nostr.hench.worker.WorkerJobSnapshot
import net.nostr.hench.worker.WorkerJobSnapshotStore
import net.nostr.hench.worker.SharedPreferencesWorkerStatsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val workerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WorkerForegroundServiceContract.ACTION_WORKER_STATE_CHANGED) return
            renderDashboard()
        }
    }
    private lateinit var settingsRepository: SharedPreferencesSettingsRepository
    private lateinit var statsStore: SharedPreferencesWorkerStatsStore
    private lateinit var cookieStore: CookieStore
    private lateinit var walletBalanceCache: WalletBalanceCache
    private lateinit var jobSnapshotStore: WorkerJobSnapshotStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SharedPreferencesSettingsRepository(this)
        statsStore = SharedPreferencesWorkerStatsStore(this)
        cookieStore = CookieStore(this)
        walletBalanceCache = WalletBalanceCache(this)
        jobSnapshotStore = WorkerJobSnapshotStore(this)
        registerWorkerStateReceiver()
        requestNotificationPermissionIfNeeded()
        if (settingsRepository.isOnboardingComplete()) {
            startEnabledWorkerIfNeeded()
            renderDashboard()
            syncWalletBalanceInBackground()
        } else {
            renderFirstRunSetup()
            configureFirstRunInBackground()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::settingsRepository.isInitialized && settingsRepository.isOnboardingComplete()) {
            renderDashboard()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(workerStateReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    private fun registerWorkerStateReceiver() {
        val filter = IntentFilter(WorkerForegroundServiceContract.ACTION_WORKER_STATE_CHANGED)
        ContextCompat.registerReceiver(this, workerStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private fun renderFirstRunSetup() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(56), dp(24), dp(28))
            setBackgroundColor(Color.rgb(8, 9, 10))
            gravity = Gravity.CENTER
        }

        root.addView(TextView(this).apply {
            text = "Setting up worker"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(247, 248, 248))
            gravity = Gravity.CENTER
        }, matchTop(dp(28)))

        root.addView(TextView(this).apply {
            text = "Creating the worker identity and Spark wallet on this phone."
            textSize = 15f
            setTextColor(Color.rgb(178, 183, 192))
            gravity = Gravity.CENTER
            setLineSpacing(4f, 1.0f)
        }, matchTop(dp(16)))

        setContentView(root)
    }

    private fun configureFirstRunInBackground() {
        scope.launch {
            withContext(Dispatchers.IO) {
                settingsRepository.ensureFirstRunConfigured()
            }
            startEnabledWorkerIfNeeded()
            renderDashboard()
            syncWalletBalanceInBackground()
        }
    }

    private fun renderDashboard() {
        val settings = settingsRepository.load()
        val state = DashboardState.from(
            settings = settings,
            stats = statsStore.load(),
            platformSessions = cookieStore.all(),
            cachedWalletBalanceSats = walletBalanceCache.load()?.sats,
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(56), dp(24), dp(24))
            setBackgroundColor(Color.rgb(8, 9, 10))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(8, 9, 10))
            addView(root)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(TextView(this).apply {
            text = "Hench"
            textSize = 22f
            setTextColor(Color.rgb(247, 248, 248))
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(Button(this).apply {
            text = "Settings"
            textSize = 13f
            setTextColor(Color.rgb(247, 248, 248))
            background = rounded(Color.rgb(25, 26, 27), dp(10), Color.rgb(36, 40, 44))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        root.addView(topBar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        root.addView(statusPill(state.statusLabel, state.workerEnabled && state.validationIssueCount == 0))

        root.addView(TextView(this).apply {
            text = if (state.workerEnabled) "Tap to stop worker" else "Tap to start worker"
            textSize = 15f
            setTextColor(Color.rgb(138, 143, 152))
            gravity = Gravity.CENTER
        }, matchTop(dp(32)))

        root.addView(Button(this).apply {
            text = if (state.workerEnabled) "ON" else "OFF"
            textSize = 46f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(if (state.workerEnabled) Color.rgb(39, 166, 68) else Color.rgb(94, 106, 210), dp(96), null)
            minHeight = dp(156)
            setOnClickListener { toggleWorker(settings.enabled.not()) }
        }, LinearLayout.LayoutParams(dp(190), dp(190)).apply { topMargin = dp(12) })

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        statsRow.addView(statCard("Money earned", "${state.moneyEarnedSats} sats", state.moneyEarnedSubtitle).apply {
            isClickable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, WalletActivity::class.java)) }
        }, LinearLayout.LayoutParams(0, dp(132), 1f).apply { rightMargin = dp(8) })
        statsRow.addView(statCard("Jobs executed", state.jobsExecuted.toString(), "completed"), LinearLayout.LayoutParams(0, dp(132), 1f).apply { leftMargin = dp(8) })
        root.addView(statsRow, matchTop(dp(28)))

        jobSnapshotStore.load()?.let { snapshot ->
            root.addView(jobStatusCard(snapshot), matchTop(dp(18)))
        }

        root.addView(TextView(this).apply {
            text = if (state.validationIssueCount == 0) {
                val cookieInfo = if (state.useCookies && state.authenticatedSessionCount > 0) {
                    " ${state.authenticatedSessionCount} authenticated session(s) active."
                } else ""
                when (settings.walletMode) {
                    WalletMode.BUILT_IN -> "Configured ${settings.relayUrls.size} relays + Blossom + built-in Breez Spark wallet.$cookieInfo Back up/export the Spark seed in Settings."
                    WalletMode.NWC -> "Configured relay + Blossom + NWC wallet. Earnings reflect real wallet-settled invoices.$cookieInfo"
                    WalletMode.TEST -> "Using local test wallet. Earnings are mocked until Breez Spark or NWC is enabled in Settings.$cookieInfo"
                }
            } else {
                "${state.validationIssueCount} setup item(s) need attention. Open Settings."
            }
            textSize = 14f
            setTextColor(Color.rgb(138, 143, 152))
            gravity = Gravity.CENTER
        }, matchTop(dp(24)))

        // Show session status cards
        if (state.sessions.isNotEmpty()) {
            state.sessions.forEach { session ->
                val healthColor = when (session.health) {
                    "HEALTHY" -> Color.rgb(16, 185, 129)
                    "BANNED", "EXPIRED" -> Color.rgb(247, 100, 100)
                    "RATE_LIMITED" -> Color.rgb(255, 191, 87)
                    else -> Color.rgb(138, 143, 152)
                }
                root.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = rounded(Color.rgb(25, 26, 27), dp(10), Color.rgb(36, 40, 44))
                    addView(TextView(this@MainActivity).apply {
                        text = session.platformName
                        textSize = 14f
                        setTextColor(Color.rgb(247, 248, 248))
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(this@MainActivity).apply {
                        text = session.health
                        textSize = 12f
                        setTextColor(healthColor)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = "  ${session.jobsProcessed} jobs"
                        textSize = 12f
                        setTextColor(Color.rgb(98, 102, 109))
                    })
                }, matchTop(dp(6)))
            }
        }

        setContentView(scroll)
    }

    private fun toggleWorker(enabled: Boolean) {
        val current = settingsRepository.load()
        val updated = current.copy(enabled = enabled)
        settingsRepository.save(updated)
        startOrStopWorker(enabled)
        renderDashboard()
    }

    private fun startEnabledWorkerIfNeeded() {
        if (settingsRepository.load().enabled) startOrStopWorker(true)
    }

    private fun startOrStopWorker(enabled: Boolean) {
        val intent = Intent(this, WorkerForegroundService::class.java).apply {
            action = if (enabled) WorkerForegroundServiceContract.ACTION_RELOAD_SETTINGS else WorkerForegroundServiceContract.ACTION_STOP
        }
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun syncWalletBalanceInBackground() {
        val settings = settingsRepository.load()
        if (settings.walletMode != WalletMode.BUILT_IN) return
        val apiKey = settings.sparkApiKey ?: return
        val seed = settings.sparkSeedHex ?: return
        scope.launch {
            val balance = runCatching {
                withContext(Dispatchers.IO) {
                    SparkLightningWallet(BreezSparkSdkClient(applicationContext, apiKey, seed)).balanceSats()
                }
            }.getOrNull() ?: return@launch
            walletBalanceCache.save(balance)
            renderDashboard()
        }
    }

    private fun statusPill(label: String, healthy: Boolean): TextView = TextView(this).apply {
        text = if (healthy) "● $label" else "● $label"
        textSize = 14f
        setTextColor(if (healthy) Color.rgb(16, 185, 129) else Color.rgb(138, 143, 152))
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = rounded(Color.rgb(15, 16, 17), dp(10), null)
    }

    private fun statCard(title: String, value: String, subtitle: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(Color.rgb(25, 26, 27), dp(16), Color.rgb(36, 40, 44))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 13f
            setTextColor(Color.rgb(138, 143, 152))
            gravity = Gravity.CENTER
        })
        addView(TextView(this@MainActivity).apply {
            text = value
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(247, 248, 248))
            gravity = Gravity.CENTER
        })
        addView(TextView(this@MainActivity).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Color.rgb(98, 102, 109))
            gravity = Gravity.CENTER
        })
    }

    private fun jobStatusCard(snapshot: WorkerJobSnapshot): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(Color.rgb(25, 26, 27), dp(16), Color.rgb(36, 40, 44))

        addView(TextView(this@MainActivity).apply {
            text = "Latest job"
            textSize = 13f
            setTextColor(Color.rgb(138, 143, 152))
        })

        addView(TextView(this@MainActivity).apply {
            text = jobStatusTitle(snapshot.status, snapshot.detail)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(jobStatusColor(snapshot.status))
        }, matchTop(dp(4)))

        val detail = buildString {
            append("Job ${snapshot.requestShortId}")
            if (snapshot.amountSats > 0) append(" · ${snapshot.amountSats} sats")
            snapshot.detail?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
        }
        addView(TextView(this@MainActivity).apply {
            text = detail
            textSize = 13f
            setTextColor(Color.rgb(178, 183, 192))
            setLineSpacing(4f, 1.0f)
        }, matchTop(dp(8)))
    }

    private fun jobStatusTitle(status: String, detail: String? = null): String = when (status) {
        "WAITING_FOR_PAYMENT" -> "Invoice sent"
        "PROCESSING" -> when {
            detail?.contains("gate confirmation pending", ignoreCase = true) == true -> "Gate confirmation pending"
            detail?.contains("already in progress", ignoreCase = true) == true -> "Running"
            else -> "Payment received"
        }
        "DOWNLOADING" -> "Downloading"
        "UPLOADING" -> "Uploading result"
        "UPLOAD_RETRYING" -> "Retrying upload"
        "COMPLETE" -> "Job complete"
        "FAILED" -> "Job failed"
        else -> status.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() }
    }

    private fun jobStatusColor(status: String): Int = when (status) {
        "COMPLETE" -> Color.rgb(16, 185, 129)
        "FAILED" -> Color.rgb(247, 100, 100)
        "UPLOAD_RETRYING" -> Color.rgb(255, 191, 87)
        else -> Color.rgb(247, 248, 248)
    }

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

    companion object {
        private const val REQUEST_NOTIFICATIONS = 100
    }
}
