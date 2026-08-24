package net.weero.measix.pilot.service

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.findUserTurnStart
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.findModelById
import net.weero.measix.pilot.data.datastore.findProvider
import net.weero.measix.pilot.data.datastore.getCurrentChatModel
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
import net.weero.measix.pilot.service.runtime.UpdateHeader
import net.weero.measix.pilot.service.runtime.ReplaceMessageTree
import net.weero.measix.pilot.service.runtime.TurnOutcome
import net.weero.measix.pilot.utils.SoundEffectPlayer
import net.weero.measix.pilot.utils.applyPlaceholders
import net.weero.measix.pilot.utils.runCatchingPreservingCancellation
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * 会话生成副作用域。
 *
 * 处理生成事件的非编排副作用，独立于 Master turn 编排：
 *  - 音效反馈（流式步进 / 审批提醒 / 完成 / 失败）
 *  - 会话衍生数据生成（标题 / 建议 / 压缩）——三者共用同一后台生成骨架
 *    （settings → 专属模型(可选 fastModel fallback) → provider → generateText → 命令提交）
 */
class GenerationSideEffects(
    private val context: Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val artifactStore: ArtifactStore,
    private val runtimeRegistry: ConversationRuntimeRegistry,
    private val commandCoordinator: ConversationCommandCoordinator,
    private val soundEffectPlayer: SoundEffectPlayer,
    private val json: Json,
    private val chatErrorStore: ChatErrorStore,
    private val autoTitleGeneration: AutoTitleGenerationTracker,
) {
    // ---- 音效反馈 ----

    /** 预装载音效资源（Application onCreate / Service init）。 */
    fun preloadSoundEffects() {
        soundEffectPlayer.preload(
            R.raw.loop_complete,
            R.raw.loop_failed,
            R.raw.loop_step,
            R.raw.loop_approval,
        )
    }

    fun playStepSound() {
        soundEffectPlayer.play(R.raw.loop_step)
    }

    fun playApprovalSound() {
        soundEffectPlayer.play(R.raw.loop_approval)
    }

    fun playTurnCompleteSound() {
        soundEffectPlayer.play(R.raw.loop_complete)
    }

    fun playTurnFailedSound() {
        soundEffectPlayer.play(R.raw.loop_failed)
    }

    /** 删除会话时清除标题自动生成的注意力跟踪。 */
    fun clearTitleTracking(conversationId: Uuid) {
        autoTitleGeneration.clear(conversationId)
    }

    /**
     * 前台流式音效（launchRun 的 Streaming 事件消费）：
     * 末条消息 finishedAt 变化 → step；出现新的注意力键（待审批工具 / 子助手 ask_user）
     * → approval。per-turn 实例持有去重状态。
     */
    inner class StreamingSoundTracker {
        private var previousFinishedAt: LocalDateTime? = null
        private val previousAttentionKeys = mutableSetOf<String>()

        fun onStreaming(lastMessage: UIMessage?) {
            if (lastMessage?.finishedAt != null && lastMessage.finishedAt != previousFinishedAt) {
                playStepSound()
            }
            previousFinishedAt = lastMessage?.finishedAt

            val attentionKeys = collectUserAttentionKeys(listOfNotNull(lastMessage), json)
            if (attentionKeys.any { previousAttentionKeys.add(it) }) {
                playApprovalSound()
            }
        }
    }

    fun soundTracker(): StreamingSoundTracker = StreamingSoundTracker()

    // ---- 后台生成公共骨架（标题 / 建议 / 压缩共用） ----

    /**
     * 背景生成唯一装配路径：settings → 专属模型（可选 fastModel fallback）→ provider →
     * generateText。返回 null 表示模型/provider 缺失或无 choice（调用方按各自语义处理）。
     */
    private suspend fun runBackgroundGeneration(
        settings: Settings,
        modelId: Uuid?,
        fallbackToFastModel: Boolean,
        prompt: String,
    ): String? {
        val model = if (fallbackToFastModel) {
            settings.findModelById(modelId, fallback = settings.fastModelId)
                ?: settings.getCurrentChatModel()
        } else {
            settings.findModelById(modelId)
                ?: settings.getCurrentChatModel()
        } ?: return null
        val provider = model.findProvider(settings.providers) ?: return null
        val providerHandler = providerManager.getProviderByType(provider)
        val result = providerHandler.generateText(
            providerSetting = provider,
            messages = listOf(UIMessage.user(prompt)),
            params = backgroundTextGenerationParams(model),
        )
        return result.choices.getOrNull(0)?.message?.toText()
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        snapshot: ConversationSnapshot,
        force: Boolean = false,
    ) {
        val conversationId = snapshot.conversationId
        val decision = autoTitleGeneration.begin(
            conversationId = conversationId,
            force = force,
            titleBlank = snapshot.header.title.isBlank(),
        )
        if (decision != AutoTitleGenerationDecision.Proceed) return

        var cancelled = false
        try {
            val settings = settingsStore.settingsFlow.value

            if (!force) {
                autoTitleGeneration.recordAttempt(conversationId)
            }

            val generatedTitle = runBackgroundGeneration(
                settings = settings,
                modelId = settings.titleModelId,
                fallbackToFastModel = true,
                prompt = settings.titlePrompt.applyPlaceholders(
                    "locale" to Locale.getDefault().displayName,
                    "content" to snapshot.currentMessages()
                        .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) },
                ),
            ).orEmpty()
            val latestTitle = runtimeRegistry.findRuntime(conversationId)?.snapshot?.value?.header?.title
                ?: snapshot.header.title
            val titleToWrite = resolveGeneratedTitleWrite(
                force = force,
                latestTitle = latestTitle,
                generatedTitle = generatedTitle,
            ) ?: return
            commandCoordinator.executeOrThrow(conversationId, UpdateHeader(title = titleToWrite))
        } catch (error: CancellationException) {
            cancelled = true
            throw error
        } catch (error: Exception) {
            error.printStackTrace()
            chatErrorStore.add(
                ChatError(
                    error = error,
                    conversationId = conversationId,
                    title = context.getString(R.string.error_title_generate_title),
                    solution = ChatErrorSolution.CheckTitleModelSettings,
                )
            )
        } finally {
            val retry = autoTitleGeneration.end(conversationId)
            if (!cancelled && retry != null) {
                launchWithConversationReference(conversationId) {
                    val latest = commandCoordinator.load(conversationId).snapshot.value
                    generateTitle(latest, force = retry.force)
                }
            }
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(snapshot: ConversationSnapshot) {
        val conversationId = snapshot.conversationId
        try {
            val settings = settingsStore.settingsFlow.value
            if (!settings.enableSuggestion) return

            commandCoordinator.executeOrThrow(conversationId, UpdateHeader(suggestions = emptyList()))

            val generated = runBackgroundGeneration(
                settings = settings,
                modelId = settings.suggestionModelId,
                fallbackToFastModel = true,
                prompt = settings.suggestionPrompt.applyPlaceholders(
                    "locale" to Locale.getDefault().displayName,
                    "content" to snapshot.currentMessages()
                        .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) },
                ),
            ) ?: return
            val suggestions = generated.split("\n")
                .map(String::trim)
                .filter(String::isNotBlank)
                .take(10)

            commandCoordinator.executeOrThrow(conversationId, UpdateHeader(suggestions = suggestions))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.printStackTrace()
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        snapshot: ConversationSnapshot,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatchingPreservingCancellation {
        val settings = settingsStore.settingsFlow.value
        val maxMessagesPerChunk = 256
        val conversationId = snapshot.conversationId
        val allMessages = snapshot.currentMessages()

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            val keepStartIndex = allMessages.findUserTurnStart(allMessages.size - keepRecentMessages)
            messagesToCompress = allMessages.take(keepStartIndex)
            messagesToKeep = allMessages.drop(keepStartIndex)
            if (messagesToCompress.isEmpty()) {
                throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
            }
        } else if (keepRecentMessages > 0) {
            // Not enough messages to compress while keeping recent ones
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
            messagesToKeep = emptyList()
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val rawMid = messages.size / 2
            val mid = messages.findUserTurnStart(rawMid).takeIf { it > 0 } ?: rawMid
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            return runBackgroundGeneration(
                settings = settings,
                modelId = settings.compressModelId,
                fallbackToFastModel = false,
                prompt = prompt,
            )?.trim()
                ?: throw IllegalStateException("No model available for compression")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // Replace older history with summary messages while preserving complete recent turns.
        val newMessageNodes = buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary).toMessageNode())
            }
            addAll(messagesToKeep.map { it.toMessageNode() })
        }

        // 压缩 = ReplaceMessageTree（树替换 delta）+ 清空建议；全量文件扫描由 GC 取代
        commandCoordinator.executeOrThrow(conversationId, ReplaceMessageTree(newMessageNodes))
        commandCoordinator.executeOrThrow(conversationId, UpdateHeader(suggestions = emptyList()))
        artifactStore.collectGarbage()
    }

    // ---- 私有基础设施 ----

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ) = appScope.launch {
        val lease = runtimeRegistry.acquireRuntime(conversationId)
        try {
            block()
        } finally {
            lease.close()
        }
    }
}

/**
 * 需要用户立刻处理的注意力键：普通工具 Pending，以及子助手桥接的 ask_user。
 * 用于前台审批音效去重，避免同一交互重复播放。
 */
internal fun collectUserAttentionKeys(
    messages: List<UIMessage>,
    json: Json,
): Set<String> {
    val keys = linkedSetOf<String>()
    messages.forEach { message ->
        message.getTools().forEachIndexed { ordinal, tool ->
            if (tool.isPending) {
                keys += "tool:${message.id}:$ordinal"
            }
            if (tool.toolName == "assistant_call") {
                val metadata = tool.getSubAssistantCallMetadata(json)
                val interaction = metadata?.userInteraction
                if (
                    metadata != null &&
                    !metadata.state.isTerminal() &&
                    interaction?.toolName == "ask_user"
                ) {
                    val interactionId = interaction.interactionId.takeIf { it.isNotBlank() }
                    if (interactionId != null) {
                        keys += "ask:$interactionId"
                    }
                }
            }
        }
    }
    return keys
}

internal fun shouldLaunchCompletionSideEffects(outcome: TurnOutcome?): Boolean {
    return outcome is TurnOutcome.Completed
}

internal fun backgroundTextGenerationParams(
    model: me.rerere.ai.provider.Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)
