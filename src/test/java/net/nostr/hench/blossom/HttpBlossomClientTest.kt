package net.nostr.hench.blossom

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpBlossomClientTest {
    @Test
    fun uploadSendsShaAndNostrAuthorizationHeaders() = runTest {
        val requestRef = AtomicReference<Request>()
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requestRef.set(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body(
                        """
                        {
                          "url": "https://blossom.example/blob.txt",
                          "sha256": "${request.header("X-SHA-256")}",
                          "size": 11,
                          "type": "text/plain"
                        }
                        """.trimIndent().toResponseBody("application/json".toMediaType()),
                    )
                    .build()
            }
            .build()
        val file = File.createTempFile("blossom-upload", ".txt")
        try {
            file.writeText("hello world")

            val result = HttpBlossomClient(
                serverUrl = "https://blossom.example",
                http = http,
                authSecretKeyHex = "0".repeat(63) + "1",
            ).upload(file)

            val request = requestRef.get()
            val expectedSha = sha256(file)
            assertEquals("PUT", request.method)
            assertEquals("https://blossom.example/upload", request.url.toString())
            assertEquals(expectedSha, request.header("X-SHA-256"))
            assertNotNull(request.header("Authorization"))
            assertTrue(request.header("Authorization")!!.startsWith("Nostr "))
            assertEquals(expectedSha, result.sha256)
            assertEquals("https://blossom.example/blob.txt", result.url)
        } finally {
            file.delete()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
