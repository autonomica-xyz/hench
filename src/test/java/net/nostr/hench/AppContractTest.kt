package net.nostr.hench

import org.junit.Assert.assertEquals
import org.junit.Test

class AppContractTest {
    @Test
    fun usesHenchApplicationId() {
        assertEquals("net.nostr.hench", AppContract.APPLICATION_ID)
    }
}
