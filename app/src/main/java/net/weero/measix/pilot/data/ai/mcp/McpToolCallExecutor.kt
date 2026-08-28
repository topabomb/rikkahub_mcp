package net.weero.measix.pilot.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.ImageMime
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.requireDiscarded
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.utils.JsonInstant
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

internal data class McpInvocationLease(
    val client: Client,
    val serverName: String,
    val generation: Long,
)

internal enum class McpInvocationFailureKind {
    REMOTE,
    TIMEOUT,
    AUTHORIZATION,
    CONNECTION,
    PROTOCOL,
    UNKNOWN,
}

internal sealed interface McpInvocationOutcome {
    data class Succeeded(val content: List<UIMessagePart>) : McpInvocationOutcome

    data class Failed(
        val kind: McpInvocationFailureKind,
        val failure: ToolExecutionFailure,
    ) : McpInvocationOutcome
}

/** Executes an already-admitted invocation. It never reads Settings or mutates connection state. */
internal class McpToolCallExecutor(
    private val artifactStore: ArtifactStore,
) {
    suspend fun execute(
        lease: McpInvocationLease,
        toolName: String,
        args: JsonObject,
        onArtifactCreated: (OwnedArtifact) -> Unit,
    ): McpInvocationOutcome {
        val createdArtifacts = mutableListOf<OwnedArtifact>()
        var receivedResult = false
        return try {
            val result = lease.client.callTool(
                CallToolRequest(CallToolRequestParams(name = toolName, arguments = args)),
                RequestOptions(timeout = 120.seconds),
            )
            receivedResult = true
            val projected = result.content.map {
                when (it) {
                    is TextContent -> UIMessagePart.Text(it.text)
                    is ImageContent -> convertImageContentToFilePart(it, createdArtifacts::add)
                    else -> UIMessagePart.Text(JsonInstant.encodeToString(it))
                }
            }
            createdArtifacts.forEach(onArtifactCreated)
            if (result.isError == true) {
                McpInvocationOutcome.Failed(
                    kind = McpInvocationFailureKind.REMOTE,
                    failure = McpToolFailureProjector.project(
                        kind = McpToolFailureKind.REMOTE_ERROR,
                        structuredContent = result.structuredContent,
                        remoteContent = projected,
                    ),
                )
            } else {
                McpInvocationOutcome.Succeeded(
                    buildList {
                        addAll(projected)
                        result.structuredContent?.let { structured ->
                            add(
                                UIMessagePart.Text(
                                    buildJsonObject { put("structured_content", structured) }.toString()
                                )
                            )
                        }
                    }
                )
            }
        } catch (timeout: TimeoutCancellationException) {
            discardCreatedArtifacts(createdArtifacts, "MCP tool result rollback", timeout)
            val kind = if (receivedResult) {
                McpInvocationFailureKind.PROTOCOL
            } else {
                McpInvocationFailureKind.TIMEOUT
            }
            McpInvocationOutcome.Failed(
                kind,
                McpToolFailureProjector.project(
                    kind = if (receivedResult) {
                        McpToolFailureKind.PROTOCOL_INCOMPATIBLE
                    } else {
                        McpToolFailureKind.OUTCOME_UNKNOWN
                    },
                    cause = timeout,
                ),
            )
        } catch (cancelled: CancellationException) {
            discardCreatedArtifacts(createdArtifacts, "MCP tool result rollback", cancelled)
            throw cancelled
        } catch (error: Throwable) {
            discardCreatedArtifacts(createdArtifacts, "MCP tool result rollback", error)
            val kind = when {
                receivedResult -> McpInvocationFailureKind.PROTOCOL
                error is McpException -> McpInvocationFailureKind.REMOTE
                McpProtocolFailureClassifier.isUnauthorized(error) -> McpInvocationFailureKind.AUTHORIZATION
                McpProtocolFailureClassifier.isConnectionError(error) -> McpInvocationFailureKind.CONNECTION
                else -> McpInvocationFailureKind.UNKNOWN
            }
            val projectedKind = when (kind) {
                McpInvocationFailureKind.REMOTE -> McpToolFailureKind.REMOTE_ERROR
                McpInvocationFailureKind.AUTHORIZATION -> McpToolFailureKind.AUTHORIZATION_REQUIRED
                McpInvocationFailureKind.PROTOCOL -> McpToolFailureKind.PROTOCOL_INCOMPATIBLE
                else -> McpToolFailureKind.OUTCOME_UNKNOWN
            }
            McpInvocationOutcome.Failed(
                kind,
                McpToolFailureProjector.project(
                    kind = projectedKind,
                    remoteMessage = (error as? McpException)?.message,
                    cause = error,
                ),
            )
        }
    }

    private suspend fun convertImageContentToFilePart(
        image: ImageContent,
        onArtifactCreated: (OwnedArtifact) -> Unit,
    ): UIMessagePart.Image {
        require(image.data.isNotEmpty() && image.data.length <= MAX_MCP_IMAGE_BASE64_CHARS) {
            "MCP image payload exceeds the size limit"
        }
        val bytes = Base64.decode(image.data)
        require(bytes.size <= GeneratedMediaStore.MAX_IMAGE_BYTES) { "MCP image payload exceeds the size limit" }
        require(ImageMime.isAcceptedImage(bytes)) { "MCP image payload is invalid" }
        val detectedMime = requireNotNull(ImageMime.sniff(bytes)) { "MCP image MIME cannot be detected" }
        val ext = android.webkit.MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(detectedMime) ?: "bin"
        val owned = artifactStore.createFromBytes(
            bytes = bytes,
            displayName = "mcp_image.$ext",
            mimeType = detectedMime,
            origin = ArtifactOrigin.SYSTEM,
        )
        return try {
            net.weero.measix.pilot.data.ai.attachments.AttachmentRefs.ensureAttachmentRef(
                UIMessagePart.Image(url = owned.uri.toString()),
            ).also { onArtifactCreated(owned) } as UIMessagePart.Image
        } catch (error: Throwable) {
            discardCreatedArtifacts(listOf(owned), "MCP image projection rollback", error)
            throw error
        }
    }

    private suspend fun discardCreatedArtifacts(
        artifacts: List<OwnedArtifact>,
        operation: String,
        primary: Throwable,
    ) = withContext(NonCancellable) {
        artifacts.asReversed().forEach { owned ->
            try {
                artifactStore.discardUnpublished(owned).requireDiscarded(operation)
            } catch (cleanupFailure: Throwable) {
                primary.addSuppressed(cleanupFailure)
            }
        }
    }

    companion object {
        private const val MAX_MCP_IMAGE_BASE64_CHARS = (GeneratedMediaStore.MAX_IMAGE_BYTES * 4 / 3) + 4
    }
}
