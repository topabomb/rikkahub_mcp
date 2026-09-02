package net.weero.measix.pilot.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dependency direction and removed-symbol architecture seal. */
class SingleWriterContractTest {
    private val sourceRoot = File("src/main/java/net/weero/measix/pilot")
    private val sources by lazy {
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun hits(token: String, files: List<File> = sources): List<String> = files
        .filter { it.readText().contains(token) }
        .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }

    private fun assertNoHits(token: String, files: List<File> = sources) {
        val violations = hits(token, files)
        assertTrue("forbidden architecture token '$token': $violations", violations.isEmpty())
    }

    @Test
    fun `removed compatibility surfaces cannot return`() {
        listOf(
            "ConversationAggregateSnapshot.toConversation(",
            "updateConversationState",
            "updatePersistedConversation",
            "submitHeaderUpdate",
            "getSession(",
            "getOrCreateSession",
            "getSessionsSnapshot",
            "ApplyStreamingDelta",
            "GenerationCommand",
            "DomainCommand",
            "BeginTurn",
            "TransitionToolExecution",
            "MasterTurnOutcome",
            "FilesManager",
            "ManagedLocalArtifactStore",
            "deleteChatFiles",
            "deleteManagedFilePermanently",
            "deleteManagedFolderPermanently",
            "finishInterruptedPendingTools",
            "updateGenerationCheckpoint",
            "recoverInterruptedExecutions",
            "getAllChildConversationIds",
            "retainedChildren",
            "getConversationHeaderSnapshot",
            "getConversationsOfAssistantPaging",
            "getConversationsOfAssistantPage",
            "searchConversationsOfAssistantPage",
            "deleteRecovery",
            "recoveryWrite",
            "USER_TASK_WRITTEN",
            "INTERACTION_LIMIT_REACHED",
            "afterCommit",
            "AutoTitleGenerationTracker",
            "getGenerationJobFlow",
            "getConversationJobs",
            "conversationJob",
            "loadingJob",
            "SubAssistantRunLeaseRegistry",
            "getProcessingStatusFlow",
            "Image output omitted",
            "CAPABILITY_HINT",
            "backward compat",
            "Phase A",
            "Phase B",
            "Phase C",
            "Phase D",
            "TODO",
            "FIXME",
        ).forEach(::assertNoHits)
        assertFalse(File(sourceRoot, "service/ChatService.kt").exists())
        assertFalse(File(sourceRoot, "service/AssistantDataRecovery.kt").exists())
        assertFalse(File(sourceRoot, "data/files/FilesManager.kt").exists())
        assertFalse(File(sourceRoot, "data/files/ManagedLocalArtifactStore.kt").exists())
        assertFalse(File(sourceRoot, "data/ai/mcp/transport/SseClientTransport.kt").exists())
        assertFalse(File(sourceRoot, "data/ai/mcp/transport/StreamableHttpClientTransport.kt").exists())
    }

    @Test
    fun `UI depends only on application ports`() {
        val ui = sources.filter { it.relativeTo(sourceRoot).invariantSeparatorsPath.startsWith("ui/") }
        listOf(
            "ConversationRepository",
            "ConversationRuntimeRegistry",
            "ConversationRuntimeLease",
            "FilesManager",
            "data.db.dao.",
            "FavoriteRepository",
            "FolderRepository",
            "ConversationCommand",
            "UpdateHeader",
            "SelectNodeVariant",
            "data.model.Conversation",
            "data.db.entity.ArtifactEntity",
            "data.db.entity.ArtifactOrigin",
            "data.files.ArtifactStore",
            "data.files.ArtifactDeleteImpact",
            "data.files.ArtifactDeleteResult",
        ).forEach { token -> assertNoHits(token, ui) }
    }

    @Test
    fun `conversation persistence writes have one caller graph`() {
        val coordinator = "service/runtime/ConversationCommandCoordinator.kt"
        val allowed = mapOf(
            "repository.commit(" to setOf(coordinator),
            "repository.insertConversation(" to setOf(coordinator),
            "repository.insertConversationTree(" to setOf(coordinator),
            "repository.deleteConversation(" to setOf(coordinator),
        )
        allowed.forEach { (call, owners) ->
            val violations = hits(call).filterNot(owners::contains)
            assertTrue("$call must be confined to $owners: $violations", violations.isEmpty())
        }
    }

    @Test
    fun `runtime loading is confined to registry and command coordinator`() {
        val allowed = setOf(
            "service/runtime/ConversationRuntimeRegistry.kt",
            "service/runtime/ConversationCommandCoordinator.kt",
        )
        val violations = hits("loadRuntime(").filterNot(allowed::contains)
        assertTrue("runtime load must share the command lock boundary: $violations", violations.isEmpty())
    }

    @Test
    fun `tool runtime context planner and archived output have single owners`() {
        val generationLoop = File(sourceRoot, "data/ai/GenerationLoop.kt").readText()
        val toolRuntime = File(sourceRoot, "data/ai/tools/ToolCallRuntime.kt")
        val planner = File(sourceRoot, "data/ai/ConversationContextPlanner.kt")
        val outputStore = File(sourceRoot, "data/ai/tools/ToolOutputStore.kt")
        assertTrue(toolRuntime.isFile)
        assertTrue(planner.isFile)
        assertTrue(outputStore.isFile)
        listOf(
            "MAX_TOOL_OUTPUT_CHARS",
            "TOOL_OUTPUT_PREVIEW_CHARS",
            "maybeTruncateToolOutput",
            "legacyTruncateOutput",
            "/tool_outputs/",
            "limitContext(",
        ).forEach { legacy -> assertFalse("GenerationLoop must not retain $legacy", generationLoop.contains(legacy)) }
        assertTrue(generationLoop.contains("contextPlanner.planRequest"))
        assertTrue(generationLoop.contains("contextPlanner.planPostStepCompaction"))
        assertEquals(
            1,
            Regex(Regex.escape("contextPlanner.planPostStepCompaction(")).findAll(generationLoop).count(),
        )
        assertEquals(
            1,
            Regex(Regex.escape("toolOutputStore.stageCompaction(")).findAll(generationLoop).count(),
        )
        assertTrue(generationLoop.contains("checkpointMessages = checkpointMessages"))
        assertFalse(File(sourceRoot, "MeasixPilotApp.kt").readText().contains("cleanupToolOutputs"))
        listOf("baseline-prof.txt", "startup-prof.txt").forEach { profileName ->
            val profile = File("src/release/generated/baselineProfiles", profileName)
            assertTrue(profile.isFile)
            assertFalse(profile.readText().contains("cleanupToolOutputs"))
        }
        assertFalse(
            File("../workspace/src/main/java/me/rerere/workspace/ProotLaunchSpec.kt")
                .readText().contains("TOOL_OUTPUTS_DIR")
        )
        val ui = sources.filter { it.relativeTo(sourceRoot).invariantSeparatorsPath.startsWith("ui/") }
        assertNoHits("import net.weero.measix.pilot.data.files.ArtifactStore", ui)
        assertNoHits("import net.weero.measix.pilot.data.ai.tools.ToolOutputStore", ui)
    }
    @Test
    fun `tool output ownership transfer is mandatory`() {
        assertTrue(hits("registerUnpublishedResource").isNotEmpty())
        assertTrue(hits("ToolResourceLease").isNotEmpty())
        assertNoHits("ToolArtifactRewriter?")
    }

    @Test
    fun `tool UI uses the typed lifecycle and never gates inspection on output`() {
        val toolUi = sources.filter {
            it.relativeTo(sourceRoot).invariantSeparatorsPath.startsWith("ui/components/message/")
        }
        assertNoHits("loading && !step.tool.hasReplayResult", toolUi)
        assertNoHits("val loading: Boolean", toolUi)
        assertTrue(hits("ToolCallPhase", toolUi).isNotEmpty())
        val chatMessageTools = sourceRoot.resolve("ui/components/message/ChatMessageTools.kt").readText()
        assertTrue(chatMessageTools.contains("val isPending = phase == ToolCallPhase.AWAITING_INPUT"))
        assertTrue(chatMessageTools.contains("phase == ToolCallPhase.ANSWERED"))
        assertFalse(chatMessageTools.contains("val isPending = tool.approvalState"))
        val nerdLine = sourceRoot.resolve("ui/components/message/ChatMessageNerdLine.kt").readText()
        assertTrue(nerdLine.contains("mutableStateOf(false)"))
        assertTrue(nerdLine.contains("AnimatedVisibility(visible = expanded)"))
        assertTrue(nerdLine.contains("FlowRow("))
        assertTrue(nerdLine.contains("appendInlineContent(SUMMARY_TOGGLE_INLINE_ID)"))
        assertFalse(nerdLine.contains("Modifier.weight(1f)"))
        val notificationManager = sourceRoot.resolve("service/ChatNotificationManager.kt").readText()
        assertFalse(
            "notifications must not infer execution from Provider replay results",
            notificationManager.contains("!lastTool.hasReplayResult"),
        )
        assertTrue(notificationManager.contains("executingToolOrdinal"))
        assertTrue(notificationManager.contains("committedToolPhaseChanged"))
        assertTrue(notificationManager.contains("ChatGenerationAwaitingApproval"))
        assertTrue(notificationManager.contains("if (!event.notifyCompletion) return"))
        assertTrue(notificationManager.contains("activeLiveNotifications.toList().forEach(::cancelLiveUpdateNotification)"))
        val masterCoordinator = sourceRoot.resolve("service/MasterTurnCoordinator.kt").readText()
        listOf("ChatGenerationAwaitingApproval", "ChatGenerationEnded").forEach { event ->
            assertTrue(
                "$event must use suspending delivery",
                Regex("""appEventBus\.emit\(\s*AppEvent\.$event\b""").containsMatchIn(masterCoordinator),
            )
        }
    }

    @Test
    fun `conversation UI consumes typed turn presentation instead of coroutine jobs`() {
        val chatUi = sources.filter {
            it.relativeTo(sourceRoot).invariantSeparatorsPath.startsWith("ui/pages/chat/")
        }
        assertNoHits("conversationJob", chatUi)
        assertNoHits("loadingJob", chatUi)
        assertTrue(hits("ConversationPresentation", chatUi).isNotEmpty())
        val chatPage = File(sourceRoot, "ui/pages/chat/ChatPage.kt").readText()
        assertTrue(chatPage.contains("turnPresentation.phase == ConversationTurnPhase.STOPPING"))
    }

    @Test
    fun `chat page exclusively owns turn screen wakefulness`() {
        assertEquals(
            setOf(
                "ui/components/ui/KeepScreenOn.kt",
                "ui/pages/chat/ChatPage.kt",
            ),
            hits("KeepScreenOn(").toSet(),
        )
        val chatPage = File(sourceRoot, "ui/pages/chat/ChatPage.kt").readText()
        assertTrue(chatPage.contains("KeepScreenOn(enabled = turnPresentation.isActive)"))
    }

    @Test
    fun `foreground turn haptics have one page owner outside adaptive layout branches`() {
        assertEquals(
            setOf("ui/pages/chat/TurnHapticFeedback.kt", "ui/pages/chat/ChatPage.kt"),
            hits("TurnHapticFeedback(").toSet(),
        )
        val page = File(sourceRoot, "ui/pages/chat/ChatPage.kt").readText()
        assertEquals(1, Regex("""\bTurnHapticFeedback\(""").findAll(page).count())
        assertTrue(page.indexOf("TurnHapticFeedback(") > page.indexOf("fun ChatPage("))
        assertTrue(page.indexOf("TurnHapticFeedback(") < page.indexOf("val pageLayout:"))
        assertTrue(page.contains("combine(vm.conversationUiModel, vm.settings)"))
        val effect = File(sourceRoot, "ui/pages/chat/TurnHapticFeedback.kt").readText()
        assertTrue(effect.contains("repeatOnLifecycle(Lifecycle.State.RESUMED)"))
        assertFalse(effect.contains("snapshotFlow"))
        assertFalse(effect.contains("metadata"))
        assertFalse(effect.contains("debounce"))
        assertEquals(setOf("ui/pages/chat/TurnHapticFeedback.kt"), hits("VibrationEffect.").toSet())
        assertTrue(effect.contains("Settings.System.HAPTIC_FEEDBACK_ENABLED"))
        assertFalse(effect.contains("FLAG_BYPASS"))
        assertFalse(effect.contains("FLAG_IGNORE"))
        assertFalse(File(sourceRoot, "ui/components/message/ChatMessage.kt").readText().contains("HapticFeedback"))
    }

    @Test
    fun `folder bar keeps item state attached to durable folder identity`() {
        val chatDrawer = File(sourceRoot, "ui/pages/chat/ChatDrawer.kt").readText()
        assertTrue(chatDrawer.contains("items(folders, key = { it.id })"))
    }

    @Test
    fun `settings files page consumes only the file management ports`() {
        val filesPage = File(sourceRoot, "ui/pages/setting/SettingFilesPage.kt").readText()
        val settingsPage = File(sourceRoot, "ui/pages/setting/SettingPage.kt").readText()
        val fileQueryService = File(sourceRoot, "service/FileManagementQueryService.kt").readText()
        assertFalse("page must not import GeneratedMediaStore", filesPage.contains("import net.weero.measix.pilot.data.imggen.GeneratedMediaStore"))
        assertFalse("page must not import GenMediaRepository", filesPage.contains("import net.weero.measix.pilot.data.repository.GenMediaRepository"))
        assertFalse("settings entry must not import GeneratedMediaStore", settingsPage.contains("import net.weero.measix.pilot.data.imggen.GeneratedMediaStore"))
        assertFalse("UI must not parse artifact string identities", filesPage.contains("substringAfter(\"artifact:"))
        assertFalse("UI must not parse generated-media string identities", filesPage.contains("substringAfter(\"genmedia:"))
        assertFalse("typed application keys are not Bundle-saveable lazy keys", filesPage.contains("key = { it.key }"))
        assertTrue(filesPage.contains("items(uploadFiles, key = { it.uploadKey().artifactId })"))
        assertTrue(filesPage.contains("items(generatedImages, key = { it.generatedKey().mediaId })"))
        assertFalse("application identity must not depend on Android Parcelable", fileQueryService.contains("Parcelable"))
        assertFalse("application identity must not depend on Java serialization", fileQueryService.contains("Serializable"))
        assertTrue(filesPage.contains("FileManagementApplicationService"))
        assertTrue(filesPage.contains("FileManagementQueryService"))
        assertTrue(settingsPage.contains("FileManagementQueryService"))
    }

    @Test
    fun `all UI generated media access goes through application and query ports`() {
        val uiSources = sources.filter {
            it.relativeTo(sourceRoot).invariantSeparatorsPath.startsWith("ui/")
        }
        assertNoHits("data.imggen.GeneratedMediaStore", uiSources)
        assertNoHits("data.repository.GenMediaRepository", uiSources)
        assertNoHits("data.db.entity.GenMediaEntity", uiSources)
        assertNoHits("GeneratedMediaStore.IMAGES_DIR", uiSources)
        val galleryPage = File(sourceRoot, "ui/pages/imggen/ImgGenPage.kt").readText()
        assertTrue(galleryPage.contains("refreshState is LoadState.Loading"))
        assertTrue(galleryPage.contains("refreshState is LoadState.Error"))
        assertTrue(galleryPage.contains("generatedImages::retry"))
        assertTrue(galleryPage.contains("generatedImages.loadState.append is LoadState.Loading"))
    }

    @Test
    fun `attachment backfill is an exact metadata patch rather than a replacement tree`() {
        assertTrue(hits("BackfillAttachmentRefs").isNotEmpty())
        assertTrue(hits("AttachmentRefBackfill").isNotEmpty())
    }

    @Test
    fun `attachment path reads belong to file owner and internal lookup belongs to preview projection`() {
        val lookupOwners = hits("AttachmentReferenceLookup.index")
        assertTrue(
            "preview projection must use the internal attachment lookup: $lookupOwners",
            lookupOwners.contains("service/ConversationAttachmentPreviewProjector.kt"),
        )
        val resolver = File(sourceRoot, "data/ai/attachments/AttachmentResolver.kt").readText()
        assertTrue(resolver.contains("artifactStore.withUploadImages"))
        listOf("AttachmentReferenceLookup", "ConversationRepository", "masterMessages", "Workspace", "SafeRemoteMediaFetcher")
            .forEach { dependency -> assertFalse("path reads must not depend on $dependency", resolver.contains(dependency)) }
        val messageUi = sources.filter {
            it.relativeTo(sourceRoot).invariantSeparatorsPath.startsWith("ui/components/message/")
        }
        assertNoHits("AttachmentRefs.walkMessageParts", messageUi)
        assertNoHits("resolveAttachmentPreviewUrl")
        assertFalse(
            File(sourceRoot, "ui/components/message/tools/AttachmentInspectionToolUI.kt")
                .readText()
                .contains("getSubAssistantCallMetadata"),
        )
    }

    @Test
    fun `durable recovery and lifecycle domains never consume render overlays`() {
        listOf(
            "service/TurnRecovery.kt",
            "service/TurnFinalization.kt",
            "service/SubAssistantLifecycle.kt",
        ).forEach { path ->
            assertFalse(
                "$path must mutate durable nodes, not renderNodes",
                File(sourceRoot, path).readText().contains("renderNodes"),
            )
        }
    }

    @Test
    fun `manual and generated titles share coordinator serialization and generated writes use CAS`() {
        assertTrue(hits("commitManualTitle").contains("service/ConversationApplicationService.kt"))
        assertTrue(hits("commitGeneratedTitle").contains("service/GenerationSideEffects.kt"))
        assertTrue(hits("updateTitleIfCurrent").contains("service/GenerationSideEffects.kt"))
        assertFalse(
            File(sourceRoot, "service/GenerationSideEffects.kt").readText()
                .contains("UpdateHeader(title ="),
        )
    }

    @Test
    fun `assistant tool composition is complete at construction`() {
        assertNoHits("DelegationCoordinator?")
        assertNoHits("GenerationToolSetFactory?")
        assertNoHits("Sub-assistant coordinator is not available")
    }

    @Test
    fun `mcp ui uses application query boundary and settings does not own remote schema`() {
        listOf(
            "ui/components/ai/McpPicker.kt",
            "ui/pages/setting/SettingMcpPage.kt",
            "ui/pages/chat/ChatVM.kt",
            "ui/components/ai/FilesPicker.kt",
        ).forEach { path ->
            assertFalse(
                "$path must not own MCP runtime",
                File(sourceRoot, path).readText().contains("McpRuntimeCoordinator"),
            )
        }
        val config = File(sourceRoot, "data/ai/mcp/McpConfig.kt").readText()
        assertTrue(config.contains("val toolPolicies: List<McpToolPolicy>"))
        assertFalse(config.contains("data class McpTool("))
        assertFalse(config.contains("fun mergeTools("))

        val settingPage = File(sourceRoot, "ui/pages/setting/SettingMcpPage.kt").readText()
        assertFalse("MCP settings UI must not write Settings directly", settingPage.contains("updateSettings("))
        assertFalse("MCP settings UI must not write SettingsStore directly", settingPage.contains("updateLocal("))
        assertFalse("MCP settings UI must not bypass its query projection through SettingVM", settingPage.contains("SettingVM"))
        assertFalse(
            "MCP settings UI must not consume EffectiveSettingsSnapshot",
            settingPage.contains("EffectiveSettingsSnapshot"),
        )
        assertFalse(File(sourceRoot, "ui/pages/setting/McpSettingMutation.kt").exists())
        assertEquals(
            "pull-to-refresh must be the single global refresh command in MCP settings",
            1,
            Regex(Regex.escape("mcpApplicationService.refreshAll()"))
                .findAll(settingPage)
                .count(),
        )
        assertFalse(
            "MCP settings must not duplicate pull-to-refresh in the top app bar",
            settingPage.contains("HugeIcons.Refresh"),
        )

        val chatPage = File(sourceRoot, "ui/pages/chat/ChatPage.kt").readText()
        assertTrue(
            "conversation readiness must open the same MCP picker used by chat controls",
            Regex("onReadinessMcpClick\\s*=\\s*\\{\\s*showMcpPicker\\s*=\\s*true\\s*}")
                .containsMatchIn(chatPage),
        )
        assertTrue(chatPage.contains("McpPickerSheet("))
        val mcpPicker = File(sourceRoot, "ui/components/ai/McpPicker.kt").readText()
        assertTrue(mcpPicker.contains("R.string.mcp_picker_manage_servers"))
        assertTrue(mcpPicker.contains("onNavigateToSettings"))

        listOf(
            "ui/pages/setting/SettingMcpPage.kt",
            "ui/components/ai/McpPicker.kt",
            "ui/components/ai/FilesPicker.kt",
            "ui/pages/chat/ConversationReadiness.kt",
            "ui/pages/assistant/detail/AssistantMcpPage.kt",
        ).forEach { path ->
            val source = File(sourceRoot, path).readText()
            assertFalse(
                "$path must consume McpQueryService projection instead of Settings MCP definitions",
                source.contains("settings.mcpServers") || source.contains("setting.mcpServers") ||
                    source.contains("effectiveSettings.settings.mcpServers"),
            )
        }

        val applicationService = File(sourceRoot, "service/McpApplicationService.kt").readText()
        assertTrue(applicationService.contains("settingsStore.updateLocal"))
        assertFalse(applicationService.contains("updateToolPolicy"))

        val catalog = File(sourceRoot, "data/ai/mcp/McpCatalogStore.kt").readText()
        assertFalse("negotiated protocol is session state, not catalog identity", catalog.contains("protocolVersion"))
        assertFalse("unused server metadata must not become durable catalog fields", catalog.contains("serverImplementation"))
        assertFalse("unused wall-clock metadata must not become durable catalog fields", catalog.contains("verifiedAtEpochMillis"))

        val query = File(sourceRoot, "service/McpQueryService.kt").readText()
        assertFalse("UI query must consume the slot capability projection", query.contains("McpCatalogStore"))

        val toolFactory = File(sourceRoot, "data/ai/tools/GenerationToolSetFactory.kt").readText()
        assertFalse(
            "turn callers must pass an explicit MCP snapshot",
            toolFactory.contains("mcpCapabilities: TurnMcpCapabilitySnapshot ="),
        )
    }

    @Test
    fun `transient ui owners survive ime changes and destructive terminal actions require confirmation`() {
        val chatInput = File(sourceRoot, "ui/components/ai/ChatInput.kt").readText()
        assertEquals(
            "the model sheet must have exactly one stable composition owner",
            1,
            Regex(Regex.escape("ModelListSheet(")).findAll(chatInput).count(),
        )
        assertTrue(
            "the model sheet must be composed after the IME-dependent action row",
            chatInput.lastIndexOf("ModelListSheet(") > chatInput.lastIndexOf("actionRow(Modifier.fillMaxWidth())"),
        )

        val terminal = File(sourceRoot, "ui/pages/extensions/workspace/WorkspaceTerminalPage.kt").readText()
        assertTrue(terminal.contains("pendingCloseTabId = tab.id"))
        assertTrue(terminal.contains("workspace_terminal_close_confirmation"))
        assertTrue(
            "terminal close must only be invoked by the confirmation action",
            Regex(Regex.escape("onClose(tab.id)")).findAll(terminal).count() == 1,
        )
    }

    @Test
    fun `generation foreground timeout releases the service before application cancellation work`() {
        val service = File(sourceRoot, "service/ChatGenerationForegroundService.kt").readText()
        assertTrue(service.contains("override fun onTimeout(startId: Int, fgsType: Int)"))
        val timeout = service.substringAfter("override fun onTimeout(startId: Int, fgsType: Int)")
            .substringBefore("override fun onDestroy")
        assertTrue(timeout.indexOf("stopSelf(startId)") < timeout.indexOf("stopProjectedGenerations()"))
        assertFalse(service.contains("override fun onTimeout(startId: Int)"))
    }

    @Test
    fun `conversation query tools cannot bypass the query port or load message trees for summaries`() {
        val toolSources = sources.filter {
            it.relativeTo(sourceRoot).invariantSeparatorsPath.startsWith("data/ai/tools/")
        }
        assertNoHits("ConversationRepository", toolSources)
        val listRecordFields = net.weero.measix.pilot.data.repository.ConversationListRecord::class.members
            .map { it.name }
            .toSet()
        assertFalse(
            "conversation summaries must not carry a message tree: $listRecordFields",
            listRecordFields.any { it.contains("node", ignoreCase = true) || it.contains("message", ignoreCase = true) },
        )
    }

    @Test
    fun `ArtifactDAO has exactly one domain owner`() {
        val domainOwners = hits("ArtifactDAO").filterNot {
            it == "data/db/AppDatabase.kt" ||
                it == "data/db/dao/ArtifactDAO.kt" ||
                it == "di/RepositoryModule.kt"
        }
        assertTrue("ArtifactDAO domain owner must be ArtifactStore: $domainOwners", domainOwners == listOf("data/files/ArtifactStore.kt"))
    }

    @Test
    fun `unchecked rollback cannot be silently swallowed`() {
        assertNoHits("runCatching { artifactStore.discardUnpublished")
        assertNoHits("runCatching { store.discardUnpublished")
        assertNoHits("@Deprecated")
        assertNoHits("getKoin()")
        assertNoHits("KoinJavaComponent")
        assertNoHits("GlobalContext")
        assertNoHits("runCatching { previousJob?.join()")
        assertNoHits("runCatching { job.join()")
        assertNoHits("runCatching { loadRuntime(")
        assertNoHits("conversationEntityToConversation(entity, emptyList())")
    }

    @Test
    fun `MCP parallel maps and retired query files cannot return`() {
        assertNoHits("syncAll")
        assertNoHits("getServerLock")
        assertFalse(File(sourceRoot, "data/ai/mcp/McpConnectionKey.kt").exists())
        assertFalse(File(sourceRoot, "service/workspace/WorkspaceTerminalQueryService.kt").exists())
    }

    @Test
    fun `legacy master persistence skeleton cannot return`() {
        assertNoHits("handleMessageComplete")
        assertNoHits("finalizeMasterTurn")
        assertNoHits("persistMessageNodes")
        assertNoHits("V1C")
        assertNoHits("工作流")
        assertNoHits("修订记录")
        assertNoHits("兼容白名单")
    }
}
