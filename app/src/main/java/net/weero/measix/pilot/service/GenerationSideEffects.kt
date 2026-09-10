package net.weero.measix.pilot.service

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.findUserTurnStart
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.UpdateHeader
import net.weero.measix.pilot.data.configuration.ModelSelectionRole
import net.weero.measix.pilot.service.runtime.generateText
import net.weero.measix.pilot.service.turn.TurnOutcome
import net.weero.measix.pilot.service.turn.TurnRunResult
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
 *    （原域模型捕获 → 请求准入 → generateText → 原会话命令提交）
 */
class GenerationSideEffects internal constructor(
    private val context: Context,
    private val appScope: AppScope,
    private val modelExecutions: ModelExecutionService,
    private val providerManager: ProviderManager,
    private val runtimeRegistry: ConversationRuntimeRegistry,
    private val commandCoordinator: ConversationCommandCoordinator,
    private val soundEffectPlayer: SoundEffectPlayer,
    private val json: Json,
    private val chatErrorStore: ChatErrorStore,
    private val titleCoordinator: ConversationTitleCoordinator,
    private val sessions: EnterpriseSessionController,
) {
    private data class GenerationOwner(val runtime: ConversationRuntime, val access: RealmAccess, val worker: Job)

    /** Callers hold the original Session and conversation admission until the worker is registered. */
    internal fun launchTitle(runtime: ConversationRuntime, access: RealmAccess, force: Boolean = false) =
        launchOwned(runtime, access) { owner, snapshot -> generateTitle(owner, snapshot, force) }

    internal fun launchSuggestion(runtime: ConversationRuntime, access: RealmAccess) =
        launchOwned(runtime, access) { owner, snapshot -> generateSuggestion(owner, snapshot) }

    internal fun launchCompression(
        runtime: ConversationRuntime,
        access: RealmAccess,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int,
        commit: suspend (List<MessageNode>) -> Unit,
    ): Deferred<Result<Unit>> = launchOwned(runtime, access) { owner, snapshot ->
        runCatchingPreservingCancellation {
            val nodes = compressConversation(owner, snapshot, additionalPrompt, targetTokens, keepRecentMessages)
            owner.runtime.releaseAuxiliaryModels(listOf(owner.worker))
            withOwner(owner, tree = true) {
                check(runtime.durable.header.assistantId == snapshot.header.assistantId &&
                    runtime.durable.nodes == snapshot.nodes && runtime.durable.modelContextEntries == snapshot.modelContextEntries) {
                    "conversation_changed_during_compression"
                }
                commit(nodes)
            }
        }
    }

    private fun <T> launchOwned(
        runtime: ConversationRuntime,
        access: RealmAccess,
        block: suspend (GenerationOwner, ConversationAggregateSnapshot) -> T,
    ): Deferred<T> {
        val snapshot = runtime.durable
        val worker = appScope.async(start = CoroutineStart.LAZY) {
            val owner = GenerationOwner(runtime, access, requireNotNull(coroutineContext[Job]))
            var failure: Throwable? = null
            try {
                withOwner(owner) { Unit }
                block(owner, snapshot)
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try { withContext(NonCancellable) { runtime.releaseAuxiliaryModels(listOf(owner.worker)) } }
                catch (error: Throwable) {
                    if (failure == null) throw error
                    if (failure !== error) failure.addSuppressed(error)
                }
            }
        }
        try {
            runtime.registerAuxiliaryWorker(access, worker)
            worker.start()
            return worker
        } catch (error: Throwable) {
            worker.cancel()
            throw error
        }
    }

    private suspend fun <T> withOwner(owner: GenerationOwner, tree: Boolean = false, operation: suspend () -> T): T =
        withSessionOwner(owner) {
            suspend fun execute(): T {
                owner.worker.ensureActive()
                check(runtimeRegistry.findRuntime(owner.runtime.id) === owner.runtime &&
                    owner.runtime.ownsAuxiliaryWorker(owner.access, owner.worker)) { "auxiliary_worker_owner_changed" }
                return operation()
            }
            if (tree) commandCoordinator.withRootTree(owner.access.scope, owner.runtime.id) { execute() }
            else commandCoordinator.withResidentRuntime(owner.runtime.id) { execute() }
        }

    private suspend fun <T> withSessionOwner(owner: GenerationOwner, operation: suspend () -> T): T =
        try {
            sessions.withRealmAccess(owner.access) { owner.worker.ensureActive(); operation() }
        } catch (error: EnterpriseConfigurationException) {
            if (error.reason != "enterprise_data_access_unavailable") throw error
            throw CancellationException("Auxiliary generation authorization revoked", error)
        }

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

    /** 删除会话时清除标题阶段、候选所有权和重试状态。 */
    fun clearTitleTracking(conversationId: Uuid) {
        titleCoordinator.clear(conversationId)
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

    private suspend fun runBackgroundGeneration(
        captured: CapturedModelConfiguration,
        role: ModelSelectionRole,
        prompt: String,
    ): String? {
        val result = captured.model.requests.execute { target ->
            target.generateText(providerManager, listOf(ModelRequestMessage.user(prompt)),
                backgroundTextGenerationParams(captured.model.model), role)
        }
        return result.choices.getOrNull(0)?.message?.toText()
    }

    // ---- 生成标题 ----

    private suspend fun generateTitle(owner: GenerationOwner, snapshot: ConversationAggregateSnapshot, force: Boolean) {
        var current = snapshot
        var requestedForce = force
        while (true) {
            val retry = generateTitleAttempt(owner, current, requestedForce) ?: return
            current = withOwner(owner) { owner.runtime.durable }
            if (current.header.assistantId != snapshot.header.assistantId) return
            requestedForce = retry.force
        }
    }

    private suspend fun generateTitleAttempt(
        owner: GenerationOwner,
        snapshot: ConversationAggregateSnapshot,
        force: Boolean = false,
    ): ConversationTitleRetry? {
        val conversationId = snapshot.conversationId
        if (titleCoordinator.phaseOf(conversationId) == null) {
            // Without in-process provenance, a persisted nonblank title is authoritative. This
            // protects manual titles after restart while a blank title remains auto-eligible.
            titleCoordinator.synchronize(conversationId, snapshot.header.title, localFallbackTitle = null)
        }
        val initialPhase = requireNotNull(titleCoordinator.phaseOf(conversationId))
        val beginResult = titleCoordinator.begin(
            conversationId = conversationId,
            force = force,
            autoEligible = initialPhase == ConversationTitlePhase.EMPTY ||
                initialPhase == ConversationTitlePhase.LOCAL_FALLBACK,
            expectedTitle = snapshot.header.title,
        )
        val token = (beginResult as? ConversationTitleBeginResult.Granted)?.token ?: return null

        var retry: ConversationTitleRetry? = null
        try {
            val captured = modelExecutions.captureAuxiliary(owner.access, owner.runtime, owner.worker,
                snapshot.header.assistantId, ModelSelectionRole.TITLE)
            val settings = captured.userSettings

            if (!force) {
                titleCoordinator.recordAttempt(token)
            }

            val generatedTitle = runBackgroundGeneration(
                captured = captured,
                role = ModelSelectionRole.TITLE,
                prompt = settings.titlePrompt.applyPlaceholders(
                    "locale" to Locale.getDefault().displayName,
                    "content" to snapshot.currentMessages()
                        .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) },
                ),
            ).orEmpty()
            val titleToWrite = normalizeGeneratedTitle(generatedTitle)
            // The title mutex precedes conversation locks, for both manual and generated commits.
            if (titleToWrite != null) withSessionOwner(owner) {
                titleCoordinator.commitGeneratedTitle(token, titleToWrite) { expectedTitle, newTitle ->
                    commandCoordinator.withResidentRuntime(conversationId) { current ->
                        owner.worker.ensureActive()
                        check(current === owner.runtime && current.ownsAuxiliaryWorker(owner.access, owner.worker)) {
                            "auxiliary_worker_owner_changed"
                        }
                        if (current.durable.header.assistantId != snapshot.header.assistantId || current.durable.nodes != snapshot.nodes) false
                        else commandCoordinator.updateTitleIfCurrent(conversationId, expectedTitle, newTitle)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.printStackTrace()
            withOwner(owner) {
                chatErrorStore.add(
                    error = error,
                    conversationId = conversationId,
                    title = context.getString(R.string.error_title_generate_title),
                    solution = ChatErrorSolution.CheckTitleModelSettings,
                )
            }
        } finally {
            retry = titleCoordinator.end(token)
        }
        return retry
    }

    // ---- 生成建议 ----

    private suspend fun generateSuggestion(owner: GenerationOwner, snapshot: ConversationAggregateSnapshot) {
        val conversationId = snapshot.conversationId
        try {
            if (!modelExecutions.read(owner.access).configuration.selections.enableSuggestion) return
            val captured = modelExecutions.captureAuxiliary(owner.access, owner.runtime, owner.worker,
                snapshot.header.assistantId, ModelSelectionRole.SUGGESTION)
            val settings = captured.userSettings
            if (!captured.configuration.selections.enableSuggestion) return

            val current = withOwner(owner) {
                if (owner.runtime.durable.header.assistantId != snapshot.header.assistantId || owner.runtime.durable.nodes != snapshot.nodes) false
                else {
                    commandCoordinator.executeOrThrow(conversationId, UpdateHeader(suggestions = emptyList()))
                    true
                }
            }
            if (!current) return

            val generated = runBackgroundGeneration(
                captured = captured,
                role = ModelSelectionRole.SUGGESTION,
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

            withOwner(owner) {
                if (owner.runtime.durable.header.assistantId == snapshot.header.assistantId && owner.runtime.durable.nodes == snapshot.nodes) {
                    commandCoordinator.executeOrThrow(conversationId, UpdateHeader(suggestions = suggestions))
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.printStackTrace()
            withOwner(owner) {
                chatErrorStore.add(
                    error = error,
                    conversationId = conversationId,
                    title = context.getString(R.string.error_title_generate_suggestion),
                )
            }
        }
    }

    // ---- 压缩对话历史 ----

    private suspend fun compressConversation(
        owner: GenerationOwner,
        snapshot: ConversationAggregateSnapshot,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): List<MessageNode> {
        val captured = modelExecutions.captureAuxiliary(owner.access, owner.runtime, owner.worker,
            snapshot.header.assistantId, ModelSelectionRole.COMPRESS)
        val settings = captured.userSettings
        val maxMessagesPerChunk = 256
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
                captured = captured,
                role = ModelSelectionRole.COMPRESS,
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
        return buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary).toMessageNode())
            }
            addAll(messagesToKeep.map { it.toMessageNode() })
        }

    }
}

/**
 * 需要用户立刻处理的注意力键：普通工具 Pending，以及子助手桥接的 typed UserInput。
 * 用于前台审批音效去重，避免同一交互重复播放。
 */
internal fun collectUserAttentionKeys(
    messages: List<UIMessage>,
    json: Json,
): Set<String> {
    val keys = linkedSetOf<String>()
    messages.forEach { message ->
        message.getTools().forEach { tool ->
            if (tool.isPending) {
                keys += "tool:${message.id}:${tool.localCallId}"
            }
            val metadata = tool.getSubAssistantCallMetadata(json)
            val interaction = metadata?.userInteraction
            if (
                metadata != null &&
                !metadata.state.isTerminal() &&
                metadata.phase == net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallPhase.AWAITING_USER &&
                interaction != null
            ) {
                val interactionId = interaction.interactionId.takeIf { it.isNotBlank() }
                if (interactionId != null) {
                    keys += "ask:$interactionId"
                }
            }
        }
    }
    return keys
}

internal fun shouldLaunchCompletionSideEffects(result: TurnRunResult): Boolean {
    return result is TurnOutcome.Completed
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
