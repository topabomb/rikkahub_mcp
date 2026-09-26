package me.rerere.asr

import android.Manifest
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import me.rerere.asr.providers.HttpAsrController
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HttpAsrLifecycleInstrumentedTest {
    @Test fun realRecordingUploadsCompleteWaveThenDeletesIt() = runBlocking {
        requireLiveAudio()
        val context = microphoneContext()
        val uploaded = CompletableDeferred<File>()
        val delivered = CompletableDeferred<String>()
        val controller = withContext(Dispatchers.Main) {
            HttpAsrController(context, { it() }, transcribe = { file ->
                val bytes = file.readBytes()
                assertEquals("RIFF", bytes.copyOfRange(0, 4).decodeToString())
                assertEquals("WAVEfmt ", bytes.copyOfRange(8, 16).decodeToString())
                val fields = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(bytes.size - 8, fields.getInt(4))
                assertEquals(24_000, fields.getInt(24))
                assertEquals(1, fields.getShort(22).toInt())
                assertEquals(16, fields.getShort(34).toInt())
                assertEquals(bytes.size - 44, fields.getInt(40))
                assertTrue(bytes.size > 44)
                uploaded.complete(file)
                "simulated transcription"
            }, admitTranscript = { it() }).also { it.start { text -> delivered.complete(text) } }
        }
        try {
            awaitRecording(controller)
            delay(1_500)
            withContext(Dispatchers.Main) { controller.stop() }
            assertEquals("simulated transcription", awaitResult(controller, delivered))
            withTimeout(10_000) { controller.state.first { it.status == ASRStatus.Idle } }
        } finally { withContext(Dispatchers.Main) { controller.dispose() }.awaitClosed() }
        assertFalse(uploaded.await().exists())
        assertEquals(ASRState(), controller.state.value)
    }

    @Test fun cancellationWaitsForOriginalUploadAndDiscardsLateText() = runBlocking {
        requireLiveAudio()
        val entered = CompletableDeferred<File>()
        val release = CompletableDeferred<Unit>()
        var callbacks = 0
        val controller = withContext(Dispatchers.Main) {
            HttpAsrController(microphoneContext(), { it() }, transcribe = { file ->
                entered.complete(file)
                withContext(NonCancellable) { release.await() }
                "late text"
            }, admitTranscript = { it() }).also { it.start { callbacks++ } }
        }
        try {
            awaitRecording(controller)
            delay(1_500)
            withContext(Dispatchers.Main) { controller.stop() }
            val file = awaitResult(controller, entered)
            val receipt = withContext(Dispatchers.Main) { controller.dispose() }
            val closed = async(start = CoroutineStart.UNDISPATCHED) { receipt.awaitClosed() }
            assertFalse(closed.isCompleted)
            assertTrue(file.exists())
            release.complete(Unit)
            withTimeout(10_000) { closed.await() }
            assertFalse(file.exists())
            assertEquals(0, callbacks)
            assertEquals(ASRState(), controller.state.value)
        } finally {
            release.complete(Unit)
            withContext(Dispatchers.Main) { controller.dispose() }.awaitClosed()
        }
    }

    @Test fun cancellingRecordingClosesCaptureAndDeletesFileWithoutUploading() = runBlocking {
        val context = microphoneContext()
        val before = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("asr-") }.toSet()
        var uploads = 0
        var callbacks = 0
        val controller = withContext(Dispatchers.Main) {
            HttpAsrController(context, { it() }, transcribe = { uploads++; "unexpected" },
                admitTranscript = { it() }).also { it.start { callbacks++ } }
        }
        try {
            awaitRecording(controller)
            assertTrue(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("asr-") && it !in before })
        } finally {
            withTimeout(10_000) { withContext(Dispatchers.Main) { controller.dispose() }.awaitClosed() }
        }
        assertEquals(0, uploads)
        assertEquals(0, callbacks)
        assertEquals(before, context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("asr-") }.toSet())
        assertEquals(ASRState(), controller.state.value)
    }

    @Test fun revokedAdmissionNeverStartsRecordingOrUpload() = runBlocking {
        var uploads = 0
        val context = microphoneContext()
        val before = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("asr-") }.toSet()
        val controller = withContext(Dispatchers.Main) {
            HttpAsrController(context, { error("original page closed") }, transcribe = { uploads++; "invalid" },
                admitTranscript = { error("no transcript expected") }).also { it.start { error("no delivery expected") } }
        }
        try { withTimeout(10_000) { controller.state.first { it.status == ASRStatus.Error } } }
        finally { withContext(Dispatchers.Main) { controller.dispose() }.awaitClosed() }
        assertEquals(0, uploads)
        assertEquals(before, context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("asr-") }.toSet())
    }

    private fun microphoneContext(): Context = InstrumentationRegistry.getInstrumentation().let { instrumentation ->
        instrumentation.targetContext.also { instrumentation.uiAutomation.grantRuntimePermission(it.packageName, Manifest.permission.RECORD_AUDIO) }
    }

    private fun requireLiveAudio() = assumeTrue(
        "Requires httpAsrLiveAudio=true and continuous audible microphone input throughout both recordings",
        InstrumentationRegistry.getArguments().getString("httpAsrLiveAudio") == "true",
    )

    private suspend fun awaitRecording(controller: HttpAsrController) {
        val state = withTimeout(10_000) {
            controller.state.first { it.status == ASRStatus.Error || (it.status == ASRStatus.Listening && it.amplitudes.isNotEmpty()) }
        }
        check(state.status != ASRStatus.Error) { state.errorMessage.orEmpty() }
    }

    private suspend fun <T> awaitResult(controller: HttpAsrController, result: Deferred<T>): T = coroutineScope {
        val failure = async {
            val state = controller.state.first { it.status == ASRStatus.Error }
            throw AssertionError(state.errorMessage)
        }
        try {
            withTimeout(10_000) {
                select {
                    result.onAwait { it }
                    failure.onAwait { error("ASR failure observer completed without an error") }
                }
            }
        } finally {
            failure.cancel()
        }
    }
}
