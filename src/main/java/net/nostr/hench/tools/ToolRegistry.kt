package net.nostr.hench.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Registry of published tools. Multi-tool-capable; CvmServer publishes whatever
 * is registered here in tools/list. v1 only registers download_media until a
 * second concrete tool exists (PLAN §12).
 */
class ToolRegistry(vararg tools: Tool) {
    private val byName: Map<String, Tool> = tools.associateBy { it.name }
    val all: List<Tool> get() = byName.values.toList().sortedBy { it.name }

    fun get(name: String): Tool? = byName[name]

    /** MCP tools/list result payload (the `tools` array). */
    fun toolsListJson(): JsonArray = buildJsonArray {
        for (t in all) {
            add(buildJsonObject {
                put("name", t.name)
                put("description", t.description)
                put("inputSchema", t.inputSchema())
            })
        }
    }

    /** MCP tools/list announcement payload for cvm_server.publish_tools. */
    fun toolsPublishJson(): String =
        Json.encodeToString(JsonArray.serializer(), toolsListJson())
}
