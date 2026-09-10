package net.weero.measix.pilot.service

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import me.rerere.ai.provider.Model
import me.rerere.tts.controller.*
import me.rerere.tts.model.*
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.configuration.*
import net.weero.measix.pilot.data.datastore.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.model.Assistant
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechApplicationServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val main = newSingleThreadContext("speech-owner-test")
    @Before fun installMain() { Dispatchers.setMain(main) }
    @After fun resetMain() { Dispatchers.resetMain(); main.close() }

    @Test fun `capture cannot survive leaving and reentering the same enterprise`() = runBlocking {
        environment { e ->
            val capture = e.capture()
            val original = capture.selection
            val personal = e.sessions.switchRealm(RealmSwitchRequest(original, RealmAccess.Personal), e.service::revoke)
            e.sessions.switchRealm(RealmSwitchRequest(personal, original.access), e.service::revoke)
            e.service.enqueue(e.capture(), "current", "new reply", false, null)
            rejected { e.service.enqueue(capture, "original", "old reply", false, null) }
            verify(exactly = 1) { e.player.speak(any(), any(), any(), any()) }
            verify(exactly = 0) { e.player.stop() }
        }
    }

    @Test fun `first queue rejects changed private revision even when generation is unchanged`() = runBlocking {
        environment { e ->
            val capture = e.capture()
            e.sessions.synchronize(capture.selection.access as RealmAccess.Enterprise,
                e.packet.copy(runtimeBindings = e.packet.runtimeBindings.map { if (it.resourceId == e.packet.configuration.defaults.ttsId) it.copy(protocol = EnterpriseRuntimeProtocol.OPENAI_TTS, endpoint = "https://speech.test/v1/audio/speech", credential = "changed") else it }))
            // A private publication always has its own revision, independently of generation.
            rejected { e.service.enqueue(capture, "original", "old reply", false, null) }
            verify(exactly = 0) { e.player.speak(any(), any(), any(), any()) }
            e.exit()
        }
    }

    @Test fun `queue retains binding until the first playback cleanup completes`() = runBlocking {
        environment { e ->
            val capture = e.capture()
            e.service.enqueue(capture, "original", "first chunk", false, null)
            val closing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { e.receipt.awaitClosed() } coAnswers { closing.complete(Unit); release.await() }
            val token = e.sessions.beginExit(requireNotNull(e.sessions.captureExitRequest()))
            val cleanup = async { e.service.closeRealm(capture.selection.access) }
            closing.await()
            rejected { e.sessions.finishExit(token) }
            assertFalse(cleanup.isCompleted)
            release.complete(Unit)
            cleanup.await()
            e.sessions.finishExit(token)
            verify(exactly = 1) { e.player.stop() }
        }
    }

    @Test fun `failed cleanup retains original receipt for retry without stopping a new queue`() = runBlocking {
        environment { e ->
            val capture = e.capture()
            e.service.enqueue(capture, "original", "first chunk", false, null)
            coEvery { e.receipt.awaitClosed() } throws java.io.IOException("cleanup failure")
            try { e.service.closeRealm(capture.selection.access); fail("cleanup failure hidden") } catch (_: java.io.IOException) { }
            val token = e.sessions.beginExit(requireNotNull(e.sessions.captureExitRequest()))
            rejected { e.sessions.finishExit(token) }
            coEvery { e.receipt.awaitClosed() } returns Unit
            e.service.closeRealm(capture.selection.access)
            e.sessions.finishExit(token)
            verify(exactly = 1) { e.player.stop() }
        }
    }

    @Test fun `system speech follows user TTS admission without changing personal settings`() = runBlocking {
        environment { e ->
            val access = e.sessions.captureSelectedRealmAccess()
            ConfigurationApplicationService(e.settings, e.sessions, e.gate, mockk(), mockk()).selectResource(
                RealmSelection(access, e.sessions.selectionRevision.value), ResourceSelectionSlot.TTS, DEFAULT_SYSTEM_TTS_ID)
            e.sessions.synchronize(access as RealmAccess.Enterprise, e.packet.copy(configuration = e.packet.configuration.copy(
                generation = 2, policy = e.packet.configuration.policy.copy(allowLocalTts = false))))
            rejected { e.service.enqueue(e.capture(), "blocked", "system voice", false, null) }
            assertTrue(e.queries.read(RealmAccess.Personal).access(ConfigurationCategory.TTS, DEFAULT_SYSTEM_TTS_ID).canExecute)
            assertFalse(e.queries.read(access).access(ConfigurationCategory.TTS, DEFAULT_SYSTEM_TTS_ID).canExecute)
            verify(exactly = 0) { e.player.speak(any(), any(), any(), any()) }
        }
    }

    @Test fun `428 terminates original capture and syncs even when retired file cleanup fails`() = runBlocking {
        for (cleanupFails in listOf(false, true)) environment { e ->
            val stopping = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val capture = e.capture { stopping.complete(Unit); release.await() }
            coEvery { e.transport.synthesize(any(), any(), any()) } throws ManagedSnapshotRequired(2, "request-test")
            e.service.enqueue(capture, "original", "first reply", false, null)
            if (cleanupFails) coEvery { e.receipt.awaitClosed() } throws java.io.IOException("file cleanup failed")
            try {
                rejected { e.playbackSession!!.synthesize(TtsChunk(index = 0, text = "first reply")) }
                withTimeout(5_000) { stopping.await() }
                rejected { e.service.enqueue(capture, "original", "late reply", false, null) }
                verify(exactly = 1) { e.player.speak(any(), any(), any(), any()) }
                coVerify(exactly = 1) { e.transport.synthesize(any(), any(), any()) }
            } finally { release.complete(Unit) }
            withTimeout(5_000) { e.synchronized.await() }
            coVerify(exactly = 1) { e.sync.synchronize(any()) }
        }
    }

    @Test fun `termination or session expiry during settings wait cannot cross final playback admission`() = runBlocking {
        for (expired in listOf(false, true)) environment { e ->
            val capture = e.capture()
            e.service.enqueue(capture, "original", "first reply", false, null)
            val session = requireNotNull(e.playbackSession)
            val locked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = async {
                e.settings.withExecutionConfiguration(capture.selection.access.scope, e.sessions.state.value) {
                    locked.complete(Unit)
                    release.await()
                }
            }
            locked.await()
            var starts = 0
            val pending = withContext(Dispatchers.Main.immediate) {
                e.scope.async(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
                    session.admitPlayback { starts++ }
                }
            }
            try {
                if (expired) e.clock.set((e.sessions.state.value as EnterpriseState.Available).manifest.session!!.expiresAtMillis + 1)
                else capture.terminate()
                release.complete(Unit)
                holder.await()
                rejected { pending.await() }
                assertEquals(0, starts)
            } finally { release.complete(Unit); holder.join(); pending.cancelAndJoin() }
        }
    }

    private suspend fun environment(block: suspend (Environment) -> Unit) {
        val e = Environment(temporary.newFolder())
        try {
            e.settings.userSettings.first { !it.init }
            e.settings.updateLocal { Settings() }
            e.sessions.recover()
            e.sessions.enrollFixture(e.packet)
            e.gate.ready()
            block(e)
        } finally {
            coEvery { e.receipt.awaitClosed() } returns Unit
            e.service.closeRealm(RealmAccess.Personal)
            (e.sessions.state.value as? EnterpriseState.Available)?.manifest?.session?.let {
                e.service.closeRealm(RealmAccess.Enterprise(it.identity.scope, it.id))
            }
            e.scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private class Environment(root: File) {
        val scope = AppScope(Dispatchers.Default)
        private val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = root.resolve("files").apply { mkdirs() }
        }
        val settings = SettingsStore(context, scope, dataStore = PreferenceDataStoreFactory.create(
            migrations = listOf(UserSettingsMigration()), scope = scope, produceFile = { root.resolve("settings.preferences_pb") }))
        val clock = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(root.resolve("enterprise")), clock::get)
        val packet = exampleEnterprisePackage()
        val gate = ApplicationRecoveryGate()
        val queries = ConfigurationQueryService(settings, sessions, gate)
        val receipt = mockk<TtsPlaybackCleanup> { coEvery { awaitClosed() } returns Unit }
        var playbackSession: TtsPlaybackSession? = null
        val player = mockk<TtsController>(relaxed = true) {
            every { playbackState } returns MutableStateFlow(PlaybackState())
            every { error } returns MutableStateFlow(null)
            every { stop() } returns receipt
            every { setSession(any()) } answers { playbackSession = firstArg(); receipt }
        }
        val synchronized = CompletableDeferred<Unit>()
        val sync = mockk<EnterpriseSynchronizationService> { coEvery { synchronize(any()) } coAnswers { synchronized.complete(Unit); sessions.state.value as EnterpriseState.Available } }
        val transport = mockk<EnterpriseSpeechTransport>()
        val service = SpeechApplicationService(context, settings, sessions, queries, sync, mockk(relaxed = true), transport, mockk(), scope, player)
        suspend fun capture(stopParent: suspend () -> Unit = {}): SpeechCapture {
            val access = sessions.captureSelectedRealmAccess()
            val configuration = queries.readExecution(access)
            val version = (sessions.state.value as EnterpriseState.Available).manifest.applied
            return service.captureTurn(CapturedModelConfiguration(configuration.userSettings, configuration.configuration,
                Assistant(), ModelExecutionSnapshot(Model(), mockk(), configuration.userRevision, version), sessions.selectionRevision.value, Uuid.random()), access, stopParent)
        }
        suspend fun exit() {
            val token = sessions.beginExit(requireNotNull(sessions.captureExitRequest()))
            sessions.finishExit(token)
        }
    }

    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("expected rejected speech") }
        catch (_: EnterpriseConfigurationException) { }
        catch (_: IllegalStateException) { }
    }
}
