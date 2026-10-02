package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rerere.tts.controller.TtsChunk
import me.rerere.tts.controller.TtsController
import me.rerere.tts.controller.TtsPlaybackCleanup
import me.rerere.tts.controller.TtsPlaybackSession
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.provider.TTSManager
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.test.installExecutionConfigurationFixture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechApplicationServiceBarrierTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `enterprise system speech has no platform usage refresh while cloud speech preserves it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            for (protocol in listOf(EnterpriseTtsProtocol.SYSTEM, EnterpriseTtsProtocol.OPENAI)) {
                val sessions = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder()))
                val packet = exampleEnterprisePackage().let { original ->
                    val configuration = original.configuration.copy(tts = original.configuration.tts.map { definition ->
                        if (protocol == EnterpriseTtsProtocol.SYSTEM) definition.copy(protocol = protocol, modelId = null, voice = null,
                            voiceDesignPrompt = null, speechRate = 1.0, pitch = 1.0) else definition.copy(protocol = protocol)
                    })
                    original.copy(configuration = configuration)
                }
                sessions.enrollFixture(exampleEnterprisePackage())
                val selection = requireNotNull(sessions.readPresentation().selection)
                val access = selection.access as RealmAccess.Enterprise
                val candidate = platformCandidate(packet.copy(configuration = packet.configuration.copy(generation = packet.configuration.generation + 1)))
                val execution = candidate.execution as EnterpriseExecution.Platform
                val available = sessions.synchronize(access, candidate.copy(execution = execution.copy(
                    runtimePaths = execution.runtimePaths.filterKeys { it in candidate.configuration.runtimeResources() })))
                val settings = mockk<SettingsStore>()
                every { settings.userSettings } returns MutableStateFlow(Settings())
                installExecutionConfigurationFixture(settings)
                val player = mockk<TtsController>(relaxed = true)
                every { player.playbackState } returns MutableStateFlow(PlaybackState())
                every { player.error } returns MutableStateFlow<String?>(null)
                var playbackSession: TtsPlaybackSession? = null
                every { player.setSession(any()) } answers { playbackSession = firstArg(); mockk<TtsPlaybackCleanup>(relaxed = true) }
                val manager = mockk<TTSManager>()
                every { manager.generateSpeech(any(), any()) } returns flow { throw IOException("speech transport diagnostic") }
                val platform = mockk<PlatformEnterpriseService>(relaxed = true)
                coEvery { platform.accessToken(access.sessionId, any()) } returns PlatformAccessToken("fixture-token", Long.MAX_VALUE)
                coEvery { platform.managedRuntimeFailure(access, any()) } coAnswers { secondArg() }
                val queries = mockk<ConfigurationQueryService>()
                every { queries.observeCurrent() } returns emptyFlow()
                val service = SpeechApplicationService(speechContext(), settings, sessions, queries, mockk(),
                    manager, EnterpriseSpeechTransport(), okhttp3.OkHttpClient(), scope, player, platform)
                val definition = packet.configuration.tts.single()
                val capture = SpeechCapture(selection, ConfigurationCategory.TTS, packet.identity.reference(definition.id),
                    null, null, definition, null, available.manifest.applied, "int_${kotlin.uuid.Uuid.random()}", {}, {}, "", false, false)
                service.enqueue(capture, "speech-queue", "Speech text", false, null)
                val failure = runCatching { requireNotNull(playbackSession).synthesize(TtsChunk(index = 0, text = "Speech text")) }.exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains("speech transport diagnostic"))
                verify(exactly = if (protocol == EnterpriseTtsProtocol.SYSTEM) 0 else 1) { platform.runtimeCompleted(access) }
                coVerify(exactly = if (protocol == EnterpriseTtsProtocol.SYSTEM) 0 else 1) { platform.accessToken(access.sessionId, any()) }
                service.closeRealm(access)
            }
        } finally { scope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun `speech barrier cleans playback and binding even after parent stop fails without synchronizing or replaying`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val sessions = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder()))
        val packet = exampleEnterprisePackage()
        val available = sessions.enrollFixture(packet)
        val selection = requireNotNull(sessions.readPresentation().selection)
        val access = selection.access as RealmAccess.Enterprise
        val settings = mockk<SettingsStore>()
        every { settings.userSettings } returns MutableStateFlow(Settings())
        installExecutionConfigurationFixture(settings)
        val queries = mockk<ConfigurationQueryService>()
        every { queries.observeCurrent() } returns emptyFlow()
        val synchronization = mockk<EnterpriseSynchronizationService>()
        val player = mockk<TtsController>(relaxed = true)
        every { player.playbackState } returns MutableStateFlow(PlaybackState())
        every { player.error } returns MutableStateFlow<String?>(null)
        var playbackSession: TtsPlaybackSession? = null
        val playbackClosed = CompletableDeferred<Unit>()
        every { player.setSession(any()) } answers {
            playbackSession = firstArg()
            if (playbackSession == null) playbackClosed.complete(Unit)
            mockk<TtsPlaybackCleanup>(relaxed = true)
        }
        val cleanupStarted = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val cleanup = mockk<TtsPlaybackCleanup>()
        coEvery { cleanup.awaitClosed() } coAnswers { cleanupStarted.complete(Unit); finishCleanup.await() }
        every { player.stop() } returns cleanup
        val barrier = ManagedSnapshotRequired(packet.configuration.generation + 1, "req_speech_manual_sync")
        val manager = mockk<TTSManager>()
        every { manager.generateSpeech(any(), any()) } returns flow { throw barrier }
        val platform = mockk<PlatformEnterpriseService>()
        coEvery { platform.accessToken(access.sessionId, any()) } returns PlatformAccessToken("fixture-token", Long.MAX_VALUE)
        coEvery { platform.managedRuntimeFailure(access, any()) } coAnswers { secondArg() }
        every { platform.runtimeCompleted(access) } returns Unit
        val service = SpeechApplicationService(speechContext(), settings, sessions, queries, synchronization,
            manager, EnterpriseSpeechTransport(), okhttp3.OkHttpClient(), scope, player, platform)
        var parentStops = 0
        val definition = packet.configuration.tts.single()
        val capture = SpeechCapture(selection, ConfigurationCategory.TTS, packet.identity.reference(definition.id),
            null, null, definition, null, available.manifest.applied, "int_speech_manual_sync", {},
            { parentStops++; throw IOException("parent_cleanup_fixture") }, "", false, false)
        try {
            service.enqueue(capture, "speech-queue", "Original speech text", false, null)
            val request = requireNotNull(playbackSession)
            val failure = runCatching { request.synthesize(TtsChunk(index = 0, text = "Original speech text")) }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it is ManagedSnapshotRequired })
            runCurrent()
            assertEquals(1, parentStops)
            assertTrue(cleanupStarted.isCompleted)
            assertTrue(runCatching { capture.requireActive() }.exceptionOrNull()?.message == "speech_interaction_terminated")
            coVerify(exactly = 0) { synchronization.synchronize(any()) }
            val token = sessions.beginExit(requireNotNull(sessions.captureExitRequest()))
            assertEquals("enterprise_executions_pending",
                (runCatching { sessions.finishExit(token) }.exceptionOrNull() as EnterpriseConfigurationException).reason)

            finishCleanup.complete(Unit)
            playbackClosed.await()
            runCurrent()
            assertNull(playbackSession)
            assertTrue(service.playback.error.value?.detail.orEmpty().contains("parent_cleanup_fixture"))
            val presented = requireNotNull(service.playback.error.value)
            service.dismissError(kotlin.uuid.Uuid.random())
            assertSame(presented, service.playback.error.value)
            service.dismissError(presented.id)
            assertNull(service.playback.error.value)
            assertTrue(runCatching { service.enqueue(capture, "speech-queue", "Do not replay", false, null) }.isFailure)
            verify(exactly = 1) { manager.generateSpeech(any(), any()) }
            verify(exactly = 1) { player.stop() }
            coVerify(exactly = 0) { synchronization.synchronize(any()) }
            sessions.finishExit(token)
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (sessions.state.value as EnterpriseState.Available).manifest.phase)
        } finally {
            finishCleanup.complete(Unit)
            runCurrent()
            scope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun speechContext(): android.content.Context = mockk {
        every { getSystemService(any<String>()) } answers { RuntimeEnvironment.getApplication().getSystemService(firstArg<String>()) }
        every { getString(any()) } answers { "resource-${firstArg<Int>()}" }
    }
}
