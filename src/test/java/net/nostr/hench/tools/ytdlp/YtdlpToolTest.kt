package net.nostr.hench.tools.ytdlp

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.nostr.hench.auth.CookieStore
import net.nostr.hench.cvm.IncomingContext
import net.nostr.hench.cvm.ToolCallContext
import net.nostr.hench.cvm.canonical
import net.nostr.hench.cvm.sha256Hex
import net.nostr.hench.settings.WorkerSettings
import net.nostr.hench.tools.ContentBlock
import net.nostr.hench.tools.ToolResult
import net.nostr.hench.worker.DeliveryMode
import net.nostr.hench.worker.InMemoryPaymentSessionStore
import net.nostr.hench.worker.JobArtifact
import net.nostr.hench.worker.JobExecutor
import net.nostr.hench.worker.JobRecord
import net.nostr.hench.worker.JobRequest
import net.nostr.hench.worker.PaymentSessionStoreI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtdlpToolTest {

    @Test
    fun happyPathDownloadsAndWritesCompletedRecord() = runTest {
        val executor = FakeExecutor(JobArtifact("https://blossom.test/x.mp4", "abc", 12345))
        val (tool, store) = buildTool(executor)
        val ctx = ctxFor()

        val result = tool.execute(ctx) {}

        assertFalse("must not be an error: $result", result.isError)
        assertEquals(2, result.content.size)
        val body = (result.content[0] as ContentBlock.Text).text
        assertTrue(body.contains("https://blossom.test/x.mp4"))
        assertTrue(body.contains("\"mimeType\":\"video/mp4\""))
        assertEquals(1, executor.callCount)

        val record = store.all().single()
        assertEquals(JobRecord.STATUS_COMPLETED, record.status)
        assertEquals(ctx.idempotencyKey, record.idempotencyKey)
        assertEquals("canon-123", record.canonicalInvocationId)
        assertNull(record.quoteJson)
        assertNull(record.invoiceJson)
        assertTrue(record.terminalResultJson?.contains("https://blossom.test/x.mp4") == true)
    }

    @Test
    fun stalledExecutorReturnsTimeoutAndWritesFailedRecord() = runTest {
        val executor = FakeExecutor(JobArtifact("u", "s", 1), delayMs = 60_000L)
        val (tool, store) = buildTool(executor, executionBudgetSecs = 1L)
        val ctx = ctxFor()

        val result = tool.execute(ctx) {}

        assertTrue(result.isError)
        val body = (result.content[0] as ContentBlock.Text).text
        assertTrue(body.contains("TIMEOUT"))

        val record = store.all().single()
        assertEquals(JobRecord.STATUS_FAILED, record.status)
        assertTrue(record.terminalResultJson?.contains("TIMEOUT") == true)
    }

    @Test
    fun socketTimeoutIsPassedToExecutor() = runTest {
        val executor = FakeExecutor(JobArtifact("u", "s", 1))
        val (tool, store) = buildTool(executor, executionBudgetSecs = 123L)
        val ctx = ctxFor()

        tool.execute(ctx) {}

        assertEquals(123L, executor.lastSocketTimeoutSecs)
    }

    @Test
    fun downloadFailureIsMappedToAuthFailure() = runTest {
        val executor = FakeExecutor(throwable = RuntimeException("HTTP Error 403: login required"))
        val (tool, _) = buildTool(executor)
        val ctx = ctxFor()

        val result = tool.execute(ctx) {}

        assertTrue(result.isError)
        assertTrue((result.content[0] as ContentBlock.Text).text.contains("AUTH_FAILURE"))
    }

    @Test
    fun unsupportedUrlIsRejectedBeforeExecution() = runTest {
        val executor = FakeExecutor(JobArtifact("u", "s", 1))
        val (tool, store) = buildTool(executor)
        val args = buildJsonObject { put("url", "file:///etc/passwd"); put("mode", "full") }
        val ctx = ToolCallContext(
            IncomingContext("e", "c", "1", "tools/call"),
            "download_media", args, sha256Hex(canonical(args)),
        )

        val result = tool.execute(ctx) {}

        assertTrue(result.isError)
        assertTrue((result.content[0] as ContentBlock.Text).text.contains("UNSUPPORTED_URL"))
        assertEquals(0, executor.callCount)
        assertTrue(store.all().isEmpty())
    }

    // --- helpers ------------------------------------------------------------

    private fun buildTool(
        executor: FakeExecutor,
        executionBudgetSecs: Long = 600L,
    ): Pair<YtdlpTool, PaymentSessionStoreI> {
        val store = InMemoryPaymentSessionStore()
        val settings = WorkerSettings(
            enabled = true,
            relayUrls = listOf("wss://relay.test"),
            blossomUrls = listOf("https://blossom.test"),
            workerSecretKeyHex = "a".repeat(64),
            acceptAudio = true,
            acceptVideo = true,
            audioPriceMsatPerHour = 60_000,
            videoPriceMsatPerHour = 60_000,
        )
        @Suppress("UNCHECKED_CAST")
        val nullContext = null as android.content.Context?
        val tool = YtdlpTool(
            context = nullContext,
            settings = settings,
            cookieStore = null,
            store = store,
            executorProvider = { executor },
            executionBudgetSecs = executionBudgetSecs,
        )
        return tool to store
    }

    private fun ctxFor(canonicalInvocationId: String? = "canon-123"): ToolCallContext {
        val args = buildJsonObject {
            put("url", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            put("mode", "full")
        }
        return ToolCallContext(
            incoming = IncomingContext("evt-1", "clientA", "1", "tools/call"),
            toolName = "download_media",
            args = args,
            argsHash = sha256Hex(canonical(args)),
            canonicalInvocationId = canonicalInvocationId,
        )
    }
}

private class FakeExecutor(
    private val result: JobArtifact = JobArtifact("", "", 0),
    private val throwable: Throwable? = null,
    private val delayMs: Long = 0L,
) : JobExecutor {
    var callCount = 0
        private set
    var lastSocketTimeoutSecs: Long? = null
        private set

    override suspend fun execute(request: JobRequest): JobArtifact {
        callCount++
        lastSocketTimeoutSecs = request.socketTimeoutSecs
        if (delayMs > 0L) delay(delayMs)
        throwable?.let { throw it }
        return result
    }
}
