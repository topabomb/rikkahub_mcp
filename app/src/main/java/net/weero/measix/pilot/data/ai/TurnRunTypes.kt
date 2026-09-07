package net.weero.measix.pilot.data.ai

import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.ToolOutputArchive
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import kotlin.uuid.Uuid

/** 单个 Tool Call 的执行事实：loop 在不可逆副作用前后产出，reducer 归约为 durable tool_execution 行。 */
data class ToolExecutionFact(
    val executionId: String,
    val assistantMessageId: Uuid,
    val stepId: Uuid,
    val localCallId: Uuid,
    val providerCallId: String,
    val toolName: String,
    val status: ToolExecutionStatus,
    /** 委派类工具派生的 Child 会话 id（调用↔Child 关系归位到执行事实行）。 */
    val childConversationId: String? = null,
    /** assistant_call 派生的 Child Turn id。 */
    val childTurnId: String? = null,
    /** assistant_call 的稳定运行 id。 */
    val subAssistantRunId: String? = null,
)

/** 对一个已消费历史 Tool Result 的窄压缩改写；不得携带整条历史消息。 */
data class ToolOutputCompactionPatch(
    val locator: ToolCallLocator,
    val marker: UIMessagePart.Text,
    /** 可归档正文有 Artifact；可再生回查结果只折叠 marker。 */
    val archive: ToolOutputArchive? = null,
)

/** Typed presentation fact committed with a tool-result message checkpoint. */
data class ToolResultFact(
    val locator: ToolCallLocator,
    val status: ToolResultStatus,
)
