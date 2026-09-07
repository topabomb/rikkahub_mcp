package net.weero.measix.pilot.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceType
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    // null = 普通用户会话；非 null = 子助手 Child Conversation，值为 Master Conversation ID
    val parentConversationId: Uuid? = null,
    @Transient
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes.flatMap { it.messages }.collectFileUris().toList()

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()

        messages.forEachIndexed { index, message ->
            val node = newNodes
                .getOrElse(index) { message.toMessageNode() }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            if (newMessages.any { it.id == message.id }) {
                newMessages[newMessages.indexOfFirst { it.id == message.id }] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )

            // 更新newNodes
            if (index > newNodes.lastIndex) {
                newNodes.add(newNode)
            } else {
                newNodes[index] = newNode
            }
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/**
 * 递归展开所有 parts，包括工具调用结果中的嵌套 parts。
 */
private fun List<UIMessagePart>.collectAllParts(): List<UIMessagePart> =
    this + filterIsInstance<UIMessagePart.Tool>().flatMap { it.output.collectAllParts() }

/**
 * 提取 part 中引用的本地文件 URL。比较与探测用字符串，避免 JVM 单测依赖 Android Uri。
 */
private fun UIMessagePart.fileUrlString(): String? = when (this) {
    is UIMessagePart.Image -> url.takeIf { it.startsWith("file://", ignoreCase = true) }
    is UIMessagePart.Document -> url.takeIf { it.startsWith("file://", ignoreCase = true) }
    is UIMessagePart.Video -> url.takeIf { it.startsWith("file://", ignoreCase = true) }
    is UIMessagePart.Audio -> url.takeIf { it.startsWith("file://", ignoreCase = true) }
    else -> null
}

// 与 ToolArtifactRewriter.ARTIFACT_KEY / SubAssistantCallMetadata 的 metadata 键保持一致。
// 这里用字面量是为了让 data.model 不依赖 data.files / data.ai.subassistant。
private const val TOOL_METADATA_ARTIFACT_KEY = "artifact"
private const val TOOL_METADATA_SUB_ASSISTANT_CALL_KEY = "sub_assistant_call"
private const val TOOL_METADATA_ARTIFACTS_KEY = "artifacts"
private const val LOCAL_ARTIFACT_RELATIVE_PATH_KEY = "relativePath"
private fun artifactRelativePathOf(element: kotlinx.serialization.json.JsonElement): String? {
    val primitive = (element as? kotlinx.serialization.json.JsonObject)
        ?.get(LOCAL_ARTIFACT_RELATIVE_PATH_KEY) as? kotlinx.serialization.json.JsonPrimitive
        ?: return null
    return primitive.content.takeIf { it.isNotBlank() }
}

/**
 * Tool.metadata 中的 LocalArtifactRef 相对路径（generate_image 的 "artifact" 键、
 * sub_assistant_call.artifacts[].artifact）。Master 卡片把这些当正式交付物引用，
 * 文件清理时必须视为仍被引用。
 */
private fun UIMessagePart.toolMetadataReferenceTokens(): List<String> {
    if (this !is UIMessagePart.Tool) return emptyList()
    val metadata = this.metadata ?: return emptyList()
    val tokens = mutableListOf<String>()
    metadata[TOOL_METADATA_ARTIFACT_KEY]?.let { element ->
        artifactRelativePathOf(element)?.let(tokens::add)
    }
    val artifacts = (metadata[TOOL_METADATA_SUB_ASSISTANT_CALL_KEY] as? kotlinx.serialization.json.JsonObject)
        ?.get(TOOL_METADATA_ARTIFACTS_KEY) as? kotlinx.serialization.json.JsonArray
    artifacts?.forEach { item ->
        artifactRelativePathOf(item)?.let(tokens::add)
    }
    return tokens
}

/**
 * runtimeState.archive.artifact 的相对路径：已归档 Tool Result 的唯一 durable 句柄，
 * 引用类型为 TOOL_OUTPUT，与附件生命周期分开。
 */
private fun UIMessagePart.toolOutputArchiveReferences(): List<MessageArtifactReference> {
    if (this !is UIMessagePart.Tool) return emptyList()
    val archive = this.runtimeState.archive ?: return emptyList()
    return listOf(
        MessageArtifactReference(
            token = archive.artifact.relativePath,
            type = ArtifactReferenceType.TOOL_OUTPUT,
            expectedArtifactId = archive.ref,
        ),
    )
}

/** 消息历史对 artifact 的带类型引用 token（file:// URL 或相对路径 + 引用类型）。 */
internal data class MessageArtifactReference(
    val token: String,
    val type: ArtifactReferenceType,
    /** Tool Output ref 指定的 Artifact id；投影时必须与相对路径解析出的实体一致。 */
    val expectedArtifactId: Long? = null,
)

internal fun List<UIMessage>.collectFileUrlStrings(): Set<String> =
    flatMap { it.parts }.collectAllParts().mapNotNull { it.fileUrlString() }.toSet()

/**
 * 会话引用的全部 artifact token，按语义分类：
 *  - 媒体 part URL 与 Tool.metadata 交付物 → ATTACHMENT；
 *  - runtimeState.archive.artifact → TOOL_OUTPUT。
 * 文件清理的保留判定与 artifact_reference 投影共用这一份规则。
 */
internal fun List<UIMessage>.collectArtifactReferences(): List<MessageArtifactReference> =
    flatMap { it.parts }.collectAllParts().flatMap { part ->
        listOfNotNull(part.fileUrlString()).map {
            MessageArtifactReference(it, ArtifactReferenceType.ATTACHMENT)
        } + part.toolMetadataReferenceTokens().map {
            MessageArtifactReference(it, ArtifactReferenceType.ATTACHMENT)
        } + part.toolOutputArchiveReferences()
    }

/** 会话引用的全部文件标识 token：文件清理的保留判定用这个集合。 */
internal fun List<UIMessage>.collectFileReferenceTokens(): Set<String> =
    collectArtifactReferences().map { it.token }.toSet()

internal fun List<UIMessage>.collectFileUris(): Set<Uri> =
    collectFileUrlStrings().map { it.toUri() }.toSet()
