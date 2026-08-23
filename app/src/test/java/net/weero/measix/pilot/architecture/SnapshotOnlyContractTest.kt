package net.weero.measix.pilot.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * I7 快照唯一事实源契约（V1 正式阶段·架构收敛 §12 工作流 O）。
 *
 * 兼容投影退役后，运行时状态形状唯一（ConversationSnapshot）：
 *  - 全库无 `getConversationFlow` 引用（兼容流 API 已物理删除）
 *  - `ConversationRuntime` 无第二状态流（`_compatibleState` / `state` 投影）
 *  - `ConversationSnapshot` 无 `conversation` 投影 getter（读取走 renderNodes/currentMessages()）
 */
class SnapshotOnlyContractTest {

    private val srcDir: File = File("src/main/java/net/weero/measix/pilot")

    private fun mainSources(): List<File> =
        srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun referencingFiles(call: String): List<String> =
        mainSources()
            .filter { it.readText().contains(call) }
            .map { it.relativeTo(File("src/main/java/net/weero/measix/pilot")).path }

    @Test
    fun `I7 no references to removed compatible projection API`() {
        // 精确匹配调用/定义（getConversationFlow( ），不误伤 DAO 的 getConversationFlowById
        val violations = referencingFiles("getConversationFlow(")
        assertTrue(
            "getConversationFlow 兼容流已退役（snapshot 为唯一事实流），违规文件：$violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun `I7 ConversationRuntime has a single state flow`() {
        val runtimeFile = File(srcDir, "service/runtime/ConversationRuntime.kt")
        assertTrue("ConversationRuntime.kt 应存在", runtimeFile.isFile)
        val text = runtimeFile.readText()
        assertTrue(
            "ConversationRuntime 不得维护第二状态流（_compatibleState）",
            !text.contains("_compatibleState"),
        )
        assertTrue(
            "ConversationRuntime 不得暴露兼容投影 state 流（snapshot 为唯一事实流）",
            !text.contains("val state: StateFlow"),
        )
    }

    @Test
    fun `I7 ConversationSnapshot has no legacy conversation projection getter`() {
        val commandsFile = File(srcDir, "service/runtime/ConversationCommands.kt")
        assertTrue("ConversationCommands.kt 应存在", commandsFile.isFile)
        val text = commandsFile.readText()
        assertTrue(
            "ConversationSnapshot 不得提供 conversation 投影 getter（持久化边界转换走顶层 toConversation 纯函数）",
            !text.contains("val conversation: Conversation"),
        )
    }
}
