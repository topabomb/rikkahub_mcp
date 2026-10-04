package net.weero.measix.pilot.ui.pages.remoteworkspace

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import net.weero.measix.pilot.RouteActivity
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.service.remoteworkspace.*
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.context.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

/** Opt-in on an already enrolled dedicated device. Only the unique test directory is mutated. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RemoteMediaPreviewLiveAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()

    @Test fun deployedCoreDavStreamsAndSeeksFourGiBThroughTheRemotePage() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("remotePreviewLive") == "true")
        withTimeout(240_000) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val koin = GlobalContext.get()
            koin.get<ApplicationRecoveryGate>().awaitReady()
            val sessions = koin.get<EnterpriseSessionController>()
            val selection = requireNotNull(sessions.readPresentation().selection)
            check(selection.access is RealmAccess.Enterprise)
            val remote = koin.get<RemoteWorkspaceService>()
            val handle = remote.open(selection)
            val folder = "android-preview-${java.util.UUID.randomUUID()}"
            val evidence = JSONObject().put("space", handle.space).put("folder", folder)
            val store = ViewModelStore()
            var failure: Throwable? = null
            var created = false
            val oldOrientation = compose.activity.requestedOrientation
            try {
                assertEquals(RemoteOutcome.SUCCEEDED, remote.createDirectory(handle, folder).outcome)
                created = true
                val bytes = instrumentation.context.assets.open("media/preview-test.mp4").use { it.readBytes() }
                val upload = remote.upload(handle, "$folder/short.mp4", null, bytes.size.toLong(), { bytes.inputStream() })
                assertEquals(upload.diagnostic, RemoteOutcome.SUCCEEDED, upload.outcome)
                awaitSparseFixture(context.cacheDir, folder, handle.space)
                val large = remote.list(handle, folder).files.single { it.name == "four-gib.mp4" }
                assertEquals(4L * 1024 * 1024 * 1024, large.size)
                val source = remote.mediaSource(handle, large)
                try {
                    assertEquals(4L * 1024 * 1024 * 1024, source.length)
                    assertEquals(16, source.readRange(3L * 1024 * 1024 * 1024, 16).size)
                    assertEquals(16, source.readRange(source.length - 16, 16).size)
                } finally { source.close() }
                evidence.put("headAndRangesBeyondTwoGiB", true)
                lateinit var vm: RemoteWorkspaceVM
                compose.runOnUiThread {
                    vm = RemoteWorkspaceVM(selection, remote, context)
                    store.put("live-preview", vm)
                    compose.activity.setContent {
                        MaterialTheme {
                            CompositionLocalProvider(
                                LocalNavController provides remember { Navigator(mutableStateListOf(Screen.Enterprise)) },
                                LocalSettings provides Settings(),
                                LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo(),
                            ) { RemoteWorkspacePage(selection, vm) }
                        }
                    }
                }
                compose.waitUntil(30_000) { vm.state.value.directory != null }
                fileNode(folder).performClick()
                compose.waitUntil(30_000) { vm.state.value.directory?.path == folder }
                for (name in listOf("short.mp4", "four-gib.mp4")) {
                    fileNode(name).performClick()
                    var view: PlayerView? = null
                    compose.waitUntil(30_000) {
                        compose.runOnIdle {
                            view = findPlayer(compose.activity.window.decorView)
                            view?.player?.playbackState == Player.STATE_READY
                        }
                    }
                    val playerView = requireNotNull(view)
                    val player = compose.runOnIdle { requireNotNull(playerView.player) }
                    val frameUs = java.util.concurrent.atomic.AtomicLong(-1)
                    compose.runOnIdle {
                        (player as androidx.media3.exoplayer.ExoPlayer).setVideoFrameMetadataListener { time, _, _, _ -> frameUs.set(time) }
                        playerView.showController()
                    }
                    onView(withId(androidx.media3.ui.R.id.exo_play_pause)).perform(click())
                    compose.waitUntil(20_000) { compose.runOnIdle { player.currentPosition > 500 && player.videoSize.width == 320 } }
                    compose.runOnIdle { playerView.showController() }
                    onView(withId(androidx.media3.ui.R.id.exo_play_pause)).perform(click())
                    onView(withId(androidx.media3.ui.R.id.exo_progress)).perform(swipeRight())
                    compose.waitUntil(30_000) { compose.runOnIdle {
                        player.playbackState == Player.STATE_READY && !player.playWhenReady && frameUs.get() > 6_000_000
                    } }
                    capture("remote-${name.removeSuffix(".mp4")}-seek")
                    compose.onNodeWithText(context.getString(net.weero.measix.pilot.R.string.file_media_rotate)).performClick()
                    compose.waitUntil(10_000) { context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                    compose.runOnIdle { assertSame(player, findPlayer(compose.activity.window.decorView)?.player) }
                    capture("remote-${name.removeSuffix(".mp4")}-landscape")
                    compose.onNodeWithText(context.getString(net.weero.measix.pilot.R.string.file_media_rotate)).performClick()
                    compose.waitUntil(10_000) { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
                    pressBack()
                    compose.waitUntil(10_000) { compose.runOnIdle { playerView.player == null } }
                    evidence.put(name, "decoded_played_paused_seek_rotated_closed")
                }
                val short = remote.list(handle, folder).files.single { it.name == "short.mp4" }
                val original = remote.mediaSource(handle, short)
                try {
                    assertEquals(RemoteOutcome.SUCCEEDED, remote.upload(handle, short.path, short.etag,
                        bytes.size.toLong(), { bytes.inputStream() }).outcome)
                    try { original.readRange(0, 32); fail("Old media version must be rejected") }
                    catch (expected: PlatformHttpException) {
                        // Existing deployments used 409; revised Core preserves HTTP 412.
                        assertTrue(expected.status == 412 || expected.status == 409)
                        assertEquals("file_version_conflict", expected.problem?.code)
                    }
                    evidence.put("oldMediaVersionRejected", true)
                } finally { original.close() }
            } catch (error: Throwable) { failure = error; throw error }
            finally {
                compose.runOnUiThread { store.clear(); compose.activity.requestedOrientation = oldOrientation }
                withContext(NonCancellable) {
                    try {
                        withTimeout(30_000) {
                            if (created) {
                                val directory = remote.list(handle, "").files.single { it.path == folder }
                                val deleted = remote.mutate(handle, RemoteFileAction.DELETE, directory, recursiveConfirmed = true)
                                assertEquals(deleted.diagnostic, RemoteOutcome.SUCCEEDED, deleted.outcome)
                                evidence.put("testDirectoryRemoved", true)
                            }
                        }
                    } catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: throw cleanup }
                    finally { remote.close(handle); File(context.cacheDir, "remote-preview-live-evidence.json").writeText(evidence.toString(2)) }
                }
            }
        }
    }

    private suspend fun awaitSparseFixture(cache: File, folder: String, space: String) {
        val request = File(cache, "remote-preview-sparse-request.json")
        val reply = File(cache, "remote-preview-sparse.done")
        check(!request.exists() && !reply.exists()) { "Stale live fixture handshake" }
        request.writeText(JSONObject().put("folder", folder).put("agentSpaceId", space).toString())
        try {
            withTimeout(90_000) { while (!reply.exists()) delay(200) }
            check(reply.readText() == folder) { "Sparse fixture belongs to another test" }
        } finally {
            check(!request.exists() || request.delete())
            check(!reply.exists() || reply.delete())
        }
    }

    private fun fileNode(name: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("remote-file-list").performScrollToNode(hasText(name))
        return compose.onNodeWithText(name)
    }

    private fun findPlayer(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) repeat(view.childCount) { findPlayer(view.getChildAt(it))?.let { found -> return found } }
        return null
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
