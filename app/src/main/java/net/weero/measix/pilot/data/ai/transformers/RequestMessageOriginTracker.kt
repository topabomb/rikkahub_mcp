package net.weero.measix.pilot.data.ai.transformers

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.request.DurableMessageLocator
import net.weero.measix.pilot.data.ai.request.RequestMessageOrigin
import net.weero.measix.pilot.data.ai.request.SyntheticMessageKind
import net.weero.measix.pilot.service.turn.ResolvedPromptInjection
import java.util.IdentityHashMap
import kotlin.uuid.Uuid

/** Request-local provenance. Durable identity is always supplied by the conversation owner. */
class RequestMessageOriginTracker {
    private val origins = mutableMapOf<Uuid, RequestMessageOrigin>()
    private val applicationHistory = mutableSetOf<Uuid>()
    private val previousUserTimes = mutableMapOf<Uuid, RealUserTime?>()
    private val messageTimeZones = mutableMapOf<Uuid, String>()
    private val partSources = IdentityHashMap<UIMessagePart, RequestPartSource>()
    private val originalParts = IdentityHashMap<UIMessagePart, UIMessagePart>()
    private val documentInputs = mutableMapOf<Pair<Uuid, Int>, String>()
    private val messageTimes = mutableMapOf<Uuid, String>()

    fun markDurable(messageId: Uuid, locator: DurableMessageLocator) {
        origins[messageId] = RequestMessageOrigin.Durable(locator)
    }

    fun markSynthetic(messageId: Uuid, kind: SyntheticMessageKind) {
        origins[messageId] = RequestMessageOrigin.Synthetic(kind)
    }

    fun markSynthetic(message: UIMessage, kind: SyntheticMessageKind) = markSynthetic(message.id, kind)
    fun source(message: UIMessage): RequestMessageOrigin? = origins[message.id]
    fun isSynthetic(message: UIMessage): Boolean = isSynthetic(message.id)
    fun isSynthetic(messageId: Uuid): Boolean = origins[messageId] is RequestMessageOrigin.Synthetic

    /** Presets and summaries remain durable history, but never reset the real-user clock. */
    fun markApplicationHistory(messageId: Uuid) { applicationHistory += messageId }
    /** Supplied from the selected durable branch before its history window is applied. */
    internal fun markPreviousRealUserTime(messageId: Uuid, previous: UIMessage?) {
        previousUserTimes[messageId] = previous?.let { RealUserTime(it.id, it.createdAt) }
    }
    internal fun previousRealUserTimes(): Map<Uuid, RealUserTime?> = previousUserTimes.toMap()
    internal fun rememberMessageTimeZone(messageId: Uuid, zoneId: String) { messageTimeZones.putIfAbsent(messageId, zoneId) }
    internal fun messageTimeZone(messageId: Uuid): String? = messageTimeZones[messageId]

    internal fun rememberDocument(messageId: Uuid, partIndex: Int, text: String) {
        documentInputs[messageId to partIndex] = text
    }
    internal fun documentText(messageId: Uuid, partIndex: Int): String? = documentInputs[messageId to partIndex]
    internal fun rememberMessageTime(messageId: Uuid, text: String) { messageTimes[messageId] = text }
    internal fun messageTimeText(messageId: Uuid): String? = messageTimes[messageId]

    fun isApplicationHistory(messageId: Uuid): Boolean = messageId in applicationHistory
    fun isRealUser(message: UIMessage): Boolean = message.role == MessageRole.USER &&
        !isSynthetic(message) && !isApplicationHistory(message.id)

    fun markPart(part: UIMessagePart, source: RequestPartSource) { partSources[part] = source }
    fun source(part: UIMessagePart): RequestPartSource? = partSources[part]
    fun isOpaque(part: UIMessagePart): Boolean = source(part) != null

    /** Copies in a request projection must explicitly retain the source of the replaced part. */
    fun transferPartSource(from: UIMessagePart, to: UIMessagePart) {
        source(from)?.let { markPart(to, it) }
    }

    internal fun rememberOriginalPart(from: UIMessagePart, to: UIMessagePart) {
        originalParts[to] = originalPart(from)
    }
    internal fun originalPart(part: UIMessagePart): UIMessagePart = originalParts[part] ?: part

    fun frozenOrigins(): Map<Uuid, RequestMessageOrigin> = origins.toMap()

    fun markNewMessages(before: List<UIMessage>, source: List<UIMessage>, kind: SyntheticMessageKind) {
        val knownIds = before.mapTo(HashSet(before.size)) { it.id }
        source.forEach { if (it.id !in knownIds) markSynthetic(it.id, kind) }
    }
}

/** A real predecessor can be outside the request window and have its own saved time interpretation. */
internal data class RealUserTime(val messageId: Uuid, val createdAt: LocalDateTime)

/** Content already rendered by its typed producer must not execute as a user template. */
sealed interface RequestPartSource {
    data class Admitted(val entryId: Uuid) : RequestPartSource
    data class PromptRule(val rule: ResolvedPromptInjection) : RequestPartSource
    data class DocumentInput(val document: UIMessagePart.Document) : RequestPartSource
    data class AttachmentInput(val attachment: UIMessagePart, val mode: String) : RequestPartSource
    data class MessageTime(val message: UIMessage) : RequestPartSource
    data object RenderedSystem : RequestPartSource
}
