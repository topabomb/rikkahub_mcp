package net.weero.measix.pilot.service.turn

import me.rerere.ai.core.FrozenToolDefinition
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.RequestMediaCapabilities
import net.weero.measix.pilot.data.ai.tools.ToolExecutionBinding
import net.weero.measix.pilot.data.model.AssistantRegex
import net.weero.measix.pilot.data.model.InjectionPosition
import net.weero.measix.pilot.service.runtime.FrozenProviderWireShape
import net.weero.measix.pilot.service.runtime.ProviderTransportLease
import kotlin.uuid.Uuid

/** One immutable, process-local source for every model-visible value used by a durable Turn. */
internal data class TurnContext(
    val assistant: TurnAssistantSnapshot,
    val model: TurnModelSnapshot,
    val mediaCapabilities: RequestMediaCapabilities,
    val promptInputs: TurnPromptSnapshot,
    val toolDefinitions: List<FrozenToolDefinition>,
    val toolBindingsByName: Map<String, ToolExecutionBinding>,
)

/**
 * Assistant values that can affect request construction or output projection during this Turn.
 *
 * 属于请求管线 SPI（`TransformerContext` / `TurnRunInputs`）的公开载体，但唯一合法来源是
 * START 时的 `TurnContextFactory`；任何消费者都不得从中回读 Settings 或重新求值。
 */
data class TurnAssistantSnapshot(
    val id: Uuid,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val streamOutput: Boolean,
    val contextMessageLimit: Int,
    val temperature: Float?,
    val topP: Float?,
    val maxTokens: Int?,
    val reasoningLevel: ReasoningLevel,
    val customHeaders: List<CustomHeader>,
    val customBodies: List<CustomBody>,
    val regexes: List<AssistantRegex>,
)

/** Provider request shape selected once at START; credentials are supplied separately by its transport owner. */
internal data class TurnModelSnapshot(
    val model: Model,
    val providerShape: FrozenProviderWireShape,
    val transportLease: ProviderTransportLease,
)

/** 单条模式注入在本 Turn 内的已解析投影，仅由 START 冻结。 */
data class ResolvedPromptInjection(
    val id: Uuid,
    val priority: Int,
    val position: InjectionPosition,
    val content: String,
    val injectDepth: Int,
    val role: MessageRole,
)

/**
 * Inputs consumed by request transformers; all clock, locale, Settings and Workspace reads happen before START.
 *
 * 唯一构造入口是 [net.weero.measix.pilot.service.turn.TurnContextFactory]；
 * 同一 Turn 内不得按 step 重新构造，否则破坏 prompt 前缀稳定性。
 */
data class TurnPromptSnapshot(
    val messageTemplate: String?,
    val promptInjections: List<ResolvedPromptInjection>,
    val workspaceReminder: String?,
    val localeTag: String,
    val zoneId: String,
    val conversationSystemPrompt: String?,
    val modeInjectionIds: Set<Uuid>,
    val enableTimeReminder: Boolean,
    val placeholderValues: Map<String, String>,
)
