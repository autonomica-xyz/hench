package net.nostr.hench.cvm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.nostr.hench.tools.ContentBlock
import net.nostr.hench.tools.ToolResult
import uniffi.contextvm_ffi.IncomingRequest

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// ─── Request contexts (PLAN §3) ─────────────────────────────────────────

/**
 * Transport-only context for every incoming request. Always parseable, even
 * for malformed/non-tool requests, so the worker can always reply
 * method-not-found or invalid_params. Built before any tool-field parsing.
 */
data class IncomingContext(
    val transportEventId: String,
    val clientPubkey: String,
    val jsonRpcId: String,
    val method: String,
) {
    companion object {
        fun from(req: IncomingRequest): IncomingContext {
            val msg = req.message
            return IncomingContext(
                transportEventId = req.eventId,
                clientPubkey = req.clientPubkey,
                jsonRpcId = msg.id,
                method = msg.method,
            )
        }
    }
}

/**
 * Tool-specific context, built only after method == "tools/call" is confirmed.
 * Holds the hashed idempotency key (PLAN §4: hash of a canonical JSON array,
 * not a concatenated string — JSON-RPC ids can contain delimiters).
 */
data class ToolCallContext(
    val incoming: IncomingContext,
    val toolName: String,
    val args: JsonObject,
    val argsHash: String,
    val canonicalInvocationId: String? = null,
) {
    val transportEventId: String get() = incoming.transportEventId
    val clientPubkey: String get() = incoming.clientPubkey
    val jsonRpcId: String get() = incoming.jsonRpcId

    val idempotencyKey: String get() =
        sha256Hex("""["${incoming.clientPubkey}","${incoming.jsonRpcId}","$toolName","$argsHash"]""")
}

/** Parse tool fields from an incoming tools/call request; null if malformed. */
fun parseToolCall(req: IncomingRequest, ictx: IncomingContext): ToolCallContext? {
    val payload = runCatching { json.parseToJsonElement(req.message.payloadJson).jsonObject }.getOrNull() ?: return null
    val params = payload["params"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
    val name = params["name"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: return null
    val args = params["arguments"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
    return ToolCallContext(
        incoming = ictx,
        toolName = name,
        args = args,
        argsHash = sha256Hex(canonical(args)),
        canonicalInvocationId = req.canonicalInvocationId,
    )
}

// ─── MCP JSON-RPC payload builders ──────────────────────────────────────

/** MCP success response envelope string. */
fun mcpSuccess(jsonRpcId: String, result: JsonObject): String = encode(buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", jsonRpcId)
    put("result", result)
})

fun mcpError(jsonRpcId: String, code: Int, message: String): String = encode(buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", jsonRpcId)
    put("error", buildJsonObject {
        put("code", code)
        put("message", message)
    })
})

fun methodNotFound(jsonRpcId: String, method: String): String =
    mcpError(jsonRpcId, -32601, "Method not found: $method")

fun invalidParams(jsonRpcId: String, message: String): String =
    mcpError(jsonRpcId, -32602, message)

/** MCP tools/list result object. */
fun toolsListResult(toolsArray: JsonArray): JsonObject = buildJsonObject {
    put("tools", toolsArray)
}

/** Convert a ToolResult to the MCP result object (content + isError). */
fun ToolResult.toMcpResultJson(): JsonObject = buildJsonObject {
    put("content", buildJsonArray { content.forEach { add(it.toJson()) } })
    put("isError", isError)
}

private fun ContentBlock.toJson(): JsonObject = when (this) {
    is ContentBlock.Text -> buildJsonObject {
        put("type", "text")
        put("text", text)
    }
    is ContentBlock.ResourceLink -> buildJsonObject {
        put("type", "resource_link")
        put("uri", uri)
        put("name", name)
        put("mimeType", mimeType)
        description?.let { put("description", it) }
    }
}

private fun encode(el: JsonElement): String =
    json.encodeToString(JsonElement.serializer(), el)

// ─── canonical JSON + sha256 (for idempotency keys) ─────────────────────

/** Canonical JSON string: sorted keys, compact. For hashing only. */
fun canonical(el: JsonElement): String =
    json.encodeToString(JsonElement.serializer(), sortKeys(el))

private fun sortKeys(el: JsonElement): JsonElement = when (el) {
    is JsonObject -> buildJsonObject {
        el.entries.sortedBy { it.key }.forEach { (k, v) -> put(k, sortKeys(v)) }
    }
    is JsonArray -> buildJsonArray { el.forEach { add(sortKeys(it)) } }
    else -> el
}

private val sha = java.security.MessageDigest.getInstance("SHA-256")

fun sha256Hex(s: String): String {
    val digest = synchronized(sha) { sha.digest(s.toByteArray(Charsets.UTF_8)) }
    return digest.joinToString("") { "%02x".format(it) }
}
