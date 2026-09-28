package net.weero.measix.pilot.ui.pages.setting

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.configuration.*
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.ConfigurationApplicationService
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.SpeechCatalogReadState
import net.weero.measix.pilot.service.SpeechCatalogUiModel
import net.weero.measix.pilot.service.SpeechPlayback
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.context.LocalTTSState
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.context.Navigator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.IOException

/** Full-page binding with controlled application ports; no enterprise join or audio-engine claim. */
@RunWith(AndroidJUnit4::class)
class SettingSpeechPageAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun managedDefinitionsCannotEditDeleteOrReorderWhileSelectionAndAdmittedPreviewRemainAvailable() = page { fixture ->
        val testLabel = compose.activity.getString(R.string.test_tts)
        val stopLabel = compose.activity.getString(R.string.stop)
        val original = fixture.catalog()
        compose.onNodeWithContentDescription(testLabel).assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.more_options)).assertDoesNotExist()
        compose.onNodeWithText(TTS_A).performScrollTo().assertHasNoClickAction()
        compose.onNodeWithText(TTS_B).performScrollTo().assertHasNoClickAction()

        // Real long-press motion crosses the other card; managed definitions have no drag handle.
        val first = compose.onNodeWithText(TTS_A).fetchSemanticsNode().boundsInRoot.center
        val second = compose.onNodeWithText(TTS_B).fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithText(TTS_A).performTouchInput {
            down(center)
            advanceEventTime(650)
            moveTo(center + Offset(0f, second.y - first.y), delayMillis = 400)
            up()
        }
        compose.waitForIdle()
        verify(exactly = 0) { fixture.vm.updateSettings(any()) }
        assertEquals(original, fixture.catalog())
        compose.onNodeWithText(compose.activity.getString(R.string.setting_tts_page_edit_provider)).assertDoesNotExist()

        radio(TTS_A).performScrollTo().assertIsEnabled().performClick()
        radio(TTS_A).assertIsSelected()
        coVerify(exactly = 1) { fixture.commands.selectResource(fixture.originalSelection, ResourceSelectionSlot.TTS, fixture.ttsA) }
        compose.onNodeWithContentDescription(testLabel).performScrollTo().performClick()
        verify(exactly = 1) {
            fixture.playback.speak(fixture.originalSelection, compose.activity.getString(R.string.setting_tts_page_test_text))
        }
        compose.onNodeWithContentDescription(stopLabel).performScrollTo().performClick()
        verify(exactly = 1) { fixture.playback.stop() }

        compose.runOnIdle {
            fixture.publish(fixture.catalog().let { catalog -> catalog.copy(
                selectedTts = ConfigurationSelection(fixture.ttsA, ConfigurationUnavailableReason.RESOURCE_DISABLED),
                resources = catalog.resources.map { item ->
                    if (item.key.reference == fixture.ttsA) item.copy(access = ConfigurationAccess(false,
                        ConfigurationUnavailableReason.RESOURCE_DISABLED)) else item
                },
                managedTts = catalog.managedTts.map { if (it.id == fixture.ttsA.id) it.copy(enabled = false) else it },
            ) })
        }
        radio(TTS_A).assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithContentDescription(testLabel).assertDoesNotExist()
        // B is executable but not selected, so it must not acquire a preview action yet.
        radio(TTS_B).performScrollTo().assertIsEnabled().assertIsNotSelected().performClick()
        radio(TTS_B).assertIsSelected()
        compose.onNodeWithContentDescription(testLabel).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText(compose.activity.getString(R.string.speech_tab_asr)).performClick()
        compose.onNodeWithText(ASR_A).performScrollTo().assertHasNoClickAction()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.more_options)).assertDoesNotExist()
        radio(ASR_A).assertIsEnabled().performClick().assertIsSelected()
        coVerify(exactly = 1) { fixture.commands.selectResource(fixture.originalSelection, ResourceSelectionSlot.ASR, fixture.asrA) }
        compose.onNodeWithContentDescription(testLabel).assertDoesNotExist()
        verify(exactly = 0) { fixture.vm.updateSettings(any()) }
        assertEquals(original.resources.map { it.key }, fixture.catalog().resources.map { it.key })
    }

    @Test
    fun delayedSelectionFailureKeepsItsOriginalRealmAndDiagnosticThenNewSelectionCanRetry() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            page { fixture ->
                val before = fixture.settings.value
                val rejected = IllegalStateException("stale_speech_selection", IOException("original lease retired"))
                coEvery { fixture.commands.selectResource(fixture.originalSelection, ResourceSelectionSlot.TTS, fixture.ttsA) } coAnswers {
                    entered.complete(Unit)
                    release.await()
                    throw rejected
                }
                radio(TTS_A).performScrollTo().performClick()
                compose.waitUntil(5_000) { entered.isCompleted }
                radio(TTS_A).assertIsNotSelected()
                val newSelection = RealmSelection(
                    (fixture.originalSelection.access as RealmAccess.Enterprise).copy(sessionId = "new-session"), 2,
                )
                compose.runOnIdle { fixture.publish(fixture.catalog().copy(selection = newSelection)) }
                compose.waitForIdle()
                release.complete(Unit)
                compose.waitUntil(5_000) {
                    compose.onAllNodes(hasText("IllegalStateException: stale_speech_selection", substring = true))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(hasText("IllegalStateException: stale_speech_selection", substring = true)).assertIsDisplayed()
                compose.onNode(hasText("Caused by: IOException: original lease retired", substring = true)).assertIsDisplayed()
                radio(TTS_A).assertIsNotSelected()
                coVerify(exactly = 1) { fixture.commands.selectResource(fixture.originalSelection, ResourceSelectionSlot.TTS, fixture.ttsA) }
                coVerify(exactly = 0) { fixture.commands.selectResource(newSelection, any(), any()) }
                verify(exactly = 0) { fixture.vm.updateSettings(any()) }
                assertSame(before, fixture.settings.value)
                assertEquals(newSelection, fixture.catalog().selection)

                radio(TTS_A).performScrollTo().performClick().assertIsSelected()
                coVerify(exactly = 1) { fixture.commands.selectResource(newSelection, ResourceSelectionSlot.TTS, fixture.ttsA) }
                assertSame(before, fixture.settings.value)
            }
        } finally { release.complete(Unit) }
    }

    private fun radio(name: String) = compose.onNode(hasContentDescription(name) and isSelectable())

    private fun page(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        val isolated = koinApplication { modules(module {
            single<ConfigurationQueryService> { fixture.queries }
            single<ConfigurationApplicationService> { fixture.commands }
        }) }
        val visible = mutableStateOf(true)
        try {
            compose.setContent {
                KoinIsolatedContext(isolated) {
                    MaterialTheme {
                        val toaster = rememberToasterState()
                        val settings by fixture.settings.collectAsState()
                        CompositionLocalProvider(
                            LocalNavController provides Navigator(mutableListOf<NavKey>(Screen.Startup())),
                            LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo(),
                            LocalSettings provides settings,
                            LocalTTSState provides fixture.playback,
                            LocalToaster provides toaster,
                        ) {
                            if (visible.value) Box {
                                SettingSpeechPage(fixture.vm)
                                Toaster(state = toaster, alignment = Alignment.TopCenter)
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText(TTS_A).performScrollTo().assertIsDisplayed()
            block(fixture)
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            isolated.close()
        }
    }

    private class Fixture {
        private val authority = EnterpriseAuthority("dep_speech_page")
        val originalSelection = RealmSelection(RealmAccess.Enterprise(
            ConfigurationScope.Enterprise(authority, "page-user"), "original-session"), 1)
        val ttsA = ConfigurationReference.Enterprise(authority, "tts_page_a")
        val ttsB = ConfigurationReference.Enterprise(authority, "tts_page_b")
        val asrA = ConfigurationReference.Enterprise(authority, "asr_page_a")
        val settings = MutableStateFlow(Settings(ttsProviders = emptyList(), asrProviders = emptyList()))
        val vm = mockk<SettingVM>()
        val queries = mockk<ConfigurationQueryService>()
        val commands = mockk<ConfigurationApplicationService>()
        val playback = mockk<SpeechPlayback>()
        private val speaking = MutableStateFlow(false)
        private val state = MutableStateFlow<SpeechCatalogReadState>(SpeechCatalogReadState.Available(
            SpeechCatalogUiModel(originalSelection, ConfigurationSelection(null, null), ConfigurationSelection(null, null),
                listOf(
                    ConfigurationCatalogItem(ConfigurationKey(ConfigurationCategory.TTS, ttsA), TTS_A, ConfigurationAccess(false)),
                    ConfigurationCatalogItem(ConfigurationKey(ConfigurationCategory.TTS, ttsB), TTS_B, ConfigurationAccess(false)),
                    ConfigurationCatalogItem(ConfigurationKey(ConfigurationCategory.ASR, asrA), ASR_A, ConfigurationAccess(false)),
                ),
                listOf(
                    EnterpriseTtsResource(ttsA.id, TTS_A, protocol = EnterpriseTtsProtocol.SYSTEM, speechRate = 1.0, pitch = 1.0),
                    EnterpriseTtsResource(ttsB.id, TTS_B, protocol = EnterpriseTtsProtocol.SYSTEM, speechRate = 1.0, pitch = 1.0),
                ),
                listOf(EnterpriseAsrResource(asrA.id, ASR_A, modelId = "asr-test", protocol = EnterpriseAsrProtocol.OPENAI_HTTP)),
            ),
        ))

        init {
            every { vm.settings } returns settings
            every { vm.updateSettings(any()) } answers { error("Managed definition must not issue a settings write") }
            every { queries.observeSpeechCatalog() } returns state
            every { playback.isSpeaking } returns speaking
            every { playback.speak(any<RealmSelection>(), any()) } answers { speaking.value = true }
            every { playback.stop() } answers { speaking.value = false }
            coEvery { commands.selectResource(any(), any(), any()) } coAnswers {
                val selection = firstArg<RealmSelection>()
                check(selection == catalog().selection) { "Unexpected selection in fixture" }
                val selected = ConfigurationSelection(thirdArg(), null)
                publish(when (secondArg<ResourceSelectionSlot>()) {
                    ResourceSelectionSlot.TTS -> catalog().copy(selectedTts = selected)
                    ResourceSelectionSlot.ASR -> catalog().copy(selectedAsr = selected)
                    else -> error("Unexpected speech selection slot")
                })
            }
        }

        fun catalog() = (state.value as SpeechCatalogReadState.Available).catalog
        fun publish(catalog: SpeechCatalogUiModel) { state.value = SpeechCatalogReadState.Available(catalog) }
    }

    private companion object {
        const val TTS_A = "Managed speech A"
        const val TTS_B = "Managed speech B"
        const val ASR_A = "Managed recognition A"
    }
}
