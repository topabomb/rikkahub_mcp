package net.weero.measix.pilot.data.ai.subassistant

import net.weero.measix.pilot.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * 统一计算显式允许与全局可见的有效集合。
 * 供 UI、Catalog、Tool 执行和运行中撤权复用。
 *
 * 有效访问公式（设计文档 §4.1）：
 * ```
 * Target.allowAsSubAssistant
 * && Target.id != Caller.id
 * && (Target.id in Caller.allowedSubAssistantIds || Target.isSubAssistantGloballyVisible)
 * ```
 */
object SubAssistantAccessPolicy {

    /**
     * 判断 caller 是否能访问指定 Target。
     * 允许列表中的缺失 ID、普通 Assistant ID 和 caller 自身 ID 在读取时忽略。
     */
    fun canAccess(caller: Assistant, target: Assistant): Boolean {
        if (!target.allowAsSubAssistant) return false
        if (target.id == caller.id) return false
        return target.id in caller.allowedSubAssistantIds || target.isSubAssistantGloballyVisible
    }

    /**
     * 计算 caller 可访问的全部子助手，保持 Settings.assistants 中的用户顺序。
     */
    fun accessibleSubAssistants(caller: Assistant, allAssistants: List<Assistant>): List<Assistant> {
        return allAssistants.filter { canAccess(caller, it) }
    }

    /**
     * 判断 caller 是否启用了管理或调用工具。
     */
    fun hasSubAssistantTools(caller: Assistant): Boolean {
        return caller.localTools.any {
            it == net.weero.measix.pilot.data.ai.tools.local.LocalToolOption.AssistantManagement ||
                it == net.weero.measix.pilot.data.ai.tools.local.LocalToolOption.AssistantDelegation
        }
    }
}
