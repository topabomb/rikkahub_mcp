package net.weero.measix.pilot.service.turn

import android.os.Build
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import net.weero.measix.pilot.data.ai.tools.freezeToolSet
import net.weero.measix.pilot.data.ai.transformers.buildWorkspacePrompt
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.effectiveContextMessageLimit
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import net.weero.measix.pilot.service.runtime.ProviderTransportLease
import net.weero.measix.pilot.service.runtime.freezeProviderWireShape
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

class TurnContextFactory(
    private val workspaceRepository: WorkspaceRepository,
    private val clock: Clock = Clock.System,
    private val locale: () -> Locale = Locale::getDefault,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    /**
     * START 前的一次性捕获：读 Workspace 冻结 prompt 快照，连同已装配好的原始输入。
     * 这是唯一允许 IO 的阶段，可失败——失败时尚无 Turn，直接放弃即可。返回的
     * [TurnLaunchPlan] 只交给 [materialize]，不得被其它消费者读取。
     */
    internal suspend fun prepareLaunch(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        providerSetting: ProviderSetting,
        providerTransportLease: ProviderTransportLease,
        mediaCapabilities: RequestMediaCapabilities,
        conversationSystemPrompt: String?,
        conversationModeInjectionIds: Set<Uuid>,
        tools: List<me.rerere.ai.core.Tool>,
    ): TurnLaunchPlan {
        val workspaceReminder = assistant.workspaceId
            ?.let { workspaceRepository.getById(it.toString()) }
            ?.let(::buildWorkspacePrompt)
        val promptInputs = freezeTurnPromptSnapshot(
            settings = settings,
            assistant = assistant,
            model = model,
            conversationSystemPrompt = conversationSystemPrompt,
            conversationModeInjectionIds = conversationModeInjectionIds,
            workspaceReminder = workspaceReminder,
            instant = clock.now(),
            locale = locale(),
            zoneId = zoneId(),
        )
        return TurnLaunchPlan(
            assistant = assistant,
            model = model,
            providerSetting = providerSetting,
            providerTransportLease = providerTransportLease,
            mediaCapabilities = mediaCapabilities,
            promptInputs = promptInputs,
            tools = tools,
        )
    }

    /**
     * 纯绑定：只消费 [prepareLaunch] 已捕获的快照，禁止 IO、禁止重读 Settings。
     * 正常路径不可失败；若仍抛错即为编程错误，调用方须以
     * `FinalizeTurn(Failed, reason=turn_context_materialize)` 收口已启动的 Turn。
     */
    internal fun materialize(plan: TurnLaunchPlan): TurnContext {
        val frozenTools = freezeToolSet(plan.tools)
        val frozenModel = plan.model.copy(
            customHeaders = plan.model.customHeaders.toList(),
            customBodies = plan.model.customBodies.toList(),
            inputModalities = plan.model.inputModalities.toList(),
            outputModalities = plan.model.outputModalities.toList(),
            abilities = plan.model.abilities.toList(),
            tools = plan.model.tools.toSet(),
            providerOverwrite = null,
        )
        return TurnContext(
            assistant = resolveTurnAssistantSnapshot(plan.assistant),
            model = TurnModelSnapshot(
                model = frozenModel,
                providerShape = freezeProviderWireShape(plan.providerSetting, frozenModel),
                transportLease = plan.providerTransportLease,
            ),
            mediaCapabilities = plan.mediaCapabilities,
            promptInputs = plan.promptInputs,
            toolDefinitions = frozenTools.definitions,
            toolBindingsByName = frozenTools.bindingsByName,
        )
    }
}

/**
 * [TurnContextFactory.prepareLaunch] 与 `StartTurn` 事务之间的短生命周期捕获。
 * 只保存已读取的快照与原始装配输入，不做任何冻结；唯一消费者是 [TurnContextFactory.materialize]。
 */
internal class TurnLaunchPlan(
    val assistant: Assistant,
    val model: Model,
    val providerSetting: ProviderSetting,
    val providerTransportLease: ProviderTransportLease,
    val mediaCapabilities: RequestMediaCapabilities,
    val promptInputs: TurnPromptSnapshot,
    val tools: List<me.rerere.ai.core.Tool>,
)

/** Pure snapshot of every transformer input; callers must supply already-read Workspace disclosure and clock values. */
fun freezeTurnPromptSnapshot(
    settings: Settings,
    assistant: Assistant,
    model: Model,
    conversationSystemPrompt: String?,
    conversationModeInjectionIds: Set<Uuid>,
    workspaceReminder: String?,
    instant: Instant,
    locale: Locale,
    zoneId: ZoneId,
): TurnPromptSnapshot {
    val effectiveInjectionIds = if (assistant.allowConversationPromptInjection) {
        conversationModeInjectionIds
    } else {
        assistant.modeInjectionIds
    }
    val injections = settings.modeInjections
        .asSequence()
        .filter { it.enabled && it.id in effectiveInjectionIds }
        .map { injection ->
            ResolvedPromptInjection(
                id = injection.id,
                priority = injection.priority,
                position = injection.position,
                content = injection.content,
                injectDepth = injection.injectDepth,
                role = injection.role,
            )
        }
        .toList()
    return TurnPromptSnapshot(
        messageTemplate = assistant.messageTemplate,
        promptInjections = injections,
        workspaceReminder = workspaceReminder,
        localeTag = locale.toLanguageTag(),
        zoneId = zoneId.id,
        conversationSystemPrompt = conversationSystemPrompt
            ?.takeIf { assistant.allowConversationSystemPrompt && it.isNotBlank() },
        modeInjectionIds = effectiveInjectionIds.toSet(),
        enableTimeReminder = assistant.enableTimeReminder,
        placeholderValues = placeholderValues(settings, assistant, model, instant, locale, zoneId),
    )
}

fun resolveTurnAssistantSnapshot(assistant: Assistant): TurnAssistantSnapshot = with(assistant) {
    TurnAssistantSnapshot(
        id = id,
        name = name,
        description = description,
        systemPrompt = systemPrompt,
        streamOutput = streamOutput,
        contextMessageLimit = effectiveContextMessageLimit(),
        temperature = temperature,
        topP = topP,
        maxTokens = maxTokens,
        reasoningLevel = reasoningLevel,
        customHeaders = customHeaders.toList(),
        customBodies = customBodies.toList(),
        regexes = regexes.toList(),
    )
}

private fun placeholderValues(
    settings: Settings,
    assistant: Assistant,
    model: Model,
    instant: Instant,
    locale: Locale,
    zoneId: ZoneId,
): Map<String, String> {
    val local = instant.toJavaInstant().atZone(zoneId)
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(local)
    val nickname = settings.displaySetting.userNickname.ifBlank { "user" }
    return linkedMapOf(
        "cur_date" to date,
        "model_id" to model.modelId,
        "model_name" to model.displayName,
        "locale" to locale.displayName,
        "timezone" to TimeZone.getTimeZone(zoneId).getDisplayName(locale),
        "system_version" to "Android SDK v${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})",
        "device_info" to "${Build.BRAND} ${Build.MODEL}",
        "nickname" to nickname,
        "char" to assistant.name.ifBlank { "assistant" },
        "description" to assistant.description,
        "user" to nickname,
    )
}

/** Resolves the Memory namespace captured at START and rechecked before each live write. */
internal fun resolveMemoryOwnerId(assistant: Assistant?): String? {
    if (assistant?.enableMemory != true) return null
    return if (assistant.useGlobalMemory) MemoryRepository.GLOBAL_MEMORY_ID else assistant.id.toString()
}
