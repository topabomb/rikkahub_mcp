package net.weero.measix.pilot.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 生成期保活的纯决策：只有 RESPONSE_GENERATION 属于"持续生成"。
 *
 * APPROVAL_REQUIRED（等待用户审批）与 TITLE_GENERATION（标题阶段）都不占用前台保活配额；
 * Target 运行由其 Master 的 RESPONSE_GENERATION 自然覆盖，不需要第二套 Target 保活。
 */
class ChatGenerationForegroundDecisionTest {

    private fun activities(
        generating: Set<Uuid> = emptySet(),
        awaitingApproval: Set<Uuid> = emptySet(),
        titling: Set<Uuid> = emptySet(),
    ): Map<Uuid, Set<ConversationActivity>> {
        val result = linkedMapOf<Uuid, Set<ConversationActivity>>()
        generating.forEach { id -> result[id] = setOf(ConversationActivity.RESPONSE_GENERATION) }
        awaitingApproval.forEach { id -> result[id] = result[id].orEmpty() + ConversationActivity.APPROVAL_REQUIRED }
        titling.forEach { id -> result[id] = result[id].orEmpty() + ConversationActivity.TITLE_GENERATION }
        return result
    }

    @Test
    fun `empty projection does not keep the service alive`() {
        assertFalse(shouldKeepGenerationForeground(emptyMap()))
    }

    @Test
    fun `any RESPONSE_GENERATION keeps the service alive`() {
        assertTrue(shouldKeepGenerationForeground(activities(generating = setOf(Uuid.random()))))
        assertTrue(
            shouldKeepGenerationForeground(
                activities(
                    generating = setOf(Uuid.random()),
                    awaitingApproval = setOf(Uuid.random()),
                    titling = setOf(Uuid.random()),
                )
            )
        )
    }

    @Test
    fun `approval waiting alone does not keep the service alive`() {
        assertFalse(shouldKeepGenerationForeground(activities(awaitingApproval = setOf(Uuid.random()))))
    }

    @Test
    fun `title generation alone does not keep the service alive`() {
        assertFalse(shouldKeepGenerationForeground(activities(titling = setOf(Uuid.random()))))
    }

    @Test
    fun `approval and title combined still do not keep the service alive`() {
        assertFalse(
            shouldKeepGenerationForeground(
                activities(
                    awaitingApproval = setOf(Uuid.random(), Uuid.random()),
                    titling = setOf(Uuid.random()),
                )
            )
        )
    }

    @Test
    fun `multiple active generations are handled without per-conversation state`() {
        val ids = List(10) { Uuid.random() }
        assertTrue(shouldKeepGenerationForeground(activities(generating = ids.toSet())))
    }
}
