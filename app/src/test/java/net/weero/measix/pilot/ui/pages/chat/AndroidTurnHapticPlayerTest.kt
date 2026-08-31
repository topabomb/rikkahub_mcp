package net.weero.measix.pilot.ui.pages.chat

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.test.core.app.ApplicationProvider
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 29, 33], manifest = Config.NONE)
@Suppress("DEPRECATION")
class AndroidTurnHapticPlayerTest {
    private lateinit var context: Context
    private lateinit var view: View
    private lateinit var haptic: HapticFeedback
    private lateinit var vibrator: Vibrator
    private lateinit var player: AndroidTurnHapticPlayer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        view = View(context).apply { isHapticFeedbackEnabled = true }
        haptic = mockk(relaxed = true)
        vibrator = mockk(relaxed = true)
        every { vibrator.hasVibrator() } returns true
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        player = AndroidTurnHapticPlayer(view, haptic, vibrator)
    }

    @Test
    fun workKeepsTheExistingKeyboardTapWithoutDirectMotorCalls() {
        player.play(TurnHapticPulse.WORK)
        verify(exactly = 1) { haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap) }
        verify { vibrator wasNot Called }
    }

    @Test
    fun attentionUsesHeavyClickOrShortLegacyPulseWithTouchUsageAndNoBypass() {
        player.play(TurnHapticPulse.ATTENTION)
        verifyAttentionCount(1)
        verify { haptic wasNot Called }
    }

    @Test
    @Config(sdk = [26, 29])
    fun systemOptOutIsRecheckedBetweenPulsesAndDoesNotBecomeCachedState() {
        player.play(TurnHapticPulse.ATTENTION)
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        player.play(TurnHapticPulse.ATTENTION)
        verifyAttentionCount(1)
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        player.play(TurnHapticPulse.ATTENTION)
        verifyAttentionCount(2)
        verify { haptic wasNot Called }
    }

    @Test
    @Config(sdk = [33])
    fun modernAndroidDelegatesToSystemTouchPolicyInsteadOfReadingTheObsoleteSetting() {
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        player.play(TurnHapticPulse.ATTENTION)
        verifyAttentionCount(1)
        verify { haptic wasNot Called }
    }

    @Test
    fun viewOptOutIsRecheckedBetweenPulsesAndDoesNotFallBackToLightFeedback() {
        player.play(TurnHapticPulse.ATTENTION)
        view.isHapticFeedbackEnabled = false
        player.play(TurnHapticPulse.ATTENTION)
        verifyAttentionCount(1)
        view.isHapticFeedbackEnabled = true
        player.play(TurnHapticPulse.ATTENTION)
        verifyAttentionCount(2)
        verify { haptic wasNot Called }
    }

    @Test
    fun missingVibratorHardwareIsSilent() {
        every { vibrator.hasVibrator() } returns false
        player.play(TurnHapticPulse.ATTENTION)
        verifyAttentionCount(0)
        verify { haptic wasNot Called }
    }

    @Test
    fun missingVibratorServiceIsSilent() {
        AndroidTurnHapticPlayer(view, haptic, vibrator = null).play(TurnHapticPulse.ATTENTION)
        verify { haptic wasNot Called }
        verify { vibrator wasNot Called }
    }

    private fun verifyAttentionCount(count: Int) {
        val expected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        } else {
            VibrationEffect.createOneShot(35L, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            verify(exactly = count) {
                vibrator.vibrate(expected, match<VibrationAttributes> {
                    it.usage == VibrationAttributes.USAGE_TOUCH && it.flags == 0
                })
            }
        } else {
            verify(exactly = count) {
                vibrator.vibrate(expected, match<AudioAttributes> {
                    it.usage == AudioAttributes.USAGE_ASSISTANCE_SONIFICATION &&
                        it.contentType == AudioAttributes.CONTENT_TYPE_SONIFICATION && it.flags == 0
                })
            }
        }
        verify(exactly = 0) { vibrator.vibrate(any<VibrationEffect>()) }
    }
}
