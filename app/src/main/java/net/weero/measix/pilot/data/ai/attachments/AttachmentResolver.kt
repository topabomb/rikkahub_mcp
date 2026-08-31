package net.weero.measix.pilot.data.ai.attachments

import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeImageBytes
import kotlinx.coroutines.CancellationException
import net.weero.measix.pilot.data.files.ArtifactImageReadResult
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.LocalToolPath

sealed interface AttachmentResolveResult {
    data class Success(val parts: List<UIMessagePart.Image>) : AttachmentResolveResult
    data class Failure(val reason: String) : AttachmentResolveResult
}

/** Model-facing paths are resolved by the file owner, never by scanning conversation history. */
class AttachmentResolver(private val artifactStore: ArtifactStore) {
    /**
     * Inspection consumes an in-memory snapshot. No file URI outlives the owner's read protection,
     * and no temporary artifact is created merely to let a Provider read existing image content.
     */
    suspend fun readImages(paths: List<String>): AttachmentResolveResult {
        if (!validPaths(paths)) return invalidPaths()
        return artifactStore.withUploadImages(paths) { result ->
            when (result) {
                is ArtifactImageReadResult.Failure -> result.toAttachmentFailure()
                is ArtifactImageReadResult.Success -> try {
                    AttachmentResolveResult.Success(
                        result.images.map { image -> UIMessagePart.Image(url = encodeImageBytes(image.bytes).base64) },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    AttachmentResolveResult.Failure(AttachmentFailureReasons.UNSUPPORTED_ATTACHMENT_TYPE)
                }
            }
        }
    }

    /**
     * Delegation preserves local file identity. The consumer commits the Child message while
     * the existing artifacts are retained; success, failure and cancellation all release pins.
     */
    suspend fun <T> withImages(
        paths: List<String>,
        consume: suspend (AttachmentResolveResult) -> T,
    ): T {
        if (paths.isEmpty()) return consume(AttachmentResolveResult.Success(emptyList()))
        if (!validPaths(paths)) return consume(invalidPaths())
        return artifactStore.withUploadImages(paths.distinct()) { result ->
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
