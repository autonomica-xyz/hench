package net.nostr.hench.blossom

import java.io.File
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import net.nostr.hench.tools.ytdlp.UrlSafety

private const val UPLOAD_ATTEMPTS = 3
private const val UPLOAD_RETRY_DELAY_MS = 1_000L

/**
 * Upload [file] to the first of [servers] that both ACKs and serves it,
 * trying each server [UPLOAD_ATTEMPTS] times before falling through.
 */
suspend fun uploadVerified(
    file: File,
    servers: List<String>,
    clientFor: (server: String) -> BlossomClient,
): BlossomUploadResult {
    var lastError: Throwable? = null
    for (server in servers) {
        repeat(UPLOAD_ATTEMPTS) { attempt ->
            try {
                // HttpBlossomClient validates the server-returned blob URL
                // (public http(s) only) and DNS-guards every request hop.
                val result = clientFor(server).upload(file)
                // Verify-after-upload: some Blossom frontends ACK a PUT the
                // storage layer never actually serves (seen on-device with
                // an image/png). Treat an unservable blob as a failed upload
                // and fall through to the next server.
                if (!blobIsServed(result.url)) {
                    throw IllegalStateException("server acked upload but blob is not servable: ${result.url}")
                }
                return result
            } catch (t: TimeoutCancellationException) {
                throw t
            } catch (t: Throwable) {
                lastError = t
                if (attempt + 1 < UPLOAD_ATTEMPTS) delay(UPLOAD_RETRY_DELAY_MS)
            }
        }
    }
    throw lastError ?: IllegalStateException("no Blossom servers configured")
}

private fun blobIsServed(url: String): Boolean = runCatching {
    okhttp3.OkHttpClient.Builder()
        .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .dns(UrlSafety.publicOnlyDns())
        // Redirects refused outright: the DNS hook never sees literal-IP
        // redirect targets, so following 3xx would bypass the guard.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
        .newCall(okhttp3.Request.Builder().url(url).head().build())
        .execute()
        .use { it.isSuccessful }
}.getOrDefault(false)
