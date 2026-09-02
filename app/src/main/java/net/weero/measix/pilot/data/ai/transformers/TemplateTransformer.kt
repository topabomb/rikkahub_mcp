package net.weero.measix.pilot.data.ai.transformers

import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.utils.toLocalDate
import net.weero.measix.pilot.utils.toLocalTime
import java.io.StringWriter
import kotlin.time.toJavaInstant

class TemplateTransformer(
    private val engine: PebbleEngine,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val templateText = ctx.promptInputs.messageTemplate ?: "{{ message }}"
        val template = engine.getLiteralTemplate(templateText)
        val timeZone = TimeZone.of(ctx.promptInputs.zoneId)
        return messages.map { message ->
            // 本次请求由管线合成的内容（System、时间提醒、模式注入、Workspace 提醒）不是用户消息，
            // 不应被用户的 messageTemplate 二次包裹。
            if (ctx.requestOrigins.isSynthetic(message)) return@map message
            // 使用消息本身的发送时间而不是当前时间, 保证多次请求时渲染结果稳定, 不破坏 prompt 缓存
            val createdAt = message.createdAt.toInstant(timeZone).toJavaInstant()
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            val result = StringWriter()
                            template.evaluate(
                                result, mapOf(
                                    "message" to part.text,
                                    "role" to message.role.name.lowercase(),
                                    "time" to createdAt.toLocalTime(),
                                    "date" to createdAt.toLocalDate(),
                                    "description" to ctx.assistant.description,
                                )
                            )
                            part.copy(
                                text = result.toString()
                            )
                        }

                        else -> part
                    }
                }
            )
        }
    }
}
