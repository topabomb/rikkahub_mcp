package net.weero.measix.pilot.data.enterprise

import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Synthetic network variations for isolated tests. Core shared contract cases are consumed unchanged. */
internal fun withStarterOpeningMock(raw: String): String {
    val root = Json.parseToJsonElement(raw).jsonObject
    if ("supportedSnapshotSchemaVersions" in root) {
        return JsonObject(root + ("supportedSnapshotSchemaVersions" to JsonArray(listOf(JsonPrimitive(5))))).toString()
    }
    if (root["schemaVersion"]?.jsonPrimitive?.longOrNull != 4L) return raw
    val updated = root.toMutableMap().apply { put("schemaVersion", JsonPrimitive(5)) }
    val starters = root["starters"] as? JsonArray
    if (starters != null) updated["starters"] = JsonArray(starters.map { value ->
        val starter = value.jsonObject
        JsonObject((starter - "description") + ("openingSnapshot" to starterOpeningMock()))
    })
    // A deterministic mock identity, never the hash of the original published Core v4 bytes.
    val body = JsonObject(updated - "snapshotHash").toString().toByteArray(Charsets.UTF_8)
    updated["snapshotHash"] = JsonPrimitive("sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(body).joinToString("") { "%02x".format(it) })
    return JsonObject(updated).toString()
}

internal fun starterOpeningMock(): JsonObject = buildJsonObject {
    put("format", 1)
    put("systemPrompt", "  企业任务 {{user}}\n保持原文。  ")
    putJsonArray("initialContexts") {
        addJsonObject {
            put("id", "second")
            put("content", "  第二段先出现，{{literal}} 不执行模板。\n")
        }
        addJsonObject {
            put("id", "first")
            put("content", "<context>保留字面内容</context>")
        }
    }
}
