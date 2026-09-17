package net.nostr.hench.service

object WorkerForegroundServiceContract {
    const val ACTION_START = "net.nostr.hench.worker.START"
    const val ACTION_STOP = "net.nostr.hench.worker.STOP"
    const val ACTION_RELOAD_SETTINGS = "net.nostr.hench.worker.RELOAD_SETTINGS"
    const val ACTION_WORKER_STATE_CHANGED = "net.nostr.hench.worker.STATE_CHANGED"
    const val NOTIFICATION_CHANNEL_ID = "worker"
    const val NOTIFICATION_ID = 1001
}
