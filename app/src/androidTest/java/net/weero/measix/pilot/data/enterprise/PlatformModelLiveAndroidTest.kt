package net.weero.measix.pilot.data.enterprise

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.common.http.RoutedHttpException
import me.rerere.common.http.readResponse
import me.rerere.common.http.withExplicitRoute
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.ModelExecutionService
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.service.runtime.generateText
import net.weero.measix.pilot.service.runtime.streamText
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.uuid.Uuid

/** Opt-in, uses the already enrolled device Session; never exchanges a code or resets application data. */
@RunWith(AndroidJUnit4::class)
class PlatformModelLiveAndroidTest {
    @Test fun enrolledPlatformRejectsStaleGenerationBeforeForwarding() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("platformLive") == "true")
        withTimeout(60_000) {
            val koin = GlobalContext.get()
            koin.get<ApplicationRecoveryGate>().awaitReady()
            val sessions = koin.get<EnterpriseSessionController>()
            val access = sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            val platform = koin.get<net.weero.measix.pilot.service.PlatformEnterpriseService>()
            platform.synchronize(access)
            val configuration = requireNotNull(koin.get<ModelExecutionService>().read(access).configuration.enterpriseConfiguration)
            val model = configuration.models.first { it.enabled }
            val version = koin.get<net.weero.measix.pilot.service.EnterpriseSynchronizationService>().prepareExecution(access)
            assumeTrue(version.generation > 1)
            val lease = sessions.captureExecution(access, version)
            val client = OkHttpClient()
            try {
                val execution = lease.execution as EnterpriseExecution.Platform
                val endpoint = execution.connection.runtime(model.id, execution.runtimePaths.getValue(model.id))
                val token = platform.accessToken(lease.sessionId)
                val request = Request.Builder().url(endpoint)
                    .header("Authorization", "Bearer ${token.value}")
                    .header("X-Measix-Managed-Generation", (version.generation - 1).toString())
                    .header("X-Measix-Interaction-Id", "int_${Uuid.random()}")
                    .post("""{"model":"${model.modelId}","messages":[{"role":"user","content":"generation check"}],"stream":false}"""
                        .toRequestBody()).build()
                val failure = try {
                    client.withExplicitRoute(endpoint, token.value).newCall(request).readResponse { it.code }
                    null
                } catch (expected: RoutedHttpException) { expected }
                assertNotNull("Expected the real Core generation barrier", failure)
                assertEquals(428, failure!!.status)
                assertEquals(version.generation, ManagedSnapshotRequired.find(failure)?.targetGeneration)
            } finally {
                lease.release()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun enrolledPlatformTranscribesKnownSpeechThroughPublishedAsr() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("platformLive") == "true")
        withTimeout(120_000) {
            val koin = GlobalContext.get()
            koin.get<ApplicationRecoveryGate>().awaitReady()
            val sessions = koin.get<EnterpriseSessionController>()
            val access = sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            val platform = koin.get<net.weero.measix.pilot.service.PlatformEnterpriseService>()
            platform.synchronize(access)
            val configuration = requireNotNull(koin.get<ModelExecutionService>().read(access).configuration.enterpriseConfiguration)
            val asr = configuration.asr.single { it.id == configuration.defaults.asrId }
            assertEquals(EnterpriseAsrProtocol.DASHSCOPE_HTTP, asr.protocol)
            val tts = configuration.tts.first { it.enabled && it.protocol == EnterpriseTtsProtocol.MIMO }
            val version = koin.get<net.weero.measix.pilot.service.EnterpriseSynchronizationService>().prepareExecution(access)
            val lease = sessions.captureExecution(access, version)
            val recording = java.io.File.createTempFile("platform-asr-", ".wav",
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
            try {
                val execution = lease.execution as EnterpriseExecution.Platform
                val token = platform.accessToken(lease.sessionId)
                val transport = koin.get<EnterpriseSpeechTransport>()
                fun reference(id: String) = me.rerere.common.configuration.ConfigurationReference.Enterprise(access.scope.authority, id)
                fun target(id: String) = transport.platformTarget(execution, reference(id), lease.version, "int_${Uuid.random()}", token.value)
                val speech = me.rerere.tts.controller.TtsSynthesizer(koin.get()).synthesize(tts.providerSetting(reference(tts.id)),
                    me.rerere.tts.controller.TtsChunk(index = 0, text = "The quick brown fox jumps over the lazy dog."), target(tts.id))
                assertEquals(me.rerere.tts.model.AudioFormat.PCM, speech.format)
                val rate = requireNotNull(speech.sampleRate)
                val pcm = speech.audioData
                val wave = java.nio.ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                wave.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16)
                    .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                    .put("data".toByteArray()).putInt(pcm.size).put(pcm)
                recording.writeBytes(wave.array())
                val transcript = transport.transcribe(target(asr.id), asr, recording)
                assertTrue("Unexpected transcript: $transcript", transcript.contains("quick brown fox", ignoreCase = true))
                assertTrue("Unexpected transcript: $transcript", transcript.contains("lazy dog", ignoreCase = true))
            } finally {
                recording.delete()
                lease.release()
            }
        }
    }

    @Test fun enrolledPlatformDefaultSpeechReachesActualPlaybackAndCompletes() = verifyPlayback(null)

    @Test fun enrolledPlatformSystemSpeechUsesDeviceEngineAndCompletes() = verifyPlayback(EnterpriseTtsProtocol.SYSTEM)

    private fun verifyPlayback(protocol: EnterpriseTtsProtocol?) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("platformLive") == "true")
        withTimeout(90_000) {
            val koin = GlobalContext.get()
            koin.get<ApplicationRecoveryGate>().awaitReady()
            val sessions = koin.get<EnterpriseSessionController>()
            val access = sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            koin.get<net.weero.measix.pilot.service.PlatformEnterpriseService>().synchronize(access)
            val selection = sessions.observeSelectedRealmSelection().first { it?.access == access }!!
            val commands = koin.get<net.weero.measix.pilot.service.ConfigurationApplicationService>()
            val saved = koin.get<net.weero.measix.pilot.data.datastore.SettingsStore>().snapshotUserDocument()
                .preferences.forScope(access.scope).selectedTTSProviderId
            if (protocol != null) {
                val configuration = koin.get<ModelExecutionService>().read(access).configuration
                val resource = requireNotNull(configuration.enterpriseConfiguration).tts.first { it.enabled && it.protocol == protocol }
                commands.selectResource(selection, net.weero.measix.pilot.data.configuration.ResourceSelectionSlot.TTS,
                    me.rerere.common.configuration.ConfigurationReference.Enterprise(access.scope.authority, resource.id))
            }
            val playback = koin.get<net.weero.measix.pilot.service.SpeechApplicationService>().playback
            val started = async(start = CoroutineStart.UNDISPATCHED) {
                combine(playback.playbackState, playback.error) { state, error -> state to error }.first {
                    it.first.status == me.rerere.tts.model.PlaybackStatus.Playing || it.second != null
                }
            }
            try {
                playback.speak(selection, "The enterprise speech integration test is complete.")
                val (state, error) = started.await()
                assertEquals("Playback failed: ${state.errorMessage}; $error",
                    me.rerere.tts.model.PlaybackStatus.Playing, state.status)
                playback.isSpeaking.first { !it }
                assertNull(playback.error.value)
            } finally {
                started.cancel()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    koin.get<net.weero.measix.pilot.service.SpeechApplicationService>().closeRealm(access)
                }
                if (protocol != null) commands.selectResource(selection,
                    net.weero.measix.pilot.data.configuration.ResourceSelectionSlot.TTS, saved)
            }
        }
    }

    @Test fun initialEnterpriseRequestUsesDeclaredSelectionOrExistingSpaceEntry() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("platformLive") == "true")
        val koin = GlobalContext.get()
        koin.get<ApplicationRecoveryGate>().awaitReady()
        val access = koin.get<EnterpriseSessionController>().captureSelectedRealmAccess()
        val selected = koin.get<ModelExecutionService>().read(access).configuration.selections.assistantId
        val initial = koin.get<net.weero.measix.pilot.service.ConversationApplicationService>().initialRequest(true)
        if (selected == null) assertEquals(net.weero.measix.pilot.service.InitialConversationRequest.SelectEnterpriseAssistant, initial)
        else assertEquals(selected, ((initial as net.weero.measix.pilot.service.InitialConversationRequest.Open).request as
            net.weero.measix.pilot.service.ConversationOpenRequest.NewDraft).assistantId)
    }

    @Test fun enrolledPlatformMcpDiscoversAndPersistsPublicCatalog() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("platformLive") == "true")
        withTimeout(60_000) {
            val koin = GlobalContext.get()
            koin.get<ApplicationRecoveryGate>().awaitReady()
            val sessions = koin.get<EnterpriseSessionController>()
            val access = sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            check(access.scope.authority.sourceNamespace.startsWith("platform:"))
            koin.get<net.weero.measix.pilot.service.PlatformEnterpriseService>().synchronize(access)
            val models = koin.get<ModelExecutionService>()
            val assistant = models.read(access).configuration.assistants.values.first {
                it.id is me.rerere.common.configuration.ConfigurationReference.Enterprise && it.mcpServers.isNotEmpty()
            }
            val conversation = Conversation(assistantId = assistant.id, scope = access.scope, messageNodes = emptyList())
            val runtime = ConversationRuntime(conversation.id, conversation.toSnapshot(), koin.get<AppScope>(), {})
            val turn = Uuid.random()
            val worker = Job()
            runtime.installTurnWorker(turn, worker)
            try {
                val captured = models.captureTurn(access, runtime, turn, worker, assistant.id, stopInteraction = {})
                val mcp = koin.get<net.weero.measix.pilot.data.ai.mcp.McpRuntimeCoordinator>()
                val capabilities = mcp.prepareTurnCapabilities(access, captured, runtime, turn, worker, stopInteraction = {})
                assertTrue("No managed tools: ${capabilities.serverOutcomes}", capabilities.tools.isNotEmpty())
                assertTrue(capabilities.tools.all { it.interactionId == "int_${captured.interactionId}" })
                val projected = mcp.inspectCapabilities(access, models.read(access), captured.assistant)
                assertEquals(capabilities.tools.map { it.name }.toSet(), projected.tools.map { it.name }.toSet())
            } finally {
                worker.cancel()
                runtime.releaseTurnWorker(turn, worker, false)
            }
        }
    }

    @Test fun enrolledPlatformModelExecutesStreamingAndAuxiliaryThroughOriginalOwner() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("platformLive") == "true")
        withTimeout(60_000) {
            val koin = GlobalContext.get()
            koin.get<ApplicationRecoveryGate>().awaitReady()
            val sessions = koin.get<EnterpriseSessionController>()
            val access = sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            check(access.scope.authority.sourceNamespace.startsWith("platform:"))
            val models = koin.get<ModelExecutionService>()
            val configuration = models.read(access).configuration
            val assistant = configuration.assistants.values.first { it.id is me.rerere.common.configuration.ConfigurationReference.Enterprise }
            val conversation = Conversation(assistantId = assistant.id, scope = access.scope, messageNodes = emptyList())
            val runtime = ConversationRuntime(conversation.id, conversation.toSnapshot(), koin.get<AppScope>(), {})
            val turn = Uuid.random()
            val worker = Job()
            runtime.installTurnWorker(turn, worker)
            try {
                val captured = models.captureTurn(access, runtime, turn, worker, assistant.id, stopInteraction = {})
                val providers = koin.get<ProviderManager>()
                val params = TextGenerationParams(captured.model.model)
                val messages = listOf(ModelRequestMessage.user("Reply with a short connection confirmation."))
                val text = StringBuilder()
                var terminal = false
                captured.model.requests.execute { target ->
                    target.streamText(providers, messages, params).collect { chunk ->
                        chunk.choices.firstOrNull()?.let { choice ->
                            choice.delta?.toText()?.let(text::append)
                            if (choice.finishReason != null) terminal = true
                        }
                    }
                }
                assertTrue("Missing streaming terminal", terminal)
                assertTrue("Empty streaming response", text.isNotBlank())
                val auxiliary = captured.model.requests.execute { target ->
                    target.generateText(providers, messages, params)
                }
                assertFalse(auxiliary.choices.single().message!!.toText().isBlank())
                assertNotNull(auxiliary.choices.single().finishReason)
            } finally {
                worker.cancel()
                runtime.releaseTurnWorker(turn, worker, false)
            }
        }
    }
}
