package net.weero.measix.pilot.architecture

import java.io.File
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
            "ConversationSnapshot.toConversation(",
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
            "repository.applyMutation(" to setOf(coordinator),
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
    fun `tool output ownership transfer is mandatory`() {
        val toolSource = File("../ai/src/main/java/me/rerere/ai/core/Tool.kt").readText()
        val toolContext = toolSource
            .substringAfter("data class ToolExecutionContext(")
            .substringBefore("\n)")
        val transformerContext = File(sourceRoot, "data/ai/transformers/Transformer.kt").readText()
            .substringAfter("class TransformerContext(")
            .substringBefore("\n)")
        val lease = toolSource
            .substringAfter("class ToolResourceLease(")
            .substringBefore("\n)")
        listOf(
            "tool execution resource registration" to toolContext,
            "transformer resource registration" to transformerContext,
        ).forEach { (label, declaration) ->
            val field = declaration.substringAfter("val registerUnpublishedResource:").substringBefore(",\n")
            assertFalse("$label must not have a no-op default", field.contains("="))
        }
        listOf("publish", "discard").forEach { operation ->
            val field = lease.substringAfter("val $operation:").substringBefore(",\n")
            assertFalse("resource lease $operation must be explicit", field.contains("="))
        }
        val masterConstructor = File(sourceRoot, "service/MasterTurnCoordinator.kt").readText()
            .substringAfter("class MasterTurnCoordinator(")
            .substringBefore("\n) {")
        val attachmentCloner = File(sourceRoot, "data/files/AttachmentCloner.kt").readText()
        assertFalse(masterConstructor.contains("ToolArtifactRewriter?"))
        assertFalse(attachmentCloner.contains("ToolArtifactRewriter?"))
    }

    @Test
    fun `tool UI uses the typed lifecycle and never gates inspection on output`() {
        val toolUi = File(sourceRoot, "ui/components/message/ChatMessageTools.kt").readText()
        val context = File(sourceRoot, "ui/components/message/tools/ToolUI.kt").readText()
        assertTrue(context.contains("val phase: ToolCallPhase"))
        assertFalse(context.contains("val loading: Boolean"))
        assertTrue(toolUi.contains("onClick = { showResult = true }"))
        assertFalse(toolUi.contains("onClick = if (context.content"))
        assertFalse(toolUi.contains("loading && !step.tool.isExecuted"))
        assertTrue(toolUi.contains("resolvedPhase != ToolCallPhase.COMPLETED"))
    }

    @Test
    fun `conversation UI consumes typed turn presentation instead of coroutine jobs`() {
        val chatUi = sources.filter {
            it.relativeTo(sourceRoot).invariantSeparatorsPath.startsWith("ui/pages/chat/")
        }
        assertNoHits("conversationJob", chatUi)
        assertNoHits("loadingJob", chatUi)
        assertTrue(hits("ConversationTurnPresentation", chatUi).isNotEmpty())
    }

    @Test
    fun `attachment backfill is an exact metadata patch rather than a replacement tree`() {
        val commands = File(sourceRoot, "service/runtime/ConversationCommands.kt").readText()
        val coordinator = File(sourceRoot, "service/MasterTurnCoordinator.kt").readText()
        val runtime = File(sourceRoot, "service/runtime/ConversationRuntime.kt").readText()
        val declaration = commands
            .substringAfter("data class BackfillAttachmentRefs(")
            .substringBefore(") : ConversationCommand")
        val launchRun = coordinator
            .substringAfter("private suspend fun launchRun(")
            .substringBefore("// ---- 检查无效消息 ----")
        val guardedPreflight = launchRun
            .substringAfter("if (launchPolicy.runStructuralPreflight)")
            .substringBefore("var snapshot =")
        val ownerValidation = runtime
            .substringAfter("private fun validateCommandOwner(")
            .substringBefore("private val commandWrites")
        assertTrue(declaration.contains("List<AttachmentRefBackfill>"))
        assertFalse(declaration.contains("List<MessageNode>"))
        assertTrue(guardedPreflight.contains("checkInvalidMessages(conversationId)"))
        assertTrue(guardedPreflight.contains("BackfillAttachmentRefs(attachmentRefBackfills)"))
        assertFalse(launchRun.substringBefore("if (launchPolicy.runStructuralPreflight)").contains("BackfillAttachmentRefs"))
        assertFalse(ownerValidation.contains("is BackfillAttachmentRefs -> Unit"))
    }

    @Test
    fun `attachment execution and previews share one durable reference lookup`() {
        val resolver = File(sourceRoot, "data/ai/attachments/AttachmentResolver.kt").readText()
        val projector = File(sourceRoot, "service/ConversationAttachmentPreviewProjector.kt").readText()
        val messageUi = sources.filter {
            it.relativeTo(sourceRoot).invariantSeparatorsPath.startsWith("ui/components/message/")
        }
        assertTrue(resolver.contains("AttachmentReferenceLookup.index"))
        assertTrue(projector.contains("AttachmentReferenceLookup.index"))
        assertFalse(projector.contains("fun resolve("))
        assertTrue(projector.contains("projectMessages(listOf(assistant))"))
        assertFalse(projector.contains("projectMessages(active.messages)"))
        assertNoHits("AttachmentRefs.walkMessageParts", messageUi)
        assertNoHits("resolveAttachmentPreviewUrl")
        val inspectionUi = File(sourceRoot, "ui/components/message/tools/AttachmentInspectionToolUI.kt").readText()
        assertFalse(inspectionUi.contains("getSubAssistantCallMetadata"))
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
        val application = File(sourceRoot, "service/ConversationApplicationService.kt").readText()
        val sideEffects = File(sourceRoot, "service/GenerationSideEffects.kt").readText()
        assertTrue(application.contains("titleCoordinator.commitManualTitle"))
        assertTrue(sideEffects.contains("titleCoordinator.commitGeneratedTitle"))
        assertTrue(sideEffects.contains("commandCoordinator.updateTitleIfCurrent"))
        assertFalse(sideEffects.contains("UpdateHeader(title ="))
    }

    @Test
    fun `assistant tool composition is complete at construction`() {
        val source = File(sourceRoot, "data/ai/tools/AssistantToolFactory.kt").readText()
        val constructor = source
            .substringAfter("class AssistantToolFactory(")
            .substringBefore("\n) {")
        assertFalse(constructor.contains("DelegationCoordinator?"))
        assertFalse(constructor.contains("GenerationToolSetFactory?"))
        assertFalse(source.contains("Sub-assistant coordinator is not available"))
    }

    @Test
    fun `conversation query tools cannot bypass the query port or load message trees for summaries`() {
        val toolSources = sources.filter {
            it.relativeTo(sourceRoot).invariantSeparatorsPath.startsWith("data/ai/tools/")
        }
        assertNoHits("ConversationRepository", toolSources)

        val repository = File(sourceRoot, "data/repository/ConversationRepository.kt").readText()
        val recentRecords = repository
            .substringAfter("suspend fun getRecentConversationRecords(")
            .substringBefore("fun getConversationsOfAssistant(")
        assertFalse(
            "recent conversation summaries must not load message nodes",
            recentRecords.contains("loadMessageNodes"),
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
    fun `MCP suspend recovery paths preserve cancellation`() {
        val source = File(sourceRoot, "data/ai/mcp/McpManager.kt").readText()
        fun section(start: String, end: String): String =
            source.substringAfter(start).substringBefore(end)

        val refresh = section("private suspend fun ensureFreshToken", "private suspend fun persistOAuthState")
        val discovery = section("private suspend fun needsAuthorization", "/** 从异常链中提取")
        val authorization = section("fun startAuthorization", "/** 取消进行中的 OAuth")
        listOf(refresh, discovery, authorization).forEach { body ->
            assertTrue(
                "suspend runCatching path must rethrow CancellationException",
                body.contains("is CancellationException) throw"),
            )
        }
        assertFalse(authorization.contains("return@onFailure"))
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
