package net.nostr.hench.service

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkerForegroundServiceContractTest {
    @Test
    fun exposesStableServiceActions() {
        assertEquals("net.nostr.hench.worker.START", WorkerForegroundServiceContract.ACTION_START)
        assertEquals("net.nostr.hench.worker.STOP", WorkerForegroundServiceContract.ACTION_STOP)
        assertEquals("net.nostr.hench.worker.RELOAD_SETTINGS", WorkerForegroundServiceContract.ACTION_RELOAD_SETTINGS)
    }
}
