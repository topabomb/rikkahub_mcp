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
            "loadSnapshot(",
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
