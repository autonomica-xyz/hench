package net.nostr.hench

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import net.nostr.hench.runtime.PythonPackageManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class PythonPackageManagerInstrumentedTest {
    @Test
    fun successfulCurlInstallDoesNotMarkFailedYtdlpUpdateComplete() {
        assumeTrue(Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val app = ApplicationProvider.getApplicationContext<Context>()
        val name = "python-upgrade-${UUID.randomUUID()}"
        val root = File(app.cacheDir, name)
        val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val isolated = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
        try {
            File(root, "youtubedl-android/packages/python/usr/lib/python3.14/site-packages").mkdirs()
            val blockedTarget = File(root, "youtubedl-android/yt-dlp/yt-dlp")
            assertTrue(blockedTarget.mkdirs())
            assertTrue(PythonPackageManager.install(isolated))
            assertNull(PythonPackageManager.getYtdlpVersion(isolated))
            assertTrue(blockedTarget.delete())
            assertTrue(PythonPackageManager.install(isolated))
            assertFalse(PythonPackageManager.install(isolated))
        } finally {
            root.deleteRecursively()
            app.deleteSharedPreferences(name)
        }
    }
}
