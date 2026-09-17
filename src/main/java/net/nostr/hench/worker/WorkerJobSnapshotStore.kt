package net.nostr.hench.worker

import android.content.Context
import androidx.core.content.edit

data class WorkerJobSnapshot(
    val requestId: String,
    val status: String,
    val amountMsat: Long,
    val detail: String?,
    val updatedAtMs: Long,
) {
    val requestShortId: String get() = requestId.take(8)
    val amountSats: Long get() = amountMsat / 1_000L
}

class WorkerJobSnapshotStore(context: Context) {
    private val prefs = context.getSharedPreferences("worker_job_snapshot", Context.MODE_PRIVATE)

    fun load(): WorkerJobSnapshot? {
        val requestId = prefs.getString(KEY_REQUEST_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        return WorkerJobSnapshot(
            requestId = requestId,
            status = prefs.getString(KEY_STATUS, null).orEmpty(),
            amountMsat = prefs.getLong(KEY_AMOUNT_MSAT, 0L),
            detail = prefs.getString(KEY_DETAIL, null),
            updatedAtMs = prefs.getLong(KEY_UPDATED_AT_MS, 0L),
        )
    }

    fun save(requestId: String, status: String, amountMsat: Long, detail: String? = null) {
        prefs.edit {
            putString(KEY_REQUEST_ID, requestId)
            putString(KEY_STATUS, status)
            putLong(KEY_AMOUNT_MSAT, amountMsat.coerceAtLeast(0L))
            putString(KEY_DETAIL, detail)
            putLong(KEY_UPDATED_AT_MS, System.currentTimeMillis())
        }
    }

    fun saveProgress(requestId: String, status: String, detail: String? = null) {
        val existing = load()
        save(
            requestId = requestId,
            status = status,
            amountMsat = existing?.takeIf { it.requestId == requestId }?.amountMsat ?: 0L,
            detail = detail,
        )
    }

    companion object {
        private const val KEY_REQUEST_ID = "request_id"
        private const val KEY_STATUS = "status"
        private const val KEY_AMOUNT_MSAT = "amount_msat"
        private const val KEY_DETAIL = "detail"
        private const val KEY_UPDATED_AT_MS = "updated_at_ms"
    }
}
