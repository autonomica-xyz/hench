package net.nostr.hench.tools

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun textResultIsNotError() {
        val r = ToolResult.text("hello")
        assertFalse(r.isError)
        assertEquals(1, r.content.size)
        assertTrue(r.content.first() is ContentBlock.Text)
    }

    @Test
    fun errorResultCarriesCodeAndIsFlagged() {
        val r = ToolResult.errorJson("AUTH_FAILURE", "session expired")
        assertTrue(r.isError)
        val body = json.parseToJsonElement((r.content.first() as ContentBlock.Text).text).toString()
        assertTrue(body.contains("AUTH_FAILURE"))
        assertTrue(body.contains("session expired"))
    }

    @Test
    fun resourceLinkBlockCarriesUriNameMime() {
        val block = ContentBlock.ResourceLink(
            uri = "https://blossom.test/x.mp4",
            name = "x.mp4",
            mimeType = "video/mp4",
            description = "d",
        )
        // Spec-correct block for a *hosted* artifact: resource_link, not resource.
        assertEquals("https://blossom.test/x.mp4", block.uri)
        assertEquals("video/mp4", block.mimeType)
    }
}
