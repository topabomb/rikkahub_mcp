package net.weero.measix.pilot.data.imggen

import net.weero.measix.pilot.data.configuration.AssistantPreferenceChange
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.data.configuration.ConfigurationScope

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.service.ImageSource
import android.util.Log
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.ai.attachments.ImageMime
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.requireDiscarded

data class BackgroundUpdateResult(
    val requested: Boolean,
    val updated: Boolean,
    val reason: String? = null,
    val cleanupPending: Boolean = false,
)

internal sealed interface AssistantBackgroundTarget {
    val assistantId: ConfigurationReference
    val scope: ConfigurationScope

    data class Page(val selection: RealmSelection, override val assistantId: ConfigurationReference) : AssistantBackgroundTarget {
        override val scope get() = selection.access.scope
    }
    data class Task(val access: RealmAccess, override val assistantId: ConfigurationReference) : AssistantBackgroundTarget {
        override val scope get() = access.scope
    }
    data class Definition(override val assistantId: ConfigurationReference.User) : AssistantBackgroundTarget {
        override val scope = ConfigurationScope.Personal
    }
}

internal class AssistantBackgroundService(
    private val artifactStore: ArtifactStore,
    private val generatedMediaStore: GeneratedMediaStore,
    private val sessions: EnterpriseSessionController,
    private val recoveryGate: ApplicationRecoveryGate,
) {
    suspend fun replaceUserSelectedBackground(
        target: AssistantBackgroundTarget,
        image: ImageSource,
    ): BackgroundUpdateResult = replaceBackground(target, ArtifactOrigin.USER) {
        val bytes = image.readBytes()
        image.requireAccess()
        validatedBackground(bytes, image.displayName ?: "background")
    }

    suspend fun replaceGeneratedBackground(
        access: RealmAccess,
        assistantId: ConfigurationReference,
        mediaId: Long,
    ): BackgroundUpdateResult = replaceBackground(AssistantBackgroundTarget.Task(access, assistantId), ArtifactOrigin.GENERATED) {
        val bytes = sessions.withRealmAccess(access) { generatedMediaStore.readImage(access.scope, Math.toIntExact(mediaId)) }
        validatedBackground(bytes, "background")
    }

    private suspend fun <T> withTarget(target: AssistantBackgroundTarget, operation: suspend () -> T): T = when (target) {
        is AssistantBackgroundTarget.Page -> sessions.withSelectedRealmSelection(target.selection, operation)
        is AssistantBackgroundTarget.Task -> sessions.withRealmAccess(target.access, operation)
        is AssistantBackgroundTarget.Definition -> operation()
    }

    /** Source reads retain their own authorization; the destination is rechecked before its reference transaction. */
    private suspend fun replaceBackground(
        target: AssistantBackgroundTarget,
        origin: ArtifactOrigin,
        materialize: suspend () -> MaterializedBackground?,
    ): BackgroundUpdateResult {
        recoveryGate.awaitReady()
        val image = try {
            withTarget(target) { }
            materialize()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "failed to read background", error)
            null
        } ?: return BackgroundUpdateResult(true, false, "background_copy_failed")
        var copy: OwnedArtifact? = null
        try {
            withTarget(target) {
                copy = artifactStore.createFromBytes(target.scope, image.bytes, image.displayName,
                    image.mimeType, origin = origin)
                artifactStore.updateAssistantPreferenceReferences(target.scope, sessions.state.value,
                    target.assistantId, AssistantPreferenceChange.Background(requireNotNull(copy).uri.toString()), {
                        when (target) {
                            is AssistantBackgroundTarget.Page -> sessions.requirePublishedSelection(target.selection)
                            is AssistantBackgroundTarget.Task -> sessions.requirePublishedRealmAccess(target.access)
                            is AssistantBackgroundTarget.Definition -> Unit
                        }
                    }) { it() }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                copy?.let { discardUncommittedBackgroundCopy(it, it.uri.toString()) }?.let(cancelled::addSuppressed)
            }
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "failed to write background settings", error)
            val cleanupFailure = withContext(NonCancellable) {
                copy?.let { discardUncommittedBackgroundCopy(it, it.uri.toString()) }
            }
            return BackgroundUpdateResult(true, false, "settings_write_failed", cleanupFailure != null)
        }
        val cleanupPending = try {
            artifactStore.collectGarbage(protectionWindowMillis = 0)
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "old background cleanup deferred", error)
            true
        }
        return BackgroundUpdateResult(true, true, cleanupPending = cleanupPending)
    }

    private fun validatedBackground(
        bytes: ByteArray,
        displayName: String,
    ): MaterializedBackground? {
        if (bytes.size > GeneratedMediaStore.MAX_IMAGE_BYTES || !ImageMime.isAcceptedImage(bytes)) {
            return null
        }
        val mimeType = ImageMime.sniff(bytes) ?: return null
        val baseName = displayName.substringBeforeLast('.').ifBlank { "background" }
        return MaterializedBackground(bytes, mimeType, "$baseName.${extensionForMime(mimeType)}")
    }

    private fun extensionForMime(mimeType: String): String = when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        else -> "png"
    }

    private suspend fun discardUncommittedBackgroundCopy(
        copy: OwnedArtifact,
        newUri: String,
    ): Throwable? = try {
        artifactStore.discardUnpublished(copy).requireDiscarded(
            context = "uncommitted background $newUri",
            allowAlreadyPublished = true,
        )
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "failed to discard uncommitted background $newUri", error)
        error
    }

    companion object {
        private const val TAG = "AssistantBackgroundService"
    }
}

private data class MaterializedBackground(
    val bytes: ByteArray,
    val mimeType: String,
    val displayName: String,
)
