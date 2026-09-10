package net.weero.measix.pilot.data.configuration

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.search.SearchServiceOptions
import net.weero.measix.pilot.data.ai.mcp.McpCommonOptions
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.datastore.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

internal fun appliedConfiguration(packet: EnterprisePackage): EnterpriseState.Available = EnterpriseState.Available(
    EnterpriseManifest(2, EnterpriseSessionPhase.READY,
        EnterpriseSession(Uuid.random().toString(), packet.identity, Long.MAX_VALUE),
        EnterpriseAppliedVersion(Uuid.random().toString(), packet.configuration.generation, "0".repeat(64), "0".repeat(64)),
        packet.identity.scope, packet.identity),
    packet.configuration,
)

class ConfigurationResolverTest {
    @Test
    fun `enterprise memory seeds follow only the authorized assistant bindings and never user memory preferences`() {
        val original = exampleEnterprisePackage()
        val definition = original.configuration.assistants.first().copy(memorySeedIds = listOf("seed_second", "seed_first"))
        val packet = original.copy(configuration = original.configuration.copy(
            assistants = listOf(definition),
            memorySeeds = listOf(EnterpriseMemorySeed("seed_first", "First"), EnterpriseMemorySeed("seed_second", "Second")),
        ))
        val reference = packet.identity.reference(definition.id)
        val resolved = ConfigurationResolver.resolve(document, packet.identity.scope, appliedConfiguration(packet))
        assertEquals(listOf("Second", "First"), resolved.assistantMemorySeeds(reference).map { it.content })
        assertEquals(listOf("Second", "First"), resolved.copy(assistants = resolved.assistants.mapValues { (_, value) ->
            value.copy(enableMemory = false, useGlobalMemory = true)
        }).assistantMemorySeeds(reference).map { it.content })
        assertTrue(resolved.assistantMemorySeeds(userAssistant.id).isEmpty())
        assertTrue(ConfigurationResolver.resolve(document, ConfigurationScope.Personal, appliedConfiguration(packet))
            .assistantMemorySeeds(reference).isEmpty())
        val unavailable = packet.copy(configuration = packet.configuration.copy(assistants = listOf(definition.copy(enabled = false))))
        assertTrue(ConfigurationResolver.resolve(document, packet.identity.scope, appliedConfiguration(unavailable))
            .assistantMemorySeeds(reference).isEmpty())
    }

    @Test
    fun `only unset personal search inherits the configured first service without rewriting stored selection`() {
        val services = listOf(SearchServiceOptions.BingLocalOptions(), SearchServiceOptions.TavilyOptions(apiKey = "key"))
        val document = UserSettingsDocument.empty().withPersonalSettings(Settings(searchServices = services))
        val packet = exampleEnterprisePackage()
        val personal = ConfigurationResolver.resolve(document, ConfigurationScope.Personal, appliedConfiguration(packet))
        assertEquals(services.first().id, personal.selection(ResourceSelectionSlot.SEARCH).reference)
        assertNull(personal.storedSelections.selectedSearchServiceId)
        assertNull(ConfigurationResolver.resolve(document, packet.identity.scope, appliedConfiguration(packet))
            .selection(ResourceSelectionSlot.SEARCH).reference)
        val missing = ConfigurationReference.random()
        val explicit = document.copy(preferences = document.preferences.withSelections(ConfigurationScope.Personal,
            ResourceSelections(selectedSearchServiceId = missing)))
        val unresolved = ConfigurationResolver.resolve(explicit, ConfigurationScope.Personal, appliedConfiguration(packet))
        assertEquals(missing, unresolved.selection(ResourceSelectionSlot.SEARCH).reference)
        assertFalse(unresolved.selection(ResourceSelectionSlot.SEARCH).isAvailable)
        val empty = document.copy(configuration = document.configuration.copy(searchServices = emptyList()))
        assertNull(ConfigurationResolver.resolve(empty, ConfigurationScope.Personal, appliedConfiguration(packet))
            .selection(ResourceSelectionSlot.SEARCH).reference)
    }

    @Test
    fun `chat capabilities follow the model transport override instead of the containing provider`() {
        val model = Model(modelId = "overridden", providerOverwrite = ProviderSetting.Google())
        val parent = ProviderSetting.OpenAI(models = listOf(model))
        val document = UserSettingsDocument.empty().withPersonalSettings(Settings(providers = listOf(parent)))
        val resolved = ConfigurationResolver.resolve(document, ConfigurationScope.Personal, appliedConfiguration(exampleEnterprisePackage()))
        assertEquals(me.rerere.ai.provider.ChatTransportCapabilities.GOOGLE, resolved.models.getValue(model.id).transportCapabilities)
        val plain = model.copy(providerOverwrite = ProviderSetting.OpenAI(useResponseApi = false))
        val replaced = document.copy(configuration = document.configuration.copy(providers = listOf(ProviderSetting.Google(models = listOf(plain)))))
        assertEquals(me.rerere.ai.provider.ChatTransportCapabilities.BASIC,
            ConfigurationResolver.resolve(replaced, ConfigurationScope.Personal, appliedConfiguration(exampleEnterprisePackage())).models.getValue(model.id).transportCapabilities)
    }

    private val userModel = Model(modelId = "personal", displayName = "Shared name")
    private val userProvider = ProviderSetting.OpenAI(models = listOf(userModel), apiKey = "personal-key")
    private val userMcp = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(name = "Enterprise-like name"), url = "https://example.invalid")
    private val userAssistant = Assistant(name = "My assistant", chatModelId = userModel.id, mcpServers = setOf(userMcp.id))
    private val document = UserSettingsDocument.empty().withPersonalSettings(Settings(
        providers = listOf(userProvider), assistants = listOf(userAssistant), mcpServers = listOf(userMcp),
        assistantId = userAssistant.id, chatModelId = userModel.id,
    ))

    @Test
    fun `repeated provider import leaves ambiguous model unavailable without breaking the other catalog entries`() {
        val packet = exampleEnterprisePackage()
        val imported = userProvider.copyProvider(ConfigurationReference.random())
        val duplicated = document.copy(configuration = document.configuration.copy(providers = listOf(userProvider, imported)))
        val resolved = ConfigurationResolver.resolve(duplicated, ConfigurationScope.Personal, appliedConfiguration(packet))
        assertEquals(userModel.id, resolved.modelSelection(ModelSelectionRole.CHAT).reference)
        assertEquals(ConfigurationUnavailableReason.REFERENCE_AMBIGUOUS, resolved.modelSelection(ModelSelectionRole.CHAT).unavailableReason)
        assertNull(resolved.models[userModel.id])
        assertTrue(resolved.access(ConfigurationCategory.PROVIDER, imported.id).canEditDefinition)
        assertTrue(resolved.access(ConfigurationCategory.ASSISTANT, userAssistant.id).canSelect)
        assertEquals(2, duplicated.configuration.providers.size)
    }

    @Test
    fun `enterprise admission reuses user identities and never changes personal configuration`() {
        val packet = exampleEnterprisePackage()
        val enterprise = ConfigurationResolver.resolve(document, packet.identity.scope, appliedConfiguration(packet))
        assertTrue(enterprise.access(ConfigurationCategory.MODEL, userModel.id).canSelect)
        assertEquals(userModel, enterprise.models[userModel.id]!!.model)
        assertEquals(userProvider.id, enterprise.models[userModel.id]!!.userProviderId)
        assertEquals(userAssistant, enterprise.assistants[userAssistant.id])
        assertEquals("personal-key", (document.configuration.providers.single() as ProviderSetting.OpenAI).apiKey)
        val personal = ConfigurationResolver.resolve(document, ConfigurationScope.Personal, appliedConfiguration(packet))
        assertEquals(userModel.id, personal.modelSelection(ModelSelectionRole.CHAT).reference)
        assertFalse(personal.models.keys.any { it is ConfigurationReference.Enterprise })
    }

    @Test
    fun `closing five admission flags disables only controlled user categories`() {
        val original = exampleEnterprisePackage()
        val packet = original.copy(configuration = original.configuration.copy(policy = EnterprisePolicy(false, false, false, false, false)))
        val resolved = ConfigurationResolver.resolve(document, packet.identity.scope, appliedConfiguration(packet))
        listOf(ConfigurationCategory.PROVIDER to userProvider.id, ConfigurationCategory.MODEL to userModel.id,
            ConfigurationCategory.MCP to userMcp.id, ConfigurationCategory.ASSISTANT to userAssistant.id,
            ConfigurationCategory.TTS to DEFAULT_SYSTEM_TTS_ID).forEach { (kind, id) ->
            assertEquals(ConfigurationUnavailableReason.USER_CATEGORY_NOT_ALLOWED, resolved.access(kind, id).unavailableReason)
            assertTrue(resolved.access(kind, id).canEditDefinition)
        }
        assertTrue(resolved.access(ConfigurationCategory.SEARCH, document.configuration.searchServices.first().id).canSelect)
        val managedMcp = resolved.access(ConfigurationCategory.MCP, packet.identity.reference("mcp_example"))
        assertTrue(managedMcp.canSelect)
        assertTrue(managedMcp.requiredEnabled)
        assertFalse(managedMcp.canEditDefinition)
    }

    @Test
    fun `explicit missing model remains missing even when a matching name or another default exists`() {
        val packet = exampleEnterprisePackage()
        val missing = packet.identity.reference("mdl_removed")
        val scoped = document.copy(preferences = document.preferences.copy(scopes = document.preferences.scopes +
            ScopedUserPreferences(packet.identity.scope, ResourceSelections(chatModelId = missing))))
        val resolved = ConfigurationResolver.resolve(scoped, packet.identity.scope, appliedConfiguration(packet))
        assertEquals(missing, resolved.modelSelection(ModelSelectionRole.CHAT).reference)
        assertEquals(ConfigurationUnavailableReason.REFERENCE_MISSING, resolved.modelSelection(ModelSelectionRole.CHAT).unavailableReason)
        assertFalse(resolved.modelSelection(ModelSelectionRole.CHAT).isAvailable)
    }

    @Test
    fun `user assistant can use an enterprise model without rewriting its personal binding`() {
        val packet = exampleEnterprisePackage()
        val managedModel = packet.identity.reference("mdl_chat")
        val scoped = document.copy(preferences = document.preferences.withAssistantUsage(packet.identity.scope,
            AssistantUsagePreferences(userAssistant.id, chatModelId = UsageValue(managedModel))))
        val resolved = ConfigurationResolver.resolve(scoped, packet.identity.scope, appliedConfiguration(packet))
        assertEquals(managedModel, resolved.assistantModel(userAssistant.id).reference)
        assertTrue(resolved.assistantModel(userAssistant.id).isAvailable)
        assertEquals(userModel.id, scoped.configuration.assistants.single().chatModelId)
    }

    @Test
    fun `missing enterprise defaults do not inherit the personal selections`() {
        val original = exampleEnterprisePackage()
        val packet = original.copy(configuration = original.configuration.copy(defaults = EnterpriseDefaults()))
        val resolved = ConfigurationResolver.resolve(document, packet.identity.scope, appliedConfiguration(packet))
        assertNull(resolved.selections.chatModelId)
        assertNull(resolved.selections.assistantId)
        assertNull(resolved.selections.selectedTTSProviderId)
        assertFalse(resolved.modelSelection(ModelSelectionRole.CHAT).isAvailable)
    }

    @Test
    fun `deleted capability references remain visible on the shared assistant`() {
        val packet = exampleEnterprisePackage()
        val missingInjection = ConfigurationReference.random()
        val missingQuickMessage = ConfigurationReference.random()
        val original = userAssistant.copy(modeInjectionIds = setOf(missingInjection), quickMessageIds = setOf(missingQuickMessage))
        val changed = document.copy(configuration = document.configuration.copy(mcpServers = emptyList(), assistants = listOf(original)))
        val resolved = ConfigurationResolver.resolve(changed, packet.identity.scope, appliedConfiguration(packet))
        val assistant = resolved.assistants[userAssistant.id]!!
        assertEquals(setOf(userMcp.id), assistant.mcpServers)
        assertEquals(setOf(missingInjection), assistant.modeInjectionIds)
        assertEquals(setOf(missingQuickMessage), assistant.quickMessageIds)
        assertEquals(ConfigurationUnavailableReason.REFERENCE_MISSING, resolved.access(ConfigurationCategory.MCP, userMcp.id).unavailableReason)
    }

    @Test
    fun `another principal cannot project the active enterprise configuration`() {
        val packet = exampleEnterprisePackage()
        val resolved = ConfigurationResolver.resolve(document, packet.identity.scope.copy(userId = "another"), appliedConfiguration(packet))
        assertNull(resolved.enterpriseConfiguration)
        assertFalse(resolved.models.keys.any { it is ConfigurationReference.Enterprise })
        assertEquals(ConfigurationUnavailableReason.ENTERPRISE_CONFIGURATION_NOT_READY, resolved.access(ConfigurationCategory.MODEL, userModel.id).unavailableReason)
    }

    @Test
    fun `search labels never contain API keys and image models cannot satisfy a chat role`() {
        val packet = exampleEnterprisePackage()
        val search = SearchServiceOptions.TavilyOptions(apiKey = "search-private-key")
        val scoped = document.copy(configuration = document.configuration.copy(searchServices = listOf(search)),
            preferences = document.preferences.copy(scopes = document.preferences.scopes + ScopedUserPreferences(packet.identity.scope,
                ResourceSelections(chatModelId = packet.identity.reference("mdl_image")))))
        val resolved = ConfigurationResolver.resolve(scoped, packet.identity.scope, appliedConfiguration(packet))
        assertFalse(resolved.catalog.values.any { it.name.contains("search-private-key") })
        assertEquals(ConfigurationUnavailableReason.RESOURCE_CAPABILITY_MISMATCH, resolved.modelSelection(ModelSelectionRole.CHAT).unavailableReason)
        assertEquals(ModelType.IMAGE, resolved.models[packet.identity.reference("mdl_image")]!!.model.type)
    }
}
