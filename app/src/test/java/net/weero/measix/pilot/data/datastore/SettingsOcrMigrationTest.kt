package net.weero.measix.pilot.data.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsOcrMigrationTest {
    private val visionModel = Model(
        id = Uuid.parse("11111111-1111-1111-1111-111111111111"),
        modelId = "vision",
        displayName = "Vision",
        type = ModelType.CHAT,
        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
    )
    private val textModel = Model(
        id = Uuid.parse("22222222-2222-2222-2222-222222222222"),
        modelId = "text",
        displayName = "Text",
        type = ModelType.CHAT,
        inputModalities = listOf(Modality.TEXT),
    )

    private fun prefsOf(vararg entries: Pair<Preferences.Key<String>, String>): MutablePreferences {
        val prefs = emptyPreferences().toMutablePreferences()
        entries.forEach { (key, value) -> prefs[key] = value }
        return prefs
    }

    @Test
    fun `valid legacy ocr model migrates to new key`() {
        val prefs = prefsOf(
            Pair(LEGACY_OCR_MODEL, visionModel.id.toString()),
            Pair(LEGACY_OCR_PROMPT, "legacy prompt"),
        )
        val wrote = migrateOcrPreferences(prefs, providersWith(visionModel))
        assertTrue(wrote)
        assertEquals(visionModel.id.toString(), prefs[SettingsStore.ATTACHMENT_INSPECTION_MODEL])
    }

    @Test
    fun `legacy text only model does not migrate`() {
        val prefs = prefsOf(
            Pair(LEGACY_OCR_MODEL, textModel.id.toString()),
            Pair(LEGACY_OCR_PROMPT, "legacy prompt"),
        )
        val wrote = migrateOcrPreferences(prefs, providersWith(textModel))
        assertFalse(wrote)
        assertNull(prefs[SettingsStore.ATTACHMENT_INSPECTION_MODEL])
    }

    @Test
    fun `legacy random uuid without matching model does not migrate`() {
        val prefs = prefsOf(
            Pair(LEGACY_OCR_MODEL, Uuid.random().toString()),
        )
        val wrote = migrateOcrPreferences(prefs, providersWith(visionModel))
        assertFalse(wrote)
        assertNull(prefs[SettingsStore.ATTACHMENT_INSPECTION_MODEL])
    }

    @Test
    fun `legacy model from removed provider does not migrate`() {
        val prefs = prefsOf(
            Pair(LEGACY_OCR_MODEL, visionModel.id.toString()),
        )
        val wrote = migrateOcrPreferences(prefs, providers = emptyList())
        assertFalse(wrote)
        assertNull(prefs[SettingsStore.ATTACHMENT_INSPECTION_MODEL])
    }

    @Test
    fun `new key wins when already present`() {
        val existing = "33333333-3333-3333-3333-333333333333"
        val prefs = prefsOf(
            Pair(LEGACY_OCR_MODEL, visionModel.id.toString()),
            Pair(SettingsStore.ATTACHMENT_INSPECTION_MODEL, existing),
        )
        val wrote = migrateOcrPreferences(prefs, providersWith(visionModel))
        assertTrue(wrote)
        assertEquals(existing, prefs[SettingsStore.ATTACHMENT_INSPECTION_MODEL])
    }

    @Test
    fun `legacy keys are always removed`() {
        val prefs = prefsOf(
            Pair(LEGACY_OCR_MODEL, visionModel.id.toString()),
            Pair(LEGACY_OCR_PROMPT, "legacy prompt"),
        )
        migrateOcrPreferences(prefs, providersWith(visionModel))
        assertFalse(prefs.contains(LEGACY_OCR_MODEL))
        assertFalse(prefs.contains(LEGACY_OCR_PROMPT))
    }

    @Test
    fun `legacy backup json maps ocrModelId to attachmentInspectionModelId`() {
        val raw = """{"chatModelId":"${Uuid.random()}","ocrModelId":"${visionModel.id}","ocrPrompt":"p","titlePrompt":"t"}"""
        val patched = JsonInstant.parseToJsonElement(migrateLegacySettingsJson(raw)) as kotlinx.serialization.json.JsonObject
        assertEquals(visionModel.id.toString(), patched["attachmentInspectionModelId"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertNull(patched["ocrModelId"])
        assertNull(patched["ocrPrompt"])
        assertEquals("t", patched["titlePrompt"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun `legacy backup json keeps new key when both present`() {
        val raw = """{"ocrModelId":"11111111-1111-1111-1111-111111111111","attachmentInspectionModelId":"22222222-2222-2222-2222-222222222222"}"""
        val patched = JsonInstant.parseToJsonElement(migrateLegacySettingsJson(raw)) as kotlinx.serialization.json.JsonObject
        assertEquals("22222222-2222-2222-2222-222222222222", patched["attachmentInspectionModelId"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertNull(patched["ocrModelId"])
    }

    @Test
    fun `modern backup json is unchanged`() {
        val raw = """{"chatModelId":"${Uuid.random()}","titlePrompt":"t"}"""
        assertEquals(raw, migrateLegacySettingsJson(raw))
    }

    @Test
    fun `invalid json is returned as is`() {
        val raw = "not json"
        assertEquals(raw, migrateLegacySettingsJson(raw))
    }

    private fun providersWith(vararg models: Model): List<ProviderSetting> =
        listOf(ProviderSetting.OpenAI(models = models.toList()))
}
