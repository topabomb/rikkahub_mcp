package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.db.dao.ConversationDAO
import net.weero.measix.pilot.data.db.dao.FolderDAO
import net.weero.measix.pilot.data.db.entity.FolderEntity
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.FolderRepository
import net.weero.measix.pilot.service.runtime.*
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
class ConversationFolderAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `create rename move and delete preserve enterprise ownership through actual command and repository paths`() = runTest {
        fixture { f ->
            val access = f.directory().access
            f.application.createFolder(access, "  work  ")
            val folder = f.folders.value.single()
            assertEquals(access.selection.access.scope, folder.scope)
            assertEquals(DEFAULT_ASSISTANT_ID.toString(), folder.assistantId)
            assertEquals("work", folder.name)
            val id = Uuid.parse(folder.id)
            f.application.renameFolder(access, id, "updated")
            val conversation = f.addConversation(access.selection.access.scope)
            f.application.moveToFolder(access, f.summary(conversation, access), id)
            assertEquals(id, f.headers.getValue(conversation).folderId)
            f.application.deleteFolder(access, id)
            assertNull(f.headers.getValue(conversation).folderId)
            assertTrue(f.folders.value.isEmpty())
            coVerify(exactly = 0) { f.repository.getConversationSnapshotById(any()) }
        }
    }

    @Test fun `old directory cannot act after an unobserved round trip or same identity re-enrollment`() = runTest {
        fixture { f ->
            val old = f.directory().access
            f.application.createFolder(old, "original")
            val id = Uuid.parse(f.folders.value.single().id)
            val conversation = f.addConversation(old.selection.access.scope)
            f.sessions.switchToPersonal()
            f.sessions.switchToEnterprise()
            suspend fun rejectsAll() {
                rejects<EnterpriseConfigurationException> { f.application.createFolder(old, "new") }
                rejects<EnterpriseConfigurationException> { f.application.renameFolder(old, id, "changed") }
                rejects<EnterpriseConfigurationException> { f.application.moveToFolder(old, f.summary(conversation, old), id) }
                rejects<EnterpriseConfigurationException> { f.application.deleteFolder(old, id) }
            }
            rejectsAll()
            f.sessions.finishExit(requireNotNull(f.sessions.beginExit()))
            f.sessions.enrollFixture(exampleEnterprisePackage())
            rejectsAll()
            assertEquals("original", f.folders.value.single().name)
            assertNull(f.headers.getValue(conversation).folderId)
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `folder and conversation targets reject foreign principals assistants and child roots before writes`() = runTest {
        fixture { f ->
            val access = f.directory().access
            f.application.createFolder(access, "owned")
            val id = Uuid.parse(f.folders.value.single().id)
            val original = f.folders.value.single()
            for (foreign in listOf(
                original.copy(scope = ConfigurationScope.Personal),
                original.copy(assistantId = ConfigurationReference.random().toString()),
            )) {
                f.folders.value = listOf(foreign)
                rejects<IllegalStateException> { f.application.renameFolder(access, id, "changed") }
                rejects<IllegalStateException> { f.application.deleteFolder(access, id) }
                rejects<IllegalStateException> { f.application.moveToFolder(access, f.summary(f.addConversation(access.selection.access.scope), access), id) }
            }
            f.folders.value = listOf(original)
            val conversation = f.addConversation(access.selection.access.scope)
            val header = f.headers.getValue(conversation)
            for (foreign in listOf(
                header.copy(scope = ConfigurationScope.Personal),
                header.copy(assistantId = ConfigurationReference.random()),
                header.copy(parentConversationId = Uuid.random()),
            )) {
                f.headers[conversation] = foreign
                rejects<IllegalStateException> { f.application.moveToFolder(access, f.summary(conversation, access), id) }
            }
            coVerify(exactly = 0) { f.repository.commit(any()) }
            coVerify(exactly = 0) { f.repository.getConversationSnapshotById(any()) }
        }
    }

    @Test fun `failed detach keeps folder and retry processes remaining members without touching another realm`() = runTest {
        fixture { f ->
            val access = f.directory().access
            f.application.createFolder(access, "retry")
            val id = Uuid.parse(f.folders.value.single().id)
            val first = f.addConversation(access.selection.access.scope, id)
            val second = f.addConversation(access.selection.access.scope, id)
            val personal = f.addConversation(ConfigurationScope.Personal)
            f.failCommit = second
            rejects<IOException> { f.application.deleteFolder(access, id) }
            assertNull(f.headers.getValue(first).folderId)
            assertEquals(id, f.headers.getValue(second).folderId)
            assertEquals(1, f.folders.value.size)
            f.failCommit = null
            f.application.deleteFolder(access, id)
            assertNull(f.headers.getValue(second).folderId)
            assertTrue(f.folders.value.isEmpty())
            coVerify(exactly = 0) { f.repository.getConversationHeader(personal) }
        }
    }

    @Test fun `authorization covers the repository commit and a queued old command is rejected after switch`() = runTest {
        fixture { f ->
            val access = f.directory().access
            f.application.createFolder(access, "locked")
            val id = Uuid.parse(f.folders.value.single().id)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { f.folderDao.rename(id.toString(), any()) } coAnswers {
                entered.complete(Unit)
                release.await()
            }
            val rename = launch { f.application.renameFolder(access, id, "held") }
            entered.await()
            var switched = false
            val switch = launch { f.sessions.switchToPersonal(); switched = true }
            runCurrent()
            assertFalse(switched)
            release.complete(Unit)
            rename.join()
            switch.join()
            assertTrue(switched)
            rejects<EnterpriseConfigurationException> { f.application.deleteFolder(access, id) }
        }
    }

    @Test fun `directory reissues authority after conflated round trip and does not revive old emissions`() = runTest {
        fixture { f ->
            var latest: ConversationFolderDirectory? = null
            val job = backgroundScope.launch { f.query.foldersOfAssistant(DEFAULT_ASSISTANT_ID).collect { latest = it } }
            runCurrent()
            val original = requireNotNull(latest).access
            f.sessions.switchToPersonal()
            f.sessions.switchToEnterprise()
            runCurrent()
            val newer = requireNotNull(latest).access
            assertNotEquals(original, newer)
            rejects<EnterpriseConfigurationException> { f.application.createFolder(original, "stale") }
            f.application.createFolder(newer, "current")
            runCurrent()
            assertEquals("current", requireNotNull(latest).folders.single().name)
            job.cancel()
        }
    }

    @Test fun `new directory cannot authorize an old conversation row`() = runTest {
        fixture { f ->
            val original = f.directory().access
            val id = f.addConversation(original.selection.access.scope)
            val row = f.summary(id, original)
            f.sessions.switchToPersonal()
            f.sessions.switchToEnterprise()
            val newer = f.directory().access
            rejects<IllegalStateException> { f.application.moveToFolder(newer, row, null) }
            coVerify(exactly = 0) { f.repository.getConversationHeader(any()) }
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `active member prevents every detach even when preceded by an idle member`() = runTest {
        fixture { f ->
            val access = f.directory().access
            f.application.createFolder(access, "busy")
            val id = Uuid.parse(f.folders.value.single().id)
            val idle = f.addConversation(access.selection.access.scope, id)
            val busy = f.addConversation(access.selection.access.scope, id)
            val conversation = Conversation.ofId(busy, DEFAULT_ASSISTANT_ID).copy(scope = access.selection.access.scope, folderId = id)
            val runtime = f.registry.registerSnapshot(conversation.toSnapshot())
            val worker = kotlinx.coroutines.Job()
            try {
                runtime.installTurnWorker(Uuid.random(), worker)
                rejects<ConversationFolderBusyException> { f.application.deleteFolder(access, id) }
                assertEquals(id, f.headers.getValue(idle).folderId)
                assertEquals(1, f.folders.value.size)
                coVerify(exactly = 0) { f.repository.commit(any()) }
            } finally { worker.cancel() }
        }
    }

    @Test fun `policy revocation blocks creating a folder for the previously available user assistant`() = runTest {
        fixture { f ->
            val original = f.directory().access
            val packet = exampleEnterprisePackage()
            f.sessions.synchronize(original.selection.access as RealmAccess.Enterprise, packet.copy(configuration = packet.configuration.copy(
                generation = packet.configuration.generation + 1,
                policy = packet.configuration.policy.copy(allowLocalAssistants = false),
            )))
            rejects<IllegalStateException> { f.application.createFolder(original, "not allowed") }
            assertTrue(f.folders.value.isEmpty())
        }
    }

    private suspend fun TestScope.fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(this)
        try {
            f.sessions.enrollFixture(exampleEnterprisePackage())
            block(f)
        } finally { f.appScope.cancel() }
    }

    private inner class Fixture(test: TestScope) {
        val appScope = AppScope(StandardTestDispatcher(test.testScheduler))
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder()))
        val folderDao = mockk<FolderDAO>()
        val conversationDao = mockk<ConversationDAO>()
        val repository = mockk<ConversationRepository>()
        val settings = mockk<SettingsStore>()
        val folders = MutableStateFlow<List<FolderEntity>>(emptyList())
        val headers = linkedMapOf<Uuid, ConversationHeader>()
        var failCommit: Uuid? = null
        val folderRepository = FolderRepository(folderDao, conversationDao)
        val locks = ConversationOperationLocks()
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        val gate = ApplicationRecoveryGate().apply { ready() }
        val coordinator = ConversationCommandCoordinator(registry, repository, gate, locks)
        val application = ConversationApplicationService(
            settings, repository, folderRepository, registry, coordinator, gate, mockk(), mockk(), mockk(),
            mockk(), mockk(), JsonInstant, mockk(), mockk(), sessions,
        )
        val query = ConversationQueryService(repository, registry, folderRepository, mockk(), mockk(), sessions, gate)

        init {
            every { folderDao.getFoldersOfAssistant(any(), any()) } answers {
                val scope = firstArg<ConfigurationScope>()
                val assistant = secondArg<String>()
                folders.map { rows -> rows.filter { it.scope == scope && it.assistantId == assistant } }
            }
            coEvery { folderDao.getFolderById(any()) } answers { folders.value.find { it.id == firstArg<String>() } }
            coEvery { folderDao.insert(any()) } answers { folders.value += firstArg<FolderEntity>() }
            coEvery { folderDao.rename(any(), any()) } answers {
                val id = firstArg<String>()
                val name = secondArg<String>()
                folders.value = folders.value.map { if (it.id == id) it.copy(name = name) else it }
            }
            coEvery { folderDao.deleteById(any()) } answers { folders.value = folders.value.filterNot { it.id == firstArg<String>() } }
            coEvery { conversationDao.getIdsByFolder(any()) } answers {
                val id = firstArg<String>()
                headers.values.filter { it.folderId?.toString() == id }.map { it.id.toString() }
            }
            coEvery { repository.getConversationHeader(any()) } answers { headers[firstArg()] }
            coEvery { repository.commit(any()) } answers {
                val mutation = (firstArg<ConversationWrite>() as ConversationWrite.Mutate).mutation
                if (mutation.conversationId == failCommit) throw IOException("injected detach failure")
                val old = headers.getValue(mutation.conversationId)
                val folderId = when (val value = requireNotNull(mutation.headerPatch).folderId) {
                    OptionalFolderId.Clear -> null
                    OptionalFolderId.Keep -> old.folderId
                    is OptionalFolderId.SetTo -> value.id
                }
                headers[old.id] = old.copy(folderId = folderId)
                true
            }
            coEvery { settings.withResolvedConfiguration<Unit>(any(), any(), any()) } coAnswers {
                val configuration = ConfigurationResolver.resolve(UserSettingsDocument.empty(), firstArg(), secondArg())
                thirdArg<suspend (ResolvedConfiguration) -> Unit>()(configuration)
            }
        }

        fun summary(id: Uuid, access: ConversationFolderAccess): ConversationSummary {
            val header = headers.getValue(id)
            return ConversationSummary(id, header.assistantId, header.title, header.folderId, header.isPinned,
                java.time.Instant.ofEpochMilli(header.createAt), java.time.Instant.ofEpochMilli(header.updateAt), access.selection)
        }

        suspend fun directory(): ConversationFolderDirectory = query.foldersOfAssistant(DEFAULT_ASSISTANT_ID).first { it != null }!!

        fun addConversation(scope: ConfigurationScope, folderId: Uuid? = null): Uuid {
            val conversation = Conversation.ofId(Uuid.random(), DEFAULT_ASSISTANT_ID).copy(scope = scope, folderId = folderId)
            headers[conversation.id] = conversation.toSnapshot().header
            return conversation.id
        }
    }

    private suspend inline fun <reified T : Throwable> rejects(block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.simpleName}") }
        catch (error: Throwable) { if (error !is T) throw error }
    }
}
