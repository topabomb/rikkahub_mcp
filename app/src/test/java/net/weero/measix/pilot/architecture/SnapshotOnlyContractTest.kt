package net.weero.measix.pilot.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runtime exposes one authoritative state shape: ConversationSnapshot. */
class SnapshotOnlyContractTest {

    private val srcDir: File = File("src/main/java/net/weero/measix/pilot")

    private fun mainSources(): List<File> =
        srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun referencingFiles(call: String): List<String> =
        mainSources()
            .filter { it.readText().contains(call) }
            .map { it.relativeTo(File("src/main/java/net/weero/measix/pilot")).path }

    @Test
    fun `no references to removed conversation flow projection`() {
        // 精确匹配调用/定义，避免把无关的 Flow 查询误判为聚合兼容投影。
        val violations = referencingFiles("getConversationFlow(")
        assertTrue(
            "getConversationFlow 已退役（snapshot 为唯一事实流），违规文件：$violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun `ConversationRuntime has a single state flow`() {
        val runtimeFile = File(srcDir, "service/runtime/ConversationRuntime.kt")
        assertTrue("ConversationRuntime.kt 应存在", runtimeFile.isFile)
        val text = runtimeFile.readText()
        assertTrue(
            "ConversationRuntime 不得维护第二状态流（_compatibleState）",
            !text.contains("_compatibleState"),
        )
        assertTrue(
            "ConversationRuntime 不得暴露第二条 state 流（snapshot 为唯一事实流）",
            !text.contains("val state: StateFlow"),
        )
    }

    @Test
    fun `ConversationSnapshot has no conversation projection getter`() {
        val commandsFile = File(srcDir, "service/runtime/ConversationCommands.kt")
        assertTrue("ConversationCommands.kt 应存在", commandsFile.isFile)
        val text = commandsFile.readText()
        assertTrue(
            "ConversationSnapshot 不得提供 Conversation 兼容投影 getter",
            !text.contains("val conversation: Conversation"),
        )
    }
}
