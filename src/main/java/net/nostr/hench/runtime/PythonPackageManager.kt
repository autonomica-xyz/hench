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
    private var importTestResult: Boolean? = null
    private const val TAG = "PythonPackageManager"
    private const val PREFS_NAME = "python_packages"
    private const val KEY_CURL_CFFI_VERSION = "curl_cffi_version"
    private const val KEY_YTDLP_VERSION = "ytdlp_version"

    /** Current curl_cffi version bundled as asset */
    private const val BUNDLED_CURL_CFFI_VERSION = "0.15.0-patch4"

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
            installed = installCurlCffi(context, sitePackages) || installed
            if (installed) {
                prefs.edit { putString(KEY_CURL_CFFI_VERSION, BUNDLED_CURL_CFFI_VERSION) }
            }
        } else {
            // Verify the .so exists (in case user cleared data)
            if (!File(sitePackages, "curl_cffi/_wrapper.abi3.so").exists()) {
                installed = installCurlCffi(context, sitePackages) || installed
            }
        }

        // Update yt-dlp binary
        val currentYtdlpVersion = prefs.getString(KEY_YTDLP_VERSION, null)
        if (currentYtdlpVersion != BUNDLED_YTDLP_VERSION) {
            installed = updateYtdlp(context) || installed
            if (installed) {
                prefs.edit { putString(KEY_YTDLP_VERSION, BUNDLED_YTDLP_VERSION) }
            }
        }

        return installed
    }

    /**
     * Check if curl_cffi is installed and its native .so is loadable.
     * Tests the actual import to verify the linker can resolve dependencies.
     */
    fun isCurlCffiImportable(context: Context): Boolean {
        // Cache the result to avoid repeated import attempts
        if (importTestResult != null) return importTestResult!!

        val pythonDir = File(context.noBackupFilesDir, "youtubedl-android/packages/python")
        val sitePackages = findSitePackages(pythonDir) ?: run {
            importTestResult = false
            return false
        }

        // Check: does the _wrapper.abi3.so file exist and is there a _cffi_backend?
        val hasWrapper = File(sitePackages, "curl_cffi/_wrapper.abi3.so").exists()
        val hasCffiBackend = File(sitePackages, "_cffi_backend.cpython-312.so").exists()
            || File(sitePackages, "_cffi_backend.cpython-313-aarch64-linux-android.so").exists()
        importTestResult = hasWrapper && hasCffiBackend

        Log.i(TAG, "curl_cffi importable: $importTestResult (wrapper=$hasWrapper, cffi_backend=$hasCffiBackend)")
        return importTestResult!!
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
        return try {
            // Only install for arm64 (the only Android wheel available)
            val assetName = "python-packages/curl_cffi_arm64.zip"
            val destDir = File(sitePackages, "curl_cffi")

            // Clean previous installation (may race with concurrent installs)
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

            // Verify the .so was extracted
            val soFile = File(destDir, "_wrapper.abi3.so")
            if (soFile.exists()) {
                Log.i(TAG, "curl_cffi $BUNDLED_CURL_CFFI_VERSION installed (${soFile.length() / 1024}KB)")
                true
            } else {
                Log.e(TAG, "curl_cffi _wrapper.abi3.so not found after extraction")
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

            // Patch yt-dlp's curl_cffi version check to accept 0.15.x.
            // yt-dlp 2026.03.17 only allows curl_cffi <= 0.14.x, but 0.15.0 is the
            // first version with an official Android arm64 wheel. The API is compatible.
            patchCurlCffiVersionCheck(ytdlpFile)

            Log.i(TAG, "yt-dlp updated to $BUNDLED_YTDLP_VERSION (${ytdlpFile.length() / 1024}KB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update yt-dlp", e)
            false
        }
    }

    /**
     * Patch the yt-dlp zipapp to accept curl_cffi 0.15.x.
     * Replaces `< (0, 15)` with `< (0, 16)` in the _curlcffi.py module.
     * The yt-dlp binary is a Python zipapp so we can do a byte-level replacement.
     */
    private fun patchCurlCffiVersionCheck(ytdlpFile: File) {
        try {
            val oldBytes = "< (0, 15)".toByteArray()
            val newBytes = "< (0, 16)".toByteArray()
            check(oldBytes.size == newBytes.size) { "Replacement must be same length" }

            val bytes = ytdlpFile.readBytes()
            val index = bytes.indexOfSequence(oldBytes)
            if (index >= 0) {
                System.arraycopy(newBytes, 0, bytes, index, newBytes.size)
                ytdlpFile.writeBytes(bytes)
                Log.i(TAG, "Patched yt-dlp curl_cffi version check: (0,15) -> (0,16)")
            } else {
                Log.d(TAG, "yt-dlp curl_cffi version check: pattern not found (already patched or different version)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to patch yt-dlp curl_cffi version check", e)
        }
    }
}

/** Find a byte sequence within a ByteArray. */
private fun ByteArray.indexOfSequence(target: ByteArray): Int {
    if (target.isEmpty()) return 0
    for (i in 0..(size - target.size)) {
        if (this.sliceArray(i until i + target.size).contentEquals(target)) return i
    }
    return -1
}
