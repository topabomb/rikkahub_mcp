package net.weero.measix.pilot.data.ai.subassistant

import me.rerere.ai.core.Tool
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import kotlinx.serialization.json.JsonPrimitive
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantRunPolicyTest {

    private val callerId = Uuid.random()
    private val targetId = Uuid.random()

    private fun makeAssistant(
        id: Uuid = targetId,
        allowAsSub: Boolean = true,
        description: String = "A helpful assistant",
        enableMemory: Boolean = true,
        useGlobalMemory: Boolean = false,
        localTools: List<LocalToolOption> = emptyList(),
        isGloballyVisible: Boolean = false,
        allowedSubAssistantIds: Set<Uuid> = emptySet(),
    ) = Assistant(
        id = id,
        name = "Test Assistant",
        description = description,
        allowAsSubAssistant = allowAsSub,
        isSubAssistantGloballyVisible = isGloballyVisible,
        allowedSubAssistantIds = allowedSubAssistantIds,
        enableMemory = enableMemory,
        useGlobalMemory = useGlobalMemory,
        localTools = localTools,
    )

    private fun makeModel(): Model = Model(
        id = Uuid.random(),
        displayName = "test-model",
        type = ModelType.CHAT,
    )

    // ---- validateReadiness ----

    @Test
    fun `ready when all conditions met with explicit allow`() {
        val result = validateReadiness(
            targetAssistant = makeAssistant(),
            callerAssistantId = callerId,
            callerAllowedSubAssistantIds = setOf(targetId),
            callerHasDelegation = true,
            settingsChatModel = makeModel(),
            isActiveRun = false,
        )
        assertTrue(result is ReadinessResult.Ready)
    }

    @Test
    fun `ready when all conditions met with globally visible`() {
        val result = validateReadiness(
            targetAssistant = makeAssistant(isGloballyVisible = true),
            callerAssistantId = callerId,
            callerAllowedSubAssistantIds = emptySet(),
            callerHasDelegation = true,
            settingsChatModel = makeModel(),
            isActiveRun = false,
        )
        assertTrue(result is ReadinessResult.Ready)
    }

    @Test
    fun `blocked tool_not_permitted when caller has no delegation`() {
        val result = validateReadiness(
            targetAssistant = makeAssistant(),
            callerAssistantId = callerId,
            callerAllowedSubAssistantIds = setOf(targetId),
            callerHasDelegation = false,
            settingsChatModel = makeModel(),
            isActiveRun = false,
        )
        assertTrue(result is ReadinessResult.Blocked)
        assertEquals("tool_not_permitted", (result as ReadinessResult.Blocked).reason)
    }

    @Test
    fun `blocked assistant_not_found when target is null`() {
        val result = validateReadiness(
            targetAssistant = null,
            callerAssistantId = callerId,
            callerAllowedSubAssistantIds = setOf(targetId),
            callerHasDelegation = true,
            settingsChatModel = makeModel(),
            isActiveRun = false,
        )
        assertTrue(result is ReadinessResult.Blocked)
        assertEquals("assistant_not_found", (result as ReadinessResult.Blocked).reason)
    }

    @Test
    fun `blocked target_not_allowed when target is caller`() {
        val result = validateReadiness(
            targetAssistant = makeAssistant(id = callerId),
            callerAssistantId = callerId,
            callerAllowedSubAssistantIds = setOf(callerId),
            callerHasDelegation = true,
            settingsChatModel = makeModel(),
            isActiveRun = false,
        )
        assertTrue(result is ReadinessResult.Blocked)
        assertEquals("target_not_allowed", (result as ReadinessResult.Blocked).reason)
    }

    @Test
    fun `blocked target_not_allowed when allowAsSubAssistant is false`() {
        val result = validateReadiness(
            targetAssistant = makeAssistant(allowAsSub = false),
            callerAssistantId = callerId,
            callerAllowedSubAssistantIds = setOf(targetId),
            callerHasDelegation = true,
            settingsChatModel = makeModel(),
            isActiveRun = false,
        )
        assertTrue(result is ReadinessResult.Blocked)
        assertEquals("target_not_allowed", (result as ReadinessResult.Blocked).reason)
    }

    @Test
    fun `blocked target_not_allowed when not in allowed list and not globally visible`() {
        val result = validateReadiness(
            targetAssistant = makeAssistant(isGloballyVisible = false),
            callerAssistantId = callerId,
            callerAllowedSubAssistantIds = emptySet(),
            callerHasDelegation = true,
            settingsChatModel = makeModel(),
            isActiveRun = false,
        )
        assertTrue(result is ReadinessResult.Blocked)
        assertEquals("target_not_allowed", (result as ReadinessResult.Blocked).reason)
    }

    @Test
    fun `blank routing description does not block an explicit call`() {
        val result = validateReadiness(
            targetAssistant = makeAssistant(description = ""),
            callerAssistantId = callerId,
            callerAllowedSubAssistantIds = setOf(targetId),
            callerHasDelegation = true,
            settingsChatModel = makeModel(),
            isActiveRun = false,
        )
        assertTrue(result is ReadinessResult.Ready)
    }

    @Test
    fun `blocked target_model_unavailable when model is null`() {
        val result = validateReadiness(
            targetAssistant = makeAssistant(),
            callerAssistantId = callerId,
            callerAllowedSubAssistantIds = setOf(targetId),
            callerHasDelegation = true,
            settingsChatModel = null,
            isActiveRun = false,
        )
        assertTrue(result is ReadinessResult.Blocked)
        assertEquals("target_model_unavailable", (result as ReadinessResult.Blocked).reason)
    }

    @Test
    fun `model blocker preserves the resolved fallback reason`() {
        val result = validateReadiness(
            targetAssistant = makeAssistant(),
            callerAssistantId = callerId,
            callerAllowedSubAssistantIds = setOf(targetId),
            callerHasDelegation = true,
            settingsChatModel = null,
            isActiveRun = false,
            modelUnavailableReason = "caller_model_unavailable",
        )
        assertEquals("caller_model_unavailable", (result as ReadinessResult.Blocked).reason)
    }

    @Test
    fun `blocked target_busy when active run exists`() {
        val result = validateReadiness(
            targetAssistant = makeAssistant(),
            callerAssistantId = callerId,
            callerAllowedSubAssistantIds = setOf(targetId),
            callerHasDelegation = true,
            settingsChatModel = makeModel(),
            isActiveRun = true,
        )
        assertTrue(result is ReadinessResult.Blocked)
        assertEquals("target_busy", (result as ReadinessResult.Blocked).reason)
    }

    // ---- resolveSubAssistantRunSpec ----

    @Test
    fun `configured target model and parameters take precedence over caller`() {
        val callerModel = makeModel()
        val targetModel = makeModel()
        val caller = makeAssistant(id = callerId).copy(
            chatModelId = callerModel.id,
            temperature = 0.1f,
        )
        val target = makeAssistant().copy(
            chatModelId = targetModel.id,
            temperature = 0.8f,
        )
        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(callerModel, targetModel))),
            assistants = listOf(caller, target),
        )

        val resolution = resolveSubAssistantRunSpec(settings, caller, target)
            as SubAssistantRunSpecResolution.Ready

        assertEquals(SubAssistantModelSource.TARGET_CONFIGURED, resolution.spec.modelSource)
        assertEquals(targetModel.id, resolution.spec.model.id)
        assertEquals(target, resolution.spec.assistant)
    }

    @Test
    fun `configured target model works even when caller has no model`() {
        val targetModel = makeModel()
        val caller = makeAssistant(id = callerId)
        val target = makeAssistant().copy(chatModelId = targetModel.id)
        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(targetModel))),
            assistants = listOf(caller, target),
        )

        val resolution = resolveSubAssistantRunSpec(settings, caller, target)
            as SubAssistantRunSpecResolution.Ready

        assertEquals(SubAssistantModelSource.TARGET_CONFIGURED, resolution.spec.modelSource)
        assertEquals(targetModel.id, resolution.spec.model.id)
    }

    @Test
    fun `unconfigured target inherits caller model execution settings without persisting them`() {
        val callerModel = makeModel()
        val callerHeader = CustomHeader("x-test", "caller")
        val callerBody = CustomBody("effort", JsonPrimitive("high"))
        val caller = makeAssistant(id = callerId).copy(
            chatModelId = callerModel.id,
            temperature = 0.2f,
            topP = 0.7f,
            contextMessageLimit = 144,
            streamOutput = false,
            reasoningLevel = ReasoningLevel.HIGH,
            maxTokens = 2048,
            customHeaders = listOf(callerHeader),
            customBodies = listOf(callerBody),
        )
        val target = makeAssistant().copy(
            chatModelId = null,
            systemPrompt = "Target role",
            localTools = listOf(LocalToolOption.AskUser),
        )
        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(callerModel))),
            assistants = listOf(caller, target),
        )

        val resolution = resolveSubAssistantRunSpec(settings, caller, target)
            as SubAssistantRunSpecResolution.Ready
        val runtimeTarget = resolution.spec.assistant

        assertEquals(SubAssistantModelSource.CALLER_FALLBACK, resolution.spec.modelSource)
        assertEquals(callerModel.id, resolution.spec.model.id)
        assertEquals(callerModel.id, runtimeTarget.chatModelId)
        assertEquals(caller.temperature, runtimeTarget.temperature)
        assertEquals(caller.topP, runtimeTarget.topP)
        assertEquals(caller.contextMessageLimit, runtimeTarget.contextMessageLimit)
        assertEquals(caller.streamOutput, runtimeTarget.streamOutput)
        assertEquals(caller.reasoningLevel, runtimeTarget.reasoningLevel)
        assertEquals(caller.maxTokens, runtimeTarget.maxTokens)
        assertEquals(caller.customHeaders, runtimeTarget.customHeaders)
        assertEquals(caller.customBodies, runtimeTarget.customBodies)
        assertEquals(target.id, runtimeTarget.id)
        assertEquals(target.systemPrompt, runtimeTarget.systemPrompt)
        assertEquals(target.localTools, runtimeTarget.localTools)
        assertEquals(null, target.chatModelId)
    }

    @Test
    fun `unconfigured target inherits caller effective global model`() {
        val globalModel = makeModel()
        val caller = makeAssistant(id = callerId).copy(chatModelId = null, temperature = 0.3f)
        val target = makeAssistant().copy(chatModelId = null)
        val settings = Settings(
            chatModelId = globalModel.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(globalModel))),
            assistants = listOf(caller, target),
        )

        val resolution = resolveSubAssistantRunSpec(settings, caller, target)
            as SubAssistantRunSpecResolution.Ready

        assertEquals(SubAssistantModelSource.CALLER_FALLBACK, resolution.spec.modelSource)
        assertEquals(globalModel.id, resolution.spec.model.id)
        assertEquals(caller.temperature, resolution.spec.assistant.temperature)
    }

    @Test
    fun `unconfigured target blocks when caller has no valid model`() {
        val caller = makeAssistant(id = callerId)
        val target = makeAssistant()

        val resolution = resolveSubAssistantRunSpec(
            settings = Settings(providers = emptyList(), assistants = listOf(caller, target)),
            caller = caller,
            target = target,
        )

        assertEquals(
            "caller_model_unavailable",
            (resolution as SubAssistantRunSpecResolution.Blocked).reason,
        )
    }

    @Test
    fun `invalid configured target model never falls back to caller`() {
        val callerModel = makeModel()
        val caller = makeAssistant(id = callerId).copy(chatModelId = callerModel.id)
        val target = makeAssistant().copy(chatModelId = Uuid.random())
        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(callerModel))),
            assistants = listOf(caller, target),
        )

        val resolution = resolveSubAssistantRunSpec(settings, caller, target)

        assertEquals(
            "target_model_unavailable",
            (resolution as SubAssistantRunSpecResolution.Blocked).reason,
        )
    }

    @Test
    fun `active run stops when caller access is revoked`() {
        val model = makeModel()
        val caller = makeAssistant(id = callerId).copy(
            chatModelId = model.id,
            localTools = listOf(LocalToolOption.AssistantDelegation),
            allowedSubAssistantIds = setOf(targetId),
        )
        val target = makeAssistant()
        val initialSettings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
            assistants = listOf(caller, target),
        )
        val runSpec = (resolveSubAssistantRunSpec(initialSettings, caller, target)
            as SubAssistantRunSpecResolution.Ready).spec
        val revokedSettings = initialSettings.copy(
            assistants = listOf(caller.copy(allowedSubAssistantIds = emptySet()), target),
        )

        assertEquals(
            "target_access_revoked",
            resolveActiveRunStopReason(revokedSettings, callerId, targetId, runSpec),
        )
    }

    @Test
    fun `pre-write recheck reports disabled caller delegation as tool not permitted`() {
        val model = makeModel()
        val caller = makeAssistant(id = callerId).copy(
            chatModelId = model.id,
            localTools = listOf(LocalToolOption.AssistantDelegation),
            allowedSubAssistantIds = setOf(targetId),
        )
        val target = makeAssistant()
        val initialSettings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
            assistants = listOf(caller, target),
        )
        val runSpec = (resolveSubAssistantRunSpec(initialSettings, caller, target)
            as SubAssistantRunSpecResolution.Ready).spec
        val disabledSettings = initialSettings.copy(
            assistants = listOf(caller.copy(localTools = emptyList()), target),
        )

        assertEquals(
            "tool_not_permitted",
            resolvePreWriteBlockReason(disabledSettings, callerId, targetId, runSpec),
        )
    }

    @Test
    fun `pre-write recheck reports revoked target access as target not allowed`() {
        val model = makeModel()
        val caller = makeAssistant(id = callerId).copy(
            chatModelId = model.id,
            localTools = listOf(LocalToolOption.AssistantDelegation),
            allowedSubAssistantIds = setOf(targetId),
        )
        val target = makeAssistant()
        val initialSettings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
            assistants = listOf(caller, target),
        )
        val runSpec = (resolveSubAssistantRunSpec(initialSettings, caller, target)
            as SubAssistantRunSpecResolution.Ready).spec
        val revokedSettings = initialSettings.copy(
            assistants = listOf(caller.copy(allowedSubAssistantIds = emptySet()), target),
        )

        assertEquals(
            "target_not_allowed",
            resolvePreWriteBlockReason(revokedSettings, callerId, targetId, runSpec),
        )
    }

    @Test
    fun `caller fallback run keeps snapshot when caller selects another available model`() {
        val inheritedModel = makeModel()
        val nextModel = makeModel()
        val caller = makeAssistant(id = callerId).copy(
            chatModelId = inheritedModel.id,
            localTools = listOf(LocalToolOption.AssistantDelegation),
            allowedSubAssistantIds = setOf(targetId),
        )
        val target = makeAssistant()
        val initialSettings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(inheritedModel, nextModel))),
            assistants = listOf(caller, target),
        )
        val runSpec = (resolveSubAssistantRunSpec(initialSettings, caller, target)
            as SubAssistantRunSpecResolution.Ready).spec
        val changedSettings = initialSettings.copy(
            assistants = listOf(caller.copy(chatModelId = nextModel.id), target),
        )

        assertEquals(null, resolveActiveRunStopReason(changedSettings, callerId, targetId, runSpec))
    }

    @Test
    fun `caller fallback run stops when inherited model becomes unavailable`() {
        val inheritedModel = makeModel()
        val caller = makeAssistant(id = callerId).copy(
            chatModelId = inheritedModel.id,
            localTools = listOf(LocalToolOption.AssistantDelegation),
            allowedSubAssistantIds = setOf(targetId),
        )
        val target = makeAssistant()
        val initialSettings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(inheritedModel))),
            assistants = listOf(caller, target),
        )
        val runSpec = (resolveSubAssistantRunSpec(initialSettings, caller, target)
            as SubAssistantRunSpecResolution.Ready).spec
        val disabledSettings = initialSettings.copy(
            providers = listOf(ProviderSetting.OpenAI(enabled = false, models = listOf(inheritedModel))),
        )

        assertEquals(
            "caller_model_unavailable",
            resolveActiveRunStopReason(disabledSettings, callerId, targetId, runSpec),
        )
    }

    // ---- SubAssistantAccessPolicy ----

    @Test
    fun `access policy excludes non-sub-assistant`() {
        val caller = makeAssistant(id = callerId, allowAsSub = false)
        val target = makeAssistant(allowAsSub = false)
        assertFalse(SubAssistantAccessPolicy.canAccess(caller, target))
    }

    @Test
    fun `access policy excludes self`() {
        val caller = makeAssistant(id = callerId)
        assertFalse(SubAssistantAccessPolicy.canAccess(caller, caller))
    }

    @Test
    fun `access policy allows explicit allow`() {
        val caller = makeAssistant(id = callerId, allowedSubAssistantIds = setOf(targetId))
        val target = makeAssistant()
        assertTrue(SubAssistantAccessPolicy.canAccess(caller, target))
    }

    @Test
    fun `access policy allows globally visible`() {
        val caller = makeAssistant(id = callerId)
        val target = makeAssistant(isGloballyVisible = true)
        assertTrue(SubAssistantAccessPolicy.canAccess(caller, target))
    }

    @Test
    fun `access policy denies when not in allowed list and not globally visible`() {
        val caller = makeAssistant(id = callerId)
        val target = makeAssistant(isGloballyVisible = false)
        assertFalse(SubAssistantAccessPolicy.canAccess(caller, target))
    }

    // ---- filterTargetLocalTools ----

    @Test
    fun `filter removes AssistantManagement`() {
        val tools = listOf(
            LocalToolOption.AssistantManagement,
            LocalToolOption.TimeInfo,
            LocalToolOption.Clipboard,
        )
        val filtered = filterTargetLocalTools(tools)
        assertFalse(filtered.contains(LocalToolOption.AssistantManagement))
        assertTrue(filtered.contains(LocalToolOption.TimeInfo))
        assertTrue(filtered.contains(LocalToolOption.Clipboard))
    }

    @Test
    fun `filter removes AssistantDelegation`() {
        val tools = listOf(
            LocalToolOption.AssistantDelegation,
            LocalToolOption.TimeInfo,
        )
        val filtered = filterTargetLocalTools(tools)
        assertFalse(filtered.contains(LocalToolOption.AssistantDelegation))
        assertTrue(filtered.contains(LocalToolOption.TimeInfo))
    }

    @Test
    fun `filter preserves other tools`() {
        val tools = listOf(
            LocalToolOption.Tts,
            LocalToolOption.Calendar,
        )
        val filtered = filterTargetLocalTools(tools)
        assertEquals(2, filtered.size)
    }

    // ---- filterTargetTools ----

    @Test
    fun `filter preserves ask_user for host interaction bridge`() {
        val tools = listOf(
            Tool(name = "ask_user", description = "", execute = { emptyList() }),
            Tool(name = "workspace_read_file", description = "", execute = { emptyList() }),
        )
        val filtered = filterTargetTools(tools)
        assertEquals(listOf("ask_user", "workspace_read_file"), filtered.map { it.name })
    }

    @Test
    fun `filter removes assistant_manage tool`() {
        val tools = listOf(
            Tool(name = "assistant_manage", description = "", execute = { emptyList() }),
            Tool(name = "search", description = "", execute = { emptyList() }),
        )
        val filtered = filterTargetTools(tools)
        assertEquals(1, filtered.size)
        assertEquals("search", filtered[0].name)
    }

    @Test
    fun `filter removes assistant_call tool`() {
        val tools = listOf(
            Tool(name = "assistant_call", description = "", execute = { emptyList() }),
        )
        val filtered = filterTargetTools(tools)
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `filter removes assistant_memory_list tool`() {
        val tools = listOf(
            Tool(name = "assistant_memory_list", description = "", execute = { emptyList() }),
            Tool(name = "valid_tool", description = "", execute = { emptyList() }),
        )
        val filtered = filterTargetTools(tools)
        assertEquals(1, filtered.size)
    }

    @Test
    fun `active target tools use snapshot and latest capability intersection`() {
        val originalWorkspace = Uuid.random()
        val replacementWorkspace = Uuid.random()
        val retainedMcp = Uuid.random()
        val revokedMcp = Uuid.random()
        val newlyGrantedMcp = Uuid.random()
        val snapshot = makeAssistant().copy(
            enableWebSearch = true,
            enableRecentChatsReference = true,
            localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.Tts),
            mcpServers = setOf(retainedMcp, revokedMcp),
            workspaceId = originalWorkspace,
            enabledSkills = setOf("retained", "revoked"),
        )
        val latest = snapshot.copy(
            enableWebSearch = false,
            localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.Calendar),
            mcpServers = setOf(retainedMcp, newlyGrantedMcp),
            workspaceId = replacementWorkspace,
            enabledSkills = setOf("retained", "new"),
        )

        val effective = intersectTargetToolCapabilities(snapshot, latest)

        assertFalse(effective.enableWebSearch)
        assertTrue(effective.enableRecentChatsReference)
        assertEquals(listOf(LocalToolOption.TimeInfo), effective.localTools)
        assertEquals(setOf(retainedMcp), effective.mcpServers)
        assertEquals(null, effective.workspaceId)
        assertEquals(setOf("retained"), effective.enabledSkills)
    }

    // ---- buildToolCreatedAssistant ----

    @Test
    fun `tool created assistant uses default local tools and keeps extension tools disabled`() {
        val assistant = buildToolCreatedAssistant(
            name = "Test",
            description = "Test desc",
            systemPrompt = "Test prompt",
        )
        assertTrue(assistant.allowAsSubAssistant)
        assertFalse(assistant.isSubAssistantGloballyVisible)
        assertTrue(assistant.allowedSubAssistantIds.isEmpty())
        assertEquals(null, assistant.chatModelId)
        assertTrue(assistant.enableMemory)
        assertFalse(assistant.useGlobalMemory)
        assertEquals(Assistant().localTools, assistant.localTools)
        assertEquals(
            listOf(LocalToolOption.TimeInfo, LocalToolOption.Tts, LocalToolOption.AskUser),
            assistant.localTools,
        )
        assertFalse(assistant.enableWebSearch)
        assertFalse(assistant.enableRecentChatsReference)
        assertTrue(assistant.mcpServers.isEmpty())
        assertTrue(assistant.enabledSkills.isEmpty())
    }
}
