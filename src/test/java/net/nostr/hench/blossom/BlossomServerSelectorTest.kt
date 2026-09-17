package net.nostr.hench.blossom

import net.nostr.hench.worker.DeliveryMode
import net.nostr.hench.worker.JobRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class BlossomServerSelectorTest {
    @Test
    fun requesterSpecifiedServerOverridesWorkerDefaults() {
        val selector = BlossomServerSelector(listOf("https://worker-default.example/"))
        val request = JobRequest(
            id = "job1",
            url = "https://youtube.test/watch?v=abc",
            mode = DeliveryMode.FULL,
            durationSeconds = 60,
            blossomServerUrl = "https://requester.example/",
        )

        assertEquals("https://requester.example/", selector.serverFor(request))
    }

    @Test
    fun requesterSpecifiedServerIsTriedBeforeWorkerDefaults() {
        val selector = BlossomServerSelector(listOf("https://worker-a.example/", "https://worker-b.example/"))
        val request = JobRequest(
            id = "job1",
            url = "https://youtube.test/watch?v=abc",
            mode = DeliveryMode.FULL,
            durationSeconds = 60,
            blossomServerUrl = "https://requester.example/",
        )

        assertEquals(
            listOf("https://requester.example/", "https://worker-a.example/", "https://worker-b.example/"),
            selector.serverCandidatesFor(request),
        )
    }

    @Test
    fun cyclesThroughWorkerDefaultsWhenRequesterDoesNotSpecifyServer() {
        val selector = BlossomServerSelector(listOf("https://a.example/", "https://b.example/"))
        val request = JobRequest(
            id = "job1",
            url = "https://youtube.test/watch?v=abc",
            mode = DeliveryMode.FULL,
            durationSeconds = 60,
        )

        assertEquals("https://a.example/", selector.serverFor(request))
        assertEquals("https://b.example/", selector.serverFor(request))
        assertEquals("https://a.example/", selector.serverFor(request))
    }

    @Test
    fun candidatesRotateAcrossWorkerDefaultsWhenRequesterDoesNotSpecifyServer() {
        val selector = BlossomServerSelector(listOf("https://a.example/", "https://b.example/", "https://c.example/"))
        val request = JobRequest(
            id = "job1",
            url = "https://youtube.test/watch?v=abc",
            mode = DeliveryMode.FULL,
            durationSeconds = 60,
        )

        assertEquals(listOf("https://a.example/", "https://b.example/", "https://c.example/"), selector.serverCandidatesFor(request))
        assertEquals(listOf("https://b.example/", "https://c.example/", "https://a.example/"), selector.serverCandidatesFor(request))
    }
}
