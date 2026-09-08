package net.weero.measix.pilot.service

import net.weero.measix.pilot.data.enterprise.selectPersonalFixture
import net.weero.measix.pilot.data.enterprise.selectEnterpriseFixture

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.enrollFixture
import net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.AppendUserMessage
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationNotFoundException
import net.weero.measix.pilot.service.runtime.ConversationOperationLocks
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationWrite
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationPageAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `existing missing and foreign headers never load a tree or create a draft`() = runTest {
        val appScope = AppScope(StandardTestDispatcher(testScheduler))
        val repository = mockk<ConversationRepository>()
        val locks = ConversationOperationLocks()
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        val coordinator = ConversationCommandCoordinator(registry, repository, gate(), locks)
        val id = Uuid.random()
        val request = ConversationOpenRequest.OpenExisting(id, RealmAccess.Personal)
        try {
            coEvery { repository.getConversationHeader(id) } returns null
            assertFails<ConversationNotFoundException> { coordinator.openForView(request, null) }
            val packet = exampleEnterprisePackage()
            coEvery { repository.getConversationHeader(id) } returns Conversation.ofId(id)
                .copy(scope = packet.identity.scope).toSnapshot().header
            assertFails<IllegalStateException> { coordinator.openForView(request, null) }
            coEvery { repository.getConversationHeader(id) } returns Conversation.ofId(id)
                .copy(parentConversationId = Uuid.random()).toSnapshot().header
            assertFails<IllegalStateException> { coordinator.openForView(request, null) }
            assertNull(registry.findRuntime(id))
            coVerify(exactly = 0) { repository.getConversationSnapshotById(any()) }
            coVerify(exactly = 0) { repository.commit(any()) }
        } finally { appScope.cancel() }
    }

    @Test fun `explicit draft promotes in place and its restored route opens committed messages`() = runTest {
        val appScope = AppScope(StandardTestDispatcher(testScheduler))
        val repository = mockk<ConversationRepository>()
        val locks = ConversationOperationLocks()
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        val coordinator = ConversationCommandCoordinator(registry, repository, gate(), locks)
        val id = Uuid.random()
        val request = ConversationOpenRequest.NewDraft(id, RealmAccess.Personal, ConfigurationReference.random())
        var persisted: Conversation? = null
        coEvery { repository.getConversationHeader(id) } answers { persisted?.toSnapshot()?.header }
        coEvery { repository.getConversationSnapshotById(id) } answers { persisted?.toSnapshot() }
        coEvery { repository.commit(any()) } answers {
            persisted = (firstArg<ConversationWrite>() as ConversationWrite.MaterializeDraft).conversation
            true
        }
        coEvery { repository.existsConversationById(id) } answers { persisted != null }
        try {
            val draft = Conversation.ofId(id, request.assistantId, newConversation = true)
                .updateCurrentMessages(listOf(UIMessage.user("preset")))
            val lease = coordinator.openForView(request, draft)
            val runtime = registry.findRuntime(id)
            coordinator.openForView(request, null).close()
            assertSame(runtime, registry.findRuntime(id))
            assertTrue(registry.isDraft(id))
            assertNull(persisted)
            assertFails<ConversationNotFoundException> {
                coordinator.openForView(ConversationOpenRequest.OpenExisting(id, request.access), null)
            }
            coordinator.executeOrThrow(id, AppendUserMessage(UIMessage.user("first")))
            assertSame(runtime, registry.findRuntime(id))
            assertFalse(registry.isDraft(id))
            lease.close()
            val restoredRegistry = ConversationRuntimeRegistry(appScope, repository, locks)
            val restored = ConversationCommandCoordinator(restoredRegistry, repository, gate(), locks)
            // The route remains NewDraft, but the assistant may have been withdrawn since creation.
            restored.openForView(request, null).close()
            assertEquals(listOf("preset", "first"), restoredRegistry.findRuntime(id)!!.durable.nodes.map { it.currentMessage.toText() })
            coVerify(exactly = 1) { repository.commit(any()) }
            coVerify(exactly = 1) { repository.getConversationSnapshotById(id) }
        } finally { appScope.cancel() }
    }

    @Test fun `application rejects original session after reentry before any header read`() = runTest {
        val sessions = sessions()
        val packet = exampleEnterprisePackage()
        sessions.enrollFixture(packet)
        val original = sessions.captureSelectedRealmAccess()
        val request: ConversationOpenRequest = ConversationOpenRequest.OpenExisting(Uuid.random(), original)
        val restored = JsonInstant.decodeFromString<ConversationOpenRequest>(JsonInstant.encodeToString(request))
        assertEquals(request, restored)
        sessions.finishExit(sessions.beginExit(requireNotNull(sessions.captureExitRequest())))
        sessions.enrollFixture(packet)
        val repository = mockk<ConversationRepository>()
        val coordinator = mockk<ConversationCommandCoordinator>()
        assertFails<EnterpriseConfigurationException> { application(repository, coordinator, sessions).initialize(restored) }
        coVerify(exactly = 0) { repository.getConversationHeader(any()) }
        coVerify(exactly = 0) { coordinator.openForView(any(), any()) }
        assertFails<EnterpriseConfigurationException> {
            application(repository, coordinator, sessions).selectAssistantRequest(original, ConfigurationReference.random(), false)
        }
        coVerify(exactly = 0) { repository.getRecentConversationRecords(any(), any(), any()) }
    }

    @Test fun `revoked page clears values and cannot revive on same-session reselection`() = runTest {
        val sessions = sessions()
        sessions.enrollFixture(exampleEnterprisePackage())
        val access = sessions.captureSelectedRealmAccess()
        var closes = 0
        val lease = ConversationViewLease(Uuid.random(), access, sessions.selectionRevision.value) { closes++ }
        val query = query(sessions)
        val rows = MutableStateFlow("private")
        var subscriptions = 0
        var latest = ""
        backgroundScope.launch {
            query.observeForView(lease, "") { subscriptions++; rows }.collect { latest = it }
        }
        runCurrent()
        assertEquals("private", latest)
        sessions.selectPersonalFixture()
        runCurrent()
        assertEquals("", latest)
        assertEquals(1, closes)
        sessions.selectEnterpriseFixture()
        rows.value = "late"
        runCurrent()
        assertEquals("", latest)
        assertEquals(1, subscriptions)
        assertEquals(1, closes)
    }

    @Test fun `revocation between subscription and publication clears without terminating collector`() = runTest {
        val sessions = sessions()
        sessions.enrollFixture(exampleEnterprisePackage())
        val lease = ConversationViewLease(Uuid.random(), sessions.captureSelectedRealmAccess(), sessions.selectionRevision.value) {}
        val values = mutableListOf<String>()
        val published = kotlinx.coroutines.CompletableDeferred<Unit>()
        backgroundScope.launch {
            query(sessions).observeForView(lease, "") {
                flow { sessions.selectPersonalFixture(); emit("must not publish") }
            }.collect { values.add(it); published.complete(Unit) }
        }
        published.await()
        runCurrent()
        assertTrue(values.isNotEmpty())
        assertTrue(values.all(String::isEmpty))
        assertTrue(lease.closed.value)
    }

    @Test fun `unobserved selection round trip cannot revive a previously acquired page`() = runTest {
        val sessions = sessions()
        sessions.enrollFixture(exampleEnterprisePackage())
        val access = sessions.captureSelectedRealmAccess()
        val lease = ConversationViewLease(Uuid.random(), access, sessions.selectionRevision.value) {}
        sessions.selectPersonalFixture()
        sessions.selectEnterpriseFixture()
        assertEquals(access, sessions.captureSelectedRealmAccess())
        var subscribed = false
        assertEquals("", query(sessions).observeForView(lease, "") {
            subscribed = true
            flow { emit("private") }
        }.first())
        assertFalse(subscribed)
        assertTrue(lease.closed.value)
    }

    @Test fun `last conversation is stored only for a committed root under its authorized scope`() = runTest {
        val sessions = sessions().apply { recover() }
        val repository = mockk<ConversationRepository>()
        val settings = mockk<SettingsStore>()
        val id = Uuid.random()
        val lease = ConversationViewLease(id, RealmAccess.Personal, sessions.selectionRevision.value) {}
        val application = application(repository, mockk(), sessions, settings)
        coEvery { repository.getConversationHeader(id) } returns null
        application.rememberConversation(lease)
        coVerify(exactly = 0) { settings.rememberConversation(any(), any()) }
        coEvery { repository.getConversationHeader(id) } returns Conversation.ofId(id).toSnapshot().header
        coEvery { settings.rememberConversation(ConfigurationScope.Personal, id) } returns Unit
        application.rememberConversation(lease)
        coVerify(exactly = 1) { settings.rememberConversation(ConfigurationScope.Personal, id) }
        lease.close()
        assertFails<IllegalStateException> { application.rememberConversation(lease) }
        coVerify(exactly = 2) { repository.getConversationHeader(id) }
    }

    private fun gate() = ApplicationRecoveryGate().apply { ready() }
    private fun sessions() = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder()))
    private fun query(sessions: EnterpriseSessionController) = ConversationQueryService(
        mockk(), mockk(), mockk(), mockk(), mockk(), sessions, gate(),
    )
    private fun application(
        repository: ConversationRepository,
        coordinator: ConversationCommandCoordinator,
        sessions: EnterpriseSessionController,
        settings: SettingsStore = mockk(),
    ) = ConversationApplicationService(
        settings, repository, mockk(), mockk(), coordinator, gate(), mockk(), mockk(), mockk(),
        mockk(), mockk(), JsonInstant, mockk(), mockk(), sessions, mockk(),
    )

    private suspend inline fun <reified T : Throwable> assertFails(block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.simpleName}") }
        catch (error: Throwable) { if (error !is T) throw error }
    }
}
