package net.weero.measix.pilot.data.ai.mcp

import net.weero.measix.pilot.data.enterprise.ManagedSnapshotRequired

import net.weero.measix.pilot.data.configuration.ConfigurationScope

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ToolExecutionFailure
import net.weero.measix.pilot.data.ai.tools.ToolRuntimeInfrastructureException
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
    RESULT_PROCESSING,
    UNKNOWN,
}

private class McpProtocolResultException(cause: Throwable) : RuntimeException(cause)

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
        scope: ConfigurationScope,
        lease: McpInvocationLease,
        toolName: String,
        args: JsonObject,
        onArtifactCreated: (OwnedArtifact) -> Unit,
        onResolvedTool: suspend (JsonObject) -> Unit = {},
    ): McpInvocationOutcome {
        val createdArtifacts = mutableListOf<OwnedArtifact>()
        var receivedResult = false
        return try {
            val result = lease.client.callTool(
                CallToolRequest(CallToolRequestParams(name = toolName, arguments = args)),
                RequestOptions(timeout = 120.seconds),
            )
            receivedResult = true
            if (result.isError == true) {
                val remoteContent = result.content.map { content ->
                    when (content) {
                        is TextContent -> UIMessagePart.Text(content.text)
                        is ImageContent -> UIMessagePart.Text("[Remote MCP error image omitted]")
                        else -> UIMessagePart.Text("[Remote MCP error non-text content omitted]")
                    }
                }
                return McpInvocationOutcome.Failed(
                    kind = McpInvocationFailureKind.REMOTE,
                    failure = McpToolFailureProjector.project(
                        kind = McpToolFailureKind.REMOTE_ERROR,
                        structuredContent = result.structuredContent,
                        remoteContent = remoteContent,
                    ),
                )
            }
            result.meta?.get("com.measix/resolvedTool")?.let { value ->
                val metadata = value as? JsonObject
                    ?: throw McpProtocolResultException(IllegalArgumentException("invalid_gateway_result_metadata"))
                val fields = setOf("gatewayToolId", "name", "status", "requestId")
                if (!fields.all { (metadata[it] as? kotlinx.serialization.json.JsonPrimitive)?.let { value ->
                    value.isString && value.content.isNotBlank() && value.content.length <= 256
                } == true }) throw McpProtocolResultException(IllegalArgumentException("invalid_gateway_result_metadata"))
                onResolvedTool(JsonObject(metadata.filterKeys { it in fields }))
            }
            val projected = result.content.map {
                when (it) {
                    is TextContent -> UIMessagePart.Text(it.text)
                    is ImageContent -> convertImageContentToFilePart(scope, it, createdArtifacts::add)
                    else -> try {
                        UIMessagePart.Text(JsonInstant.encodeToString(it))
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        throw McpProtocolResultException(error)
                    }
                }
            }
            createdArtifacts.forEach(onArtifactCreated)
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
        } catch (timeout: TimeoutCancellationException) {
            discardCreatedArtifacts(createdArtifacts, "MCP tool result rollback", timeout)
            currentCoroutineContext().ensureActive()
            val kind = if (receivedResult) {
                McpInvocationFailureKind.RESULT_PROCESSING
            } else {
                McpInvocationFailureKind.TIMEOUT
            }
            McpInvocationOutcome.Failed(
                kind,
                McpToolFailureProjector.project(
                    kind = if (receivedResult) {
                        McpToolFailureKind.RESULT_PROCESSING_FAILED
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
            ManagedSnapshotRequired.find(error)?.let { throw it }
            net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeProblemException.find(error)?.let { throw it }
            if (error is ToolRuntimeInfrastructureException) throw error
            val kind = when {
                receivedResult && error is McpProtocolResultException -> McpInvocationFailureKind.PROTOCOL
                receivedResult -> McpInvocationFailureKind.RESULT_PROCESSING
                error is McpException && error.code == RPCError.ErrorCode.REQUEST_TIMEOUT -> McpInvocationFailureKind.TIMEOUT
                error is McpException && error.code == RPCError.ErrorCode.CONNECTION_CLOSED -> McpInvocationFailureKind.CONNECTION
                error is McpException -> McpInvocationFailureKind.REMOTE
                McpProtocolFailureClassifier.isUnauthorized(error) -> McpInvocationFailureKind.AUTHORIZATION
                McpProtocolFailureClassifier.isConnectionError(error) -> McpInvocationFailureKind.CONNECTION
                else -> McpInvocationFailureKind.UNKNOWN
            }
            val projectedKind = when (kind) {
                McpInvocationFailureKind.REMOTE -> McpToolFailureKind.REMOTE_ERROR
                McpInvocationFailureKind.AUTHORIZATION -> McpToolFailureKind.AUTHORIZATION_REQUIRED
                McpInvocationFailureKind.PROTOCOL -> McpToolFailureKind.PROTOCOL_INCOMPATIBLE
                McpInvocationFailureKind.RESULT_PROCESSING -> McpToolFailureKind.RESULT_PROCESSING_FAILED
                else -> McpToolFailureKind.OUTCOME_UNKNOWN
            }
            McpInvocationOutcome.Failed(
                kind,
                McpToolFailureProjector.project(
                    kind = projectedKind,
                    remoteMessage = if (projectedKind == McpToolFailureKind.REMOTE_ERROR) {
                        (error as? McpException)?.message
                    } else null,
                    cause = error,
                ),
            )
        }
    }

    private suspend fun convertImageContentToFilePart(
        scope: ConfigurationScope,
        image: ImageContent,
        onArtifactCreated: (OwnedArtifact) -> Unit,
    ): UIMessagePart.Image {
        val bytes: ByteArray
        val detectedMime: String
        try {
            require(image.data.isNotEmpty() && image.data.length <= MAX_MCP_IMAGE_BASE64_CHARS) {
                "MCP image payload exceeds the size limit"
            }
            bytes = Base64.decode(image.data)
            require(bytes.size <= GeneratedMediaStore.MAX_IMAGE_BYTES) { "MCP image payload exceeds the size limit" }
            require(ImageMime.isAcceptedImage(bytes)) { "MCP image payload is invalid" }
            detectedMime = requireNotNull(ImageMime.sniff(bytes)) { "MCP image MIME cannot be detected" }
        } catch (error: IllegalArgumentException) {
            throw McpProtocolResultException(error)
        }
        val ext = android.webkit.MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(detectedMime) ?: "bin"
        val owned = artifactStore.createFromBytes(
            scope = scope,
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
            if (error is CancellationException) throw error
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
