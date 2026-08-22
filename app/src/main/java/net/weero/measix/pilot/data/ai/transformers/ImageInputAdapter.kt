package net.weero.measix.pilot.data.ai.transformers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.findModelById
import net.weero.measix.pilot.data.datastore.findProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration.Companion.days

private const val TAG = "ImageInputAdapter"

class CurrentImageInputUnavailableException : IllegalStateException(
    "The current chat model cannot read the submitted image and no compatible OCR model is configured."
)

object ImageInputAdapter : KoinComponent {
    const val RENDITION_VERSION = 1
    const val HISTORICAL_UNAVAILABLE = "[Historical attachment unavailable to the current model]"
    const val CHAT_PLACEHOLDER = "[Image]"
    const val OBSERVATION_FAILED = "[ERROR, visual observation failed]"

    private val cacheLock = Any()
    private var cache: LruCache<String, String>? = null

    fun resolveCapability(model: Model, settings: Settings): ImageAdaptCapability {
        if (model.inputModalities.contains(Modality.IMAGE)) {
            return ImageAdaptCapability.NATIVE
        }
        val ocrModel = settings.findModelById(settings.ocrModelId) ?: return ImageAdaptCapability.UNAVAILABLE
        if (ocrModel.findProvider(settings.providers) == null) {
            return ImageAdaptCapability.UNAVAILABLE
        }
        // OCR fallback 模型自身必须能看图；否则 DERIVED 只会在 observe() 时必然失败
        if (!ocrModel.inputModalities.contains(Modality.IMAGE)) {
            return ImageAdaptCapability.UNAVAILABLE
        }
        return ImageAdaptCapability.DERIVED
    }

    fun preflight(
        model: Model,
        settings: Settings,
        parts: List<UIMessagePart> = emptyList(),
    ): ImageAdaptCapability {
        if (parts.isEmpty()) return resolveCapability(model, settings)
        return resolveCapability(model, settings)
    }

    fun wrapObservation(ref: String?, body: String): String {
        val attr = ref?.let { " ref=\"$it\"" }.orEmpty()
        return "<attachment_observation$attr>\n$body\n</attachment_observation>"
    }

    fun historicalUnavailableText(ref: String?): String {
        return if (ref.isNullOrBlank()) {
            HISTORICAL_UNAVAILABLE
        } else {
            "$HISTORICAL_UNAVAILABLE: $ref"
        }
    }

    fun cacheKey(contentHash: String, ocrModelId: kotlin.uuid.Uuid, ocrPrompt: String): String {
        return "$contentHash|$RENDITION_VERSION|$ocrModelId|${sha256Hex(ocrPrompt.toByteArray())}"
    }

    fun contentHash(file: File): String = sha256File(file)

    fun isCurrentTask(ctx: TransformerContext, message: UIMessage): Boolean {
        val taskId = ctx.currentTaskMessageId ?: return false
        return message.id == taskId
    }

    fun adaptPartForView(
        part: UIMessagePart.Image,
        capability: ImageAdaptCapability,
        mode: ImageAdaptMode,
        isCurrentTask: Boolean,
        observationText: String?,
        observationErrorDetail: String? = null,
    ): UIMessagePart {
        val ref = AttachmentRefs.getRef(part)
        return when (capability) {
            ImageAdaptCapability.NATIVE -> part
            ImageAdaptCapability.DERIVED -> {
                val body = observationText ?: when (mode) {
                    ImageAdaptMode.CHAT_COMPAT -> {
                        val detail = observationErrorDetail?.let { "OCR failed: $it" } ?: "OCR failed"
                        return UIMessagePart.Text("[ERROR, $detail]")
                    }
                    ImageAdaptMode.SUB_ASSISTANT -> OBSERVATION_FAILED
                }
                UIMessagePart.Text(wrapObservation(ref, body))
            }
            ImageAdaptCapability.UNAVAILABLE -> when {
                mode == ImageAdaptMode.CHAT_COMPAT -> UIMessagePart.Text(CHAT_PLACEHOLDER)
                isCurrentTask -> UIMessagePart.Text(wrapObservation(ref, OBSERVATION_FAILED))
                else -> UIMessagePart.Text(historicalUnavailableText(ref))
            }
        }
    }

    suspend fun adaptImage(
        ctx: TransformerContext,
        message: UIMessage,
        part: UIMessagePart.Image,
        capability: ImageAdaptCapability,
    ): UIMessagePart {
        if (capability == ImageAdaptCapability.NATIVE) return part
        if (capability == ImageAdaptCapability.UNAVAILABLE) {
            if (ctx.imageAdaptMode == ImageAdaptMode.CHAT_COMPAT && isCurrentTask(ctx, message)) {
                throw CurrentImageInputUnavailableException()
            }
            return adaptPartForView(
                part = part,
                capability = capability,
                mode = ctx.imageAdaptMode,
                isCurrentTask = isCurrentTask(ctx, message),
                observationText = null,
            )
        }
        val observed = resultPreservingCancellation { observe(ctx, part) }
        return adaptPartForView(
            part = part,
            capability = capability,
            mode = ctx.imageAdaptMode,
            isCurrentTask = isCurrentTask(ctx, message),
            observationText = observed.getOrNull(),
            observationErrorDetail = observed.exceptionOrNull()?.message,
        )
    }

    suspend fun observe(ctx: TransformerContext, part: UIMessagePart.Image): String {
        val file = AttachmentRefs.parseFileUrl(part.url)?.takeIf { it.isFile }
        val settings = ctx.settings
        val key = if (file != null) {
            cacheKey(contentHash(file), settings.ocrModelId, settings.ocrPrompt)
        } else {
            null
        }
        if (key != null) {
            cache(ctx.context).get(key)?.let { cached ->
                Log.i(TAG, "observe: cache hit")
                return cached
            }
        }

        val model = settings.findModelById(settings.ocrModelId) ?: error("ocr model missing")
        val providerSetting = model.findProvider(settings.providers) ?: error("ocr provider missing")
        val provider = get<ProviderManager>().getProviderByType(providerSetting)
        val result = provider.generateText(
            providerSetting = providerSetting,
            messages = listOf(
                UIMessage.system(settings.ocrPrompt),
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Image(part.url)),
                ),
            ),
            params = TextGenerationParams(
                model = model,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
        val content = result.choices[0].message?.toText()?.trim().orEmpty()
        if (content.isEmpty()) error("empty observation")
        if (key != null) {
            cache(ctx.context).put(key, content)
        }
        return content
    }

    private fun cache(context: Context): LruCache<String, String> {
        synchronized(cacheLock) {
            cache?.let { return it }
            val json = Json { allowStructuredMapKeys = true }
            val store = SingleFileCacheStore(
                file = File(context.cacheDir, "image_observation_cache.json"),
                keySerializer = String.serializer(),
                valueSerializer = String.serializer(),
                json = json,
            )
            val created = LruCache(
                capacity = 64,
                store = store,
                deleteOnEvict = true,
                preloadFromStore = true,
                expireAfterWriteMillis = 3.days.inWholeMilliseconds,
            )
            cache = created
            return created
        }
    }
}

internal suspend fun <T> resultPreservingCancellation(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

internal fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
