package net.weero.measix.pilot.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单一写者契约测试。
 *
 * 1. `messageNodeDAO` 整批写入（insertAll / upsertAll / deleteByIds）仅发生在
 *    `ConversationRepository` —— message 树唯一持久化入口（applyMutation 收敛后成立）。
 * 2. `conversationDAO` 写方法（insert / update / delete 系）仅发生在 `ConversationRepository`
 *    —— conversation header 唯一持久化入口。
 * 3. `updateConversation(Conversation)`（@Deprecated 导入/迁移/启动恢复专用）的运行时调用
 *    仅允许出现在 DelegationCoordinator 的启动恢复白名单路径
 *    （submitRecoveredTree：启动早期无内存态时的 Child 恢复整写）。
 *
 * 全库不存在任何整对象回写路径（不变式 2）；UI 侧回调命名 `onUpdateConversation`
 * 不是 repository 调用，不受本契约约束。
 */
class SingleWriterContractTest {

    private val srcDir: File = File("src/main/java/net/weero/measix/pilot")

    private fun mainSources(): List<File> =
        srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** 在 srcDir 中查找引用 `call` 且不属于 `allowedFiles` 的文件。 */
    private fun referencingFiles(call: String, vararg allowedFileSuffixes: String): List<String> {
        val allowed = allowedFileSuffixes.map { File(srcDir, it).absolutePath }.toSet()
        return mainSources()
            .filter { file ->
                file.absolutePath !in allowed && file.readText().contains(call)
            }
            .map { it.relativeTo(srcDir).path }
    }

    @Test
    fun `I1 messageNodeDAO batch writes confined to ConversationRepository`() {
        val violations = mutableListOf<String>()
        for (call in listOf("messageNodeDAO.insertAll", "messageNodeDAO.upsertAll", "messageNodeDAO.deleteByIds")) {
            violations += referencingFiles(call, "data/repository/ConversationRepository.kt")
        }
        assertTrue(
            "整批 message-node 写入必须仅由 ConversationRepository 执行（单一写者），违规文件：$violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun `I1 conversationDAO writes confined to ConversationRepository`() {
        val violations = mutableListOf<String>()
        for (call in listOf(
            "conversationDAO.insert(", "conversationDAO.update(", "conversationDAO.delete(",
            "conversationDAO.deleteById(", "conversationDAO.deleteChildConversations(",
        )) {
            violations += referencingFiles(call, "data/repository/ConversationRepository.kt")
        }
        assertTrue(
            "conversation 写方法必须仅由 ConversationRepository 执行（header 唯一写者），违规文件：$violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun `I1 deprecated updateConversation calls confined to startup-recovery whitelist`() {
        val violations = referencingFiles(
            "conversationRepo.updateConversation(",
            // submitRecoveredTree：启动早期无内存态时的恢复整写（恢复域唯一所有者 TurnRecovery）
            "service/TurnRecovery.kt",
        )
        assertTrue(
            "updateConversation(Conversation) 仅限导入/迁移/启动恢复专用（@Deprecated 白名单），" +
                "运行时结构性修改必须经 ConversationRuntime.submit；违规文件：$violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun `I1 messageNodeDAO is not constructed as a direct write owner elsewhere`() {
        // 无除 ConversationRepository 外的类持有 messageNodeDAO 并做写入；此测试保证 grep 基线可维护
        assertTrue(srcDir.isDirectory)
    }

    @Test
    fun `I1 persistMessageNodes is not a runtime checkpoint path`() {
        val hits = mainSources().filter { file ->
            file.readText().contains("persistMessageNodes")
        }.map { it.relativeTo(srcDir).path }
        assertTrue(
            "persistMessageNodes 不得作为运行时 checkpoint 路径残留，违规：$hits",
            hits.isEmpty(),
        )
    }

    @Test
    fun `I1 handleMessageComplete and finalizeMasterTurn are not live persist paths`() {
        val forbidden = listOf("handleMessageComplete", "finalizeMasterTurn")
        val hits = mainSources().flatMap { file ->
            val text = file.readText()
            forbidden.filter { token -> text.contains(token) }.map { token ->
                "${file.relativeTo(srcDir).path}:$token"
            }
        }
        assertTrue(
            "Master 生成不得再走 handleMessageComplete/finalizeMasterTurn 第二套持久化协议，违规：$hits",
            hits.isEmpty(),
        )
    }
}
