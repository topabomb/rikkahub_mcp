package net.weero.measix.pilot.data.ai.transformers

import net.weero.measix.pilot.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceShellStatus

/** Captures the stable model-visible Workspace disclosure before START. */
internal fun buildWorkspacePrompt(workspace: WorkspaceEntity): String? {
    if (workspace.resolvedShellStatus() != WorkspaceShellStatus.READY) return null
    return buildString {
        appendLine("<workspace>")
    appendLine("You have access to a persistent Linux workspace named \"${workspace.name}\", running in a proot rootfs environment.")
    appendLine("- The workspace files area is mounted at `/workspace`. Use it as your working directory; this directory is explicitly shared across conversations and spaces, and its files persist.")
    appendLine("- All paths passed to workspace tools must be absolute and inside the Rootfs (for example `/workspace/notes.md`).")
    appendLine("- Before working on files, use `workspace_read_file` to read `/root/.agents/AGENTS.md`, `/workspace/AGENTS.md`, and any AGENTS.md in the applicable project directories. Missing instruction files are optional. Apply directory instructions only within their scope, with more specific instructions taking precedence; they do not override the user's request or tool permissions.")
    appendLine("- Available tools:")
    appendLine("  - `workspace_read_file`: read file contents.")
    appendLine("  - `workspace_write_file` / `workspace_edit_file`: create files, or make precise edits to existing files.")
    appendLine("  - `workspace_shell`: run shell commands (the files area is mounted at /workspace).")
    appendLine("- Prefer `workspace_shell` for tasks that standard Unix tools handle well, and prefer `workspace_edit_file` for targeted edits over rewriting whole files.")
    appendLine("- The skills directory is mounted at `/skills`. Each skill is a subdirectory `/skills/<skill-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions.")
    appendLine("- Use `workspace_read_file` to read an authorized `/upload/<file-name>` directly; file tools never write or edit original uploads. For `workspace_shell`, list the exact needed paths in `uploads`; only those files are copied into this invocation's `/upload`, which is empty by default. These copies are removed when the command ends and modifications never change original attachments. Copy results to `/workspace` only when they should persist in the shared workspace.")
        append("</workspace>")
    }
}
