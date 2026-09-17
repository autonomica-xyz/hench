@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package net.nostr.hench.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import java.io.File
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.nostr.hench.auth.CookieStore
import net.nostr.hench.blossom.HttpBlossomClient
import net.nostr.hench.cvm.CvmServer
import net.nostr.hench.settings.SharedPreferencesSettingsRepository
import net.nostr.hench.settings.WalletMode
import net.nostr.hench.tools.ToolRegistry
import net.nostr.hench.tools.ytdlp.YtdlpTool
import net.nostr.hench.runtime.YtdlpBlossomJobExecutor
import net.nostr.hench.wallet.FakeLightningWallet
import net.nostr.hench.wallet.LightningWallet
import net.nostr.hench.wallet.SparkLightningWallet
import net.nostr.hench.worker.PaymentSessionStore
import net.nostr.hench.worker.SharedPreferencesWorkerStatsStore
import net.nostr.hench.worker.WorkerJobSnapshotStore

/**
 * Foreground service owning the CVM server recv loop. Builds the tool registry,
 * the wallet gate, the PaymentSessionStore, and the CvmServer; reconciles
 * pending sessions on startup; tears it all down on stop (PLAN §3 lifecycle).
 */
class WorkerForegroundService : Service() {
    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cvmServer: CvmServer? = null
    private var cookieStore: CookieStore? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            WorkerForegroundServiceContract.ACTION_STOP -> {
                shutdownAll()
                stopSelf()
                return START_NOT_STICKY
            }
            WorkerForegroundServiceContract.ACTION_START,
            WorkerForegroundServiceContract.ACTION_RELOAD_SETTINGS,
            null -> startWorkerForeground()
        }
        return START_STICKY
    }

    private fun startWorkerForeground() {
        ensureNotificationChannel()
        startForeground(WorkerForegroundServiceContract.NOTIFICATION_ID, workerNotification())

        val settings = SharedPreferencesSettingsRepository(this).load()
        if (!settings.enabled) {
            Log.i(TAG, "worker disabled")
            shutdownAll()
            return
        }
        val issues = settings.validate()
        if (issues.isNotEmpty() || settings.workerSecretKeyHex.isNullOrBlank()) {
            Log.e(TAG, "invalid settings $issues")
            shutdownAll()
            return
        }
        shutdownAll() // stop any existing run before reconfiguring

        cookieStore = CookieStore(applicationContext)
        val wallet = buildWallet(settings)
        val store = PaymentSessionStore(applicationContext)
        val secretHex = requireNotNull(settings.workerSecretKeyHex)

        ensureExampleToolInstalled()

        val ytdlp = YtdlpTool(
            context = applicationContext,
            settings = settings,
            cookieStore = cookieStore,
            store = store,
        )
        // Built-ins are on unless explicitly disabled (empty set = all).
        // Script tools are strictly opt-in (must appear in enabledTools).
        val builtinTools = listOfNotNull(
            ytdlp,
            net.nostr.hench.tools.browser.WebFetchTool(applicationContext, store),
            net.nostr.hench.tools.browser.PageScreenshotTool(applicationContext, settings, store),
        ).filter { it.name !in settings.disabledTools }

        val blossomSelector = net.nostr.hench.blossom.BlossomServerSelector(settings.blossomUrls)
        fun uploadArtifact(file: java.io.File): net.nostr.hench.blossom.BlossomUploadResult {
            // ponytail: no retry ladder here; script file outputs are small and the
            // browser tool keeps the verified-upload pattern. Share one impl if this
            // ever flakes in the field.
            val server = blossomSelector.serverCandidatesFor(
                net.nostr.hench.worker.JobRequest("script", "", net.nostr.hench.worker.DeliveryMode.FULL),
            ).first()
            return kotlinx.coroutines.runBlocking {
                net.nostr.hench.blossom.HttpBlossomClient(
                    server,
                    authSecretKeyHex = settings.workerSecretKeyHex,
                    callTimeoutSecs = 60,
                ).upload(file)
            }
        }

        val discoveredScripts = net.nostr.hench.tools.script.ToolPackageManager.discover(applicationContext)
        Log.i(TAG, "script packages discovered: ${discoveredScripts.joinToString { it.manifest.name }}; enabled set: ${settings.enabledScriptTools}")
        val scriptTools = discoveredScripts
            .filter { it.manifest.name in settings.enabledScriptTools }
            .map {
                net.nostr.hench.tools.script.ScriptTool(
                    applicationContext, it.manifest, it.script, store, blossomUploader = ::uploadArtifact,
                )
            }
        if (scriptTools.isNotEmpty()) {
            Log.i(TAG, "script tools enabled: ${scriptTools.joinToString { it.name }}")
        }

        val registry = ToolRegistry(*(builtinTools + scriptTools).toTypedArray())

        val server = CvmServer(
            context = applicationContext,
            secretKeyHex = secretHex,
            relayUrls = settings.relayUrls,
            registry = registry,
            store = store,
            wallet = wallet,
            settings = settings,
            announce = true,
            serverName = settings.workerName ?: net.nostr.hench.settings.OnboardingDefaults.generateWorkerName(settings.sparkSeedHex ?: ""),
            onJobUpdate = { update -> handleJobUpdate(update) },
        )
        cvmServer = server
        // Reconcile persisted records for the consumer's own state; the CEP-8 gate
        // is not bound at startup, so re-runs/replays are gate-driven once wired.
        serviceScope.launch {
            // Fail closed: a worker whose download engine cannot initialize must not
            // start/announce the CVM server — it would accept paid calls that can
            // only fail. Surface the startup failure instead (round-4 review).
            val init = runCatching { YtdlpBlossomJobExecutor.initialize(applicationContext) }
            // Probe the embedded-interpreter path used by script tools (JNA -> libpython).
            // Diagnostic only for now: proves the extension runtime before the
            // package system ships on top of it.
            runCatching {
                val probe = File(applicationContext.cacheDir, "probe.py")
                probe.writeText("import sys, json; print(json.dumps({'ok': True, 'py': sys.version.split()[0]}))\n")
                val r = net.nostr.hench.tools.script.PythonScriptRunner.run(applicationContext, probe, emptyList(), 20_000)
                Log.i(TAG, "script-runner probe: rc=${r.exitCode} timedOut=${r.timedOut} out=${r.stdout.trim().take(120)} err=${r.stderr.trim().take(200)}")
                probe.delete()
            }.onFailure { Log.w(TAG, "script-runner probe failed: ${it.message}") }
            if (init.isFailure) {
                Log.e(TAG, "ytdlp/ffmpeg/python init failed; not starting server", init.exceptionOrNull())
                handleJobUpdate(
                    net.nostr.hench.cvm.JobUpdate(
                        idempotencyKey = "startup",
                        status = net.nostr.hench.cvm.JobStatus.FAILED,
                        detail = "worker startup failed: executor init (${init.exceptionOrNull()?.message ?: "unknown"}); retry by restarting the service",
                    ),
                )
                stopSelf()
                return@launch
            }
            runCatching { server.reconcile(wallet) }
                .onFailure { Log.w(TAG, "reconcile failed: ${it.message}") }
            server.start()
            Log.i(TAG, "hench started; wallet=${settings.walletMode}")
        }
    }

    private fun handleJobUpdate(update: net.nostr.hench.cvm.JobUpdate) {
        val snapshot = WorkerJobSnapshotStore(applicationContext)
        snapshot.save(update.idempotencyKey, update.status.name, update.amountMsat, update.detail)
        if (update.status == net.nostr.hench.cvm.JobStatus.COMPLETED && update.amountMsat > 0) {
            SharedPreferencesWorkerStatsStore(applicationContext).recordCompletedJob(update.amountMsat)
        }
        sendBroadcast(Intent(WorkerForegroundServiceContract.ACTION_WORKER_STATE_CHANGED).setPackage(packageName))
    }

    /** Ships the hello_script example into the internal tools dir once (disabled by default). */
    private fun ensureExampleToolInstalled() {
        runCatching {
            val dest = net.nostr.hench.tools.script.ToolPackageManager.internalToolsDir(applicationContext)
            val exampleDir = java.io.File(dest, "hello_script")
            if (!java.io.File(exampleDir, "manifest.json").isFile) {
                exampleDir.mkdirs()
                for (asset in listOf("manifest.json", "script.py")) {
                    applicationContext.assets.open("example-tool/hello_script/$asset").use { input ->
                        java.io.File(exampleDir, asset).outputStream().use { input.copyTo(it) }
                    }
                }
                Log.i(TAG, "installed hello_script example tool (disabled)")
            }
        }
    }

    private fun buildWallet(settings: net.nostr.hench.settings.WorkerSettings): LightningWallet =
        when (settings.walletMode) {
            WalletMode.BUILT_IN -> {
                val apiKey = requireNotNull(settings.sparkApiKey) { "Breez Spark API key missing" }
                val seedHex = requireNotNull(settings.sparkSeedHex) { "Breez Spark seed missing" }
                Log.i(TAG, "using built-in Breez Spark wallet")
                SparkLightningWallet(net.nostr.hench.wallet.BreezSparkSdkClient(applicationContext, apiKey, seedHex))
            }
            WalletMode.NWC -> {
                val raw = requireNotNull(settings.nwcConnectionUri) { "NWC URI missing" }
                net.nostr.hench.nwc.NwcConnectionUri.parse(raw).fold(
                    onSuccess = { net.nostr.hench.nwc.NwcLightningWallet(it) },
                    onFailure = { throw it },
                )
            }
            WalletMode.TEST -> {
                Log.w(TAG, "using local test wallet; payments auto-settled, not real sats")
                FakeLightningWallet(autoSettle = true)
            }
        }

    override fun onDestroy() {
        serviceScope.cancel()
        shutdownAll()
        super.onDestroy()
    }

    private fun shutdownAll() {
        val server = cvmServer
        cvmServer = null
        // Cancel the service scope (stops reconcile/start) and prepare a fresh
        // one so the next start has a usable scope.
        serviceScope.cancel()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        stopForeground(STOP_FOREGROUND_REMOVE)
        sendBroadcast(Intent(WorkerForegroundServiceContract.ACTION_WORKER_STATE_CHANGED).setPackage(packageName))

        // The FFI server shutdown is blocking; dispatch it outside the service
        // scope (which is canceled) so it is never called on the main thread.
        // GlobalScope is intentional: teardown must outlive the canceled scope.
        if (server != null) {
            GlobalScope.launch(Dispatchers.IO) {
                runCatching { server.stop() }
                    .onFailure { Log.w(TAG, "server shutdown failed: ${it.message}") }
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                WorkerForegroundServiceContract.NOTIFICATION_CHANNEL_ID,
                "Hench",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun workerNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, WorkerForegroundServiceContract.NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Hench")
            .setContentText("Listening for download_media calls")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
    }

    companion object { private const val TAG = "Hench" }
}
