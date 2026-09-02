package net.weero.measix.pilot.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runtime exposes one authoritative state shape: ConversationAggregateSnapshot. */
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
    fun `presentation and query ports do not expose aggregate model context`() {
        val presentation = File(srcDir, "service/runtime/ConversationPresentation.kt").readText()
        val query = File(srcDir, "service/ConversationQueryService.kt").readText()
        val publicQueryShapes = query.substringAfter("data class ConversationSummary").substringBefore("class ConversationQueryService")
        val conversation = File(srcDir, "data/model/Conversation.kt").readText()
        val entry = File(srcDir, "data/model/ConversationModelContextEntry.kt").readText()
        assertTrue(!conversation.contains("modelContextEntries"))
        assertTrue(entry.contains("internal data class ConversationModelContextEntry"))
        assertTrue(!presentation.contains("val modelContextEntries"))
        assertTrue(!publicQueryShapes.contains("ConversationAggregateSnapshot"))
        assertTrue(!publicQueryShapes.contains("ConversationModelContextEntry"))
    }

    @Test
    fun `UI sources cannot import aggregate context or runtime owners`() {
        val uiRoot = File(srcDir, "ui")
        val forbidden = listOf(
            "ConversationAggregateSnapshot",
            "ConversationModelContextEntry",
            "ConversationModelContextDAO",
            "ConversationRepository",
            "ConversationRuntimeRegistry",
        )
        val violations = uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val imports = file.readLines().filter { it.startsWith("import ") }
                forbidden.asSequence().filter { name -> imports.any { it.endsWith(".$name") } }
                    .map { file.path to it }
            }
            .toList()
        assertTrue("UI leaked internal conversation owners: $violations", violations.isEmpty())
    }

    @Test
    fun `ConversationAggregateSnapshot has no conversation projection getter`() {
        val commandsFile = File(srcDir, "service/runtime/ConversationCommands.kt")
        assertTrue("ConversationCommands.kt 应存在", commandsFile.isFile)
        val text = commandsFile.readText()
        assertTrue(
            "ConversationAggregateSnapshot 不得提供 Conversation 兼容投影 getter",
            !text.contains("val conversation: Conversation"),
        )
    }
}
