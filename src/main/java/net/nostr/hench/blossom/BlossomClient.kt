package net.nostr.hench.blossom

import net.nostr.hench.nostr.NostrEventEnvelope
import net.nostr.hench.nostr.NostrEventSigner
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

interface BlossomClient {
    suspend fun upload(file: File): BlossomUploadResult
}

data class BlossomUploadResult(
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val mimeType: String,
)

class HttpBlossomClient(
    private val serverUrl: String,
    http: OkHttpClient = OkHttpClient(),
    private val authSecretKeyHex: String? = null,
    callTimeoutSecs: Long = 0L,
) : BlossomClient {
    private val http: OkHttpClient = http.newBuilder().apply {
        if (callTimeoutSecs > 0L) callTimeout(callTimeoutSecs, TimeUnit.SECONDS)
        // Request-time SSRF guard: the upload destination must not resolve
        // into private/loopback space. Redirects are DISABLED — the custom DNS
        // hook only filters hostname lookups; a literal-IP redirect target
        // never hits DNS, so a followed 3xx would bypass it.
        dns(net.nostr.hench.tools.ytdlp.UrlSafety.publicOnlyDns())
        followRedirects(false)
        followSslRedirects(false)
    }.build()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun upload(file: File): BlossomUploadResult = withContext(Dispatchers.IO) {
        // Validate the upload destination itself before a byte is sent —
        // literal-IP servers never hit the DNS hook.
        net.nostr.hench.tools.ytdlp.UrlSafety.checkAnyHttpUrl(serverUrl).let {
            if (it is net.nostr.hench.tools.ytdlp.UrlSafety.Result.Reject) {
                error("Blossom server URL rejected: ${it.reason}")
            }
        }
        val sha256 = sha256(file)
        val mime = mimeType(file)
        val body = file.asRequestBody(mime.toMediaType())
        val requestBuilder = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/upload")
            .header("X-SHA-256", sha256)
            .put(body)
        authSecretKeyHex?.trim()?.takeIf { it.isNotBlank() }?.let { secretKeyHex ->
            requestBuilder.header("Authorization", BlossomAuthorization.uploadHeader(serverUrl, sha256, secretKeyHex))
        }
        val request = requestBuilder.build()
        http.newCall(request).execute().use { response ->
            if (response.isRedirect) {
                error("Blossom server attempted redirect to ${response.header("Location")}; redirects are refused")
            }
            if (!response.isSuccessful) {
                val reason = response.header("X-Reason")?.takeIf { it.isNotBlank() }
                    ?: response.body.string().take(240).takeIf { it.isNotBlank() }
                error(
                    buildString {
                        append("Blossom upload failed HTTP ${response.code}")
                        if (reason != null) append(": ").append(reason)
                    },
                )
            }
            val text = response.body.string()
            val obj = json.parseToJsonElement(text).jsonObject
            val url = obj["url"]?.jsonPrimitive?.content ?: error("Blossom response missing url")
            // The server controls the returned URL; verify-after-upload and any
            // later fetch must only ever hit a public http(s) destination —
            // validated here at response time, and blobIsServed refuses
            // redirects outright rather than relying on DNS filtering.
            net.nostr.hench.tools.ytdlp.UrlSafety.checkAnyHttpUrl(url).let {
                if (it is net.nostr.hench.tools.ytdlp.UrlSafety.Result.Reject) {
                    error("Blossom server returned unusable blob URL: ${it.reason}")
                }
            }
            BlossomUploadResult(
                url = url,
                sha256 = obj["sha256"]?.jsonPrimitive?.content ?: sha256,
                sizeBytes = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: file.length(),
                mimeType = obj["type"]?.jsonPrimitive?.content ?: mime,
            )
        }
    }

    private fun mimeType(file: File): String = when (file.extension.lowercase()) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska" // ffmpeg merge fallback can produce .mkv
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
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

object BlossomAuthorization {
    private const val KIND_AUTH = 24242
    private const val TOKEN_TTL_SECONDS = 10 * 60L
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    fun uploadHeader(serverUrl: String, sha256: String, secretKeyHex: String): String {
        val event = NostrEventSigner.sign(
            NostrEventEnvelope(
                kind = KIND_AUTH,
                content = "Upload Blob",
                tags = listOf(
                    listOf("t", "upload"),
                    listOf("expiration", ((System.currentTimeMillis() / 1000L) + TOKEN_TTL_SECONDS).toString()),
                    listOf("server", serverDomain(serverUrl)),
                    listOf("x", sha256.lowercase()),
                ),
            ),
            secretKeyHex = secretKeyHex,
        )
        val encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.encodeToString(NostrEventSigner.toJson(event)).toByteArray())
        return "Nostr $encoded"
    }

    private fun serverDomain(serverUrl: String): String {
        val parsed = URI(serverUrl)
        return requireNotNull(parsed.host) { "Blossom server URL must include a host: $serverUrl" }
            .lowercase()
    }
}
