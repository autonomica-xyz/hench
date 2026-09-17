package net.nostr.hench.tools.ytdlp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.nostr.hench.settings.WorkerSettings
import net.nostr.hench.worker.DeliveryMode

/**
 * Shared preflight validation for the download_media tool. Runs before any
 * invoice is created (PaymentGateHandler) and again at execution time
 * (YtdlpTool) as defense in depth. Reuses the same URL-safety, platform,
 * mode, and Blossom checks in both places.
 */
object YtdlpPreflight {

    /** Returns a [UrlSafety.Result.Reject] if the request should not proceed, or null if OK. */
    fun validate(args: JsonObject, settings: WorkerSettings): UrlSafety.Result.Reject? {
        val url = args.string("url") ?: return UrlSafety.Result.Reject("INVALID_PARAMS", "missing url")
        val modeStr = args.string("mode") ?: return UrlSafety.Result.Reject("INVALID_PARAMS", "missing mode")
        val mode = when (modeStr.lowercase()) {
            "audio" -> DeliveryMode.AUDIO_ONLY
            "full" -> DeliveryMode.FULL
            else -> return UrlSafety.Result.Reject("UNSUPPORTED_URL", "mode must be 'audio' or 'full'")
        }
        val blossomOverride = args.string("blossom_server")

        UrlSafety.checkSource(url, settings.allowedPlatforms).reject()?.let { return it }
        if (blossomOverride != null) {
            UrlSafety.checkBlossom(blossomOverride, settings.blossomUrls).reject()?.let { return it }
        }
        if (!settings.supports(mode) || !settings.acceptsUrl(url)) {
            return UrlSafety.Result.Reject("UNSUPPORTED_URL", "worker does not accept this url/mode")
        }
        return null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun UrlSafety.Result.reject(): UrlSafety.Result.Reject? = this as? UrlSafety.Result.Reject
}
