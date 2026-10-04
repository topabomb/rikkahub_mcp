package net.weero.measix.pilot.data.ai.attachments

import net.weero.measix.pilot.data.configuration.ConfigurationScope
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeImageBytes
import kotlinx.coroutines.CancellationException
import net.weero.measix.pilot.data.files.ArtifactImageReadResult
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.LocalToolPath

sealed interface AttachmentResolveResult {
    data class Success(val parts: List<UIMessagePart.Image>) : AttachmentResolveResult
    data class Failure(val reason: String, val detail: String? = null) : AttachmentResolveResult
}

/** Model-facing paths are resolved by the file owner, never by scanning conversation history. */
class AttachmentResolver(
    private val artifactStore: ArtifactStore,
    private val remoteFetcher: SafeRemoteMediaFetcher = SafeRemoteMediaFetcher(),
) {
    /**
     * Inspection consumes an in-memory snapshot. No file URI outlives the owner's read protection,
     * and no temporary artifact is created merely to let a Provider read existing image content.
     */
    suspend fun readImages(scope: ConfigurationScope, paths: List<String>): AttachmentResolveResult {
        if (paths.size !in 1..MAX_INSPECTION_ATTACHMENTS) return invalidPaths()
        if (validPaths(paths)) return readUploadedImages(scope, paths)
        val parts = mutableListOf<UIMessagePart.Image>()
        for ((index, path) in paths.withIndex()) {
            val result = if (SafeRemoteMediaFetcher.parseHttpUrl(path) != null) {
                // A mobile client may inspect resources explicitly published on the user's LAN.
                // Loopback, link-local/metadata endpoints and unsafe redirects remain blocked.
                when (val fetched = remoteFetcher.fetch(path, allowLocalNetwork = true)) {
                    is RemoteMediaFetchResult.Failure -> AttachmentResolveResult.Failure(fetched.reason, fetched.detail)
                    is RemoteMediaFetchResult.Success -> try {
                        AttachmentResolveResult.Success(listOf(UIMessagePart.Image(url = encodeImageBytes(fetched.bytes).base64)))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { encodingFailure(error) }
                }
            } else readUploadedImages(scope, listOf(path))
            when (result) {
                is AttachmentResolveResult.Success -> parts.addAll(result.parts)
                is AttachmentResolveResult.Failure -> return result.copy(detail = "attachments[$index]: ${(result.detail ?: result.reason).trimEnd('.')}.")
            }
        }
        return AttachmentResolveResult.Success(parts)
    }

    private suspend fun readUploadedImages(scope: ConfigurationScope, paths: List<String>): AttachmentResolveResult {
        if (!validPaths(paths)) return invalidPaths()
        return artifactStore.withUploadImages(scope, paths) { result ->
            when (result) {
                is ArtifactImageReadResult.Failure -> result.toAttachmentFailure()
                is ArtifactImageReadResult.Success -> try {
                    AttachmentResolveResult.Success(
                        result.images.map { image -> UIMessagePart.Image(url = encodeImageBytes(image.bytes).base64) },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    encodingFailure(error)
                }
            }
        }
    }

    /**
     * Delegation preserves local file identity. The consumer commits the Child message while
     * the existing artifacts are retained; success, failure and cancellation all release pins.
     */
    suspend fun <T> withImages(
        scope: ConfigurationScope,
        paths: List<String>,
        consume: suspend (AttachmentResolveResult) -> T,
    ): T {
        if (paths.isEmpty()) return consume(AttachmentResolveResult.Success(emptyList()))
        if (!validPaths(paths)) return consume(invalidPaths())
        return artifactStore.withUploadImages(scope, paths.distinct()) { result ->
            consume(
                when (result) {
                    is ArtifactImageReadResult.Failure -> result.toAttachmentFailure()
                    is ArtifactImageReadResult.Success -> AttachmentResolveResult.Success(
                        result.images.map { image ->
                            AttachmentRefs.ensureAttachmentRef(UIMessagePart.Image(url = image.uri.toString()))
                                as UIMessagePart.Image
                        },
                    )
                },
            )
        }
    }

    private fun encodingFailure(error: Exception): AttachmentResolveResult.Failure {
        android.util.Log.w("AttachmentInspection", "Attachment image encoding failed", error)
        return AttachmentResolveResult.Failure(
            if (error is IllegalArgumentException) AttachmentFailureReasons.UNSUPPORTED_ATTACHMENT_TYPE
            else AttachmentFailureReasons.ATTACHMENT_READ_FAILED,
            me.rerere.ai.core.ToolErrorProtocol.boundedDetail("${error.javaClass.simpleName}: ${error.message ?: "Image encoding failed"}"),
        )
    }

    private fun validPaths(paths: List<String>): Boolean =
        paths.size in 1..MAX_INSPECTION_ATTACHMENTS && paths.all { LocalToolPath.parseUploadToolPath(it) != null }

    private fun invalidPaths() = AttachmentResolveResult.Failure(AttachmentFailureReasons.INVALID_ATTACHMENTS)

    private fun ArtifactImageReadResult.Failure.toAttachmentFailure() = AttachmentResolveResult.Failure(
        when (reason) {
            ArtifactImageReadResult.Reason.NOT_FOUND -> AttachmentFailureReasons.ATTACHMENT_NOT_FOUND
            ArtifactImageReadResult.Reason.TOO_LARGE -> AttachmentFailureReasons.ATTACHMENT_TOO_LARGE
            ArtifactImageReadResult.Reason.UNSUPPORTED_TYPE -> AttachmentFailureReasons.UNSUPPORTED_ATTACHMENT_TYPE
            ArtifactImageReadResult.Reason.READ_FAILED -> AttachmentFailureReasons.ATTACHMENT_READ_FAILED
        },
    )
}
