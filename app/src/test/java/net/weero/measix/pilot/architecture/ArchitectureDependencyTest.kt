package net.weero.measix.pilot.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分层与所有权契约：UI 与模型侧 adapter 只经 application/query ports 触达 durable owner；durable 写入、
 * runtime 加载各有唯一入口；每类事实只有一个 domain owner。这些边界防止旁路 DAO/Repository、服务定位器
 * 与第二状态源（AGENTS 架构规则）。
 */
class ArchitectureDependencyTest {
    @Test
    fun `provider settings UI never reaches provider SDK or model registry directly`() {
        val settingUi = sourcesUnder("ui/pages/setting") +
            File(architectureSourceRoot, "ui/components/ai/ProviderBalanceText.kt")
        assertNoHits("ProviderManager", settingUi)
        assertNoHits("ModelRegistry", settingUi)
    }

    @Test
    fun `UI never reaches workspace repository or entity directly`() {
        assertNoHits("WorkspaceRepository", sourcesUnder("ui"))
        assertNoHits("WorkspaceEntity", sourcesUnder("ui"))
    }

    @Test
    fun `UI never owns a raw terminal session`() {
        assertNoHits("com.termux.terminal.TerminalSession", sourcesUnder("ui"))
    }

    @Test
    fun `model workspace tools go through the workspace command owner`() {
        assertNoHits("WorkspaceRepository", sourcesUnder("data/ai/tools"))
    }

    @Test
    fun `generation loop produces facts, never Room entities`() {
        // 生成循环只产出 ToolExecutionFact / ToolResultFact；durable 实体由 reducer 归约构造，
        // 循环不感知持久 schema。状态枚举 ToolExecutionStatus / TurnExecutionStatus 允许引用。
        assertNoHits("data.db.entity.ToolExecutionEntity", sourcesUnder("service/turn"))
        assertNoHits("data.db.entity.TurnExecutionEntity", sourcesUnder("service/turn"))
    }

    @Test
    fun `UI depends only on application ports`() {
        listOf(
            "ConversationRepository",
            "ConversationRuntimeRegistry",
            "ConversationRuntimeLease",
            "ConversationAggregateSnapshot",
            "ConversationModelContextEntry",
            "ConversationModelContextDAO",
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
        ).forEach { token -> assertNoHits(token, sourcesUnder("ui")) }
    }

    @Test
    fun `presentation and query ports do not expose the durable aggregate or model context`() {
        // The Runtime keeps one authoritative durable shape; the presentation projection and the
        // public query shapes must not leak the aggregate snapshot or its model-context entries.
        val presentation = File(architectureSourceRoot, "service/runtime/ConversationPresentation.kt").readText()
        val query = File(architectureSourceRoot, "service/ConversationQueryService.kt").readText()
        val publicQueryShapes = query
            .substringAfter("data class ConversationSummary")
            .substringBefore("class ConversationQueryService")
        assertTrue(
            File(architectureSourceRoot, "data/model/ConversationModelContextEntry.kt").readText()
                .contains("internal data class ConversationModelContextEntry"),
        )
        assertFalse(presentation.contains("val modelContextEntries"))
        assertFalse(publicQueryShapes.contains("ConversationAggregateSnapshot"))
        assertFalse(publicQueryShapes.contains("ConversationModelContextEntry"))
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
    fun `chat page exclusively owns turn screen wakefulness`() {
        assertEquals(
            setOf(
                "ui/components/ui/KeepScreenOn.kt",
                "ui/pages/chat/ChatPage.kt",
            ),
            hits("KeepScreenOn(").toSet(),
        )
    }

    @Test
    fun `foreground turn haptics have one page owner outside adaptive layout branches`() {
        // Ownership boundary only: the haptic effect has a single page owner, vibration is confined to
        // the effect file, and the message row never plays haptics itself. The concrete Compose call
        // sites and system-setting reads are runtime behavior, covered by the haptic behavior tests.
        assertEquals(
            setOf("ui/pages/chat/TurnHapticFeedback.kt", "ui/pages/chat/ChatPage.kt"),
            hits("TurnHapticFeedback(").toSet(),
        )
        assertEquals(setOf("ui/pages/chat/TurnHapticFeedback.kt"), hits("VibrationEffect.").toSet())
        assertFalse(File(architectureSourceRoot, "ui/components/message/ChatMessage.kt").readText().contains("HapticFeedback"))
    }

    @Test
    fun `settings files page consumes only the file management ports`() {
        val filesPage = File(architectureSourceRoot, "ui/pages/setting/SettingFilesPage.kt").readText()
        val settingsPage = File(architectureSourceRoot, "ui/pages/setting/SettingPage.kt").readText()
        val fileQueryService = File(architectureSourceRoot, "service/FileManagementQueryService.kt").readText()
        assertFalse("page must not import GeneratedMediaStore", filesPage.contains("import net.weero.measix.pilot.data.imggen.GeneratedMediaStore"))
        assertFalse("page must not import GenMediaRepository", filesPage.contains("import net.weero.measix.pilot.data.repository.GenMediaRepository"))
        assertFalse("settings entry must not import GeneratedMediaStore", settingsPage.contains("import net.weero.measix.pilot.data.imggen.GeneratedMediaStore"))
        assertFalse("UI must not parse artifact string identities", filesPage.contains("substringAfter(\"artifact:"))
        assertFalse("UI must not parse generated-media string identities", filesPage.contains("substringAfter(\"genmedia:"))
        assertFalse("application identity must not depend on Android Parcelable", fileQueryService.contains("Parcelable"))
        assertFalse("application identity must not depend on Java serialization", fileQueryService.contains("Serializable"))
        assertTrue(filesPage.contains("FileManagementApplicationService"))
        assertTrue(filesPage.contains("FileManagementQueryService"))
        assertTrue(settingsPage.contains("FileManagementQueryService"))
    }

    @Test
    fun `all UI generated media access goes through application and query ports`() {
        assertNoHits("data.imggen.GeneratedMediaStore", sourcesUnder("ui"))
        assertNoHits("data.repository.GenMediaRepository", sourcesUnder("ui"))
        assertNoHits("data.db.entity.GenMediaEntity", sourcesUnder("ui"))
        assertNoHits("GeneratedMediaStore.IMAGES_DIR", sourcesUnder("ui"))
    }

    @Test
    fun `attachment path reads belong to file owner and internal lookup belongs to preview projection`() {
        val lookupOwners = hits("AttachmentReferenceLookup.index")
        assertTrue(
            "preview projection must use the internal attachment lookup: $lookupOwners",
            lookupOwners.contains("service/ConversationAttachmentPreviewProjector.kt"),
        )
        val resolver = File(architectureSourceRoot, "data/ai/attachments/AttachmentResolver.kt").readText()
        assertTrue(resolver.contains("artifactStore.withUploadImages"))
        listOf("AttachmentReferenceLookup", "ConversationRepository", "masterMessages", "Workspace", "SafeRemoteMediaFetcher")
            .forEach { dependency -> assertFalse("path reads must not depend on $dependency", resolver.contains(dependency)) }
        assertNoHits("AttachmentRefs.walkMessageParts", sourcesUnder("ui/components/message/"))
        assertNoHits("resolveAttachmentPreviewUrl")
        assertFalse(
            File(architectureSourceRoot, "ui/components/message/tools/AttachmentInspectionToolUI.kt")
                .readText()
                .contains("getSubAssistantCallMetadata"),
        )
    }

    @Test
    fun `manual and generated titles share coordinator serialization and generated writes use CAS`() {
        assertTrue(hits("commitManualTitle").contains("service/ConversationApplicationService.kt"))
        assertTrue(hits("commitGeneratedTitle").contains("service/GenerationSideEffects.kt"))
        assertTrue(hits("updateTitleIfCurrent").contains("service/GenerationSideEffects.kt"))
        assertFalse(
            File(architectureSourceRoot, "service/GenerationSideEffects.kt").readText()
                .contains("UpdateHeader(title ="),
        )
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
                File(architectureSourceRoot, path).readText().contains("McpRuntimeCoordinator"),
            )
        }
        val config = File(architectureSourceRoot, "data/ai/mcp/McpConfig.kt").readText()
        assertTrue(config.contains("val toolPolicies: List<McpToolPolicy>"))
        assertFalse(config.contains("data class McpTool("))
        assertFalse(config.contains("fun mergeTools("))

        val settingPage = File(architectureSourceRoot, "ui/pages/setting/SettingMcpPage.kt").readText()
        assertFalse("MCP settings UI must not write Settings directly", settingPage.contains("updateSettings("))
        assertFalse("MCP settings UI must not write SettingsStore directly", settingPage.contains("updateLocal("))
        assertFalse("MCP settings UI must not bypass its query projection through SettingVM", settingPage.contains("SettingVM"))
        assertFalse(
            "MCP settings UI must not consume EffectiveSettingsSnapshot",
            settingPage.contains("EffectiveSettingsSnapshot"),
        )
        assertFalse(File(architectureSourceRoot, "ui/pages/setting/McpSettingMutation.kt").exists())

        listOf(
            "ui/pages/setting/SettingMcpPage.kt",
            "ui/components/ai/McpPicker.kt",
            "ui/components/ai/FilesPicker.kt",
            "ui/pages/chat/ConversationReadiness.kt",
            "ui/pages/assistant/detail/AssistantMcpPage.kt",
        ).forEach { path ->
            val source = File(architectureSourceRoot, path).readText()
            assertFalse(
                "$path must consume McpQueryService projection instead of Settings MCP definitions",
                source.contains("settings.mcpServers") || source.contains("setting.mcpServers") ||
                    source.contains("effectiveSettings.settings.mcpServers"),
            )
        }

        val applicationService = File(architectureSourceRoot, "service/McpApplicationService.kt").readText()
        assertFalse(applicationService.contains("updateToolPolicy"))

        val catalog = File(architectureSourceRoot, "data/ai/mcp/McpCatalogStore.kt").readText()
        assertFalse("negotiated protocol is session state, not catalog identity", catalog.contains("protocolVersion"))
        assertFalse("unused server metadata must not become durable catalog fields", catalog.contains("serverImplementation"))
        assertFalse("unused wall-clock metadata must not become durable catalog fields", catalog.contains("verifiedAtEpochMillis"))

        val query = File(architectureSourceRoot, "service/McpQueryService.kt").readText()
        assertFalse("UI query must consume the slot capability projection", query.contains("McpCatalogStore"))

        val toolFactory = File(architectureSourceRoot, "data/ai/tools/TurnToolSetFactory.kt").readText()
        assertFalse(
            "turn callers must pass an explicit MCP snapshot",
            toolFactory.contains("mcpCapabilities: TurnMcpCapabilitySnapshot ="),
        )
    }

    @Test
    fun `destructive terminal close requires a confirmation resource`() {
        val terminal = File(architectureSourceRoot, "ui/pages/extensions/workspace/WorkspaceTerminalPage.kt").readText()
        assertTrue(terminal.contains("workspace_terminal_close_confirmation"))
    }

    @Test
    fun `conversation query tools cannot bypass the query port or load message trees for summaries`() {
        assertNoHits("ConversationRepository", sourcesUnder("data/ai/tools/"))
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
}
