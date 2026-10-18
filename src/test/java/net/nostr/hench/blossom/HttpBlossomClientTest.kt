package net.nostr.hench.blossom

import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary tests for the upload-response guards: the server controls the
 * returned blob URL and may attempt redirects — both must be refused before
 * any follow-up request can be aimed at an internal service.
 */
class HttpBlossomClientTest {

    /** Literal public IP: deterministic — no DNS dependency in the JVM suite. */
    private val server = "https://93.184.216.34"

    @Test
    fun uploadRejectsBlobUrlToPrivateAddress() = runTest {
        // MEDIUM-2 regression: a malicious Blossom server must not turn the
        // upload response into a follow-up request to an internal service.
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body(
                        """{"url": "http://169.254.169.254/latest/meta-data"}"""
                            .toResponseBody("application/json".toMediaType()),
                    )
                    .build()
            }
            .build()
        val file = File.createTempFile("blossom-upload", ".txt")
        try {
            file.writeText("hello")
            try {
                HttpBlossomClient(serverUrl = server, http = http).upload(file)
                org.junit.Assert.fail("private blob URL must be rejected")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty().contains("unusable blob URL"))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun uploadRefusesRedirectResponse() = runTest {
        // A 3xx must never be followed — a literal-IP redirect target bypasses
        // the DNS guard entirely.
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .header("Location", "http://169.254.169.254/upload")
                    .body("".toResponseBody(null))
                    .build()
            }
            .build()
        val file = File.createTempFile("blossom-upload", ".txt")
        try {
            file.writeText("hello")
            try {
                HttpBlossomClient(serverUrl = server, http = http).upload(file)
                org.junit.Assert.fail("redirect must be refused")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty().contains("redirect"))
            }
        } finally {
            file.delete()
        }
    }
}
