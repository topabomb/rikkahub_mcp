package net.weero.measix.pilot.service.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import me.rerere.ai.ui.ProviderToolCallSlot
import me.rerere.ai.ui.ToolResultStatus
import kotlin.uuid.Uuid
import net.weero.measix.pilot.data.configuration.ModelSelectionRole
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.RequestCredentials
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart

/** External I/O selection consumes the same assembled request for real and local example models. */
internal suspend fun ModelRequestTarget.streamText(
    providers: ProviderManager,
    messages: List<ModelRequestMessage>,
    params: TextGenerationParams,
): Flow<MessageChunk> = when (this) {
    is ModelRequestTarget.Remote -> providers.getProviderByType(provider).streamText(
        provider, messages, requestParams(params),
    )
    is ModelRequestTarget.LocalExample -> flow {
        verifyRequest()
        val response = exampleResponse(messages, params)
        if (response.getTools().isNotEmpty()) {
            yield()
            emit(exampleChunk(params, response, stream = true, finished = true))
        } else {
            response.toText().chunked(12).forEach { text ->
                yield()
                emit(exampleChunk(params, UIMessage.assistant(text), stream = true, finished = false))
            }
            emit(exampleChunk(params, UIMessage.assistant(""), stream = true, finished = true))
        }
    }
}

internal suspend fun ModelRequestTarget.generateText(
    providers: ProviderManager,
    messages: List<ModelRequestMessage>,
    params: TextGenerationParams,
    role: ModelSelectionRole = ModelSelectionRole.CHAT,
): MessageChunk = when (this) {
    is ModelRequestTarget.Remote -> providers.getProviderByType(provider).generateText(
        provider, messages, requestParams(params),
    )
    is ModelRequestTarget.LocalExample -> {
        verifyRequest()
        exampleChunk(params, exampleAuxiliaryResponse(messages, role) ?: exampleResponse(messages, params), stream = false, finished = true)
    }
}

internal suspend fun ModelRequestTarget.generateImage(
    providers: ProviderManager,
    params: ImageGenerationParams,
): Flow<ImageGenerationItem> = when (this) {
    is ModelRequestTarget.Remote -> {
        validateOverrides(params.customHeaders, params.customBody)
        providers.getProviderByType(provider).generateImage(provider,
            params.copy(customHeaders = params.customHeaders + headers, credentials = credentials))
    }
    is ModelRequestTarget.LocalExample -> flow {
        verifyRequest()
        exampleImages(params.model.displayName, params.prompt, params.numOfImages, params.size, 0).collect { emit(it) }
    }
}

internal suspend fun ModelRequestTarget.editImage(
    providers: ProviderManager,
    params: ImageEditParams,
): Flow<ImageGenerationItem> = when (this) {
    is ModelRequestTarget.Remote -> {
        validateOverrides(params.customHeaders, params.customBody)
        providers.getProviderByType(provider).editImage(provider,
            params.copy(customHeaders = params.customHeaders + headers, credentials = credentials))
    }
    is ModelRequestTarget.LocalExample -> flow {
        verifyRequest()
        require(params.images.isNotEmpty() && params.images.all { java.io.File(it).isFile }) { "image_edit_input_unavailable" }
        exampleImages(params.model.displayName, params.prompt, params.numOfImages, params.size, params.images.size).collect { emit(it) }
    }
}

/** A visible simulation result exercises the ordinary image delivery and persistence path. */
private fun exampleImages(model: String, prompt: String, count: Int, size: String, inputCount: Int): Flow<ImageGenerationItem> = flow {
    require(count in 1..4) { "invalid_image_count" }
    val selected = requireNotNull(ImageGenSize.entries.firstOrNull { it.value == size }) { "invalid_image_size" }
    val dimensions = (if (selected == ImageGenSize.AUTO) ImageGenSize.SQUARE_1024.value else selected.value).split('x')
    val width = dimensions[0].toInt()
    val height = dimensions[1].toInt()
    repeat(count) { index ->
        yield()
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.rgb(231, 239, 247))
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(34, 66, 94)
                textSize = width / 28f
            }
            val margin = width / 16f
            canvas.drawText("Enterprise local example", margin, height / 3f, paint)
            paint.textSize = width / 38f
            val operation = if (inputCount == 0) "Simulated generation" else "Simulated edit: $inputCount input images"
            canvas.drawText("$operation (${index + 1}/$count)", margin, height / 2f, paint)
            canvas.drawText(model.take(42), margin, height * 0.62f, paint)
            canvas.drawText(prompt.take(42), margin, height * 0.74f, paint)
            java.io.ByteArrayOutputStream().use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) { "example_image_encoding_failed" }
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
        emit(ImageGenerationItem(data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), mimeType = "image/png", partial = false))
    }
}

private fun ModelRequestTarget.Remote.requestParams(params: TextGenerationParams): TextGenerationParams {
    validateOverrides(params.customHeaders, params.customBody)
    return params.copy(customHeaders = params.customHeaders + headers, credentials = credentials)
}

private fun ModelRequestTarget.Remote.validateOverrides(customHeaders: List<CustomHeader>, customBody: List<CustomBody>) {
    if (credentials is RequestCredentials.Fixed) {
        val ownedHeaders = headers.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet() + setOf(
            "authorization", "proxy-authorization", "x-api-key", "x-goog-api-key", "host",
            "content-length", "transfer-encoding", "connection",
        )
        check(customHeaders.none { it.name.lowercase(java.util.Locale.ROOT) in ownedHeaders }) {
            "enterprise_request_header_conflict"
        }
        check(customBody.none { it.key in setOf("models", "route", "provider") }) {
            "enterprise_request_routing_override"
        }
    }
}

/** The caller supplies the task purpose; customized prompts cannot turn auxiliary work into tool calls. */
private fun exampleAuxiliaryResponse(messages: List<ModelRequestMessage>, role: ModelSelectionRole): UIMessage? = when (role) {
    ModelSelectionRole.TITLE -> UIMessage.assistant("企业示例对话")
    ModelSelectionRole.SUGGESTION -> UIMessage.assistant("查看企业信息\n查看企业公告\n查询企业指南")
    ModelSelectionRole.COMPRESS -> {
        val prompt = messages.lastOrNull { it.role == MessageRole.USER }?.toText().orEmpty()
        val content = if (prompt.contains("<conversation>") && prompt.contains("</conversation>"))
            prompt.substringAfter("<conversation>").substringBeforeLast("</conversation>").trim() else prompt
        UIMessage.assistant("[本地模拟摘要：仅截取输入，未进行语义归纳]\n" + content.take(512) +
            if (content.length > 512) "\n[其余输入已省略]" else "")
    }
    else -> null
}

/** The simulated model emits ordinary calls from this request's frozen tool surface, never executes them. */
private fun exampleResponse(messages: List<ModelRequestMessage>, params: TextGenerationParams): UIMessage {
    val userIndex = messages.indexOfLast { it.role == MessageRole.USER }
    val userTexts = messages.getOrNull(userIndex)?.parts?.filterIsInstance<UIMessagePart.Text>().orEmpty()
    val prompt = userTexts.filter { exampleDisclosure(it.text) == null }.joinToString("\n") { it.text }
    val calls = messages.drop(userIndex + 1).filter { it.role == MessageRole.ASSISTANT }
        .flatMap { it.parts.filterIsInstance<UIMessagePart.Tool>() }
    // Explicit exercise commands use the ordinary frozen tool surface and its normal execution/approval owner.
    val exercise = when {
        prompt.startsWith("演练搜索：") -> "search_web" to buildJsonObject { put("query", prompt.substringAfter("：").trim()) }
        prompt.startsWith("演练技能：") -> "use_skill" to buildJsonObject { put("name", prompt.substringAfter("：").trim()) }
        prompt.trim() == "演练工作空间" -> "workspace_shell" to buildJsonObject { put("command", "pwd") }
        else -> null
    }
    if (exercise != null) {
        calls.lastOrNull { it.toolName == exercise.first }?.let { return exampleToolReply(it) }
        if (params.tools.none { it.name == exercise.first } ||
            exercise.second.values.any { (it as? JsonPrimitive)?.content.isNullOrBlank() }) {
            return UIMessage.assistant("本地模拟：请填写演练内容，并启用及配置相应能力；当前企业规则和工具审批仍然生效。")
        }
        return exampleToolCall(exercise.first, exercise.second)
    }
    val createAssistant = listOf("创建示例子助手", "创建并调用示例子助手", "create example sub-assistant", "create and call example sub-assistant")
        .any { prompt.contains(it, true) }
    if (createAssistant) {
        val delegate = prompt.contains("创建并调用", true) || prompt.contains("create and call", true)
        if (params.tools.none { it.name == "assistant_manage" } || (delegate && params.tools.none { it.name == "assistant_call" })) {
            return UIMessage.assistant("本地模拟：请在当前助手的本地能力中启用助手管理；创建并调用还需启用子助手调用。企业域需允许用户助手。")
        }
        val created = calls.lastOrNull { it.toolName == "assistant_manage" }
        if (created == null) return exampleToolCall("assistant_manage", buildJsonObject {
            put("action", "CREATE")
            put("name", "示例协作助手")
            put("description", "用于验证本域子助手创建、授权与调用的用户助手。")
            put("instructions", "请简短回应收到的请求。你是用于验证企业本地示例的协作助手。")
        })
        if (!created.hasReplayResult || created.resultStatus != ToolResultStatus.COMPLETED) return exampleToolReply(created)
        val result = created.output.filterIsInstance<UIMessagePart.Text>().singleOrNull()?.text?.let {
            try { Json.parseToJsonElement(it) as? JsonObject } catch (_: IllegalArgumentException) { null }
        }
        val id = (result?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (result?.get("action") != JsonPrimitive("create") || id.isNullOrBlank()) return exampleToolReply(created)
        if (!delegate) return exampleToolReply(created)
        calls.lastOrNull { it.toolName == "assistant_call" }?.let { return exampleToolReply(it) }
        return exampleToolCall("assistant_call", buildJsonObject {
            put("assistant_id", id)
            put("request", "请简短确认已收到这次企业空间协作请求。")
        })
    }
    if (listOf("委派", "delegate", "调用已有子助手").any { prompt.contains(it, true) }) {
        calls.lastOrNull { it.toolName == "assistant_call" }?.let { return exampleToolReply(it) }
        val catalog = messages.take(userIndex + 1).filter { it.role == MessageRole.USER }
            .flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
            .mapNotNull { exampleDisclosure(it.text) }.lastOrNull()?.get("sub_assistants") as? JsonObject
        val mode = catalog?.get("mode")?.jsonPrimitive?.content
        val rows = (catalog?.get("rows") as? JsonArray).orEmpty().map { it.jsonArray }
        val target = rows.singleOrNull { row -> prompt.contains(row[1].jsonPrimitive.content, true) }
        if (params.tools.none { it.name == "assistant_call" } || target == null || mode !in listOf(
                ConversationDisclosureSnapshotService.SUB_ASSISTANTS_MODE_BOTH,
                ConversationDisclosureSnapshotService.SUB_ASSISTANTS_MODE_DELEGATION_ONLY)) {
            return UIMessage.assistant("本地模拟：请启用子助手调用，并在请求中指定当前授权目录内的子助手名称。")
        }
        return exampleToolCall("assistant_call", buildJsonObject {
            put("assistant_id", target[0].jsonPrimitive.content)
            put("request", "请整理企业信息并简短回复。")
        })
    }
    val action = when {
        listOf("公告", "动态", "通知", "updates", "notices").any { prompt.contains(it, true) } -> "get_enterprise_updates"
        listOf("指南", "参考", "guide", "reference").any { prompt.contains(it, true) } -> "read_enterprise_guide"
        listOf("企业信息", "企业资料", "profile").any { prompt.contains(it, true) } -> "get_enterprise_profile"
        else -> null
    }
    if (action != null && params.tools.isNotEmpty()) {
        fun unavailable() = UIMessage.assistant("本地模拟：当前助手没有可用的企业工具，或工具尚未返回有效结果。请检查本域资源选择与 Gateway 开关。")
        fun definition(suffix: String) = params.tools.singleOrNull {
            it.name.startsWith("mcp__enterprise_") && it.name.endsWith("__$suffix")
        }
        val direct = action == "get_enterprise_profile"
        val entry = definition(if (direct) action else "discover_tools") ?: return unavailable()
        val invoked = calls.lastOrNull { it.toolName == entry.name }
        if (invoked == null) return exampleToolCall(entry.name, if (direct) buildJsonObject {} else buildJsonObject {
            put("queries", buildJsonArray { add(action) }); put("limitPerQuery", 1)
        })
        if (!invoked.hasReplayResult || invoked.resultStatus != ToolResultStatus.COMPLETED) return unavailable()
        if (direct) return exampleToolReply(invoked)
        val invokeName = entry.name.removeSuffix("discover_tools") + "invoke_tool"
        if (params.tools.none { it.name == invokeName }) return unavailable()
        calls.lastOrNull { it.toolName == invokeName }?.let {
            return if (it.hasReplayResult && it.resultStatus == ToolResultStatus.COMPLETED) exampleToolReply(it) else unavailable()
        }
        val discovery = invoked.output.filterIsInstance<UIMessagePart.Text>().firstNotNullOfOrNull {
            try { (Json.parseToJsonElement(it.text) as? JsonObject)?.get("structured_content") as? JsonObject }
            catch (_: IllegalArgumentException) { null }
        }
        val matches = (discovery?.get("results") as? JsonArray)?.flatMap {
            ((it as? JsonObject)?.get("matches") as? JsonArray).orEmpty()
        }.orEmpty()
        val match = matches.filterIsInstance<JsonObject>().singleOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull == action }
        val ref = (match?.get("toolRef") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: return unavailable()
        return exampleToolCall(invokeName, buildJsonObject {
            put("toolRef", ref)
            put("arguments", buildJsonObject { if (action == "read_enterprise_guide") put("query", prompt) })
        })
    }

    val user = messages.lastOrNull { it.role == MessageRole.USER }
    val images = user?.parts?.count { it is UIMessagePart.Image } ?: 0
    return UIMessage.assistant(buildString {
        append("企业本地示例已收到你的请求。")
        if (images > 0) append("已接收 $images 张图片；本地示例返回模拟结果。")
        append("这是一条本地模拟回复，用于验证企业空间中的对话流程。可尝试：查看企业信息、查看企业公告、查询企业指南。启用助手管理和子助手调用后，也可请求创建并调用示例子助手。")
    })
}

/** Reads only the same model-visible baseline as a real provider; it does not resolve live settings. */
private fun exampleDisclosure(text: String): JsonObject? {
    val root = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: IllegalArgumentException) { null }
    if (root?.get("type") != JsonPrimitive(ConversationDisclosureSnapshotService.CONTENT_TYPE)) return null
    try { ConversationDisclosureSnapshotService.requireDurableEnvelope(text) }
    catch (_: net.weero.measix.pilot.service.DisclosureContentException) { return null }
    return root
}

private fun exampleToolCall(name: String, arguments: JsonObject) = UIMessage(
    role = MessageRole.ASSISTANT,
    parts = listOf(UIMessagePart.Tool(localCallId = Uuid.NIL, stepId = Uuid.NIL,
        providerCallId = "example_${Uuid.random()}", toolName = name, input = arguments.toString())),
)

private fun exampleToolReply(tool: UIMessagePart.Tool): UIMessage {
    val texts = tool.output.filterIsInstance<UIMessagePart.Text>()
    val structured = texts.firstNotNullOfOrNull {
        try { (Json.parseToJsonElement(it.text) as? JsonObject)?.get("structured_content") }
        catch (_: IllegalArgumentException) { null }
    }
    return UIMessage.assistant((if (tool.toolName.startsWith("mcp__enterprise_")) "本地模拟：已通过企业工具取得以下结果。\n"
        else "本地模拟：工具返回了以下结果。\n") +
        (structured?.toString() ?: texts.joinToString("\n") { it.text }).take(6000))
}

private fun exampleChunk(params: TextGenerationParams, message: UIMessage, stream: Boolean, finished: Boolean): MessageChunk {
    return MessageChunk(
        id = "local-example-response",
        model = params.model.modelId,
        choices = listOf(UIMessageChoice(
            index = 0,
            delta = message.takeIf { stream },
            message = message.takeUnless { stream },
            finishReason = (if (message.getTools().isEmpty()) "stop" else "tool_calls").takeIf { finished },
            toolCallSlots = message.getTools().indices.map { ProviderToolCallSlot.Index(it) },
        )),
    )
}
