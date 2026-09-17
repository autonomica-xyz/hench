@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package net.nostr.hench.runtime

import android.content.Context
import android.util.Log
import net.nostr.hench.auth.CookieStore
import net.nostr.hench.auth.Platform
import net.nostr.hench.blossom.BlossomClient
import net.nostr.hench.blossom.BlossomServerSelector
import net.nostr.hench.blossom.BlossomUploadResult
import net.nostr.hench.blossom.HttpBlossomClient
import net.nostr.hench.worker.DeliveryMode
import net.nostr.hench.worker.JobArtifact
import net.nostr.hench.worker.JobExecutor
import net.nostr.hench.worker.JobRequest
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class YtdlpBlossomJobExecutor(
    private val context: Context,
    private val blossomServerSelector: BlossomServerSelector,
    private val cookieStore: CookieStore? = null,
    private val useCookies: Boolean = false,
    private val blossomClientFactory: (String, Long) -> BlossomClient = { server, timeout ->
        HttpBlossomClient(server, callTimeoutSecs = timeout)
    },
    private val tempDownloadDirectory: File = File(context.filesDir, "distributed-downloads"),
    private val onProgress: (JobExecutionProgress) -> Unit = {},
) : JobExecutor {
    override suspend fun execute(request: JobRequest): JobArtifact {
        val budgetMillis = (request.socketTimeoutSecs ?: EXECUTION_BUDGET_SECS) * 1_000L
        val deadlineNanos = System.nanoTime() + budgetMillis * 1_000_000L

        return withTimeout(budgetMillis) {
            withContext(Dispatchers.IO) {
                val outDir = File(tempDownloadDirectory, request.id).apply { mkdirs() }
                val outputTemplate = File(outDir, "%(id)s.%(ext)s").absolutePath
                val ffmpegExecutable = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/bin/ffmpeg")
                val ytdlRequest = YoutubeDLRequest(request.url)
                    .addOption("--no-playlist")
                    .addOption("--restrict-filenames")
                    .addOption("--no-update")
                    .addOption("-o", outputTemplate)
                request.socketTimeoutSecs?.let { timeout ->
                    ytdlRequest.addOption("--socket-timeout", timeout.toString())
                }
                if (ffmpegExecutable.exists()) {
                    ytdlRequest.addOption("--ffmpeg-location", ffmpegExecutable.absolutePath)
                }

                // Browser impersonation for platforms that detect and block standard HTTP clients.
                // Only enabled when curl_cffi is importable (requires native .so that can load).
                // Falls back gracefully — most platforms work for public content without it.
                request.platform?.impersonateTarget?.let { target ->
                    if (PythonPackageManager.isCurlCffiImportable(context.applicationContext)) {
                        ytdlRequest.addOption("--impersonate", target)
                        Log.d(TAG, "using impersonate=$target for ${request.platform}")
                    } else {
                        Log.d(TAG, "curl_cffi not importable, skipping impersonate for ${request.platform}")
                    }
                }

                // Cookie-based authentication for supported platforms
                if (useCookies && cookieStore != null) {
                    applyCookies(ytdlRequest, request, outDir)
                }

                when (request.mode) {
                    DeliveryMode.AUDIO_ONLY -> ytdlRequest.addOption("-x").addOption("--audio-format", "mp3")
                    DeliveryMode.FULL -> ytdlRequest.addOption("--format", "best[ext=mp4]/best")
                }

                onProgress(JobExecutionProgress(request.id, "DOWNLOADING", "Downloading media"))

                // Budget remaining by the time the download starts. The outer
                // withTimeout covers the full execute path; the watchdog and
                // inner withTimeout use this so the download cannot exceed the
                // overall budget that eager startup has already consumed.
                val remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (remainingMillis <= 0L) {
                    // The outer deadline is already past; let it throw the
                    // TimeoutCancellationException instead of starting the download.
                    coroutineContext.ensureActive()
                }

                // Watchdog: if the execution budget is exceeded, ask youtubedl-android to
                // terminate the yt-dlp process. This coroutine is launched in GlobalScope
                // so it survives cancellation of the tool coroutine and can still pull
                // the rug out from under a blocking native call; it is canceled when the
                // tool finishes normally.
                // ponytail: destroyProcessById asks the native yt-dlp process to exit but
                // cannot force-kill a stuck Python interpreter inside a syscall. The
                // --socket-timeout option already bounds network waits, so this is the
                // best-effort hard ceiling on the process lifetime.
                val watchdog = GlobalScope.launch(Dispatchers.IO) {
                    try {
                        delay(remainingMillis)
                        Log.w(TAG, "execution budget exceeded for ${request.id}, destroying yt-dlp process")
                        YoutubeDL.getInstance().destroyProcessById(request.id)
                    } catch (_: CancellationException) {
                        // completed within budget
                    }
                }

                try {
                    withTimeout(remainingMillis) {
                        try {
                            YoutubeDL.getInstance().execute(ytdlRequest, request.id, false)
                        } catch (e: Exception) {
                            // Detect and rethrow auth-related failures with clear diagnostics
                            val message = e.message.orEmpty()
                            if (isAuthFailure(message)) {
                                Log.w(TAG, "authentication failure for ${request.url}: $message")
                                // Mark session as potentially expired
                                request.platform?.let { platform ->
                                    cookieStore?.get(platform)?.let { session ->
                                        cookieStore.save(session.copy(health = net.nostr.hench.auth.SessionHealth.EXPIRED))
                                    }
                                }
                            }
                            throw e
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    runCatching { watchdog.join() }
                    throw e
                } finally {
                    watchdog.cancel()
                }

                val artifactFile = outDir.walkTopDown().filter { it.isFile }.maxByOrNull { it.lastModified() }
                    ?: run {
                        if (outDir.exists()) outDir.deleteRecursively()
                        error("yt-dlp completed but no output file found in ${outDir.absolutePath}")
                    }
                val uploaded = try {
                    onProgress(JobExecutionProgress(request.id, "UPLOADING", "Uploading result"))
                    uploadWithRetry(request, artifactFile, deadlineNanos)
                } catch (e: Exception) {
                    if (outDir.exists()) outDir.deleteRecursively()
                    throw e
                }

                // Record the job in the cookie store for health tracking
                request.platform?.let { platform ->
                    cookieStore?.recordJobCompleted(platform)
                }

                // Cleanup
                if (outDir.exists()) {
                    outDir.deleteRecursively()
                }

                JobArtifact(uploaded.url, uploaded.sha256, uploaded.sizeBytes)
            }
        }
    }

    private suspend fun uploadWithRetry(request: JobRequest, artifactFile: File, deadlineNanos: Long): BlossomUploadResult {
        val servers = blossomServerSelector.serverCandidatesFor(request)
        var lastError: Throwable? = null

        for (server in servers) {
            repeat(BLOSSOM_UPLOAD_ATTEMPTS_PER_SERVER) { attempt ->
                val remainingSec = (deadlineNanos - System.nanoTime()) / 1_000_000_000L
                if (remainingSec <= 0L) {
                    // The outer deadline is already past; let it throw the
                    // TimeoutCancellationException instead of starting another upload.
                    coroutineContext.ensureActive()
                }

                try {
                    Log.i(TAG, "uploading artifact job=${request.id} server=$server attempt=${attempt + 1}/$BLOSSOM_UPLOAD_ATTEMPTS_PER_SERVER")
                    onProgress(JobExecutionProgress(request.id, "UPLOADING", "Uploading to ${serverHost(server)} (${attempt + 1}/$BLOSSOM_UPLOAD_ATTEMPTS_PER_SERVER)"))
                    // ponytail: upload() hashes the artifact synchronously; remainingSec is the HTTP call timeout, not a hash ceiling.
                    return blossomClientFactory(server, remainingSec.coerceAtLeast(1L)).upload(artifactFile)
                } catch (t: TimeoutCancellationException) {
                    // Propagate deadline exhaustion rather than retrying.
                    throw t
                } catch (t: Throwable) {
                    lastError = t
                    Log.w(TAG, "Blossom upload failed job=${request.id} server=$server attempt=${attempt + 1}: ${t.message}", t)
                    onProgress(JobExecutionProgress(request.id, "UPLOAD_RETRYING", "Upload failed on ${serverHost(server)}: ${t.message ?: t::class.java.simpleName}"))
                    if (attempt + 1 < BLOSSOM_UPLOAD_ATTEMPTS_PER_SERVER) delay(BLOSSOM_UPLOAD_RETRY_DELAY_MS)
                }
            }
        }

        throw IllegalStateException(
            "Blossom upload failed after ${servers.size * BLOSSOM_UPLOAD_ATTEMPTS_PER_SERVER} attempts across ${servers.size} server(s): ${lastError?.message ?: "unknown error"}",
            lastError,
        )
    }

    private fun serverHost(server: String): String = runCatching { java.net.URI(server).host }.getOrNull() ?: server

    /**
     * Writes a cookie file for the request's platform and passes it to yt-dlp.
     * Falls back to a combined cookie file covering all sessions if the platform
     * has no specific session.
     */
    private fun applyCookies(ytdlRequest: YoutubeDLRequest, request: JobRequest, outDir: File) {
        val platform = request.platform
        val cookieFilePath = if (platform != null) {
            // Platform-specific cookie file
            cookieStore?.writeCookieFile(platform)
                // Fall back to combined file if no specific session
                ?: cookieStore?.writeCombinedCookieFile()
        } else {
            // Unknown platform — try the combined file
            cookieStore?.writeCombinedCookieFile()
        }

        if (cookieFilePath != null) {
            ytdlRequest.addOption("--cookies", cookieFilePath)
            Log.d(TAG, "using cookies from $cookieFilePath for ${request.url}")
        }
    }

    companion object {
        private const val TAG = "HenchExecutor"
        private const val BLOSSOM_UPLOAD_ATTEMPTS_PER_SERVER = 3
        private const val BLOSSOM_UPLOAD_RETRY_DELAY_MS = 2_000L

        /** Default hard execution-budget ceiling in seconds. */
        internal const val EXECUTION_BUDGET_SECS = 600L

        fun initialize(context: Context) {
            YoutubeDL.getInstance().init(context.applicationContext)
            FFmpeg.getInstance().init(context.applicationContext)
            PythonPackageManager.install(context.applicationContext)
        }

        /** Detect yt-dlp error messages that indicate auth failures. */
        private fun isAuthFailure(message: String): Boolean {
            val lower = message.lowercase()
            return lower.contains("http error 403") ||
                lower.contains("http error 401") ||
                lower.contains("sign in") ||
                lower.contains("login required") ||
                lower.contains("authentication") ||
                lower.contains("not available") && lower.contains("login") ||
                lower.contains("private video") ||
                lower.contains("cookies") && lower.contains("required")
        }
    }
}

data class JobExecutionProgress(
    val requestId: String,
    val status: String,
    val detail: String,
)
