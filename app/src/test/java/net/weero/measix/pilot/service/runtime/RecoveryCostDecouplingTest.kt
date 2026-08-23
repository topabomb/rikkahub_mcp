package net.weero.measix.pilot.service.runtime

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.SubAssistantRunGate
import net.weero.measix.pilot.service.TurnRecovery
import org.junit.Test

/**
 * I8 恢复成本与库大小解耦契约（V1 正式阶段·架构收敛 §12 工作流 O）。
 *
 * 结构性断言（不依赖计时）：子助手 run 恢复的输入 **只允许** 是
 * `getNonTerminalTurnExecutionsWithScope()`（turn_execution 状态索引查询），
 * 全库扫描（loadAllTopLevelConversations / getAllChildConversationIds）被禁止——
 * 恢复成本与库大小解耦（G-3 关闭的运行时证明）。
 */
class RecoveryCostDecouplingTest {

    private fun turnRecovery(
        repo: ConversationRepository,
        registry: ConversationRuntimeRegistry,
        settingsStore: SettingsStore,
        runGate: SubAssistantRunGate,
    ) = TurnRecovery(
        conversationRepo = repo,
        sessionRegistry = registry,
        settingsStore = settingsStore,
        json = Json,
        runGate = runGate,
    )

    @Test
    fun `I8 recovery reads only the non-terminal turn execution index`() = runTest {
        val repo = mockk<ConversationRepository>(relaxed = true)
        val registry = mockk<ConversationRuntimeRegistry>(relaxed = true)
        val settingsStore = mockk<SettingsStore>(relaxed = true)
        val runGate = mockk<SubAssistantRunGate>(relaxed = true)
        coEvery { repo.getNonTerminalTurnExecutionsWithScope() } returns emptyList()
        every { settingsStore.settingsFlow } returns MutableStateFlow(mockk(relaxed = true))

        val recovery = turnRecovery(repo, registry, settingsStore, runGate)
        recovery.recoverInterruptedRuns()

        // 唯一允许的恢复输入：turn_execution 状态索引（JOIN 区分 Master/Child）
        coVerify(exactly = 1) { repo.getNonTerminalTurnExecutionsWithScope() }
        // 全库扫描被结构性禁止（恢复成本与库大小解耦）
        coVerify(exactly = 0) { repo.loadAllTopLevelConversations() }
        coVerify(exactly = 0) { repo.getAllChildConversationIds() }
    }

    @Test
    fun `I8 empty index short-circuits without loading any conversation`() = runTest {
        val repo = mockk<ConversationRepository>(relaxed = true)
        val registry = mockk<ConversationRuntimeRegistry>(relaxed = true)
        val settingsStore = mockk<SettingsStore>(relaxed = true)
        val runGate = mockk<SubAssistantRunGate>(relaxed = true)
        coEvery { repo.getNonTerminalTurnExecutionsWithScope() } returns emptyList()
        every { settingsStore.settingsFlow } returns MutableStateFlow(mockk(relaxed = true))

        val recovery = turnRecovery(repo, registry, settingsStore, runGate)
        recovery.recoverInterruptedRuns()

        // 健康库（无非终态 turn）：零会话加载、零恢复树写入
        coVerify(exactly = 0) { repo.getConversationById(any()) }
        coVerify(exactly = 0) { repo.loadAllTopLevelConversations() }
        // 恢复入口取消全部运行中资源（lease + pending ask_user）
        coVerify(exactly = 1) { runGate.cancelAllRuns("app_restarted") }
        coVerify(exactly = 1) { runGate.cancelPendingInteractions() }
    }
}
