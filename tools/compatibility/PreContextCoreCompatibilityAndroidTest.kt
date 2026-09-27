package net.weero.measix.pilot.data.enterprise

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.EnterpriseApplicationService
import net.weero.measix.pilot.service.EnterpriseStarterReadState
import net.weero.measix.pilot.service.EnterpriseSynchronizationService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Opt-in probe for this exact old production implementation against an isolated, real Core. */
@RunWith(AndroidJUnit4::class)
class PreContextCoreCompatibilityAndroidTest {
    @Test
    fun verifiesRequestedLiveScenario() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val path = InstrumentationRegistry.getArguments().getString("coreCompatibilityInput")
        assumeTrue(path != null)
        val inputFile = File(requireNotNull(path)).canonicalFile
        require(inputFile.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        val input = JSONObject(inputFile.readText())
        val scenario = input.getString("scenario")
        val reason = input.optString("expectedReason", "unknown_platform_field")
        require(reason in setOf("unknown_platform_field", "invalid_platform_ManagedSnapshot_schemaVersion"))
        require(scenario in setOf(
            "v4_join", "v4_reopen", "v4_recover", "v5_sync_reject", "v5_reopen_reject",
            "v5_join_reject", "v5_pending_reopen",
        ))
        val evidence = JSONObject().put("scenario", scenario).put("baseline", "29ecc1093")
            .put("acceptedSnapshotSchema", 4)
        val baselineFile = File(context.cacheDir, "core-compatibility-v4-baseline.json")
        val pendingFile = File(context.cacheDir, "core-compatibility-pending-baseline.json")
        withTimeout(120_000) {
            val koin = GlobalContext.get()
            koin.get<ApplicationRecoveryGate>().awaitReady()
            val sessions = koin.get<EnterpriseSessionController>()
            val service = koin.get<EnterpriseApplicationService>()

            suspend fun available(): EnterpriseState.Available =
                sessions.state.first { it !is EnterpriseState.Loading } as EnterpriseState.Available

            fun assertV4Retained(state: EnterpriseState.Available) {
                val baseline = JSONObject(baselineFile.readText())
                val applied = requireNotNull(state.manifest.applied)
                assertEquals(baseline.getLong("generation"), applied.generation)
                assertEquals(baseline.getString("revision"), applied.revision)
                assertEquals(baseline.getString("configurationHash"), applied.configurationHash)
                assertEquals(baseline.getString("executionHash"), applied.executionHash)
                assertEquals(baseline.getString("configurationDigest"), configurationDigest(state))
                assertEquals(EnterpriseSessionPhase.READY, state.manifest.phase)
                assertNotNull(state.configuration)
                evidence.put("appliedPreserved", true).put("configurationPreserved", true)
            }

            suspend fun verifyStarterPrefill(state: EnterpriseState.Available) {
                val selection = requireNotNull(sessions.readPresentation().selection)
                assertTrue(selection.access is RealmAccess.Enterprise)
                val catalog = koin.get<ConfigurationQueryService>().observeEnterpriseStarters(selection)
                    .first() as EnterpriseStarterReadState.Available
                assertEquals(input.getInt("expectedStarterCount"), catalog.value.starters.size)
                assertTrue(catalog.value.starters.isNotEmpty())
                catalog.value.starters.forEach { starter ->
                    val draft = koin.get<ConversationApplicationService>().newStarterDraftRequest(starter.target)
                    assertEquals(starter.prompt, draft.text)
                    assertTrue(draft.text.isNotBlank())
                    assertEquals(selection.access, draft.request.access)
                    assertEquals(requireNotNull(state.configuration).generation, starter.target.generation)
                }
                evidence.put("starterCount", catalog.value.starters.size).put("starterPrefillVerified", true)
            }

            when (scenario) {
                "v4_join" -> {
                    assertNull(available().manifest.applied)
                    service.confirmJoin(requireNotNull(service.join(input.getString("enrollment"))))
                    val state = available()
                    assertEquals(EnterpriseSessionPhase.READY, state.manifest.phase)
                    val applied = requireNotNull(state.manifest.applied)
                    assertEquals(input.getLong("expectedGeneration"), applied.generation)
                    verifyStarterPrefill(state)
                    koin.get<EnterpriseSynchronizationService>().prepareExecution(
                        requireNotNull(service.observe().first().access),
                    )
                    baselineFile.writeText(JSONObject()
                        .put("generation", applied.generation).put("revision", applied.revision)
                        .put("configurationHash", applied.configurationHash)
                        .put("executionHash", applied.executionHash)
                        .put("configurationDigest", configurationDigest(state)).toString())
                    evidence.put("executionAdmission", "accepted")
                }
                "v4_reopen" -> {
                    assertV4Retained(available())
                    service.synchronize(requireNotNull(service.observe().first().access))
                    val state = available()
                    assertV4Retained(state)
                    verifyStarterPrefill(state)
                    koin.get<EnterpriseSynchronizationService>().prepareExecution(
                        requireNotNull(service.observe().first().access),
                    )
                    evidence.put("executionAdmission", "accepted").put("reopened", true)
                }
                "v4_recover" -> {
                    // Startup can synchronize before this test resumes; the saved binding is the invariant.
                    val before = available()
                    val pendingBaseline = JSONObject(pendingFile.readText())
                    assertTrue(pendingBaseline.getBoolean("appliedAbsent"))
                    val expectedSessionId = pendingBaseline.getString("sessionId")
                    assertEquals(expectedSessionId, requireNotNull(before.manifest.session).id)
                    assertTrue(before.manifest.phase in setOf(
                        EnterpriseSessionPhase.CONFIGURATION_PENDING, EnterpriseSessionPhase.READY,
                    ))
                    val access = requireNotNull(service.observe().first().access)
                    service.synchronize(access)
                    val state = available()
                    assertEquals(expectedSessionId, requireNotNull(state.manifest.session).id)
                    assertEquals(EnterpriseSessionPhase.READY, state.manifest.phase)
                    val applied = requireNotNull(state.manifest.applied)
                    assertEquals(input.getLong("expectedGeneration"), applied.generation)
                    assertNotNull(state.configuration)
                    val selection = requireNotNull(sessions.readPresentation().selection)
                    if (selection.access != access) {
                        service.switchRealm(RealmSwitchRequest(selection, access))
                    }
                    verifyStarterPrefill(available())
                    assertEquals(applied, koin.get<EnterpriseSynchronizationService>().prepareExecution(access))
                    val overview = service.observe().first()
                    assertNull(overview.failure)
                    assertNull(overview.enrollmentRecoveryFailure)
                    assertEquals(expectedSessionId, requireNotNull(available().manifest.session).id)
                    baselineFile.writeText(JSONObject()
                        .put("generation", applied.generation).put("revision", applied.revision)
                        .put("configurationHash", applied.configurationHash)
                        .put("executionHash", applied.executionHash)
                        .put("configurationDigest", configurationDigest(state)).toString())
                    evidence.put("originalBindingPreserved", true).put("enrollmentRepeated", false)
                        .put("executionAdmission", "accepted").put("recoveryDiagnosticCleared", true)
                }
                "v5_sync_reject", "v5_reopen_reject" -> {
                    assertV4Retained(available())
                    val access = requireNotNull(service.observe().first().access)
                    requireSnapshotVersionRejection(reason) { service.synchronize(access) }
                    assertV4Retained(available())
                    requireSnapshotVersionRejection(reason) {
                        koin.get<EnterpriseSynchronizationService>().prepareExecution(access)
                    }
                    assertV4Retained(available())
                    verifyStarterPrefill(available())
                    if (scenario == "v5_reopen_reject") {
                        val overview = service.observe().first { it.enrollmentRecoveryFailure != null }
                        assertTrue(requireNotNull(overview.enrollmentRecoveryFailure).contains(reason))
                        assertNull(overview.failure)
                        evidence.put("recoveryDiagnosticVisible", true).put("reopened", true)
                    }
                    evidence.put("reason", reason).put("executionAdmission", "rejected")
                }
                "v5_join_reject" -> {
                    assertNull(available().manifest.applied)
                    requireSnapshotVersionRejection(reason) {
                        service.confirmJoin(requireNotNull(service.join(input.getString("enrollment"))))
                    }
                    val state = available()
                    assertNull(state.manifest.applied)
                    assertNull(state.configuration)
                    assertNotNull(state.manifest.session)
                    assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, state.manifest.phase)
                    assertTrue(sessions.captureSelectedRealmAccess() is RealmAccess.Personal)
                    pendingFile.writeText(JSONObject()
                        .put("sessionId", requireNotNull(state.manifest.session).id)
                        .put("appliedAbsent", true).toString())
                    evidence.put("reason", reason).put("newSnapshotCommitted", false)
                }
                "v5_pending_reopen" -> {
                    val overview = service.observe().first { it.enrollmentRecoveryFailure != null }
                    assertTrue(requireNotNull(overview.enrollmentRecoveryFailure).contains(reason))
                    assertNull(overview.failure)
                    val state = available()
                    assertNull(state.manifest.applied)
                    assertNull(state.configuration)
                    assertNotNull(state.manifest.session)
                    assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, state.manifest.phase)
                    pendingFile.writeText(JSONObject()
                        .put("sessionId", requireNotNull(state.manifest.session).id)
                        .put("appliedAbsent", true).toString())
                    evidence.put("reason", reason).put("newSnapshotCommitted", false)
                        .put("recoveryDiagnosticVisible", true).put("reopened", true)
                }
            }
            val state = available()
            evidence.put("phase", state.manifest.phase.name)
                .put("appliedGeneration", state.manifest.applied?.generation ?: JSONObject.NULL)
                .put("assistantCount", state.configuration?.assistants?.size ?: 0)
                .put("modelCount", state.configuration?.models?.size ?: 0)
                .put("passed", true)
            val output = File(context.getExternalFilesDir(null), "core-compatibility-evidence").apply { mkdirs() }
            File(output, "$scenario.json").writeText(evidence.toString(2))
        }
    }

    private suspend fun requireSnapshotVersionRejection(reason: String, block: suspend () -> Unit) {
        val failure = try {
            block()
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error
        }
        assertNotNull("Old client must reject snapshot v5", failure)
        assertTrue("Expected stable snapshot rejection $reason; got ${failure?.javaClass?.simpleName}",
            generateSequence<Throwable>(failure) { it.cause }.any { it.message?.contains(reason) == true })
    }

    private fun configurationDigest(state: EnterpriseState.Available): String =
        MessageDigest.getInstance("SHA-256").digest(
            PlatformWireCodec.json.encodeToString(requireNotNull(state.configuration)).toByteArray(),
        ).joinToString("") { "%02x".format(it) }

}
