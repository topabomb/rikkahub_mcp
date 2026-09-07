package net.weero.measix.pilot.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已退休生产符号、兼容 facade 与禁止模式的回归封禁：这些表面一旦被重新引入即破坏 V3 唯一链路，
 * 因此以「token 不得出现在任何主源码」的形式固化（AGENTS：物理删除旧 facade/fallback/兼容白名单）。
 */
class RetiredSurfaceContractTest {
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
            "GenerationCheckpoint",
            "CheckpointKind",
            "finalizeStream",
            " onTerminal =",
            "TurnOutcome.AwaitingApproval",
            "submitPauseCheckpoint",
            "recoverInterruptedExecutions",
            "getAllChildConversationIds",
            "retainedChildren",
            "getConversationHeaderSnapshot",
            "getConversationFlow(",
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
        ).forEach(::assertNoHits)
        assertFalse(File(architectureSourceRoot, "service/ChatService.kt").exists())
        assertFalse(File(architectureSourceRoot, "service/AssistantDataRecovery.kt").exists())
        assertFalse(File(architectureSourceRoot, "data/files/FilesManager.kt").exists())
        assertFalse(File(architectureSourceRoot, "data/files/ManagedLocalArtifactStore.kt").exists())
        assertFalse(File(architectureSourceRoot, "data/ai/mcp/transport/SseClientTransport.kt").exists())
        assertFalse(File(architectureSourceRoot, "data/ai/mcp/transport/StreamableHttpClientTransport.kt").exists())
    }

    @Test
    fun `turn run classification is a single TurnKind with no ToolSetRunMode`() {
        val protocol = File(architectureSourceRoot, "service/runtime/TurnProtocol.kt").readText()
        assertTrue(protocol.contains("enum class TurnKind"))
        assertTrue(protocol.contains("USER,"))
        assertTrue(protocol.contains("SUB_ASSISTANT,"))
        val factory = File(architectureSourceRoot, "data/ai/tools/TurnToolSetFactory.kt").readText()
        assertFalse("ToolSetRunMode must be physically deleted", factory.contains("ToolSetRunMode"))
        assertTrue("tool assembly classifies by TurnKind", factory.contains("turnKind: TurnKind"))
    }

    @Test
    fun `assistant tool composition is complete at construction`() {
        assertNoHits("SubAssistantRunCoordinator?")
        assertNoHits("TurnToolSetFactory?")
        assertNoHits("Sub-assistant coordinator is not available")
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
        assertFalse(File(architectureSourceRoot, "data/ai/mcp/McpConnectionKey.kt").exists())
        assertFalse(File(architectureSourceRoot, "service/workspace/WorkspaceTerminalQueryService.kt").exists())
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
