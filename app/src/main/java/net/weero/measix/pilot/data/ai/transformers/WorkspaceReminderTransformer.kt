package net.weero.measix.pilot.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.entity.WorkspaceEntity
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定了一个 shell 已就绪的 workspace 时, 在系统提示词中追加一段引导,
 * 让模型了解 workspace 环境与 workspace_* 工具的使用方式。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        // 仅在 shell 就绪时注入，保持提示与实际工具能力一致。
        if (workspace.resolvedShellStatus() != WorkspaceShellStatus.READY) return messages

        // 设计权衡：不注入 cwd（当前工作目录）。模型自己执行了 cd，结果已记录在对话历史中。
        // 注入 cwd 到 System 消息会导致任何目录切换都破坏整条请求的缓存前缀。
        val prompt = buildWorkspacePrompt(workspace)

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                val rewritten = this[systemIndex].appendText("\n\n$prompt")
                this[systemIndex] = rewritten
                // 被本次请求改写过的 System 属于合成内容，不再走用户模板
                ctx.requestOrigins.markSynthetic(rewritten)
            }
        } else {
            val created = UIMessage.system(prompt)
            ctx.requestOrigins.markSynthetic(created)
            listOf(created) + messages
        }
    }
}

private fun buildWorkspacePrompt(workspace: WorkspaceEntity): String = buildString {
    appendLine("<workspace>")
    appendLine("You have access to a persistent Linux workspace named \"${workspace.name}\", running in a sandboxed proot rootfs environment.")
    appendLine("- The workspace files area is mounted at `/workspace`. Use it as your working directory; files written there persist across turns of this conversation.")
    appendLine("- All paths passed to workspace tools must be absolute and inside the Rootfs (for example `/workspace/notes.md`).")
    appendLine("- Available tools:")
    appendLine("  - `workspace_read_file`: read file contents.")
    appendLine("  - `workspace_write_file` / `workspace_edit_file`: create files, or make precise edits to existing files.")
    appendLine("  - `workspace_shell`: run shell commands (the files area is mounted at /workspace).")
    appendLine("- Prefer `workspace_shell` for tasks that standard Unix tools handle well, and prefer `workspace_edit_file` for targeted edits over rewriting whole files.")
    appendLine("- The skills directory is mounted at `/skills`. Each skill is a subdirectory `/skills/<skill-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions.")
    appendLine("- Files shared in this conversation — uploads and generated media — are mounted at `/upload`. Treat `/upload` as READ-ONLY: read files from `/upload/<file-name>`, but never modify, overwrite, or delete anything there. If you need to change a file, copy it into `/workspace` first and edit the copy.")
    append("</workspace>")
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
