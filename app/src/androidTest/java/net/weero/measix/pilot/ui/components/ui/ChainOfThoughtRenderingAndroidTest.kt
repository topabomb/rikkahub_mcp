package net.weero.measix.pilot.ui.components.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.weero.measix.pilot.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ChainOfThoughtRenderingAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun firstMiddleLastConnectorsLeaveTransparentNodesAndFollowVisibleContentInBothDirections() {
        val opacity = mutableFloatStateOf(0f)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                MaterialTheme(colorScheme = lightColorScheme(outlineVariant = Color.Red)) {
                    Box(Modifier.width(240.dp).background(Brush.verticalGradient(listOf(Color.Green, Color.Yellow)))) {
                        ChainOfThought(
                            modifier = Modifier.width(240.dp).testTag("chain"),
                            cardColors = CardDefaults.cardColors(containerColor = Color.Blue.copy(alpha = opacity.floatValue)),
                            steps = listOf(0, 1, 2), collapsedVisibleCount = 3,
                        ) { index ->
                            ControlledChainOfThoughtStep(
                                expanded = false, onExpandedChange = {}, contentVisible = true, icon = {},
                                label = { Box(Modifier.width(120.dp).height(32.dp).testTag("label$index")) },
                                content = { Box(Modifier.width(120.dp).height(28.dp).testTag("body$index")) },
                            )
                        }
                    }
                }
            }
        }
        for (rtl in listOf(false, true)) for (alpha in listOf(0f, .5f, 1f)) {
            compose.runOnIdle { direction.value = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr; opacity.floatValue = alpha }
            val chain = compose.onNodeWithTag("chain")
            val origin = chain.fetchSemanticsNode().boundsInRoot.top
            val pixels = chain.captureToImage().toPixelMap()
            val x = (if (rtl) pixels.width - 24 * density else 24 * density).roundToInt()
            val nearbyX = (if (rtl) x - 6 * density else x + 6 * density).roundToInt()
            fun sampleY(y: Float) = (y - origin).roundToInt().coerceIn(0, pixels.height - 1)
            fun assertBackground(y: Float) {
                val row = sampleY(y)
                val actual = pixels[x, row]
                val expected = pixels[nearbyX, row]
                assertTrue("opaque node or stray line: rtl=$rtl alpha=$alpha y=$y actual=$actual expected=$expected",
                    abs(actual.red - expected.red) < .03f && abs(actual.green - expected.green) < .03f && abs(actual.blue - expected.blue) < .03f)
            }
            fun assertLine(y: Float) {
                val row = sampleY(y)
                assertTrue("missing connector: rtl=$rtl alpha=$alpha y=$y", (-1..1).any { dx ->
                    val color = pixels[(x + dx).coerceIn(0, pixels.width - 1), row]
                    color.red > .7f && color.green < .3f && color.blue < .3f
                })
            }
            for (index in 0..2) {
                val label = compose.onNodeWithTag("label$index", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue("label must have measurable bounds", label.width > 0 && label.height > 0)
                assertBackground(label.center.y)
                if (index == 0) assertBackground(label.center.y - 17 * density) else assertLine(label.center.y - 17 * density)
                if (index == 2) assertBackground(label.center.y + 17 * density) else assertLine(label.center.y + 17 * density)
                val body = compose.onNodeWithTag("body$index", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue("body must have measurable bounds", body.width > 0 && body.height > 0)
                if (index == 2) assertBackground(body.center.y) else assertLine(body.center.y)
            }
        }
    }

    @Test
    fun controlledContentVisibilityDoesNotFollowExpandedAndPinnedInteractionSurvivesOuterCollapse() {
        val expanded = mutableStateOf(false)
        val visible = mutableStateOf(true)
        var approvals = 0
        compose.setContent {
            MaterialTheme {
                ChainOfThought(steps = listOf("approval", "hidden", "tail"), collapsedVisibleCount = 1,
                    keepVisibleWhenCollapsed = { it == "approval" }) { step ->
                    ControlledChainOfThoughtStep(
                        expanded = expanded.value, onExpandedChange = { expanded.value = it },
                        label = { Text(step) }, contentVisible = visible.value,
                        content = { if (step == "approval") androidx.compose.material3.TextButton(onClick = { approvals++ }) { Text("Approve pending") } else Text("content-$step") },
                    )
                }
            }
        }
        compose.onNodeWithText("hidden").assertDoesNotExist()
        compose.onNodeWithText("Approve pending").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, approvals); assertFalse(expanded.value) }
        compose.onNodeWithText("approval").performClick()
        compose.runOnIdle { assertTrue(expanded.value); visible.value = false }
        compose.onNodeWithText("Approve pending").assertDoesNotExist()
        compose.runOnIdle { visible.value = true; expanded.value = false }
        compose.onNodeWithText("Approve pending").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.chain_of_thought_show_more_steps, 1)).performClick()
        compose.onNodeWithText("hidden").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.chain_of_thought_collapse)).performClick()
        compose.onNodeWithText("hidden").assertDoesNotExist()
        compose.onNodeWithText("Approve pending").assertIsDisplayed()
    }
}
