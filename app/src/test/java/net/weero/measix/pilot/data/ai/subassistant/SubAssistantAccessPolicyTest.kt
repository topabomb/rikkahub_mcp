package net.weero.measix.pilot.data.ai.subassistant

import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.model.Assistant
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SubAssistantAccessPolicy 访问边界回归测试。
 *
 * 覆盖：默认空、显式允许、全局可见、两者并集、caller/普通 Assistant/失效 ID 排除、稳定 Settings 顺序。
 */
class SubAssistantAccessPolicyTest {
    private fun makeAssistant(
        id: Uuid = Uuid.random(),
        allowSub: Boolean = true,
        globallyVisible: Boolean = false,
        allowedIds: Set<Uuid> = emptySet(),
        subTools: Boolean = false,
    ) = Assistant(
        id = id,
        name = "Assistant-$id",
        description = "test",
        allowAsSubAssistant = allowSub,
        isSubAssistantGloballyVisible = globallyVisible,
        allowedSubAssistantIds = allowedIds,
        localTools = if (subTools) listOf(LocalToolOption.AssistantDelegation) else emptyList(),
    )

    @Test
    fun `default empty - no accessible targets`() {
        val caller = makeAssistant(allowSub = false, subTools = true)
        val all = listOf(caller)
        val result = SubAssistantAccessPolicy.accessibleSubAssistants(caller, all)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `explicit allow - target accessible`() {
        val target = makeAssistant()
        val caller = makeAssistant(allowSub = false, allowedIds = setOf(target.id), subTools = true)
        val all = listOf(caller, target)
        val result = SubAssistantAccessPolicy.accessibleSubAssistants(caller, all)
        assertEquals(1, result.size)
        assertEquals(target.id, result[0].id)
    }

    @Test
    fun `globally visible - target accessible without explicit allow`() {
        val target = makeAssistant(globallyVisible = true)
        val caller = makeAssistant(allowSub = false, allowedIds = emptySet(), subTools = true)
        val all = listOf(caller, target)
        val result = SubAssistantAccessPolicy.accessibleSubAssistants(caller, all)
        assertEquals(1, result.size)
        assertEquals(target.id, result[0].id)
    }

    @Test
    fun `both explicit and global - union deduplicated`() {
        val target = makeAssistant(globallyVisible = true)
        val caller = makeAssistant(allowSub = false, allowedIds = setOf(target.id), subTools = true)
        val all = listOf(caller, target)
        val result = SubAssistantAccessPolicy.accessibleSubAssistants(caller, all)
        assertEquals(1, result.size)
    }

    @Test
    fun `caller self excluded`() {
        val caller = makeAssistant(allowSub = true, globallyVisible = true, subTools = true)
        val all = listOf(caller)
        val result = SubAssistantAccessPolicy.accessibleSubAssistants(caller, all)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `normal assistant excluded`() {
        val normalTarget = makeAssistant(allowSub = false)
        val caller = makeAssistant(allowSub = false, allowedIds = setOf(normalTarget.id), subTools = true)
        val all = listOf(caller, normalTarget)
        val result = SubAssistantAccessPolicy.accessibleSubAssistants(caller, all)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `invalid ID in allowed list ignored`() {
        val ghostId = Uuid.random()
        val caller = makeAssistant(allowSub = false, allowedIds = setOf(ghostId), subTools = true)
        val all = listOf(caller)
        val result = SubAssistantAccessPolicy.accessibleSubAssistants(caller, all)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `stable Settings order preserved`() {
        val a1 = makeAssistant(globallyVisible = true)
        val a2 = makeAssistant(globallyVisible = true)
        val a3 = makeAssistant(globallyVisible = true)
        val caller = makeAssistant(allowSub = false, subTools = true)
        val all = listOf(a2, a1, a3, caller)
        val result = SubAssistantAccessPolicy.accessibleSubAssistants(caller, all)
        assertEquals(3, result.size)
        assertEquals(a2.id, result[0].id)
        assertEquals(a1.id, result[1].id)
        assertEquals(a3.id, result[2].id)
    }

    @Test
    fun `canAccess - normal assistant returns false`() {
        val caller = makeAssistant(allowSub = false, subTools = true)
        val target = makeAssistant(allowSub = false)
        assertFalse(SubAssistantAccessPolicy.canAccess(caller, target))
    }

    @Test
    fun `canAccess - self returns false`() {
        val caller = makeAssistant(allowSub = true, globallyVisible = true, subTools = true)
        assertFalse(SubAssistantAccessPolicy.canAccess(caller, caller))
    }

    @Test
    fun `hasSubAssistantTools - delegation only`() {
        val caller = makeAssistant(allowSub = false, subTools = false).copy(
            localTools = listOf(LocalToolOption.AssistantDelegation)
        )
        assertTrue(SubAssistantAccessPolicy.hasSubAssistantTools(caller))
    }

    @Test
    fun `hasSubAssistantTools - management only`() {
        val caller = makeAssistant(allowSub = false, subTools = false).copy(
            localTools = listOf(LocalToolOption.AssistantManagement)
        )
        assertTrue(SubAssistantAccessPolicy.hasSubAssistantTools(caller))
    }

    @Test
    fun `hasSubAssistantTools - no tools`() {
        val caller = makeAssistant(allowSub = false, subTools = false)
        assertFalse(SubAssistantAccessPolicy.hasSubAssistantTools(caller))
    }
}
