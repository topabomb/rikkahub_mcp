package net.weero.measix.pilot.service.portal

import android.Manifest
import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class PortalCaptureAndroidTest {
    @Test
    fun recordingAutomaticallyStopsAtTheNativeLimitAndReleasesTheMicrophoneForReuse() = runBlocking<Unit> {
        withMedia { context, scope, media ->
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
            repeat(2) {
                val capture = media.reserve(PortalMediaStore.AUDIO_MP4)
                val operation = withContext(Dispatchers.Main) {
                    AndroidPortalCaptureFactory(context, scope).create(capture, 1) { it() }.apply {
                        permissionResult(true)
                        start()
                    }
                }
                try {
                    withTimeout(15_000) { operation.result.await() }
                    withContext(Dispatchers.Main) { operation.close() }.await()
                    val duration = MediaMetadataRetriever().let { reader ->
                        try {
                            reader.setDataSource(capture.file.absolutePath)
                            requireNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
                        } finally { reader.release() }
                    }
                    assertTrue("Unexpected native recording duration: $duration", duration in 500..3_000)
                    val handle = media.publish(capture)
                    assertEquals(PortalMediaStore.AUDIO_MP4, handle.mimeType)
                    assertTrue(handle.byteLength > 0)
                    media.release(handle.mediaId)
                } finally { withContext(NonCancellable + Dispatchers.Main) { operation.close().await() }; media.discard(capture) }
            }
        }
    }

    @Test
    fun deniedPermissionAndClosingWhileTheCameraOpensNeverPublishAndReleaseOriginalResources() = runBlocking<Unit> {
        withMedia { context, scope, media ->
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
            for (granted in listOf(false, true, true)) {
                val capture = media.reserve(PortalMediaStore.JPEG)
                val operation = withContext(Dispatchers.Main) {
                    AndroidPortalCaptureFactory(context, scope).create(capture, null) { it() }.apply {
                        permissionResult(granted)
                        close()
                    }
                }
                try {
                    val error = try { withTimeout(15_000) { operation.result.await() }; error("Cancelled camera succeeded") }
                    catch (failure: PortalFailure) { failure }
                    assertEquals(if (granted) "user_cancelled" else "permission_denied", error.code)
                    withContext(Dispatchers.Main) { withTimeout(15_000) { operation.close().await() } }
                    media.discard(capture)
                    assertFalse(capture.file.exists())
                } finally { withContext(NonCancellable + Dispatchers.Main) { operation.close().await() } }
            }
        }
    }

    private suspend fun withMedia(test: suspend (Context, CoroutineScope, PortalMediaSession) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.noBackupFilesDir, "portal-capture-test-${Uuid.random()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = PortalMediaStore(root).also { it.recover() }
        val media = store.open("capture-device-test")
        try { test(context, scope, media) }
        finally { withContext(NonCancellable) { scope.coroutineContext[Job]!!.cancelAndJoin(); media.close(); check(root.deleteRecursively()) } }
    }
}
