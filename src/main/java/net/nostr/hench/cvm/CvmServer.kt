package net.nostr.hench.cvm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.nostr.hench.settings.WorkerSettings
import net.nostr.hench.tools.ToolRegistry
import net.nostr.hench.tools.ToolResult
import net.nostr.hench.wallet.InvoiceStatus
import net.nostr.hench.wallet.LightningWallet
import net.nostr.hench.wallet.PaymentInvoice
import net.nostr.hench.worker.JobRecord
import net.nostr.hench.worker.PaymentRecord
import net.nostr.hench.worker.PaymentSessionStoreI
import java.util.concurrent.ConcurrentHashMap
import uniffi.contextvm_ffi.EncryptionMode
import uniffi.contextvm_ffi.FfiException
import uniffi.contextvm_ffi.GiftWrapMode
import uniffi.contextvm_ffi.Keys
import uniffi.contextvm_ffi.PaymentInteractionPolicy
import uniffi.contextvm_ffi.Server
import uniffi.contextvm_ffi.ServerConfig
import uniffi.contextvm_ffi.ErrorCode

/** A CVM job lifecycle event, surfaced to the UI via the service. */
enum class JobStatus { WAITING_FOR_PAYMENT, PROCESSING, COMPLETED, FAILED }
data class JobUpdate(
    val idempotencyKey: String,
    val status: JobStatus,
    val amountMsat: Long = 0L,
    val detail: String? = null,
)

/**
 * Thin Kotlin wrapper over libcontextvm_ffi's Server channel. Owns the recv
 * loop, the CEP-8 payment-gate drain loop, dispatches tools/call to the
 * ToolRegistry, and consults the PaymentSessionStore for replay/idempotency.
 *
 * All FFI calls are blocking (the FFI drives its own tokio runtime); call from
 * a background scope only, never the UI thread.
 */
class CvmServer(
    private val context: Context,
    private val secretKeyHex: String,
    private val relayUrls: List<String>,
    private val registry: ToolRegistry,
    private val store: PaymentSessionStoreI,
    private val wallet: LightningWallet,
    private val settings: WorkerSettings,
    private val serverName: String? = null,
    private val announce: Boolean = true,
    private val devRelayOnly: Boolean = false,
    private val paymentTtlCapSecs: ULong = 300UL,
    private val executionBudgetSecs: ULong = 600UL,
    private val onJobUpdate: (JobUpdate) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var server: Server? = null
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun start() {
        val s = Server(
            keys = Keys.fromSecretKey(secretKeyHex),
            config = ServerConfig(
                relayUrls = relayUrls,
                encryptionMode = EncryptionMode.OPTIONAL,
                giftWrapMode = GiftWrapMode.OPTIONAL,
                isAnnouncedServer = announce,
                serverName = serverName ?: "Hench",
                serverVersion = "0.1.0",
                serverPicture = null,
                serverAbout = "Hench worker: a phone that works for AI agents (media, web, scripts) over ContextVM",
                serverWebsite = null,
                allowedPubkeys = emptyList(),
                sessionTimeoutSecs = null,
                cleanupIntervalSecs = 300UL,
                excludedCapabilities = emptyList(),
                maxSessions = 100UL,
                requestTimeoutSecs = null,
                relayListUrls = relayUrls,
                bootstrapRelayUrls = relayUrls,
                publishRelayList = true,
                profileMetadataJson = null,
                paymentTtlCapSecs = paymentTtlCapSecs,
                executionBudgetSecs = executionBudgetSecs,
            ),
        )
        server = s

        val gate = FfiPaymentGateApi(s)
        val handler = PaymentGateHandler(
            paymentGate = gate,
            wallet = wallet,
            registry = registry,
            store = store,
            settings = settings,
            paymentTtlCapSecs = paymentTtlCapSecs,
            onJobUpdate = onJobUpdate,
            isExecutionInFlight = { inFlight.contains(it) },
        )

        runCatching {
            s.setPaymentInteractionPolicy(PaymentInteractionPolicy.OPTIONAL)
            s.setPricedCapabilitiesJson(handler.buildPricedCapabilitiesJson())
            // Identity marker: lets clients (hench-web) filter genuine Hench
            // workers from generic ContextVM servers / debug ghosts.
            s.setAnnouncementExtraTags("""[["agent","hench"]]""")
            s.start()
            runCatching { s.publishTools(registry.toolsPublishJson()) }
                .onFailure { Log.w(TAG, "publishTools failed: ${it.message}") }
            if (announce && !devRelayOnly) {
                runCatching { s.announce() }
                    .onFailure { Log.w(TAG, "announce failed: ${it.message}") }
                // The relay pool can report "published" before any websocket
                // handshake completes (seen on-device: announcements lost to
                // the void). Re-announcing is idempotent and cheap — repeat
                // until connections have certainly settled.
                scope.launch {
                    kotlinx.coroutines.delay(5_000)
                    runCatching { s.announce() }
                    kotlinx.coroutines.delay(15_000)
                    runCatching { s.announce() }
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "server start failed", e)
            return
        }

        scope.launch { recvLoop(s) }
        scope.launch { gateDrainLoop(gate, handler) }
    }

    fun stop() {
        scope.cancel()
        runCatching { server?.shutdown() }
        server = null
    }

    /**
     * Reconcile persisted sessions on startup. The gate is not bound at
     * startup, so this only touches the consumer's own records for
     * dashboard/crash recovery: WAITING invoices
     * already settled by the wallet become PROCESSING, Expired/Unknown become
     * FAILED, and terminal records are left as-is. Re-bind happens on the first
     * gate event for that canonical id.
     */
    suspend fun reconcile(wallet: LightningWallet) {
        for (record in store.all()) {
            when (record.status) {
                JobRecord.STATUS_WAITING -> {
                    val invoice = record.invoiceJson?.let { decodeInvoice(it) } ?: continue
                    when (wallet.lookupInvoice(invoice.id)) {
                        InvoiceStatus.Settled ->
                            store.put(record.copy(status = JobRecord.STATUS_PROCESSING))
                        InvoiceStatus.Expired,
                        InvoiceStatus.Unknown ->
                            store.put(record.copy(status = JobRecord.STATUS_FAILED))
                        InvoiceStatus.Pending -> Unit
                    }
                }
                else -> Unit
            }
        }
    }

    private suspend fun recvLoop(s: Server) {
        Log.i(TAG, "recv loop started relays=${relayUrls.joinToString()}")
        while (scope.isActive) {
            val req = try {
                s.recvTimeout(RECV_TIMEOUT_SECS)
            } catch (e: FfiException) {
                if (e.code() == ErrorCode.TIMEOUT) continue
                Log.e(TAG, "recv error code=${e.code()} msg=${runCatching { e.message() }.getOrNull()}", e)
                continue
            } catch (e: Throwable) {
                Log.e(TAG, "recv threw ${e::class.java.simpleName}: ${e.message}", e)
                continue
            }
            handleRequest(s, req)
        }
        Log.i(TAG, "recv loop exited")
    }

    private fun handleRequest(s: Server, req: uniffi.contextvm_ffi.IncomingRequest) {
        val ictx = IncomingContext.from(req)
        scope.launch {
            // Same crash-safety as the gate drain loop: no per-request exception
            // (FFI send failure, codec edge) may kill the process.
            runCatching {
                when (ictx.method) {
                "notifications/initialized" -> Unit
                "tools/list" -> {
                    val result = toolsListResult(registry.toolsListJson())
                    s.sendResponse(ictx.transportEventId, mcpSuccess(ictx.jsonRpcId, result))
                }
                "tools/call" -> {
                    val tctx = parseToolCall(req, ictx) ?: run {
                        s.sendResponse(ictx.transportEventId, invalidParams(ictx.jsonRpcId, "malformed tools/call params"))
                        return@launch
                    }
                    handleToolCall(s, tctx)
                }
                "initialize", "initialized", "ping" -> {
                    s.sendResponse(ictx.transportEventId, mcpSuccess(ictx.jsonRpcId, buildJsonObject {}))
                }
                else -> {
                    s.sendResponse(ictx.transportEventId, methodNotFound(ictx.jsonRpcId, ictx.method))
                }
                }
            }.onFailure { e ->
                Log.e(TAG, "request handling failed for ${ictx.method}", e)
            }
        }
    }

    private suspend fun handleToolCall(s: Server, ctx: ToolCallContext) {
        val updateKey = ctx.canonicalInvocationId ?: ctx.idempotencyKey
        val canonical = ctx.canonicalInvocationId

        val record = canonical?.let { store.getByCanonicalId(it) } ?: store.get(ctx.idempotencyKey)
        if (record != null) {
            when (record.status) {
                JobRecord.STATUS_COMPLETED, JobRecord.STATUS_FAILED -> {
                    val cached = decodeCached(record.terminalResultJson)
                        ?: ToolResult.errorJson("DOWNLOAD", "cached result missing")
                    s.sendResponse(ctx.transportEventId, mcpSuccess(ctx.jsonRpcId, cached.toMcpResultJson()))
                    return
                }
                JobRecord.STATUS_PROCESSING -> {
                    if (inFlight.contains(updateKey)) {
                        s.sendNotification(ctx.clientPubkey, progressEnvelope(ctx.jsonRpcId, null, "PROCESSING"), ctx.transportEventId)
                        return
                    }
                }
                else -> {}
            }
        }

        if (!inFlight.add(updateKey)) {
            s.sendNotification(ctx.clientPubkey, progressEnvelope(ctx.jsonRpcId, null, "PROCESSING"), ctx.transportEventId)
            return
        }

        try {
            val tool = registry.get(ctx.toolName) ?: run {
                s.sendResponse(ctx.transportEventId, invalidParams(ctx.jsonRpcId, "unknown tool: ${ctx.toolName}"))
                return
            }

            canonical?.let { markPaymentDispatched(it) }

            val onProgress: (String) -> Unit = { payloadJson ->
                runCatching { parseProgressUpdate(updateKey, payloadJson) }
                    .getOrNull()?.let(onJobUpdate)
                runCatching {
                    s.sendNotification(ctx.clientPubkey, progressEnvelope(ctx.jsonRpcId, payloadJson), ctx.transportEventId)
                }.onFailure { Log.w(TAG, "progress notification failed: ${it.message}") }
            }

            val budgetMillis = executionBudgetSecs.toLong() * 1_000L + 5_000L
            val deadlineNanos = System.nanoTime() + budgetMillis * 1_000_000L

            val toolResult = try {
                withTimeout(budgetMillis) {
                    tool.execute(ctx, onProgress)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                val detail = "execution timed out after ${executionBudgetSecs}s"
                onJobUpdate(JobUpdate(updateKey, JobStatus.FAILED, detail = detail))
                s.sendResponse(ctx.transportEventId, mcpSuccess(ctx.jsonRpcId, ToolResult.errorJson("TIMEOUT", detail).toMcpResultJson()))
                return
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val detail = e.message ?: "tool execution failed"
                onJobUpdate(JobUpdate(updateKey, JobStatus.FAILED, detail = detail))
                s.sendResponse(ctx.transportEventId, mcpSuccess(ctx.jsonRpcId, ToolResult.errorJson("DOWNLOAD", detail).toMcpResultJson()))
                return
            }

            // Check remaining budget before the blocking FFI send.
            // ponytail: blocking FFI send cannot be preempted; bounded by the FFI's internal send behavior.
            val remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remainingMillis <= 0) {
                val detail = "execution timed out after ${executionBudgetSecs}s"
                onJobUpdate(JobUpdate(updateKey, JobStatus.FAILED, detail = detail))
                s.sendResponse(ctx.transportEventId, mcpSuccess(ctx.jsonRpcId, ToolResult.errorJson("TIMEOUT", detail).toMcpResultJson()))
                return
            }

            if (toolResult.isError) {
                s.sendResponse(ctx.transportEventId, mcpSuccess(ctx.jsonRpcId, toolResult.toMcpResultJson()))
            } else {
                // Persist the paid amount on the terminal record so the dashboard
                // and WorkerStats show real earnings.
                val payment = canonical?.let { store.getPayment(it) }
                val amountMsat = payment?.amountSats?.times(1_000L)
                    ?: store.get(ctx.idempotencyKey)?.invoiceJson?.let { decodeInvoice(it)?.amountMsat }
                    ?: 0L
                val fresh = store.get(ctx.idempotencyKey)
                if (fresh?.status == JobRecord.STATUS_COMPLETED) {
                    store.put(fresh.copy(amountMsat = amountMsat))
                    onJobUpdate(JobUpdate(updateKey, JobStatus.COMPLETED, amountMsat = amountMsat))
                }
                s.sendResponse(ctx.transportEventId, mcpSuccess(ctx.jsonRpcId, toolResult.toMcpResultJson()))
            }
        } finally {
            inFlight.remove(updateKey)
        }
    }

    private fun markPaymentDispatched(canonical: String) {
        val payment = store.getPayment(canonical) ?: return
        if (payment.status == PaymentRecord.STATUS_DISPATCHED) return
        if (!store.putPayment(payment.copy(status = PaymentRecord.STATUS_DISPATCHED))) {
            Log.w(TAG, "putPayment failed for DISPATCHED $canonical; continuing")
        }
    }

    private suspend fun gateDrainLoop(gate: PaymentGateApi, handler: PaymentGateHandler) {
        Log.i(TAG, "payment gate drain loop started")
        while (scope.isActive) {
            val event = try {
                gate.recvGateRequest(RECV_TIMEOUT_SECS)
            } catch (e: FfiException) {
                if (e.code() == ErrorCode.TIMEOUT) continue
                Log.e(TAG, "recvGateRequest error code=${e.code()} msg=${runCatching { e.message() }.getOrNull()}", e)
                continue
            } catch (e: Throwable) {
                Log.e(TAG, "recvGateRequest threw ${e::class.java.simpleName}: ${e.message}", e)
                continue
            }
            if (event == null) continue
            scope.launch {
                // A wallet/gate failure for ONE event must never kill the process
                // (uncaught exceptions in launched coroutines crash Android — found
                // on-device: Breez connect() with a bad API key took down the worker).
                runCatching { handler.handle(event, scope) }
                    .onFailure { e ->
                        Log.e(TAG, "gate event handling failed for ${event.canonicalInvocationId.take(12)}…", e)
                        onJobUpdate(
                            JobUpdate(
                                event.canonicalInvocationId,
                                JobStatus.FAILED,
                                detail = "payment handling failed: ${e.message ?: e::class.java.simpleName}",
                            ),
                        )
                    }
            }
        }
        Log.i(TAG, "payment gate drain loop exited")
    }

    private fun parseProgressUpdate(key: String, payload: String): JobUpdate? {
        val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
        val statusStr = obj.string("status")
        val status = when (statusStr) {
            "WAITING_FOR_PAYMENT", "payment_required" -> JobStatus.WAITING_FOR_PAYMENT
            "PROCESSING", "payment_accepted", "DOWNLOADING", "UPLOADING" -> JobStatus.PROCESSING
            "COMPLETE", "COMPLETED" -> JobStatus.COMPLETED
            "FAILED" -> JobStatus.FAILED
            else -> return null
        }
        val amount = obj.string("amount_msat")?.toLongOrNull() ?: 0L
        val detail = obj.string("invoice") ?: obj.string("detail")
        return JobUpdate(key, status, amount, detail)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun progressEnvelope(jsonRpcId: String, bolt11OrPayload: Any?, status: String? = null): String {
        val params = when (bolt11OrPayload) {
            is String -> if (bolt11OrPayload.startsWith("{")) {
                json.parseToJsonElement(bolt11OrPayload) as JsonObject
            } else {
                buildJsonObject { put("status", "WAITING_FOR_PAYMENT"); put("invoice", bolt11OrPayload) }
            }
            else -> buildJsonObject { status?.let { put("status", it) } }
        }
        return mcpNotify("notifications/progress", buildJsonObject {
            put("progressToken", jsonRpcId)
            params.entries.forEach { (k, v) -> put(k, v) }
        })
    }

    private fun mcpNotify(method: String, params: JsonObject): String = encode(buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        put("params", params)
    })

    private fun decodeCached(s: String?): ToolResult? =
        s?.let { runCatching { json.decodeFromString<ToolResult>(it) }.getOrNull() }

    private fun decodeInvoice(s: String): PaymentInvoice? =
        runCatching { json.decodeFromString<PaymentInvoice>(s) }.getOrNull()

    private fun encode(el: kotlinx.serialization.json.JsonElement): String =
        json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), el)

    companion object {
        private const val TAG = "CvmServer"
        private const val RECV_TIMEOUT_SECS = 5UL
    }
}
