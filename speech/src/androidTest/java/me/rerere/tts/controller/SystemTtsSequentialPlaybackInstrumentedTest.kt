package me.rerere.tts.controller

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.providers.SystemTTSProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemTtsSequentialPlaybackInstrumentedTest {
    @Test
    fun toolbarPauseKeepsQueueAndBlocksAudioThatFinishesSynthesizing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = withContext(Dispatchers.Main) {
            TtsController(context, TTSManager(context)).also {
                it.setProvider(TTSProviderSetting.SystemTTS(speechRate = 2.0f))
            }
        }
        val playedSources = mutableListOf<String>()
        val collector = launch(Dispatchers.Main) {
            controller.activeSource.combine(controller.playbackState) { source, state ->
                source to state.status
            }.collect { (source, status) ->
                if (status == PlaybackStatus.Playing && source is String) {
                    if (playedSources.lastOrNull() != source) playedSources += source
                }
            }
        }

        try {
            withContext(Dispatchers.Main) {
                controller.speak("Master toolbar pause audio.", false, "master-paused", "turn-toolbar")
                controller.pause()
                controller.speak("Target queued while paused.", false, "target-queued", "turn-toolbar")
                controller.setSpeed(1.5f)
                controller.fastForward(250)
            }
            delay(PAUSE_OBSERVATION_MS)

            assertTrue(controller.isSpeaking.value)
            assertEquals(PlaybackStatus.Paused, controller.playbackState.value.status)
            assertEquals("master-paused", controller.activeSource.value)
            assertEquals(1.5f, controller.playbackState.value.speed)
            assertTrue("No source may start playing while toolbar is paused", playedSources.isEmpty())

            withContext(Dispatchers.Main) { controller.resume() }
            withTimeout(CONTROLLER_TIMEOUT_MS) {
                controller.playbackState.combine(controller.currentChunk) { state, currentChunk ->
                    state.status == PlaybackStatus.Ended && currentChunk == 2
                }.first { it }
            }
            delay(100)

            assertEquals(listOf("master-paused", "target-queued"), playedSources)
        } finally {
            collector.cancelAndJoin()
            withContext(Dispatchers.Main) { controller.dispose() }
        }
    }

    @Test
    fun toolbarStopClearsTurnAndLaterSubmissionStartsFreshPlayback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = withContext(Dispatchers.Main) {
            TtsController(context, TTSManager(context)).also {
                it.setProvider(TTSProviderSetting.SystemTTS(speechRate = 2.0f))
                it.setSpeed(2.0f)
            }
        }

        try {
            withContext(Dispatchers.Main) {
                controller.speak("Audio stopped by toolbar.", false, "stopped", "turn-stop")
            }
            withTimeout(CONTROLLER_TIMEOUT_MS) {
                controller.playbackState.first { it.status == PlaybackStatus.Playing }
            }
            withContext(Dispatchers.Main) { controller.stop() }

            assertFalse(controller.isSpeaking.value)
            assertEquals(PlaybackStatus.Idle, controller.playbackState.value.status)
            assertEquals(0, controller.currentChunk.value)
            assertEquals(0, controller.totalChunks.value)
            assertNull(controller.activeSource.value)

            withContext(Dispatchers.Main) {
                controller.speak("Fresh audio after toolbar stop.", false, "fresh", "turn-stop")
            }
            withTimeout(CONTROLLER_TIMEOUT_MS) {
                controller.activeSource.combine(controller.playbackState) { source, state ->
                    source == "fresh" && state.status == PlaybackStatus.Playing
                }.first { it }
            }
            withTimeout(CONTROLLER_TIMEOUT_MS) {
                controller.playbackState.first { it.status == PlaybackStatus.Ended }
            }
        } finally {
            withContext(Dispatchers.Main) { controller.dispose() }
        }
        Unit
    }

    @Test
    fun sameTurnCanResumeWithLateTargetAudioAfterQueueDrains() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = withContext(Dispatchers.Main) {
            TtsController(context, TTSManager(context)).also {
                it.setProvider(TTSProviderSetting.SystemTTS(speechRate = 2.0f))
                it.setSpeed(2.0f)
            }
        }
        val submissions = listOf(
            "master-late" to "Master late audio.",
            "target-late" to "Target late audio.",
            "master-final" to "Master final audio.",
        )

        try {
            submissions.forEachIndexed { index, (source, text) ->
                withContext(Dispatchers.Main) {
                    controller.speak(text, false, source, "turn-late")
                }
                withTimeout(CONTROLLER_TIMEOUT_MS) {
                    controller.activeSource.combine(controller.playbackState) { activeSource, state ->
                        activeSource == source && state.status == PlaybackStatus.Playing
                    }.first { it }
                }
                withTimeout(CONTROLLER_TIMEOUT_MS) {
                    controller.playbackState.combine(controller.currentChunk) { state, currentChunk ->
                        state.status == PlaybackStatus.Ended && currentChunk == index + 1
                    }.first { it }
                }
            }
        } finally {
            withContext(Dispatchers.Main) { controller.dispose() }
        }
    }

    @Test
    fun concurrentSystemTtsPrefetchProducesDistinctNonSilentWavFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = SystemTTSProvider()
        val setting = TTSProviderSetting.SystemTTS()
        val utterances = (1..8).map { index ->
            "Concurrent system speech synthesis sample number $index."
        }

        val chunks = withContext(Dispatchers.Main) {
            withTimeout(SYNTHESIS_TIMEOUT_MS) {
                coroutineScope {
                    utterances.map { text ->
                        async {
                            provider.generateSpeech(context, setting, TTSRequest(text)).first()
                        }
                    }.awaitAll()
                }
            }
        }

        chunks.forEachIndexed { index, chunk ->
            assertTrue("System TTS WAV ${index + 1} is too small", chunk.data.size > WAV_HEADER_BYTES)
            assertTrue(
                "System TTS WAV ${index + 1} contains no non-zero audio samples",
                chunk.data.drop(WAV_HEADER_BYTES).any { it.toInt() != 0 },
            )
        }
        assertEquals(chunks.size, chunks.map { it.data.contentHashCode() }.distinct().size)
    }

    @Test
    fun controllerPlaysInterleavedMasterAndTargetSourcesInSubmissionOrder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val setting = TTSProviderSetting.SystemTTS()
        val controller = withContext(Dispatchers.Main) {
            TtsController(context, TTSManager(context)).also { it.setProvider(setting) }
        }
        val playedSources = mutableListOf<String>()
        val collector = launch(Dispatchers.Main) {
            controller.activeSource.combine(controller.playbackState) { source, state ->
                source to state.status
            }.collect { (source, status) ->
                if (status == PlaybackStatus.Playing && source is String && playedSources.lastOrNull() != source) {
                    playedSources += source
                }
            }
        }

        try {
            withContext(Dispatchers.Main) {
                controller.speak("Master audio one.", false, "master-1", "turn-1")
                controller.speak("Target audio one.", false, "target-1", "turn-1")
                controller.speak("Target audio two.", false, "target-2", "turn-1")
                controller.speak("Master audio two.", false, "master-2", "turn-1")
            }

            withTimeout(CONTROLLER_TIMEOUT_MS) {
                controller.playbackState.combine(controller.currentChunk) { state, currentChunk ->
                    state.status == PlaybackStatus.Ended && currentChunk == 4
                }.first { it }
            }
            // Give the state collector one dispatch turn after the terminal transition.
            delay(100)

            assertEquals(listOf("master-1", "target-1", "target-2", "master-2"), playedSources)
        } finally {
            collector.cancelAndJoin()
            withContext(Dispatchers.Main) { controller.dispose() }
        }
    }

    @Test
    fun systemTtsAudioCanBePlayedConsecutivelyByOnePlayer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = SystemTTSProvider()
        val setting = TTSProviderSetting.SystemTTS()
        val utterances = listOf(
            "Sequential playback check one.",
            "Sequential playback check two.",
            "Sequential playback check three.",
        )
        val responses = utterances.map { text ->
            val chunk = withContext(Dispatchers.Main) {
                withTimeout(SYNTHESIS_TIMEOUT_MS) {
                    provider.generateSpeech(context, setting, TTSRequest(text)).first()
                }
            }
            assertTrue("System TTS returned empty audio for: $text", chunk.data.isNotEmpty())
            TTSResponse(audioData = chunk.data, format = chunk.format, sampleRate = chunk.sampleRate)
        }

        val player = withContext(Dispatchers.Main) { AudioPlayer(context) }
        try {
            responses.forEachIndexed { index, response ->
                withContext(Dispatchers.Main) {
                    val playing = async(start = CoroutineStart.UNDISPATCHED) {
                        player.playbackState.first { it.status == PlaybackStatus.Playing }
                    }
                    withTimeout(PLAYBACK_TIMEOUT_MS) {
                        player.play(response)
                    }
                    withTimeout(PLAYBACK_TIMEOUT_MS) {
                        playing.await()
                    }
                    assertTrue("Audio ${index + 1} never reached Playing", playing.isCompleted)
                }
            }
        } finally {
            withContext(Dispatchers.Main) { player.release() }
        }
    }

    private companion object {
        const val SYNTHESIS_TIMEOUT_MS = 30_000L
        const val PLAYBACK_TIMEOUT_MS = 15_000L
        const val CONTROLLER_TIMEOUT_MS = 60_000L
        const val PAUSE_OBSERVATION_MS = 1_500L
        const val WAV_HEADER_BYTES = 44
    }
}
