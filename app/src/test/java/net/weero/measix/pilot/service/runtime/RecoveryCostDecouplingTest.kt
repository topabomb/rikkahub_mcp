package net.weero.measix.pilot.service.runtime

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.db.dao.ScopedTurnExecution
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.toEffectiveSettingsSnapshot
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.SubAssistantRunGate
import net.weero.measix.pilot.service.TurnRecovery
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 结构性断言（不依赖计时）：子助手 run 恢复的输入 **只允许** 是
 * `getNonTerminalTurnExecutionsWithScope()`（turn_execution 状态索引查询），
 * 全库扫描（loadAllTopLevelConversations / getAllChildConversationIds）被禁止——
 * 恢复成本与库大小解耦。
 */
class RecoveryCostDecouplingTest {

    private fun turnRecovery(
        repo: ConversationRepository,
        settingsStore: SettingsStore,
        runGate: SubAssistantRunGate,
        commandCoordinator: ConversationCommandCoordinator = mockk(relaxed = true),
    ) = TurnRecovery(
        conversationRepo = repo,
        commandCoordinator = commandCoordinator,
        settingsStore = settingsStore,
        json = Json,
        runGate = runGate,
    )

    @Test
    fun `recovery reads only the non-terminal turn execution index`() = runTest {
        val repo = mockk<ConversationRepository>(relaxed = true)
        val settingsStore = mockk<SettingsStore>(relaxed = true)
        val runGate = mockk<SubAssistantRunGate>(relaxed = true)
        coEvery { repo.getNonTerminalTurnExecutionsWithScope() } returns emptyList()
        every { settingsStore.effectiveSettings } returns MutableStateFlow(Settings().toEffectiveSettingsSnapshot())

        val recovery = turnRecovery(repo, settingsStore, runGate)
        recovery.recoverInterruptedRuns()

        // 唯一允许的恢复输入：turn_execution 状态索引（JOIN 区分 Master/Child）
        coVerify(exactly = 1) { repo.getNonTerminalTurnExecutionsWithScope() }
        coVerify(exactly = 0) { repo.getConversationById(any()) }
    }

    @Test
    fun `empty index short-circuits without loading any conversation`() = runTest {
        val repo = mockk<ConversationRepository>(relaxed = true)
        val settingsStore = mockk<SettingsStore>(relaxed = true)
        val runGate = mockk<SubAssistantRunGate>(relaxed = true)
        coEvery { repo.getNonTerminalTurnExecutionsWithScope() } returns emptyList()
        every { settingsStore.effectiveSettings } returns MutableStateFlow(Settings().toEffectiveSettingsSnapshot())

        val recovery = turnRecovery(repo, settingsStore, runGate)
        recovery.recoverInterruptedRuns()

        // 健康库（无非终态 turn）：零会话加载、零恢复树写入
        coVerify(exactly = 0) { repo.getConversationById(any()) }
        // 恢复入口取消全部运行中资源（lease + pending ask_user）
        coVerify(exactly = 1) { runGate.cancelAllRuns("app_restarted") }
        coVerify(exactly = 1) { runGate.cancelPendingInteractions() }
    }

    @Test
    fun `interrupted child without master metadata is still finalized`() = runTest {
        val repo = mockk<ConversationRepository>(relaxed = true)
        val settingsStore = mockk<SettingsStore>()
        val runGate = mockk<SubAssistantRunGate>(relaxed = true)
        val commandCoordinator = mockk<ConversationCommandCoordinator>(relaxed = true)
        val masterId = Uuid.random()
        val childId = Uuid.random()
        val assistantMessageId = Uuid.random()
        val turn = TurnExecutionEntity(
            turnId = Uuid.random().toString(),
            conversationId = childId.toString(),
            assistantMessageId = assistantMessageId.toString(),
            status = TurnExecutionStatus.RUNNING,
            reason = null,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val child = Conversation(
            id = childId,
            assistantId = Uuid.random(),
            messageNodes = listOf(
                MessageNode.of(UIMessage.assistant("partial").copy(id = assistantMessageId))
            ),
            parentConversationId = masterId,
        )
        val master = Conversation(
            id = masterId,
            assistantId = Uuid.random(),
            messageNodes = emptyList(),
        )
        coEvery { repo.getNonTerminalTurnExecutionsWithScope() } returns listOf(
            ScopedTurnExecution(turn, isChild = true, parentConversationId = masterId.toString())
        )
        coEvery { repo.getConversationSnapshotById(masterId) } returns master.toSnapshot()
        coEvery { repo.getConversationSnapshotById(childId) } returns child.toSnapshot()
        coEvery { repo.getTurnExecutions(childId) } returns listOf(turn)
        coEvery { repo.getToolExecutions(turn.turnId) } returns emptyList()
        every { settingsStore.effectiveSettings } returns MutableStateFlow(Settings().toEffectiveSettingsSnapshot())

        turnRecovery(repo, settingsStore, runGate, commandCoordinator).recoverInterruptedRuns()

        coVerify(exactly = 1) {
            commandCoordinator.executeRecovery(
                childId,
                match<RecoverInterruptedTurn> {
                    assertEquals(assistantMessageId, it.assistantMessageId)
                    it.terminalReason == "app_restarted"
                },
            )
        }
    }
}
