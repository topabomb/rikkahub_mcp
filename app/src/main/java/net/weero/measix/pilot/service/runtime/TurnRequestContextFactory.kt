package net.weero.measix.pilot.service.runtime

import android.os.Build
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import net.weero.measix.pilot.data.ai.tools.freezeToolSet
import net.weero.measix.pilot.data.ai.transformers.buildWorkspacePrompt
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.effectiveContextMessageLimit
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

class TurnRequestContextFactory(
    private val workspaceRepository: WorkspaceRepository,
    private val clock: Clock = Clock.System,
    private val locale: () -> Locale = Locale::getDefault,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    /** Reads Workspace once, then delegates all value capture to the shared pure snapshot builder. */
    internal suspend fun capturePromptInputs(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        conversationSystemPrompt: String?,
        conversationModeInjectionIds: Set<Uuid>,
    ): FrozenTurnPromptInputs {
        val workspaceReminder = assistant.workspaceId
            ?.let { workspaceRepository.getById(it.toString()) }
            ?.let(::buildWorkspacePrompt)
        return freezeTurnPromptInputs(
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
    }

    /** Captures prompt values and materializes the one-to-one Tool definition/binding pair before START. */
    internal fun create(
        assistant: Assistant,
        model: Model,
        providerSetting: ProviderSetting,
        providerTransportLease: ProviderTransportLease,
        mediaCapabilities: RequestMediaCapabilities,
        promptInputs: FrozenTurnPromptInputs,
        tools: List<me.rerere.ai.core.Tool>,
    ): TurnRequestContext {
        val frozenTools = freezeToolSet(tools)
        val frozenModel = model.copy(
            customHeaders = model.customHeaders.toList(),
            customBodies = model.customBodies.toList(),
            inputModalities = model.inputModalities.toList(),
            outputModalities = model.outputModalities.toList(),
            abilities = model.abilities.toList(),
            tools = model.tools.toSet(),
            providerOverwrite = null,
        )
        return TurnRequestContext(
            assistant = resolveAssistantRequest(assistant),
            model = ResolvedModelRequest(
                model = frozenModel,
                providerShape = freezeProviderWireShape(providerSetting, frozenModel),
                transportLease = providerTransportLease,
            ),
            mediaCapabilities = mediaCapabilities,
            promptInputs = promptInputs,
            toolDefinitions = frozenTools.definitions,
            toolBindingsByName = frozenTools.bindingsByName,
        )
    }
}

/** Pure snapshot of every transformer input; callers must supply already-read Workspace disclosure and clock values. */
fun freezeTurnPromptInputs(
    settings: Settings,
    assistant: Assistant,
    model: Model,
    conversationSystemPrompt: String?,
    conversationModeInjectionIds: Set<Uuid>,
    workspaceReminder: String?,
    instant: Instant,
    locale: Locale,
    zoneId: ZoneId,
): FrozenTurnPromptInputs {
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
    return FrozenTurnPromptInputs(
        messageTemplate = assistant.messageTemplate,
        promptInjections = injections,
        workspaceReminder = workspaceReminder,
        turnInstant = instant,
        localeTag = locale.toLanguageTag(),
        zoneId = zoneId.id,
        conversationSystemPrompt = conversationSystemPrompt
            ?.takeIf { assistant.allowConversationSystemPrompt && it.isNotBlank() },
        modeInjectionIds = effectiveInjectionIds.toSet(),
        enableTimeReminder = assistant.enableTimeReminder,
        placeholderValues = placeholderValues(settings, assistant, model, instant, locale, zoneId),
    )
}

fun resolveAssistantRequest(assistant: Assistant): ResolvedAssistantRequest = with(assistant) {
    ResolvedAssistantRequest(
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
