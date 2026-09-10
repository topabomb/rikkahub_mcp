package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.data.db.dao.FavoriteDAO
import net.weero.measix.pilot.data.db.entity.FavoriteEntity
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.enrollFixture
import net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage
import net.weero.measix.pilot.data.enterprise.selectEnterpriseFixture
import net.weero.measix.pilot.data.enterprise.selectPersonalFixture
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.FavoriteRepository
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationOperationLocks
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationWrite
import net.weero.measix.pilot.service.runtime.DeleteMessage
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
class FavoriteServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `enterprise favorites derive scope and preview from durable node and directory follows selected realm`() = runTest {
        fixture { f ->
            f.favorites.toggleNode(f.page.commandTarget, f.node.id)
            val row = f.rows.value.single()
            assertEquals(f.enterpriseScope, row.scope)
            assertTrue(row.metaJson!!.contains("durable enterprise text"))
            val item = f.favorites.observeNodeFavorites().first { it.isNotEmpty() }.single()
            assertEquals(f.node.id, item.nodeId)
            f.sessions.selectPersonalFixture()
            assertTrue(f.favorites.observeNodeFavorites().drop(1).first().isEmpty())
            rejects<EnterpriseConfigurationException> { f.favorites.removeForUndo(item) }
            rejects<EnterpriseConfigurationException> { f.favorites.openRequest(item) }
            assertEquals(listOf(row), f.rows.value)
            f.sessions.selectEnterpriseFixture()
            rejects<EnterpriseConfigurationException> { f.favorites.toggleNode(f.page.commandTarget, f.node.id) }
            rejects<EnterpriseConfigurationException> { f.favorites.removeForUndo(item) }
            rejects<EnterpriseConfigurationException> { f.favorites.openRequest(item) }
        }
    }

    @Test fun `foreign node and closed page cannot create a favorite`() = runTest {
        fixture { f ->
            rejects<IllegalStateException> { f.favorites.toggleNode(f.page.commandTarget, Uuid.random()) }
            f.page.close()
            rejects<IllegalStateException> { f.favorites.toggleNode(f.page.commandTarget, f.node.id) }
            assertTrue(f.rows.value.isEmpty())
            coVerify(exactly = 0) { f.dao.upsert(any()) }
        }
    }

    @Test fun `undo revalidates original selection and deleted node and does not overwrite a later favorite`() = runTest {
        fixture { f ->
            f.favorites.toggleNode(f.page.commandTarget, f.node.id)
            val item = f.favorites.observeNodeFavorites().first { it.isNotEmpty() }.single()
            val first = f.favorites.removeForUndo(item)!!
            f.favorites.toggleNode(f.page.commandTarget, f.node.id)
            val later = f.rows.value.single()
            f.favorites.restore(first)
            assertEquals(later, f.rows.value.single())
            rejects<IllegalStateException> { f.favorites.restore(first) }
            val currentItem = f.favorites.observeNodeFavorites().first { it.isNotEmpty() }.single()
            val removed = f.favorites.removeForUndo(currentItem)!!
            f.coordinator.executeOrThrow(f.id, DeleteMessage(f.node.currentMessage.id))
            rejects<IllegalStateException> { f.favorites.restore(removed) }
            assertTrue(f.rows.value.isEmpty())
        }
        fixture { f ->
            f.favorites.toggleNode(f.page.commandTarget, f.node.id)
            val item = f.favorites.observeNodeFavorites().first { it.isNotEmpty() }.single()
            val token = f.favorites.removeForUndo(item)!!
            f.sessions.selectPersonalFixture()
            f.sessions.selectEnterpriseFixture()
            rejects<EnterpriseConfigurationException> { f.favorites.restore(token) }
            assertTrue(f.rows.value.isEmpty())
        }
    }

    @Test fun `failed undo can retry without losing its original capability`() = runTest {
        fixture { f ->
            f.favorites.toggleNode(f.page.commandTarget, f.node.id)
            val item = f.favorites.observeNodeFavorites().first { it.isNotEmpty() }.single()
            val token = f.favorites.removeForUndo(item)!!
            f.beforeWrite = { throw java.io.IOException("disk write failed") }
            rejects<java.io.IOException> { f.favorites.restore(token) }
            assertFalse(token.consumed)
            f.beforeWrite = {}
            f.favorites.restore(token)
            assertTrue(token.consumed)
            assertEquals(f.enterpriseScope, f.rows.value.single().scope)
        }
    }

    @Test fun `concurrent toggles and node deletion serialize on existing conversation lock`() = runTest {
        fixture { f ->
            (0 until 20).map { async { f.favorites.toggleNode(f.page.commandTarget, f.node.id) } }.awaitAll()
            assertTrue(f.rows.value.isEmpty())
            val writing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeWrite = { writing.complete(Unit); release.await() }
            val toggle = launch { f.favorites.toggleNode(f.page.commandTarget, f.node.id) }
            writing.await()
            val deletion = launch { f.coordinator.executeOrThrow(f.id, DeleteMessage(f.node.currentMessage.id)) }
            runCurrent()
            assertFalse(deletion.isCompleted)
            release.complete(Unit)
            toggle.join()
            deletion.join()
            assertTrue(f.rows.value.isEmpty())
            rejects<IllegalStateException> { f.favorites.toggleNode(f.page.commandTarget, f.node.id) }
        }
    }

    @Test fun `expiry during favorite lookup rejects the actual write`() = runTest {
        fixture { f ->
            f.beforeRead = { f.now = f.expiresAt }
            rejects<EnterpriseConfigurationException> { f.favorites.toggleNode(f.page.commandTarget, f.node.id) }
            assertTrue(f.rows.value.isEmpty())
            coVerify(exactly = 0) { f.dao.upsert(any()) }
        }
    }

    @Test fun `cancelled favorite write does not publish a row or swallow cancellation`() = runTest {
        fixture { f ->
            val writing = CompletableDeferred<Unit>()
            f.beforeWrite = { writing.complete(Unit); CompletableDeferred<Unit>().await() }
            val toggle = launch { f.favorites.toggleNode(f.page.commandTarget, f.node.id) }
            writing.await()
            toggle.cancel()
            toggle.join()
            assertTrue(toggle.isCancelled)
            assertTrue(f.rows.value.isEmpty())
        }
    }

    private suspend fun TestScope.fixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(this)
        try { fixture.initialize(); block(fixture) } finally { fixture.scope.cancel() }
    }

    private inner class Fixture(test: TestScope) {
        val scope = AppScope(StandardTestDispatcher(test.testScheduler))
        val gate = ApplicationRecoveryGate().apply { ready() }
        var now = 1000L
        var expiresAt = 0L
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder())) { now }
        val enterpriseScope = exampleEnterprisePackage().identity.scope
        val dao = mockk<FavoriteDAO>()
        val rows = MutableStateFlow<List<FavoriteEntity>>(emptyList())
        var beforeWrite: suspend () -> Unit = {}
        var beforeRead: suspend () -> Unit = {}
        val repository = mockk<ConversationRepository>()
        val locks = ConversationOperationLocks()
        val registry = ConversationRuntimeRegistry(scope, repository, locks)
        val coordinator = ConversationCommandCoordinator(registry, repository, gate, locks)
        val conversation = Conversation.ofId(Uuid.random(), DEFAULT_ASSISTANT_ID)
            .copy(scope = enterpriseScope, title = "Enterprise")
            .updateCurrentMessages(listOf(UIMessage.user("durable enterprise text")))
        val id = conversation.id
        val node = conversation.toSnapshot().nodes.single()
        val application = ConversationApplicationService(mockk(), repository, mockk(), registry, coordinator, gate,
            mockk(), mockk(), mockk(), mockk(), mockk(), JsonInstant, mockk(), ConversationTitleCoordinator(), sessions, mockk())
        val favorites = FavoriteService(FavoriteRepository(dao), gate, sessions, application)
        lateinit var page: ConversationViewLease

        init {
            every { dao.listByType(any(), any()) } answers {
                val selected = firstArg<ConfigurationScope>()
                rows.map { all -> all.filter { it.scope == selected } }
            }
            coEvery { dao.getByRefKey(any(), any()) } coAnswers {
                beforeRead()
                rows.value.singleOrNull { it.scope == firstArg<ConfigurationScope>() && it.refKey == secondArg<String>() }
            }
            coEvery { dao.upsert(any()) } coAnswers {
                val row = firstArg<FavoriteEntity>()
                beforeWrite()
                rows.value = rows.value.filterNot { it.refKey == row.refKey } + row
            }
            coEvery { dao.deleteByRefKey(any(), any()) } answers {
                val old = rows.value
                rows.value = old.filterNot { it.scope == firstArg<ConfigurationScope>() && it.refKey == secondArg<String>() }
                old.size - rows.value.size
            }
            coEvery { repository.getConversationHeader(id) } returns conversation.toSnapshot().header
            coEvery { repository.getConversationSnapshotById(id) } returns conversation.toSnapshot()
            coEvery { repository.commit(any()) } answers {
                val mutation = (firstArg<ConversationWrite>() as ConversationWrite.Mutate).mutation
                rows.value = rows.value.filterNot { row -> mutation.deletedNodeIds.any { row.refKey == "node:$id:$it" } }
                true
            }
        }

        suspend fun initialize() {
            expiresAt = sessions.enrollFixture(exampleEnterprisePackage()).manifest.session!!.expiresAtMillis
            registry.registerSnapshot(conversation.toSnapshot())
            val selection = requireNotNull(sessions.observeSelectedRealmSelection().first())
            page = ConversationViewLease(id, selection.access, selection.revision) {}
        }
    }

    private suspend inline fun <reified T : Throwable> rejects(block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.simpleName}") }
        catch (error: Throwable) { if (error !is T) throw error }
    }
}
