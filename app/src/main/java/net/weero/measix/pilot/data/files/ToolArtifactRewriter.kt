package net.weero.measix.pilot.data.files

import android.util.Log
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.utils.JsonInstant

data class ToolArtifactRewriteResult(
    val output: List<UIMessagePart>,
    val metadata: JsonObject?,
    val ownedArtifact: OwnedArtifact?,
)

class ToolArtifactRewriter(
    private val filesDir: File,
    private val artifactStore: ArtifactStore,
    private val json: Json = JsonInstant,
) {
    suspend fun rewriteToolOutput(
        output: List<UIMessagePart>,
        metadata: JsonObject?,
    ): ToolArtifactRewriteResult {
        val sourceRef = metadata?.let { decodeArtifactRef(it) }
        if (sourceRef == null) {
            return ToolArtifactRewriteResult(output, metadata, null)
        }
        val materialized = artifactStore.materialize(sourceRef)
        if (materialized == null) {
            Log.w(TAG, "rewrite skipped: source artifact missing or outside sandbox ${sourceRef.relativePath}")
            return ToolArtifactRewriteResult(unreadableOutput(output), removeArtifactRef(metadata), null)
        }
        val sourceFile = materialized.file(filesDir)
        val copied = artifactStore.copyFilePreservingOrigin(
            source = sourceFile,
            mimeType = materialized.mimeType,
            displayName = sourceFile.name,
        )
        try {
            val copiedRef = copied.localRef
            val rewrittenOutput = output.map { part ->
                when (part) {
                    is UIMessagePart.Image -> part.copy(url = copiedRef.fileUri(filesDir))
                    is UIMessagePart.Text -> part.copy(text = rewriteFilePathJson(part.text, copiedRef))
                    else -> part
                }
            }
            return ToolArtifactRewriteResult(
                output = rewrittenOutput,
                metadata = encodeArtifactRef(metadata, copiedRef),
                ownedArtifact = copied,
            )
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                try {
                    artifactStore.discardUnpublished(copied).requireDiscarded("tool artifact rewrite rollback")
                } catch (cleanupFailure: Throwable) {
                    error.addSuppressed(cleanupFailure)
                }
            }
            throw error
        }
    }

    suspend fun materializeToolOutput(
        output: List<UIMessagePart>,
        metadata: JsonObject?,
    ): List<UIMessagePart> {
        val ref = metadata?.let { decodeArtifactRef(it) } ?: return output
        val materialized = artifactStore.materialize(ref)
        if (materialized == null) {
            return unreadableOutput(output)
        }
        return output.map { part ->
            when (part) {
                is UIMessagePart.Image -> part.copy(url = materialized.fileUri(filesDir))
                is UIMessagePart.Text -> part.copy(text = rewriteFilePathJson(part.text, materialized))
                else -> part
            }
        }
    }

    fun decodeArtifactRef(metadata: JsonObject): LocalArtifactRef? {
        val raw = metadata[ARTIFACT_KEY] ?: return null
        return runCatching {
            json.decodeFromJsonElement(LocalArtifactRef.serializer(), raw)
        }.getOrNull()?.takeIf { it.version == LocalArtifactRef.CURRENT_VERSION }
    }

    fun encodeArtifactRef(existing: JsonObject?, ref: LocalArtifactRef): JsonObject {
        val base = existing?.toMutableMap() ?: mutableMapOf()
        base[ARTIFACT_KEY] = json.encodeToJsonElement(LocalArtifactRef.serializer(), ref)
        return JsonObject(base)
    }

    private fun removeArtifactRef(existing: JsonObject?): JsonObject? {
        if (existing == null || ARTIFACT_KEY !in existing) return existing
        return JsonObject(existing.toMutableMap().apply { remove(ARTIFACT_KEY) })
    }

    private fun rewriteFilePathJson(text: String, ref: LocalArtifactRef): String {
        val toolPath = ref.toolPath() ?: return text
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return text
        val obj = element as? JsonObject ?: return text
        val fileObj = obj["file"] as? JsonObject ?: return text
        val rewrittenFile = buildJsonObject {
            fileObj.forEach { (key, value) ->
                if (key == "path") {
                    put("path", JsonPrimitive(toolPath))
                } else {
                    put(key, value)
                }
            }
        }
        return JsonObject(obj.toMutableMap().apply { put("file", rewrittenFile) }).toString()
    }

    private fun unreadableOutput(output: List<UIMessagePart>): List<UIMessagePart> =
        output.mapNotNull { part ->
            when (part) {
                is UIMessagePart.Text -> part.copy(text = markUnreadablePath(part.text))
                is UIMessagePart.Image -> null
                else -> part
            }
        }

    private fun markUnreadablePath(text: String): String {
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return text
        val obj = element as? JsonObject ?: return text
        val rewritten = obj.toMutableMap()
        val fileObj = (obj["file"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        fileObj.remove("path")
        fileObj["available"] = JsonPrimitive(false)
        fileObj["reason"] = JsonPrimitive("artifact_missing")
        rewritten["file"] = JsonObject(fileObj)
        return JsonObject(rewritten).toString()
    }

    companion object {
        const val ARTIFACT_KEY = "artifact"
        private const val TAG = "ToolArtifactRewriter"
    }
}
