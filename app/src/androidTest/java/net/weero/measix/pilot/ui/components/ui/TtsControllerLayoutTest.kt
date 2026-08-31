package net.weero.measix.pilot.ui.components.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.context.LocalTTSState
import net.weero.measix.pilot.ui.hooks.CustomTtsState
import net.weero.measix.pilot.ui.adaptive.AdaptiveLayoutDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TtsControllerLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val tts = TestTtsState()
    private val height = mutableStateOf(400.dp)
    private val imeBottom = mutableIntStateOf(0)
    private val sideInsets = mutableIntStateOf(0)
    private val layoutDirection = mutableStateOf(LayoutDirection.Ltr)
    private val inputHeight = mutableStateOf(72.dp)
    private val inputText = mutableStateOf("")
    private var hideKeyboard: () -> Unit = {}
    private var currentImeBottom: () -> Int = { 0 }
    private var backgroundClicks = 0

    @Test
    fun toolbarMatchesInputSurfaceAcrossBlurBackgroundAndThemeChanges() {
        val blur = mutableStateOf(false)
        val visibleBackground = mutableStateOf(true)
        val dark = mutableStateOf(false)
        val backdrop = Color(0xFF2050A0)
        val baseSettings = Settings()
        compose.setContent {
            CompositionLocalProvider(
                LocalTTSState provides tts,
                LocalSettings provides baseSettings.copy(
                    displaySetting = baseSettings.displaySetting.copy(enableBlurEffect = blur.value),
                ),
            ) {
                MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                    val hazeState = rememberHazeState()
                    Box(Modifier.requiredSize(320.dp, 400.dp).testTag("surface_window")) {
                        Box(Modifier.fillMaxSize().hazeSource(hazeState).background(backdrop))
                        ChatOverlaySurface(
                            modifier = Modifier.padding(8.dp).fillMaxWidth().height(72.dp).testTag("input_surface"),
                            shape = MaterialTheme.shapes.large,
                            enableBlurEffect = blur.value,
                            hasVisibleBackground = visibleBackground.value,
                            hazeState = hazeState,
                        ) {}
                        TTSController(
                            windowInsets = WindowInsets(0),
                            hazeState = hazeState,
                            hasVisibleBackground = visibleBackground.value,
                        )
                    }
                }
            }
        }
        for (isDark in listOf(false, true)) {
            for (hasBackground in listOf(true, false)) {
                for (enableBlur in listOf(false, true)) {
                    compose.runOnIdle {
                        dark.value = isDark
                        visibleBackground.value = hasBackground
                        blur.value = enableBlur
                    }
                    compose.waitForIdle()
                    val window = compose.onNodeWithTag("surface_window")
                    val root = window.fetchSemanticsNode().boundsInRoot
                    val pixels = window.captureToImage().toPixelMap()
                    fun sampleSurface(tag: String): Color {
                        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                        val x = (bounds.center.x - root.left).toInt()
                        val y = (bounds.top - root.top + with(compose.density) { 2.dp.toPx() }).toInt()
                        val samples = (-8..8).map { pixels[x + it, y] }
                        return Color(
                            samples.map { it.red }.average().toFloat(),
                            samples.map { it.green }.average().toFloat(),
                            samples.map { it.blue }.average().toFloat(),
                        )
                    }
                    val toolbar = sampleSurface("tts_controller")
                    val input = sampleSurface("input_surface")
                    fun assertColor(expected: Color, actual: Color) {
                        assertEquals(expected.red, actual.red, 0.03f)
                        assertEquals(expected.green, actual.green, 0.03f)
                        assertEquals(expected.blue, actual.blue, 0.03f)
                    }
                    assertColor(input, toolbar)
                    if (!enableBlur) {
                        val scheme = if (isDark) darkColorScheme() else lightColorScheme()
                        val expected = scheme.surfaceContainerLow.copy(
                            alpha = if (hasBackground) 0.82f else 1f,
                        ).compositeOver(backdrop)
                        assertColor(expected, toolbar)
                    }
                }
            }
        }
    }

    @Test
    fun toolbarTracksMeasuredInputPanelThroughHeightAndImeChanges() {
        showToolbar(aboveInput = true)
        assertAboveInput()
        for ((panelHeight, keyboardHeight) in listOf(120.dp to 180, 160.dp to 180, 96.dp to 60, 72.dp to 0)) {
            compose.runOnIdle {
                inputHeight.value = panelHeight
                imeBottom.intValue = keyboardHeight
            }
            assertAboveInput()
        }
        compose.runOnIdle { height.value = 280.dp }
        assertAboveInput()
    }

    @Test
    fun clickingInputAndOpeningRealKeyboardNeverCoversInputPanel() {
        showToolbar(aboveInput = true, realKeyboard = true)
        assertAboveInput()
        compose.onNodeWithTag("input_field").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { currentImeBottom() > 0 }
        assertAboveInput()
        val singleLineHeight = compose.onNodeWithTag("input_panel").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("input_field").performTextReplacement("first line\nsecond line\nthird line")
        assertAboveInput()
        assertTrue(
            "Multiline input must increase the measured panel height",
            compose.onNodeWithTag("input_panel").fetchSemanticsNode().boundsInRoot.height > singleLineHeight,
        )
        compose.runOnIdle { hideKeyboard() }
        compose.waitUntil(timeoutMillis = 10_000) { currentImeBottom() == 0 }
        assertAboveInput()
    }

    @Test
    fun wideChatPaneAlignsToolbarWithCenteredInputPanel() {
        showToolbar(aboveInput = true, windowWidth = 1000.dp, densityOverride = 1f)
        assertAboveInput()
        val panel = compose.onNodeWithTag("input_panel").fetchSemanticsNode().boundsInRoot
        val toolbar = compose.onNodeWithTag("tts_controller").fetchSemanticsNode().boundsInRoot
        assertEquals(panel.left, toolbar.left, 1f)
    }

    @Test
    fun windowResizeAndImeChangesKeepToolbarAtSafeBottom() {
        showToolbar()
        assertAtBottom()
        compose.runOnIdle { height.value = 260.dp }
        assertAtBottom()
        compose.runOnIdle { height.value = 420.dp }
        assertAtBottom()
        for (bottom in listOf(180, 60, 0)) {
            compose.runOnIdle { imeBottom.intValue = bottom }
            assertAtBottom()
        }
        compose.runOnIdle { sideInsets.intValue = 30 }
        assertAtBottom()
    }

    @Test
    fun expansionPauseAndStopUsePlaybackStateWithoutMovingToolbar() {
        showToolbar()
        control(R.string.tts_controller_expand).performClick()
        assertAtBottom()
        control(R.string.tts_controller_pause).performClick()
        control(R.string.tts_controller_play).assertIsDisplayed()
        assertAtBottom()
        control(R.string.tts_controller_play).performClick()
        control(R.string.tts_controller_pause).assertIsDisplayed()
        control(R.string.tts_controller_expand).performScrollTo().performClick()
        assertAtBottom()
        control(R.string.tts_controller_stop).performClick()
        compose.onNodeWithTag("tts_controller").assertDoesNotExist()
    }

    @Test
    fun outsideTouchesReachPageAndVerticalDragDoesNotMoveToolbar() {
        showToolbar()
        compose.onNodeWithTag("window").performTouchInput {
            click(Offset(width - 2f, 2f))
        }
        compose.runOnIdle { assertEquals(1, backgroundClicks) }
        compose.onNodeWithTag("tts_controller").performTouchInput { swipeUp() }
        assertAtBottom()
        compose.runOnIdle { assertEquals(1, backgroundClicks) }
    }

    @Test
    fun narrowWindowWithAvatarAndLargeFontKeepsControlsReachableInBothDirections() {
        val assistant = Assistant(name = "Reader", avatar = Avatar.Emoji("R"), useAssistantAvatar = true)
        tts.activeSource.value = TtsPlaybackSource(
            assistantId = assistant.id,
            assistantName = assistant.name,
            type = TtsPlaybackSource.SourceType.SUB_ASSISTANT,
        )
        showToolbar(settings = Settings(assistants = listOf(assistant)), fontScale = 1.5f)
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            compose.runOnIdle { layoutDirection.value = direction }
            control(R.string.tts_controller_expand).performScrollTo().performClick()
            assertAtBottom()
            compose.onNodeWithTag("tts_controller").performTouchInput {
                if (direction == LayoutDirection.Ltr) swipeLeft() else swipeRight()
            }
            control(R.string.tts_controller_expand).assertIsDisplayed()
            control(R.string.tts_controller_fast_forward).performScrollTo().assertIsDisplayed()
            control(R.string.tts_controller_stop).performScrollTo().assertIsDisplayed()
            control(R.string.tts_controller_expand).performScrollTo().assertIsDisplayed().performClick()
            assertAtBottom()
        }
    }

    private fun showToolbar(
        settings: Settings = Settings(),
        fontScale: Float = 1f,
        aboveInput: Boolean = false,
        realKeyboard: Boolean = false,
        windowWidth: Dp = 320.dp,
        densityOverride: Float? = null,
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalTTSState provides tts,
                LocalSettings provides settings,
                LocalLayoutDirection provides layoutDirection.value,
                LocalDensity provides Density(densityOverride ?: LocalDensity.current.density, fontScale),
            ) {
                MaterialTheme {
                    val density = LocalDensity.current
                    val ime = WindowInsets.ime
                    val keyboard = LocalSoftwareKeyboardController.current
                    SideEffect {
                        hideKeyboard = { keyboard?.hide() }
                        currentImeBottom = { ime.getBottom(density) }
                    }
                    val insets = if (realKeyboard) ime else WindowInsets(
                        left = sideInsets.intValue,
                        right = sideInsets.intValue,
                        bottom = NAVIGATION_BOTTOM,
                    ).union(WindowInsets(bottom = imeBottom.intValue))
                    val windowModifier = if (realKeyboard) Modifier.fillMaxSize()
                        else Modifier.requiredSize(windowWidth, height.value)
                    Box(windowModifier.testTag("window")) {
                        Box(Modifier.fillMaxSize().clickable { backgroundClicks++ })
                        if (aboveInput) {
                            Scaffold(
                                contentWindowInsets = WindowInsets(0),
                                bottomBar = {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                                        Column(
                                            Modifier.widthIn(max = AdaptiveLayoutDefaults.ReadableContentMaxWidth)
                                                .fillMaxWidth().windowInsetsPadding(insets)
                                                .padding(horizontal = 8.dp),
                                        ) {
                                            val panelModifier = Modifier.fillMaxWidth().testTag("input_panel")
                                            if (realKeyboard) {
                                                Box(panelModifier) {
                                                    OutlinedTextField(
                                                        value = inputText.value,
                                                        onValueChange = { inputText.value = it },
                                                        label = { Text("Input") },
                                                        modifier = Modifier.fillMaxWidth().testTag("input_field"),
                                                    )
                                                }
                                            } else {
                                                Box(panelModifier.height(inputHeight.value))
                                            }
                                        }
                                    }
                                },
                            ) { innerPadding ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                    TTSController(
                                        modifier = Modifier.widthIn(max = AdaptiveLayoutDefaults.ReadableContentMaxWidth),
                                        contentPadding = innerPadding,
                                        windowInsets = insets,
                                    )
                                }
                            }
                        } else {
                            TTSController(windowInsets = insets)
                        }
                    }
                }
            }
        }
    }

    private fun assertAboveInput() {
        compose.waitForIdle()
        val panel = compose.onNodeWithTag("input_panel").fetchSemanticsNode().boundsInRoot
        val toolbar = compose.onNodeWithTag("tts_controller").fetchSemanticsNode().boundsInRoot
        val density = compose.onNodeWithTag("tts_controller").fetchSemanticsNode().layoutInfo.density
        val margin = with(density) { 8.dp.toPx() }
        assertTrue("Toolbar must not cover the input panel", toolbar.bottom <= panel.top)
        assertEquals(panel.top - margin, toolbar.bottom, 1f)
        assertEquals(panel.left, toolbar.left, 1f)
    }

    private fun assertAtBottom() {
        compose.waitForIdle()
        val window = compose.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
        val toolbar = compose.onNodeWithTag("tts_controller").fetchSemanticsNode().boundsInRoot
        val margin = with(compose.density) { 8.dp.toPx() }
        assertEquals(
            window.bottom - maxOf(NAVIGATION_BOTTOM, imeBottom.intValue) - margin,
            toolbar.bottom,
            1f,
        )
        if (layoutDirection.value == LayoutDirection.Ltr) {
            assertEquals(window.left + sideInsets.intValue + margin, toolbar.left, 1f)
        } else {
            assertEquals(window.right - sideInsets.intValue - margin, toolbar.right, 1f)
        }
    }

    private fun control(stringId: Int) = compose.onNodeWithContentDescription(
        InstrumentationRegistry.getInstrumentation().targetContext.getString(stringId),
    )

    private class TestTtsState : CustomTtsState {
        override val isAvailable = MutableStateFlow(true)
        override val isSpeaking = MutableStateFlow(true)
        override val error = MutableStateFlow<String?>(null)
        override val currentChunk = MutableStateFlow(0)
        override val totalChunks = MutableStateFlow(1)
        override val playbackState = MutableStateFlow(PlaybackState(status = PlaybackStatus.Playing))
        override val activeSource = MutableStateFlow<TtsPlaybackSource?>(null)

        override fun pause() { playbackState.value = playbackState.value.copy(status = PlaybackStatus.Paused) }
        override fun resume() { playbackState.value = playbackState.value.copy(status = PlaybackStatus.Playing) }
        override fun stop() { isSpeaking.value = false }
        override fun speak(text: String, flushCalled: Boolean) = Unit
        override fun speakWithSource(
            text: String,
            replaceWithinSession: Boolean,
            queueSessionId: String?,
            source: TtsPlaybackSource?,
        ) = Unit
        override fun skipNext() = Unit
        override fun fastForward(ms: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun cleanup() = Unit
    }

    private companion object {
        const val NAVIGATION_BOTTOM = 24
    }
}
