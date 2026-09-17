package net.nostr.hench.tools.script

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs task scripts on the bundled embedded CPython. youtubedl-android ships
 * `libpython.so` as a PIE EXECUTABLE in the APK's native lib dir plus a python
 * package (usr/ tree with stdlib) it unpacks on init; we spawn that executable
 * as a real subprocess with the same environment YoutubeDL uses:
 *
 *   PYTHONHOME=<pythonDir>/usr
 *   LD_LIBRARY_PATH=<pythonDir>/usr/lib:<nativeLibDir>:<pythonDir>/usr/lib
 *   SSL_CERT_FILE=<pythonDir>/usr/etc/tls/cert.pem
 *   TMPDIR=<cacheDir>
 *
 * Subprocess = real killability: the hard timeout destroys the process, so a
 * hung script can never wedge the worker (unlike an in-process interpreter).
 *
 * Trust model (v1, deliberate): a script runs with full app-process access —
 * files, network, the works. Installing a script tool is like installing an
 * app: only run packages you trust. Manifests describe; they do not confine.
 */
object PythonScriptRunner {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

    /**
     * Execute [script] with [args]; blocks up to [timeoutMs], then kills the
     * process. Requires YoutubeDL init to have run (python usr/ tree unpacked).
     */
    fun run(context: Context, script: File, args: List<String>, timeoutMs: Long): Result {
        val pythonDir = File(context.noBackupFilesDir, "youtubedl-android/packages/python")
        require(pythonDir.isDirectory) { "python runtime not initialized" }
        val pythonBin = File(context.applicationInfo.nativeLibraryDir, "libpython.so")
        require(pythonBin.canExecute()) { "libpython.so not found in ${pythonBin.parent}" }

        val pb = ProcessBuilder(listOf(pythonBin.absolutePath, script.absolutePath) + args)
        pb.environment()["PYTHONHOME"] = File(pythonDir, "usr").absolutePath
        pb.environment()["LD_LIBRARY_PATH"] = listOf(
            File(pythonDir, "usr/lib").absolutePath,
            pythonBin.parent,
            File(pythonDir, "usr/lib").absolutePath,
        ).joinToString(":")
        pb.environment()["SSL_CERT_FILE"] = File(pythonDir, "usr/etc/tls/cert.pem").absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
        pb.directory(context.cacheDir)

        val proc = pb.start()
        // Drain on reader threads so a chatty script cannot deadlock on full pipes.
        val outRef = java.util.concurrent.atomic.AtomicReference("")
        val errRef = java.util.concurrent.atomic.AtomicReference("")
        val outReader = Thread {
            runCatching { outRef.set(proc.inputStream.bufferedReader().readText()) }
        }.apply { isDaemon = true }
        val errReader = Thread {
            runCatching { errRef.set(proc.errorStream.bufferedReader().readText()) }
        }.apply { isDaemon = true }
        outReader.start(); errReader.start()

        // minSdk 24: the timed waitFor/destroyForcibly overloads are API 26+.
        @Suppress("DEPRECATION")
        val finished = if (android.os.Build.VERSION.SDK_INT >= 26) {
            runCatching { proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        } else {
            // ponytail: poll-and-destroy fallback for API 24/25; our fleet is modern.
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try { proc.exitValue(); break } catch (_: IllegalThreadStateException) { Thread.sleep(100) }
            }
            runCatching { proc.exitValue() }.isSuccess
        }
        if (!finished) {
            runCatching { proc.destroy() }
            if (android.os.Build.VERSION.SDK_INT >= 26) proc.destroyForcibly()
            outReader.join(2_000); errReader.join(2_000)
            return Result(-1, outRef.get(), errRef.get(), timedOut = true)
        }
        outReader.join(2_000); errReader.join(2_000)
        return Result(proc.exitValue(), outRef.get(), errRef.get(), timedOut = false)
    }
}
