package net.nostr.hench.tools.script

import kotlinx.serialization.json.Json
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScriptToolManifestTest {

    @Test
    fun parsesValidManifest() {
        val m = ScriptToolManifest.parse(
            """
            {"name":"my_tool","description":"does things","inputSchema":{"type":"object","required":["x"]},
             "priceSats":5,"output":"text","timeoutSecs":60}
            """.trimIndent()
        )
        assertNotNull(m)
        assertEquals("my_tool", m!!.name)
        assertEquals(5L, m.priceSats)
        assertEquals("text", m.output)
        assertEquals(60L, m.timeoutSecs)
    }

    @Test
    fun rejectsBadNamesPricesAndOutputs() {
        assertNull(ScriptToolManifest.parse("""{"name":"Bad Name","description":"d","inputSchema":{},"priceSats":5}"""))
        assertNull(ScriptToolManifest.parse("""{"name":"ok","description":"","inputSchema":{},"priceSats":5}"""))
        assertNull(ScriptToolManifest.parse("""{"name":"ok","description":"d","inputSchema":{},"priceSats":0}"""))
        assertNull(ScriptToolManifest.parse("""{"name":"ok","description":"d","inputSchema":{},"priceSats":5,"output":"xml"}"""))
        assertNull(ScriptToolManifest.parse("""{"name":"ok","description":"d","priceSats":5}"""))
        assertNull(ScriptToolManifest.parse("not json"))
    }

    @Test
    fun clampsTimeoutIntoRange() {
        val m = ScriptToolManifest.parse(
            """{"name":"t","description":"d","inputSchema":{},"priceSats":1,"timeoutSecs":99999}"""
        )
        assertEquals(600L, m!!.timeoutSecs)
    }
}

class ToolPackageManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun pkg(root: File, name: String, manifest: String = defaultManifest(name)): File =
        File(root, name).apply {
            mkdirs()
            File(this, "manifest.json").writeText(manifest)
            File(this, "script.py").writeText("print('hi')\n")
        }

    private fun defaultManifest(name: String) =
        """{"name":"$name","description":"test $name","inputSchema":{"type":"object"},"priceSats":2}"""

    @Test
    fun discoversPackagesFromBothRoots() {
        val internal = tmp.newFolder("internal")
        val external = tmp.newFolder("external")
        pkg(internal, "alpha")
        pkg(external, "beta")

        val found = ToolPackageManager.discoverDirs(internal, external)

        assertEquals(setOf("alpha", "beta"), found.map { it.manifest.name }.toSet())
    }

    @Test
    fun internalWinsOnNameCollision() {
        val internal = tmp.newFolder("internal")
        val external = tmp.newFolder("external")
        pkg(external, "dup", """{"name":"dup","description":"from external","inputSchema":{},"priceSats":2}""")
        pkg(internal, "dup", """{"name":"dup","description":"from internal","inputSchema":{},"priceSats":3}""")

        val found = ToolPackageManager.discoverDirs(internal, external)

        assertEquals(1, found.size)
        assertEquals("from internal", found.single().manifest.description)
    }

    @Test
    fun skipsInvalidManifestsAndIncompleteDirs() {
        val internal = tmp.newFolder("internal")
        val external = null
        pkg(internal, "good")
        pkg(internal, "bad_manifest", """{"name":"bad manifest","description":"x","inputSchema":{},"priceSats":1}""")
        File(internal, "no_files").mkdirs() // dir with neither manifest nor script

        val found = ToolPackageManager.discoverDirs(internal, external)

        assertEquals(listOf("good"), found.map { it.manifest.name })
    }
}

class EnabledToolsSettingsTest {

    @Test
    fun defaultsMeanAllBuiltInsAndNoScripts() {
        val settings = net.nostr.hench.settings.WorkerSettings()
        assertTrue(settings.disabledTools.isEmpty())
        assertTrue(settings.enabledScriptTools.isEmpty())
    }

    @Test
    fun twoSetsHaveDistinctSemantics() {
        // disabling a built-in must not imply enabling scripts and vice versa
        val s1 = net.nostr.hench.settings.WorkerSettings(disabledTools = setOf("web_fetch"))
        val s2 = net.nostr.hench.settings.WorkerSettings(enabledScriptTools = setOf("hello_script"))
        assertTrue(s1.enabledScriptTools.isEmpty())
        assertTrue(s2.disabledTools.isEmpty())
    }
}
