package net.weero.measix.pilot.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.workspace.RootfsPath
import me.rerere.workspace.WorkspaceManager

internal const val SHELL_TIMEOUT_MAX_SECONDS = 600L

internal data class WorkspaceWriteArguments(val path: RootfsPath, val text: String, val overwrite: Boolean)
internal data class WorkspaceEditArguments(
    val path: RootfsPath,
    val oldText: String,
    val newText: String,
    val replaceAll: Boolean,
)
internal data class WorkspaceShellArguments(val command: String, val cwd: String, val timeoutMillis: Long)

/** These parsers are pure and shared by validation, approval classification, and execution. */
internal fun parseWorkspaceReadArguments(input: JsonElement): RootfsPath =
    RootfsPath.parse(input.objectArguments().requiredString("path"))

internal fun parseWorkspaceWriteArguments(input: JsonElement): WorkspaceWriteArguments {
    val args = input.objectArguments()
    return WorkspaceWriteArguments(
        RootfsPath.parse(args.requiredString("path")),
        args.requiredString("text"),
        args.optionalBoolean("overwrite", true),
    )
}

internal fun parseWorkspaceEditArguments(input: JsonElement): WorkspaceEditArguments {
    val args = input.objectArguments()
    return WorkspaceEditArguments(
        RootfsPath.parse(args.requiredString("path")),
        args.requiredString("old_text").also { require(it.isNotEmpty()) { "old_text must not be empty" } },
        args.requiredString("new_text"),
        args.optionalBoolean("replace_all", false),
    )
}

internal fun parseWorkspaceShellArguments(input: JsonElement, defaultCwd: String? = null): WorkspaceShellArguments {
    val args = input.objectArguments()
    val timeout = args["timeout"]?.let {
        val number = it as? JsonPrimitive
        require(number != null && !number.isString) { "timeout must be an integer" }
        requireNotNull(number.longOrNull) { "timeout must be an integer" }.also { value ->
            require(value in 1..SHELL_TIMEOUT_MAX_SECONDS) {
                "timeout must be between 1 and $SHELL_TIMEOUT_MAX_SECONDS seconds"
            }
        } * 1_000
    } ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
    val cwd = if (args.containsKey("cwd")) args.requiredString("cwd") else defaultCwd.orEmpty()
    return WorkspaceShellArguments(
        args.requiredString("command").also { require(it.isNotBlank()) { "command must not be empty" } },
        normalizeWorkspaceCwd(cwd),
        timeout,
    )
}

internal fun normalizeWorkspaceCwd(cwd: String): String {
    val raw = cwd.trim()
    val path = RootfsPath.parse(if (raw.startsWith('/')) raw else "/workspace/$raw").value
    require(path == "/workspace" || path.startsWith("/workspace/")) { "cwd must be inside /workspace" }
    return path.removePrefix("/workspace").trimStart('/')
}

internal fun validateWorkspaceArguments(parse: () -> Any): JsonObject? = try {
    parse()
    null
} catch (error: IllegalArgumentException) {
    buildJsonObject {
        put("error", "invalid_arguments")
        put("detail", error.message ?: "Invalid workspace arguments")
    }
}

private fun JsonElement.objectArguments(): JsonObject =
    this as? JsonObject ?: throw IllegalArgumentException("Arguments must be a JSON object")

private fun JsonObject.requiredString(name: String): String {
    require(containsKey(name)) { "$name is required" }
    val value = this[name] as? JsonPrimitive
    require(value != null && value.isString) { "$name must be a string" }
    return value.content
}

private fun JsonObject.optionalBoolean(name: String, default: Boolean): Boolean {
    if (!containsKey(name)) return default
    val value = this[name] as? JsonPrimitive
    require(value != null && !value.isString && value.booleanOrNull != null) { "$name must be a boolean" }
    return requireNotNull(value.booleanOrNull)
}
