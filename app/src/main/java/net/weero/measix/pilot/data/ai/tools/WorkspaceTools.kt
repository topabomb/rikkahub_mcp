package net.weero.measix.pilot.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolInteractionRequirement
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.service.workspace.WorkspaceApplicationService
import net.weero.measix.pilot.service.workspace.WorkspaceToolSession
import net.weero.measix.pilot.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager

private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to true,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceApplicationService: WorkspaceApplicationService,
    approvalOverrides: Map<String, Boolean>,
    artifactStore: ArtifactStore,
    cwd: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    fun approvalRequired(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.let(::normalizeWorkspaceCwd)

    return listOf(
        createReadFileTool(workspaceId, ::approvalRequired, workspaceApplicationService, artifactStore),
        createWriteFileTool(workspaceId, ::approvalRequired, workspaceApplicationService),
        createEditFileTool(workspaceId, ::approvalRequired, workspaceApplicationService),
        createShellTool(workspaceId, ::approvalRequired, workspaceApplicationService, shellCwd),
    )
}

private fun approvalRequirementOf(required: Boolean): ToolInteractionRequirement =
    if (required) ToolInteractionRequirement.Approval else ToolInteractionRequirement.None

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    approvalRequired: (String) -> Boolean,
    workspaceApplicationService: WorkspaceApplicationService,
    artifactStore: ArtifactStore,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a UTF-8 text or image file from the bound workspace Rootfs.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty()
            },
            required = listOf("path"),
        )
    },
    validateArguments = { validateWorkspaceArguments { parseWorkspaceReadArguments(it) } },
    interactionRequirement = {
        parseWorkspaceReadArguments(it)
        approvalRequirementOf(approvalRequired("workspace_read_file"))
    },
    execute = { error("workspace_read_file requires ToolExecutionContext") },
    contextualExecute = {
        val path = parseWorkspaceReadArguments(it).value
        val registerArtifact: (net.weero.measix.pilot.data.files.OwnedArtifact) -> Unit = { owned ->
            registerUnpublishedResource(artifactStore.unpublishedLease(owned))
        }
        workspaceApplicationService.executeTool(workspaceId) {
            if (path.isImagePath()) {
                readImageInRootfs(
                    path,
                    artifactStore,
                    onArtifactCreated = registerArtifact,
                )
            } else {
                val text = readTextInRootfs(path)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("path", path)
                            put("text", text)
                        }.toString()
                    )
                )
            }
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    approvalRequired: (String) -> Boolean,
    workspaceApplicationService: WorkspaceApplicationService,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file in the bound workspace Rootfs.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty()
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    validateArguments = { validateWorkspaceArguments { parseWorkspaceWriteArguments(it) } },
    interactionRequirement = {
        val args = parseWorkspaceWriteArguments(it)
        approvalRequirementOf(approvalRequired("workspace_write_file") || args.path.requiresWriteApproval)
    },
    execute = { error("workspace_write_file requires ToolExecutionContext") },
    contextualExecute = {
        val args = parseWorkspaceWriteArguments(it)
        val approval = approvedByUser
        val entry = workspaceApplicationService.executeTool(workspaceId) {
            writeRootfsText(args.path.value, args.text, args.overwrite, approval)
        }
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    approvalRequired: (String) -> Boolean,
    workspaceApplicationService: WorkspaceApplicationService,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file in the bound workspace Rootfs.
        old_text must occur once unless replace_all=true. If no exact match, whitespace-tolerant matching is tried.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty()
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    validateArguments = { validateWorkspaceArguments { parseWorkspaceEditArguments(it) } },
    interactionRequirement = {
        val args = parseWorkspaceEditArguments(it)
        approvalRequirementOf(approvalRequired("workspace_edit_file") || args.path.requiresWriteApproval)
    },
    execute = { error("workspace_edit_file requires ToolExecutionContext") },
    contextualExecute = {
        val args = parseWorkspaceEditArguments(it)
        val path = args.path.value
        val approval = approvedByUser

        workspaceApplicationService.executeTool(workspaceId) {
            var original = ""
            lateinit var result: ReplaceTextResult
            val entry = updateRootfsText(path, MAX_READ_FILE_BYTES, approval) { content ->
                original = content
                result = replaceText(content, args.oldText, args.newText, args.replaceAll)
                result.updated
            }
            val diff = generateUnifiedDiff(original, result.updated, entry.path)
            listOf(
                UIMessagePart.Text(
                    text = buildJsonObject {
                        put("path", entry.path)
                        put("replacements", result.replacements)
                        if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                        put("sizeBytes", entry.sizeBytes)
                        put("updatedAt", entry.updatedAt)
                    }.toString(),
                    // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                    metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
                )
            )
        }
    },
)

private fun createShellTool(
    workspaceId: String,
    approvalRequired: (String) -> Boolean,
    workspaceApplicationService: WorkspaceApplicationService,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the bound workspace Rootfs. ")
        append("cwd is relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'.")
        }
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to ${WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS / 1_000}, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    validateArguments = { validateWorkspaceArguments { parseWorkspaceShellArguments(it, defaultCwd) } },
    interactionRequirement = {
        parseWorkspaceShellArguments(it, defaultCwd)
        approvalRequirementOf(approvalRequired("workspace_shell"))
    },
    execute = {
        val args = parseWorkspaceShellArguments(it, defaultCwd)
        val result = workspaceApplicationService.executeTool(workspaceId) {
            executeCommand(args.command, args.cwd, args.timeoutMillis)
        }
        val output = listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("status", if (result.timedOut || result.exitCode != 0) "failed" else "completed")
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }.toString()
            )
        )
        when {
            result.timedOut -> failToolResult(output, "shell_timeout")
            result.exitCode != 0 -> failToolResult(output, "shell_exit_nonzero")
            else -> output
        }
    },
)

private suspend fun WorkspaceToolSession.readTextInRootfs(path: String): String =
    readRootfsBytes(path, MAX_READ_FILE_BYTES).toString(Charsets.UTF_8)

private suspend fun WorkspaceToolSession.readImageInRootfs(
    path: String,
    artifactStore: ArtifactStore,
    onArtifactCreated: (net.weero.measix.pilot.data.files.OwnedArtifact) -> Unit,
): List<UIMessagePart> {
    val bytes = readRootfsBytes(path, MAX_READ_FILE_BYTES)

    // 工具读取沙箱文件产生的副本——系统产物
    val owned = artifactStore.createFromBytes(
        bytes = bytes,
        displayName = "image.png",
        mimeType = "image/png",
        origin = ArtifactOrigin.SYSTEM,
    )
    onArtifactCreated(owned)
    return listOf(
        workspaceImagePart(owned.uri.toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

/** Workspace image results are durable Tool.output media and therefore always carry a handle. */
internal fun workspaceImagePart(uri: String): UIMessagePart.Image =
    AttachmentRefs.ensureAttachmentRef(UIMessagePart.Image(url = uri)) as UIMessagePart.Image

private fun JsonObjectBuilder.putPathProperty() {
    put("path", buildJsonObject {
        put("type", "string")
        put("description", "Absolute path inside Rootfs. Use /workspace for workspace files. Symbolic-link paths are not supported by file tools; use workspace_shell if needed.")
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
