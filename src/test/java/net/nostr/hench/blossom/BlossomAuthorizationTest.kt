package net.nostr.hench.blossom

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlossomAuthorizationTest {
    @Test
    fun uploadHeaderContainsBUD11UploadScope() {
        val secretKeyHex = "0".repeat(63) + "1"
        val sha256 = "ABCDEF1234"

        val header = BlossomAuthorization.uploadHeader(
            serverUrl = "https://BLOSSOM.Example/some/path",
            sha256 = sha256,
            secretKeyHex = secretKeyHex,
        )

        assertTrue(header.startsWith("Nostr "))
        val tokenJson = String(Base64.getUrlDecoder().decode(header.removePrefix("Nostr ")))
        val token = Json.parseToJsonElement(tokenJson).jsonObject
        val tags = token["tags"]!!.jsonArray.map { tag ->
            tag.jsonArray.map { it.jsonPrimitive.content }
        }

        assertEquals("24242", token["kind"]!!.jsonPrimitive.content)
        assertEquals(
            "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            token["pubkey"]!!.jsonPrimitive.content,
        )
        assertTrue(tags.contains(listOf("t", "upload")))
        assertTrue(tags.contains(listOf("server", "blossom.example")))
        assertTrue(tags.contains(listOf("x", sha256.lowercase())))
        assertTrue(tags.any { it.firstOrNull() == "expiration" && it.getOrNull(1)?.toLongOrNull() != null })
        assertEquals(64, token["id"]!!.jsonPrimitive.content.length)
        assertEquals(128, token["sig"]!!.jsonPrimitive.content.length)
    }
}
