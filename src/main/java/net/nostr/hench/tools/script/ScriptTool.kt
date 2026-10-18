package net.nostr.hench.tools.script

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.nostr.hench.cvm.ToolCallContext
import net.nostr.hench.tools.ContentBlock
import net.nostr.hench.tools.Tool
import net.nostr.hench.tools.ToolResult
import net.nostr.hench.tools.ytdlp.UrlSafety
import net.nostr.hench.worker.JobRecord
import net.nostr.hench.worker.PaymentSessionStoreI
import java.io.File

/**
 * Manifest for a user-installable script tool package:
 *
 *   <toolsDir>/<name>/manifest.json
 *   <toolsDir>/<name>/script.py
 *
 * manifest.json:
 * {
 *   "name": "my_tool",                    // [a-z0-9_]+, the MCP tool name
 *   "description": "...",                 // shown in tools/list
 *   "inputSchema": { ... JSON Schema ... },
 *   "priceSats": 5,                       // flat per-call price, whole sats
 *   "output": "text" | "file",            // stdout text | file at argv[2]
 *   "timeoutSecs": 120                    // optional, default 120, max 600
 * }
 *
 * Contract with the script:
 *   argv[1] = JSON-encoded tool arguments (from the MCP call)
 *   argv[2] = output file path (only when output == "file"; upload the file you write here)
 *   stdout  = the tool result text (when output == "text")
 *   exit 0 = success, anything else = failure (stderr included in the error)
 */
data class ScriptToolManifest(
    val name: String,
    val description: String,
    val inputSchema: JsonElement,
    val priceSats: Long,
    val output: String,
    val timeoutSecs: Long,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val NAME = Regex("[a-z0-9_]+")

        fun parse(text: String): ScriptToolManifest? = runCatching {
            val obj = json.parseToJsonElement(text).jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return null
            val description = obj["description"]?.jsonPrimitive?.content ?: return null
            val schema = obj["inputSchema"] ?: return null
            val price = obj["priceSats"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
            val output = obj["output"]?.jsonPrimitive?.content ?: "text"
            val timeout = obj["timeoutSecs"]?.jsonPrimitive?.content?.toLongOrNull()?.coerceIn(5, 600) ?: 120
            require(NAME.matches(name)) { "tool name must be [a-z0-9_]+" }
            require(description.isNotBlank()) { "description required" }
            require(price in 1..100_000) { "priceSats must be 1..100000" }
            require(output == "text" || output == "file") { "output must be text|file" }
            (schema as? JsonObject)?.get("required")?.let { required ->
                require(required.jsonArray.all { (it as? JsonPrimitive)?.content != null }) { "required must be strings" }
            }
            ScriptToolManifest(name, description, schema, price, output, timeout)
        }.getOrNull()
    }
}

/**
 * A Tool backed by a user-installed Python script package. Runs on the bundled
 * embedded CPython as a subprocess (killable timeout — see PythonScriptRunner).
 * Trust model: the script has full app-process access; installing a package is
 * like installing an app. Only enable packages you trust.
 */
class ScriptTool(
    private val context: Context,
    private val manifest: ScriptToolManifest,
    private val scriptFile: File,
    private val store: PaymentSessionStoreI,
    private val blossomUploader: (File) -> net.nostr.hench.blossom.BlossomUploadResult,
) : Tool {

    override val name = manifest.name
    override val description = manifest.description
    override val priceRangeSats: LongRange = manifest.priceSats..manifest.priceSats
    override fun quoteSats(args: JsonObject): Long = manifest.priceSats

    override fun inputSchema(): JsonElement = manifest.inputSchema

    override fun preflight(args: JsonObject): UrlSafety.Result.Reject? {
        // Required-field check derived from the manifest schema. Scripts own
        // any deeper validation.
        val required = (manifest.inputSchema as? JsonObject)
            ?.get("required")?.jsonArray
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
        for (key in required) {
            if (!args.containsKey(key)) {
                return UrlSafety.Result.Reject("INVALID_PARAMS", "missing required argument '$key'")
            }
        }
        return null
    }

    override suspend fun execute(
        ctx: ToolCallContext,
        onProgress: (progressJson: String) -> Unit,
    ): ToolResult {
        preflight(ctx.args)?.let { return ToolResult.errorJson(it.code, it.reason) }

        val argsJson = JSON.encodeToString(JsonObject.serializer(), ctx.args)
        val workDir = File(context.cacheDir, "script-${ctx.idempotencyKey.take(12)}").apply { mkdirs() }
        try {
            val outFile = if (manifest.output == "file") File(workDir, "output.bin") else null
            val cmdArgs = buildList {
                add(argsJson)
                outFile?.let { add(it.absolutePath) }
            }
            val r = PythonScriptRunner.run(context, scriptFile, cmdArgs, manifest.timeoutSecs * 1_000)
            when {
                r.timedOut -> return finish(ctx, ToolResult.errorJson("TIMEOUT", "script exceeded ${manifest.timeoutSecs}s"))
                r.exitCode != 0 -> return finish(
                    ctx,
                    ToolResult.errorJson("SCRIPT_ERROR", "script exited ${r.exitCode}: ${r.stderr.trim().take(500)}"),
                )
                manifest.output == "file" -> {
                    if (outFile == null || !outFile.isFile || outFile.length() == 0L) {
                        return finish(ctx, ToolResult.errorJson("SCRIPT_ERROR", "script produced no output file"))
                    }
                    val uploaded = blossomUploader(outFile)
                    val meta = buildJsonObject {
                        put("url", uploaded.url)
                        put("sha256", uploaded.sha256)
                        put("sizeBytes", uploaded.sizeBytes)
                    }
                    val ok = ToolResult(
                        content = listOf(
                            ContentBlock.Text(JSON.encodeToString(JsonObject.serializer(), meta)),
                            ContentBlock.ResourceLink(
                                uri = uploaded.url,
                                name = uploaded.sha256.take(16),
                                mimeType = "application/octet-stream",
                                description = "Produced by hench script tool ${manifest.name}",
                            ),
                        ),
                    )
                    return finish(ctx, ok)
                }
                else -> return finish(ctx, ToolResult.text(r.stdout.trim().take(MAX_TEXT_CHARS)))
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    private suspend fun finish(ctx: ToolCallContext, result: ToolResult): ToolResult {
        runCatching {
            store.put(
                JobRecord(
                    idempotencyKey = ctx.idempotencyKey,
                    canonicalInvocationId = ctx.canonicalInvocationId,
                    jobRequestJson = JSON.encodeToString(JsonObject.serializer(), ctx.args),
                    argsJson = JSON.encodeToString(JsonObject.serializer(), ctx.args),
                    status = JobRecord.STATUS_COMPLETED,
                    terminalResultJson = JSON.encodeToString(result),
                ),
            )
        }
        return result
    }

    companion object {
        private const val MAX_TEXT_CHARS = 100_000
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
