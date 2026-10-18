package net.nostr.hench.runtime

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Manages Python packages for the youtubedl-android runtime.
 *
 * After [com.yausername.youtubedl_android.YoutubeDL.getInstance].init() extracts the
 * bundled Python environment, this class installs additional native packages (curl_cffi)
 * and updates yt-dlp to the latest version shipped as assets.
 *
 * curl_cffi provides TLS fingerprint impersonation — required by yt-dlp for sites
 * like TikTok, Vimeo, Instagram, etc. that block standard Python urllib/requests.
 */
object PythonPackageManager {
    private var cachedCurlInstalled: Boolean? = null
    private const val TAG = "PythonPackageManager"
    private const val PREFS_NAME = "python_packages"
    private const val KEY_CURL_CFFI_VERSION = "curl_cffi_version"
    private const val KEY_YTDLP_VERSION = "ytdlp_version"

    /** Current curl_cffi version bundled as asset */
    private const val BUNDLED_CURL_CFFI_VERSION = "0.16.3+hench.1"
    private const val CFFI_BACKEND = "_cffi_backend.cpython-314-aarch64-linux-android.so"

    /** Current yt-dlp version bundled as asset */
    private const val BUNDLED_YTDLP_VERSION = "2026.08.19"

    /**
     * Install all bundled Python packages and update yt-dlp.
     * Must be called after [YoutubeDL.getInstance().init(context)].
     *
     * @return true if any installation/update was performed
     */
    @Synchronized
    fun install(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var installed = false

        // Python site-packages lives at:
        // noBackupFilesDir/youtubedl-android/packages/python/usr/lib/python3.X/site-packages/
        val packagesDir = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val pythonDir = File(packagesDir, "python")
        val sitePackages = findSitePackages(pythonDir)

        if (sitePackages == null) {
            Log.w(TAG, "Python site-packages not found, skipping package installation")
            return false
        }

        // Install curl_cffi
        val currentCurlVersion = prefs.getString(KEY_CURL_CFFI_VERSION, null)
        if (currentCurlVersion != BUNDLED_CURL_CFFI_VERSION) {
            if (installCurlCffi(context, sitePackages)) {
                installed = true
                prefs.edit { putString(KEY_CURL_CFFI_VERSION, BUNDLED_CURL_CFFI_VERSION) }
            }
        } else {
            if (!File(sitePackages, "curl_cffi/_wrapper.abi3.so").isFile ||
                !File(sitePackages, CFFI_BACKEND).isFile) {
                installed = installCurlCffi(context, sitePackages)
            }
        }

        // Update yt-dlp binary
        val currentYtdlpVersion = prefs.getString(KEY_YTDLP_VERSION, null)
        if (currentYtdlpVersion != BUNDLED_YTDLP_VERSION) {
            if (updateYtdlp(context)) {
                installed = true
                prefs.edit { putString(KEY_YTDLP_VERSION, BUNDLED_YTDLP_VERSION) }
            }
        }

        if (installed) cachedCurlInstalled = null
        return installed
    }

    /** Checks for the bundled native files; this does not load Python. */
    fun isCurlCffiInstalled(context: Context): Boolean {
        if (cachedCurlInstalled != null) return cachedCurlInstalled!!

        val pythonDir = File(context.noBackupFilesDir, "youtubedl-android/packages/python")
        val sitePackages = findSitePackages(pythonDir) ?: run {
            cachedCurlInstalled = false
            return false
        }

        val hasWrapper = File(sitePackages, "curl_cffi/_wrapper.abi3.so").isFile
        val hasCffiBackend = File(sitePackages, CFFI_BACKEND).isFile
        cachedCurlInstalled = hasWrapper && hasCffiBackend

        Log.i(TAG, "curl_cffi installed: $cachedCurlInstalled (wrapper=$hasWrapper, cffi_backend=$hasCffiBackend)")
        return cachedCurlInstalled!!
    }

    /**
     * Get the installed yt-dlp version string.
     */
    fun getYtdlpVersion(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_YTDLP_VERSION, null)
    }

    /** usr/lib dir of the bundled python package (contains libpython3.X.so) — for the script-tool runner. */
    fun pythonUsrLibDir(context: Context): File? {
        val pythonDir = File(context.noBackupFilesDir, "youtubedl-android/packages/python")
        val libDir = File(pythonDir, "usr/lib")
        return if (libDir.isDirectory) libDir else null
    }

    private fun findSitePackages(pythonDir: File): File? {
        // Look for python3.X/site-packages/
        val libDir = File(pythonDir, "usr/lib")
        if (!libDir.exists()) return null

        // Find python3.X directory
        val pythonLibDir = libDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("python3.") }
            ?.maxByOrNull { it.name }

        if (pythonLibDir == null) {
            Log.w(TAG, "No python3.X directory found in ${libDir.absolutePath}")
            return null
        }

        val sitePackages = File(pythonLibDir, "site-packages")
        return if (sitePackages.exists() && sitePackages.isDirectory) {
            Log.d(TAG, "Found site-packages: ${sitePackages.absolutePath}")
            sitePackages
        } else {
            Log.w(TAG, "site-packages directory not found at ${sitePackages.absolutePath}")
            null
        }
    }

    private fun installCurlCffi(context: Context, sitePackages: File): Boolean {
        // arm64-only wheel; on x86_64 (emulator/CI) extracting ~20MB can never load.
        if (android.os.Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) return false
        return try {
            // Only install for arm64 (the only Android wheel available)
            val assetName = "python-packages/curl_cffi_arm64.zip"
            val destDir = File(sitePackages, "curl_cffi")

            // Replace the previous package before extracting the bundled version.
            try { if (destDir.exists()) destDir.deleteRecursively() } catch (_: Throwable) { }
            try { if (File(sitePackages, "certifi").exists()) File(sitePackages, "certifi").deleteRecursively() } catch (_: Throwable) { }

            context.assets.open(assetName).use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(sitePackages, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                val buf = ByteArray(8192)
                                var len: Int
                                while (zis.read(buf).also { len = it } > 0) {
                                    fos.write(buf, 0, len)
                                }
                            }
                            // Preserve executable bit for .so files
                            if (entry.name.endsWith(".so")) {
                                outFile.setExecutable(true, true)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            val soFile = File(destDir, "_wrapper.abi3.so")
            if (soFile.isFile && File(sitePackages, CFFI_BACKEND).isFile) {
                Log.i(TAG, "curl_cffi $BUNDLED_CURL_CFFI_VERSION installed (${soFile.length() / 1024}KB)")
                true
            } else {
                Log.e(TAG, "curl_cffi wrapper or CFFI backend missing after extraction")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install curl_cffi", e)
            false
        }
    }

    private fun updateYtdlp(context: Context): Boolean {
        return try {
            val ytdlpDir = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp")
            val ytdlpFile = File(ytdlpDir, "yt-dlp")

            context.assets.open("python-packages/yt-dlp").use { input ->
                FileOutputStream(ytdlpFile).use { output ->
                    input.copyTo(output)
                }
            }
            ytdlpFile.setExecutable(true, true)
            ytdlpFile.setReadable(true, true)

            Log.i(TAG, "yt-dlp updated to $BUNDLED_YTDLP_VERSION (${ytdlpFile.length() / 1024}KB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update yt-dlp", e)
            false
        }
    }
}
