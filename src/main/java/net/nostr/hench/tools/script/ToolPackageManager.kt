package net.nostr.hench.tools.script

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Discovers user-installable tool packages. A package is a directory
 * containing manifest.json + script.py, in either:
 *
 *   <internalFiles>/tools/<name>/    — installed via the Settings zip import
 *   <externalFiles>/tools/<name>/    — dropped via USB / file manager
 *                                     (/Android/data/net.nostr.hench/files/tools/)
 *
 * Both are scanned; internal wins on name collision. Script tools are NEVER
 * auto-enabled — the user toggles them on the Advertised tools settings page.
 */
object ToolPackageManager {

    data class DiscoveredTool(
        val dir: File,
        val manifest: ScriptToolManifest,
        val script: File,
    )

    fun discover(context: Context): List<DiscoveredTool> =
        discoverDirs(internalToolsDir(context), externalToolsDir(context))

    /** Pure scan of candidate roots (testable without Android Context). */
    fun discoverDirs(internal: File, external: File?): List<DiscoveredTool> {
        val byName = linkedMapOf<String, DiscoveredTool>()
        for (root in listOfNotNull(internal, external)) {
            val dirs = root.listFiles { f -> f.isDirectory } ?: continue
            for (dir in dirs.sortedBy { it.name }) {
                val manifestFile = File(dir, "manifest.json")
                val script = File(dir, "script.py")
                if (!manifestFile.isFile || !script.isFile) continue
                val manifest = runCatching { manifestFile.readText() }
                    .getOrNull()
                    ?.let { ScriptToolManifest.parse(it) }
                if (manifest == null) {
                    runCatching { Log.w(TAG, "invalid manifest in ${dir.absolutePath}, skipping") }
                    continue
                }
                // First root wins on collision (internal is scanned first).
                byName.putIfAbsent(manifest.name, DiscoveredTool(dir, manifest, script))
            }
        }
        return byName.values.toList()
    }

    fun internalToolsDir(context: Context): File =
        File(context.filesDir, "tools").apply { mkdirs() }

    fun externalToolsDir(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, "tools").apply { mkdirs() } }

    /**
     * Install a package from a zip stream: each top-level directory in the zip
     * (or the zip's single root directory) is treated as one package. Returns
     * the installed package dirs. Rejects path traversal entries.
     */
    fun installFromZip(context: Context, zipStream: java.io.InputStream): List<File> =
        installFromZip(internalToolsDir(context), zipStream)

    /** Install into [target] — split from the Context overload so the traversal guard is unit-testable. */
    fun installFromZip(target: File, zipStream: java.io.InputStream): List<File> {
        val installed = mutableListOf<File>()
        val targetPath = target.canonicalFile.path
        ZipInputStream(zipStream.buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                if (name.contains("..") || File(name).isAbsolute) continue
                val out = File(target, name)
                // Also reject paths that escape through an existing symlink.
                if (out.canonicalFile.path != targetPath && !out.canonicalFile.path.startsWith("$targetPath/")) {
                    continue
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                    installed.add(out.parentFile!!)
                }
            }
        }
        return installed.distinct()
    }

    private const val TAG = "ToolPackageManager"
}
