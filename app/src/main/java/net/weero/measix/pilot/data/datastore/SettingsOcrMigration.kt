package net.weero.measix.pilot.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.utils.JsonInstant

private const val TAG = "OcrSettingsMigration"

/** 旧 OCR 配置 key，仅允许出现在一次性迁移与迁移测试中。 */
internal val LEGACY_OCR_MODEL = stringPreferencesKey("ocr_model")
internal val LEGACY_OCR_PROMPT = stringPreferencesKey("ocr_prompt")

/**
 * 旧 `ocr_model` / `ocr_prompt` → `attachment_inspection_model` 的一次性 cutover。
 *
 * 迁移规则（见 multimodal-attachment-context-and-analysis-design.md）：
 * 1. 新 key 已存在时以新值为准，只清理旧 key；
 * 2. 旧模型 ID 能解析到当前存在、Provider 存在且支持 IMAGE 输入的模型时写入新 key；
 * 3. 否则新值为 null，不猜测替代模型；
 * 4. `ocr_prompt` 一律不迁移；
 * 5. 迁移完成后删除两个旧 key；
 * 6. best-effort 删除旧 observation cache 文件，失败只记日志。
 */
internal class OcrSettingsMigration(
    private val context: Context,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData.contains(LEGACY_OCR_MODEL) || currentData.contains(LEGACY_OCR_PROMPT)

    override suspend fun migrate(currentData: Preferences): Preferences {
        val providers = currentData[SettingsStore.PROVIDERS]
            ?.let { raw -> runCatching { JsonInstant.decodeFromString<List<ProviderSetting>>(raw) }.getOrNull() }
            ?: emptyList()
        val migrated = currentData.toMutablePreferences()
        migrateOcrPreferences(migrated, providers)
        Log.i(TAG, "OCR settings cutover applied")
        return migrated.toPreferences()
    }

    override suspend fun cleanUp() {
        // 旧 observation cache 只服务已删除的自动识别链，best-effort 清理。
        runCatching { File(context.cacheDir, "image_observation_cache.json").delete() }
            .onFailure { Log.w(TAG, "Failed to delete legacy observation cache", it) }
    }
}

/**
 * 纯迁移逻辑：按当前 providers 校验旧 OCR 模型并写入新 key、清除旧 key。
 * 返回值表示是否写入了新 key（便于测试断言）。
 */
internal fun migrateOcrPreferences(
    prefs: androidx.datastore.preferences.core.MutablePreferences,
    providers: List<ProviderSetting>,
): Boolean {
    val legacyModelRaw = prefs[LEGACY_OCR_MODEL]
    prefs.remove(LEGACY_OCR_MODEL)
    prefs.remove(LEGACY_OCR_PROMPT)

    if (prefs.contains(SettingsStore.ATTACHMENT_INSPECTION_MODEL)) {
        // 新 key 已存在，以新值为准。
        return true
    }
    val legacyModelId = legacyModelRaw?.let { raw -> runCatching { kotlin.uuid.Uuid.parse(raw) }.getOrNull() }
    val model = legacyModelId?.let { id -> providers.findModelById(id) } ?: return false
    val providerExists = model.findProvider(providers) != null
    if (!providerExists || !model.inputModalities.contains(Modality.IMAGE)) {
        return false
    }
    prefs[SettingsStore.ATTACHMENT_INSPECTION_MODEL] = model.id.toString()
    return true
}

/**
 * 旧备份 settings.json 导入边界的一次性映射：顶层 `ocrModelId` → `attachmentInspectionModelId`。
 * `ocrPrompt` 忽略；新字段已存在时以新值为准。模型有效性交给恢复后的运行时校验。
 */
fun migrateLegacySettingsJson(raw: String): String {
    return runCatching {
        val element = JsonInstant.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
            ?: return raw
        if (!element.containsKey("ocrModelId")) return raw
        val patched = element.toMutableMap()
        if (!patched.containsKey("attachmentInspectionModelId")) {
            patched["attachmentInspectionModelId"] = element["ocrModelId"]!!
        }
        patched.remove("ocrModelId")
        patched.remove("ocrPrompt")
        kotlinx.serialization.json.JsonObject(patched).toString()
    }.getOrDefault(raw)
}
