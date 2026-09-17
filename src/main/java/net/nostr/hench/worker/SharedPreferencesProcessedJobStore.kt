package net.nostr.hench.worker

import android.content.Context
import androidx.core.content.edit

class SharedPreferencesProcessedJobStore(context: Context) : ProcessedJobStore {
    private val prefs = context.getSharedPreferences("processed_jobs", Context.MODE_PRIVATE)

    override fun hasProcessed(requestId: String): Boolean = prefs.getBoolean(requestId, false)

    override fun markProcessed(requestId: String) {
        prefs.edit { putBoolean(requestId, true) }
    }
}
