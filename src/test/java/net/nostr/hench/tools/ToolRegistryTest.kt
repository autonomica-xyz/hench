package net.nostr.hench.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private class EchoTool(
    override val name: String = "echo",
    override val description: String = "echoes",
) : Tool {
    override fun inputSchema() = Json.parseToJsonElement("""{"type":"object"}""")
    override suspend fun execute(
        ctx: net.nostr.hench.cvm.ToolCallContext,
        onProgress: (String) -> Unit,
    ) = ToolResult.text("ok")
}

class ToolRegistryTest {

    @Test
    fun looksUpByNameAndReturnsNullForUnknown() {
        val reg = ToolRegistry(EchoTool())
        assertNotNull(reg.get("echo"))
        assertNull(reg.get("nope"))
    }

    @Test
    fun toolsListJsonExposesNameDescriptionSchema() {
        val reg = ToolRegistry(EchoTool(name = "download_media", description = "downloads"))
        val obj = Json.parseToJsonElement(reg.toolsPublishJson()).jsonArray
        assertEquals(1, obj.size)
        val tool = obj.first().jsonObject
        assertEquals("download_media", tool["name"]!!.jsonPrimitive.content)
        assertEquals("downloads", tool["description"]!!.jsonPrimitive.content)
        assertNotNull(tool["inputSchema"])
    }
}
