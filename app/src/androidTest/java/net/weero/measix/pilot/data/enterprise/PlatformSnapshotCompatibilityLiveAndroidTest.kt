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
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import kotlin.uuid.Uuid
import net.weero.measix.pilot.service.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Opt-in production-path probe. Only the isolated HTTP boundary may inject unsupported versions. */
@RunWith(AndroidJUnit4::class)
class PlatformSnapshotCompatibilityLiveAndroidTest {
    @Test
    fun verifiesRequestedLiveScenario() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = InstrumentationRegistry.getArguments().getString("snapshotCompatibilityInput")
        assumeTrue(path != null)
        val inputFile = File(requireNotNull(path)).canonicalFile
        require(inputFile.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        val input = JSONObject(inputFile.readText())
        val scenario = input.getString("scenario")
        require(scenario in setOf("join", "sync", "reopen", "reject", "recover"))
        val baselineFile = File(context.cacheDir, "snapshot-compatibility-baseline.json")
        val prior = baselineFile.takeIf(File::exists)?.let { JSONObject(it.readText()) }
        var historyFixtureId = prior?.optString("historyFixtureId")?.takeIf { it.isNotEmpty() && it != "null" }?.let(Uuid::parse)
        val evidence = JSONObject().put("scenario", scenario)
        withTimeout(120_000) {
            val koin = GlobalContext.get()
            koin.get<ApplicationRecoveryGate>().awaitReady()
            val sessions = koin.get<EnterpriseSessionController>()
            val service = koin.get<EnterpriseApplicationService>()
            val synchronization = koin.get<EnterpriseSynchronizationService>()
            val query = koin.get<ConversationQueryService>()
            val commands = koin.get<ConversationCommandCoordinator>()
            suspend fun available() = sessions.state.first { it !is EnterpriseState.Loading } as EnterpriseState.Available
            suspend fun access(): RealmAccess.Enterprise {
                val session = requireNotNull(available().manifest.session)
                return RealmAccess.Enterprise(session.identity.scope, session.id)
            }
            suspend fun enterEnterprise(target: RealmAccess.Enterprise) {
                val presentation = sessions.readPresentation()
                assertTrue(presentation.canEnterEnterprise)
                val selection = requireNotNull(presentation.selection)
                if (selection.access != target) service.switchRealm(RealmSwitchRequest(selection, target))
                assertEquals(target, sessions.readPresentation().selection?.access)
            }
            suspend fun roundTrip(target: RealmAccess.Enterprise) {
                enterEnterprise(target)
                service.switchRealm(RealmSwitchRequest(requireNotNull(sessions.readPresentation().selection), RealmAccess.Personal))
                assertEquals(RealmAccess.Personal, sessions.readPresentation().selection?.access)
                enterEnterprise(target)
                evidence.put("enterprisePersonalRoundTrip", true)
            }
            suspend fun summary(): JSONObject {
                val state = available()
                val target = access()
                val applied = state.manifest.applied
                val candidate = sessions.platformConfiguration(target).candidate
                val execution = candidate?.execution as? EnterpriseExecution.Platform
                val summaries = state.configuration?.assistants.orEmpty().flatMap { assistant ->
                    query.recentConversations(target, ConfigurationReference.Enterprise(target.scope.authority, assistant.id), Int.MAX_VALUE)
                }.distinctBy { it.id }.sortedBy { it.id.toString() }
                val fixtureNodes = historyFixtureId?.let { id ->
                    sessions.withRealmAccess(target) {
                        commands.withRootHeaders(target.scope, listOf(id)) {
                            val aggregate = requireNotNull(query.aggregateSnapshot(id))
                            assertEquals(target.scope, aggregate.header.scope)
                            assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), aggregate.nodes.map { it.role })
                            aggregate.nodes
                        }
                    }
                }
                return JSONObject().put("sessionId", target.sessionId)
                    .put("historyFixtureId", historyFixtureId?.toString() ?: JSONObject.NULL)
                    .put("historyFixtureNodeCount", fixtureNodes?.size ?: 0)
                    .put("historyFixtureDigest", fixtureNodes?.let { digest(PlatformWireCodec.json.encodeToString(it)) } ?: JSONObject.NULL)
                    .put("principalDigest", digest(target.scope.toString()))
                    .put("generation", applied?.generation ?: JSONObject.NULL)
                    .put("revision", applied?.revision ?: JSONObject.NULL)
                    .put("configurationHash", applied?.configurationHash ?: JSONObject.NULL)
                    .put("executionHash", applied?.executionHash ?: JSONObject.NULL)
                    .put("configurationDigest", state.configuration?.let { digest(PlatformWireCodec.json.encodeToString(it)) } ?: JSONObject.NULL)
                    .put("schema", execution?.snapshotSchemaVersion ?: JSONObject.NULL)
                    .put("releaseId", execution?.releaseId ?: JSONObject.NULL)
                    .put("snapshotHash", execution?.snapshotHash ?: JSONObject.NULL)
                    .put("historyCount", query.count())
                    .put("managedAssistantHistoryCount", summaries.size)
                    .put("historySummaryDigest", digest(summaries.joinToString("\n")))
            }
            fun assertRetained(before: JSONObject, after: JSONObject) {
                for (key in listOf("sessionId", "principalDigest", "generation", "revision", "configurationHash", "executionHash",
                    "configurationDigest", "schema", "releaseId", "snapshotHash", "historyCount", "managedAssistantHistoryCount", "historySummaryDigest",
                    "historyFixtureId", "historyFixtureNodeCount", "historyFixtureDigest")) {
                    assertEquals("Preserve $key", before.get(key).toString(), after.get(key).toString())
                }
                evidence.put("bindingPreserved", true).put("appliedPreserved", true).put("historySummaryPreserved", true)
            }
            suspend fun assertRejected(block: suspend () -> Unit) {
                val failure = try { block(); null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { error }
                assertNotNull("Unsupported snapshot must remain rejected until manual synchronization", failure)
                assertTrue("Execution must fail before starting an operation", failure is EnterprisePreparationException)
                val cause = requireNotNull(failure?.cause) as EnterpriseConfigurationException
                assertEquals("platform_runtime_synchronization_required", cause.reason)
                val retained = requireNotNull(synchronization.status.value?.failure)
                assertTrue(cause.message.orEmpty().contains(retained.diagnostic))
                assertTrue(retained.diagnostic.contains("enterprise_configuration_version_unsupported"))
                assertTrue(retained.diagnostic.contains("snapshot schemas"))
                assertTrue(retained.diagnostic.contains(input.getLong("rejectedSchema").toString()))
            }

            if (scenario == "reject") {
                require(input.getLong("rejectedSchema") in setOf(3L, 6L, 7L))
                if (input.has("enrollment")) {
                    assertNull(available().manifest.applied)
                    assertEquals(EnterpriseSynchronizationCommandResult.FAILURE_PRESENTED,
                        service.confirmJoin(requireNotNull(service.join(input.getString("enrollment")))))
                    assertNull(available().manifest.applied)
                    assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, available().manifest.phase)
                }
                val target = access()
                roundTrip(target)
                assertEquals(EnterpriseSynchronizationCommandResult.FAILURE_PRESENTED, service.synchronize(target))
                val snapshot = summary()
                if (input.has("enrollment")) baselineFile.writeText(snapshot.toString())
                else assertRetained(requireNotNull(prior) { "Successful or pending baseline required" }, snapshot)
                assertRejected { synchronization.prepareExecution(target) }
                assertRetained(snapshot, summary())
                val status = synchronization.status.first { it?.access == target && !it.syncing && it.failure != null }
                val issue = requireNotNull(requireNotNull(status).failure)
                val expected = if (input.getLong("rejectedSchema") > 5L) EnterpriseSynchronizationIssue.UPDATE_APP
                    else EnterpriseSynchronizationIssue.UPDATE_PLATFORM
                assertEquals(expected, issue.issue)
                assertTrue(issue.diagnostic.contains("enterprise_configuration_version_unsupported"))
                val overview = service.observe().first { it.synchronization?.failure != null }
                assertEquals(target, overview.access)
                assertTrue(overview.canEnterEnterprise)
                assertNull(overview.failure)
                evidence.put("issue", issue.issue.name).put("reason", "enterprise_configuration_version_unsupported")
                    .put("rejectedSchema", input.getLong("rejectedSchema")).put("executionAdmission", "rejected")
            } else {
                require(input.getLong("expectedSchema") in setOf(4L, 5L))
                if (scenario == "join") {
                    assertNull(available().manifest.applied)
                    assertEquals(EnterpriseSynchronizationCommandResult.COMPLETED,
                        service.confirmJoin(requireNotNull(service.join(input.getString("enrollment")))))
                } else {
                    val target = access()
                    assertEquals(requireNotNull(prior).getString("sessionId"), target.sessionId)
                    if (scenario == "reopen") {
                        assertRetained(prior, summary())
                        evidence.put("restoredBeforeSynchronization", true)
                    } else {
                        assertEquals(EnterpriseSynchronizationCommandResult.COMPLETED, service.synchronize(target))
                    }
                }
                val target = access()
                roundTrip(target)
                val state = available()
                assertEquals(EnterpriseSessionPhase.READY, state.manifest.phase)
                val candidate = requireNotNull(sessions.platformConfiguration(target).candidate)
                val execution = candidate.execution as EnterpriseExecution.Platform
                assertEquals(input.getLong("expectedSchema"), execution.snapshotSchemaVersion)
                assertEquals(input.getLong("expectedGeneration"), candidate.configuration.generation)
                if (input.has("expectedReleaseId")) assertEquals(input.getString("expectedReleaseId"), execution.releaseId)
                if (input.has("expectedSnapshotHash")) assertEquals(input.getString("expectedSnapshotHash"), execution.snapshotHash)
                val isV5 = input.getLong("expectedSchema") == 5L
                assertTrue(candidate.configuration.starters.all { (it.openingSnapshot != null) == isV5 })
                val selection = requireNotNull(sessions.readPresentation().selection)
                val catalog = koin.get<ConfigurationQueryService>().observeEnterpriseStarters(selection).first() as EnterpriseStarterReadState.Available
                if (input.has("expectedStarterCount")) assertEquals(input.getInt("expectedStarterCount"), catalog.value.starters.size)
                catalog.value.starters.forEach { starter ->
                    val draft = koin.get<ConversationApplicationService>().newStarterDraftRequest(starter.target)
                    assertEquals(starter.prompt, draft.text)
                    assertEquals(target, draft.request.access)
                    assertEquals(isV5, draft.request.starter != null)
                    draft.request.starter?.let {
                        assertEquals(execution.releaseId, it.releaseId)
                        assertEquals(execution.snapshotHash, it.snapshotHash)
                        assertEquals(candidate.configuration.generation, it.generation)
                    }
                }
                assertEquals(state.manifest.applied, synchronization.prepareExecution(target))
                val status = synchronization.status.value
                assertTrue(status == null || (status.access == target && !status.syncing && status.failure == null))
                if (scenario == "join" && input.optBoolean("seedHistory", true)) {
                    assertNull(historyFixtureId)
                    val assistant = candidate.configuration.assistants.first { it.enabled }
                    val fixture = Conversation(
                        assistantId = ConfigurationReference.Enterprise(target.scope.authority, assistant.id),
                        title = "Snapshot compatibility history fixture",
                        messageNodes = listOf(
                            MessageNode.of(UIMessage.user("Compatibility fixture: retain this enterprise question.")),
                            MessageNode.of(UIMessage.assistant("Compatibility fixture: preserve this saved answer without a supplier call.")),
                        ),
                        scope = target.scope,
                    )
                    sessions.withRealmAccess(target) { commands.create(fixture) }
                    historyFixtureId = fixture.id
                }
                val snapshot = summary()
                if (scenario == "reopen") assertRetained(requireNotNull(prior), snapshot)
                if (scenario == "recover") {
                    assertEquals(requireNotNull(prior).getString("sessionId"), snapshot.getString("sessionId"))
                    assertEquals(prior.getString("principalDigest"), snapshot.getString("principalDigest"))
                    assertEquals(prior.getInt("historyCount"), snapshot.getInt("historyCount"))
                    for (key in listOf("historyFixtureId", "historyFixtureNodeCount", "historyFixtureDigest")) {
                        assertEquals(key, prior.get(key).toString(), snapshot.get(key).toString())
                    }
                    evidence.put("sameSessionRecovered", true).put("enrollmentRepeated", false)
                }
                baselineFile.writeText(snapshot.toString())
                evidence.put("executionAdmission", "accepted").put("starterCount", catalog.value.starters.size)
                    .put("starterPrefillVerified", true).put("starterOpeningVerified", true)
            }
            val final = summary()
            for (key in listOf("schema", "releaseId", "snapshotHash", "generation", "historyCount", "managedAssistantHistoryCount", "historySummaryDigest", "historyFixtureNodeCount", "historyFixtureDigest")) {
                evidence.put(key, final.get(key))
            }
            evidence.put("phase", available().manifest.phase.name).put("passed", true)
            val output = File(context.getExternalFilesDir(null), "snapshot-compatibility-evidence").apply { mkdirs() }
            File(output, "$scenario.json").writeText(evidence.toString(2))
        }
        Unit
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
