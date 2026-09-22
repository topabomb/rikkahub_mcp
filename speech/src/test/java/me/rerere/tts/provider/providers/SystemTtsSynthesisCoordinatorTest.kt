package me.rerere.tts.provider.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.tts.provider.SystemTtsParameterPolicy
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class SystemTtsSynthesisCoordinatorTest {
    @Test
    fun `vendor engine requests are serialized and each engine is closed once`() = runTest {
        val coordinator = SystemTtsSynthesisCoordinator(timeoutMs = 5_000)
        val first = FakeEngine()
        val second = FakeEngine()
        val firstFile = temporaryFile()
        val secondFile = temporaryFile()

        val firstResult = async { coordinator.synthesize(first.factory(), setting(), "first", Locale.US, firstFile) }
        first.started.await()
        val secondResult = async { coordinator.synthesize(second.factory(), setting(), "second", Locale.US, secondFile) }
        runCurrent()

        assertEquals(1, first.createCalls.get())
        assertEquals(0, second.createCalls.get())

        first.complete(firstFile, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), firstResult.await())
        second.started.await()
        second.complete(secondFile, byteArrayOf(4, 5, 6))
        assertArrayEquals(byteArrayOf(4, 5, 6), secondResult.await())
        assertEquals(1, first.shutdownCalls.get())
        assertEquals(1, second.shutdownCalls.get())
    }

    @Test
    fun `duplicate terminal callbacks keep the first result and never throw`() = runTest {
        val coordinator = SystemTtsSynthesisCoordinator(timeoutMs = 5_000)
        val engine = FakeEngine()
        val file = temporaryFile()
        val result = async { coordinator.synthesize(engine.factory(), setting(), "hello", Locale.US, file) }
        engine.started.await()

        engine.complete(file, byteArrayOf(7, 8))
        engine.error(errorCode = -7)

        assertArrayEquals(byteArrayOf(7, 8), result.await())
        assertEquals(1, engine.shutdownCalls.get())
    }

    @Test
    fun `engine error code is preserved as an explicit failure`() = runTest {
        supervisorScope {
            val coordinator = SystemTtsSynthesisCoordinator(timeoutMs = 5_000)
            val engine = FakeEngine()
            val file = temporaryFile()
            val result = async { coordinator.synthesize(engine.factory(), setting(), "hello", Locale.US, file) }
            engine.started.await()

            engine.error(errorCode = -6)

            val failure = expectSystemTtsFailure(result, "system_tts_synthesis_failed")
            assertTrue(requireNotNull(failure.message).contains("-6"))
            assertEquals(1, engine.shutdownCalls.get())
            assertFalse(file.exists())
        }
    }

    @Test
    fun `engine stop is a terminal failure instead of an infinite buffer`() = runTest {
        supervisorScope {
            val coordinator = SystemTtsSynthesisCoordinator(timeoutMs = 5_000)
            val engine = FakeEngine()
            val file = temporaryFile()
            val result = async { coordinator.synthesize(engine.factory(), setting(), "hello", Locale.US, file) }
            engine.started.await()

            engine.stop(interrupted = true)

            expectSystemTtsFailure(result, "system_tts_synthesis_stopped")
            assertEquals(1, engine.shutdownCalls.get())
        }
    }

    @Test
    fun `missing vendor callback times out and releases the engine`() = runTest {
        supervisorScope {
            val coordinator = SystemTtsSynthesisCoordinator(timeoutMs = 100)
            val engine = FakeEngine()
            val file = temporaryFile()
            val result = async { coordinator.synthesize(engine.factory(), setting(), "hello", Locale.US, file) }
            engine.started.await()

            advanceTimeBy(101)
            runCurrent()

            expectSystemTtsFailure(result, "system_tts_timeout")
            assertEquals(1, engine.shutdownCalls.get())
            assertFalse(file.exists())
        }
    }

    @Test
    fun `caller cancellation releases the engine without becoming a speech failure`() = runTest {
        val coordinator = SystemTtsSynthesisCoordinator(timeoutMs = 5_000)
        val engine = FakeEngine()
        val file = temporaryFile()
        val result = async { coordinator.synthesize(engine.factory(), setting(), "hello", Locale.US, file) }
        engine.started.await()

        result.cancel(CancellationException("user stopped playback"))
        result.cancelAndJoin()

        assertTrue(result.isCancelled)
        assertEquals(1, engine.shutdownCalls.get())
        assertFalse(file.exists())
    }

    @Test
    fun `cancellation while vendor initialization is pending still releases the retained engine`() = runTest {
        val coordinator = SystemTtsSynthesisCoordinator(timeoutMs = 5_000)
        val engine = FakeEngine(autoInitialize = false)
        val file = temporaryFile()
        val result = async { coordinator.synthesize(engine.factory(), setting(), "hello", Locale.US, file) }
        engine.created.await()

        result.cancel(CancellationException("selection replaced"))
        result.cancelAndJoin()

        assertEquals(1, engine.shutdownCalls.get())
        assertEquals(0, engine.synthesisCalls.get())
        assertFalse(file.exists())
    }

    @Test
    fun `unsupported locale and invalid parameters fail before unsafe synthesis`() = runTest {
        supervisorScope {
            val unavailable = FakeEngine(languageStatus = -2)
            val unavailableFile = temporaryFile()
            val localeFailure = async {
                SystemTtsSynthesisCoordinator().synthesize(
                    unavailable.factory(), setting(), "hello", Locale.JAPAN, unavailableFile
                )
            }
            expectSystemTtsFailure(localeFailure, "system_tts_language_unavailable")
            assertEquals(0, unavailable.synthesisCalls.get())

            val invalid = FakeEngine()
            val invalidFile = temporaryFile()
            val parameterFailure = async {
                SystemTtsSynthesisCoordinator().synthesize(
                    invalid.factory(), setting(speechRate = Float.POSITIVE_INFINITY), "hello", Locale.US, invalidFile
                )
            }
            try {
                parameterFailure.await()
                fail("Expected invalid system TTS parameters")
            } catch (error: IllegalArgumentException) {
                assertEquals("invalid_system_tts_speech_rate", error.message)
            }
            assertEquals(0, invalid.createCalls.get())
            assertFalse(invalidFile.exists())
        }
    }

    @Test
    fun `vendor runtime exception is converted into a stable failure`() = runTest {
        supervisorScope {
            val engine = FakeEngine(languageFailure = IllegalStateException("vendor binder crashed"))
            val result = async {
                SystemTtsSynthesisCoordinator().synthesize(
                    engine.factory(), setting(), "hello", Locale.US, temporaryFile()
                )
            }

            val failure = expectSystemTtsFailure(result, "system_tts_language_failed")
            assertTrue(requireNotNull(failure.message).contains("vendor binder crashed"))
            assertEquals(1, engine.shutdownCalls.get())
        }
    }

    @Test
    fun `initialization and shutdown failures are explicit and still clean temporary output`() = runTest {
        supervisorScope {
            val initFailure = FakeEngine(initStatus = -1)
            val initFile = temporaryFile()
            val initResult = async {
                SystemTtsSynthesisCoordinator().synthesize(
                    initFailure.factory(), setting(), "hello", Locale.US, initFile
                )
            }
            expectSystemTtsFailure(initResult, "system_tts_initialization_failed")
            assertEquals(1, initFailure.shutdownCalls.get())
            assertFalse(initFile.exists())

            val shutdownFailure = FakeEngine(shutdownFailure = IllegalStateException("vendor unbind failed"))
            val shutdownFile = temporaryFile()
            val shutdownResult = async {
                SystemTtsSynthesisCoordinator().synthesize(
                    shutdownFailure.factory(), setting(), "hello", Locale.US, shutdownFile
                )
            }
            shutdownFailure.started.await()
            shutdownFailure.complete(shutdownFile, byteArrayOf(1))
            expectSystemTtsFailure(shutdownResult, "system_tts_shutdown_failed")
            assertFalse(shutdownFile.exists())
        }
    }

    @Test
    fun `parameter policy has one bounded contract for personal and enterprise settings`() {
        SystemTtsParameterPolicy.requireValid(0.1, 0.1)
        SystemTtsParameterPolicy.requireValid(3.0, 2.0)

        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.09, 3.01).forEach { rate ->
            try {
                SystemTtsParameterPolicy.requireValid(rate, 1.0)
                fail("Expected rate $rate to be rejected")
            } catch (_: IllegalArgumentException) {}
        }
    }

    private suspend fun expectSystemTtsFailure(
        result: kotlinx.coroutines.Deferred<ByteArray>,
        reason: String,
    ): SystemTtsException = try {
        result.await()
        fail("Expected $reason")
        error("unreachable")
    } catch (error: SystemTtsException) {
        assertEquals(reason, error.reason)
        error
    }

    private fun setting(speechRate: Float = 1.0f) = TTSProviderSetting.SystemTTS(
        speechRate = speechRate,
        pitch = 1.0f,
    )

    private fun temporaryFile(): File = Files.createTempFile("system_tts_test_", ".wav").toFile()

    private class FakeEngine(
        private val initStatus: Int = 0,
        private val languageStatus: Int = 0,
        private val speechRateStatus: Int = 0,
        private val pitchStatus: Int = 0,
        private val listenerStatus: Int = 0,
        private val synthesisStatus: Int = 0,
        private val languageFailure: RuntimeException? = null,
        private val autoInitialize: Boolean = true,
        private val shutdownFailure: RuntimeException? = null,
    ) : SystemTtsEngine {
        override val name: String = "fake.vendor.tts"
        val createCalls = AtomicInteger()
        val synthesisCalls = AtomicInteger()
        val shutdownCalls = AtomicInteger()
        val created = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        private var listener: SystemTtsProgressListener? = null
        private var utteranceId: String? = null

        fun factory() = SystemTtsEngineFactory { onInit ->
            createCalls.incrementAndGet()
            created.complete(Unit)
            if (autoInitialize) onInit(initStatus)
            this
        }

        override fun setLanguage(locale: Locale): Int = languageFailure?.let { throw it } ?: languageStatus
        override fun setSpeechRate(rate: Float): Int = speechRateStatus
        override fun setPitch(pitch: Float): Int = pitchStatus
        override fun setProgressListener(listener: SystemTtsProgressListener): Int {
            this.listener = listener
            return listenerStatus
        }

        override fun synthesizeToFile(text: String, file: File, utteranceId: String): Int {
            synthesisCalls.incrementAndGet()
            this.utteranceId = utteranceId
            started.complete(Unit)
            return synthesisStatus
        }

        override fun shutdown() {
            shutdownCalls.incrementAndGet()
            shutdownFailure?.let { throw it }
        }

        fun complete(file: File, bytes: ByteArray) {
            file.writeBytes(bytes)
            requireNotNull(listener).onDone(requireNotNull(utteranceId))
        }

        fun error(errorCode: Int) {
            requireNotNull(listener).onError(requireNotNull(utteranceId), errorCode)
        }

        fun stop(interrupted: Boolean) {
            requireNotNull(listener).onStop(requireNotNull(utteranceId), interrupted)
        }
    }
}
