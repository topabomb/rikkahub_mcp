package net.weero.measix.pilot.data.ai.mcp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpCatalogPublicationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val definition = McpServerConfig.StreamableHTTPServer(url = "https://catalog.example/mcp")

    @Test
    fun `initial read finishes before restore and discovery can replace the directory`() = runBlocking {
        withStore(pauseInitialRead = true) { store, disk ->
            withTimeout(5_000) { disk.readCaptured.await() }
            val restored = candidate("restored").initialSnapshot()
            val restore = async(start = CoroutineStart.UNDISPATCHED) {
                store.restoreCatalogs(listOf(restored), listOf(definition))
            }
            val discovery = async(start = CoroutineStart.UNDISPATCHED) { store.commitCandidate(candidate("discovered")) }
            assertFalse(restore.isCompleted)
            assertFalse(discovery.isCompleted)
            assertEquals(0, disk.writes.get())
            disk.releaseRead.complete(Unit)
            withTimeout(5_000) { restore.await(); discovery.await() }
            // Observe again after another complete replacement; no delayed disk subscription may roll it back.
            store.restoreCatalogs(listOf(restored), listOf(definition))
            val final = store.commitCandidate(candidate("final")) as McpCatalogCommitResult.Committed
            assertEquals(final.snapshot, store.catalogs.value[definition.id])
            assertEquals(listOf(final.snapshot), store.snapshotForBackup(listOf(definition)))
        }
    }

    @Test
    fun `migration failure rejects backups and every mutation`() = runBlocking {
        withStore(failMigration = true) { store, disk ->
            val snapshot = candidate("unused").initialSnapshot()
            val operations: List<suspend () -> Any?> = listOf(
                { store.snapshotForBackup(listOf(definition)) },
                { store.restoreCatalogs(listOf(snapshot), listOf(definition)) },
                { store.commitCandidate(candidate("unused")) },
                { store.remove(definition.id) },
                { store.rollbackCommitted(snapshot, null, 1) },
            )
            operations.forEach { operation ->
                try { withTimeout(5_000) { operation() }; fail("Failed initialization was accepted") }
                catch (_: IOException) { }
            }
            assertEquals(0, disk.writes.get())
            assertTrue(disk.delegate.data.first().asMap().isEmpty())
        }
    }

    @Test
    fun `explicit full restore repairs failed initial read without pretending backup succeeded`() = runBlocking {
        val valid = candidate("restored").initialSnapshot()
        val corruptInputs = listOf(
            "{", JsonInstant.encodeToString(listOf(valid.copy(catalogDigest = "broken"))),
            JsonInstant.encodeToString(listOf(valid, valid)),
        )
        for (encoded in corruptInputs + null) {
            withStore(failRead = encoded == null, initialCatalog = encoded) { store, _ ->
                try { store.snapshotForBackup(listOf(definition)); fail("Unreadable directory became an empty backup") }
                catch (_: IOException) { }
                catch (_: IllegalArgumentException) { }
                store.restoreCatalogs(listOf(valid), listOf(definition))
                assertEquals(listOf(valid), store.snapshotForBackup(listOf(definition)))
                assertEquals(mapOf(definition.id to valid), store.catalogs.value)
            }
        }
    }

    @Test
    fun `invalid candidate or failed disk write does not invalidate the previous rollback receipt`() = runBlocking {
        withStore { store, disk ->
            for (storageFailure in listOf(false, true)) {
                val accepted = store.commitCandidate(candidate("accepted")) as McpCatalogCommitResult.Committed
                val rejected = candidate("rejected")
                disk.rejectNextWrite.set(storageFailure)
                try {
                    store.commitCandidate(if (storageFailure) rejected else rejected.copy(tools = rejected.tools + rejected.tools))
                    fail("Invalid candidate committed")
                } catch (_: IOException) { }
                catch (_: IllegalArgumentException) { }
                store.rollbackCommitted(accepted.snapshot, accepted.previous, accepted.headToken)
                assertTrue(store.catalogs.value.isEmpty())
                assertTrue(store.snapshotForBackup(listOf(definition)).isEmpty())
            }
        }
    }

    @Test
    fun `cancelled initialization never releases waiting restore as successful`() = runBlocking {
        withStore(pauseInitialRead = true) { store, disk ->
            withTimeout(5_000) { disk.readCaptured.await() }
            val restore = async(start = CoroutineStart.UNDISPATCHED) {
                store.restoreCatalogs(listOf(candidate("unused").initialSnapshot()), listOf(definition))
            }
            disk.owner.coroutineContext[Job]!!.cancelAndJoin()
            withTimeout(5_000) { restore.join() }
            assertTrue(restore.isCancelled)
            assertEquals(0, disk.writes.get())
        }
    }

    @Test
    fun `cancelled committed writer holds publication and head ownership until disk acknowledgement`() = runBlocking {
        withStore { store, disk ->
            val first = store.commitCandidate(candidate("first")) as McpCatalogCommitResult.Committed
            disk.pauseNextAck.set(true)
            val writer = async(start = CoroutineStart.UNDISPATCHED) { store.commitCandidate(candidate("second")) }
            withTimeout(5_000) { disk.writeCommitted.await() }
            writer.cancel()
            assertFalse(writer.isCompleted)
            assertEquals(first.snapshot, store.catalogs.value[definition.id])
            val following = async(start = CoroutineStart.UNDISPATCHED) { store.commitCandidate(candidate("third")) }
            assertFalse(following.isCompleted)
            disk.releaseAck.complete(Unit)
            withTimeout(5_000) { writer.join() }
            assertTrue(writer.isCancelled)
            val latest = withTimeout(5_000) { following.await() } as McpCatalogCommitResult.Committed
            assertEquals(3L, latest.snapshot.revision)
            store.rollbackCommitted(first.snapshot, null, first.headToken)
            assertEquals(latest.snapshot, store.catalogs.value[definition.id])
            assertEquals(listOf(latest.snapshot), store.snapshotForBackup(listOf(definition)))
        }
    }

    private suspend fun withStore(
        pauseInitialRead: Boolean = false,
        failMigration: Boolean = false,
        failRead: Boolean = false,
        initialCatalog: String? = null,
        block: suspend (McpCatalogStore, PausedDisk) -> Unit,
    ) {
        val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = AppScope(Dispatchers.Default)
        val file = File(temporary.newFolder(), "catalog.preferences_pb")
        val delegate = PreferenceDataStoreFactory.create(scope = diskScope, produceFile = { file })
        if (initialCatalog != null) delegate.edit { it[stringPreferencesKey("catalogs")] = initialCatalog }
        val disk = PausedDisk(delegate, owner, pauseInitialRead, failRead)
        val settings = mockk<SettingsStore>()
        if (failMigration) coEvery { settings.pendingMcpCatalogMigration() } throws IOException("migration read failed")
        else coEvery { settings.pendingMcpCatalogMigration() } returns null
        try { block(McpCatalogStore(disk, owner, settings), disk) }
        finally {
            disk.releaseRead.complete(Unit)
            disk.releaseAck.complete(Unit)
            owner.coroutineContext[Job]!!.cancelAndJoin()
            diskScope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private fun candidate(tool: String) = McpCatalogCandidate(
        definition.id, definition.mcpDefinitionDigest(),
        listOf(McpCatalogTool(tool, inputSchema = buildJsonObject { put("type", "object") })),
    )

    private class PausedDisk(
        val delegate: DataStore<Preferences>,
        val owner: AppScope,
        pauseInitialRead: Boolean,
        failRead: Boolean,
    ) : DataStore<Preferences> {
        private val firstRead = AtomicBoolean(pauseInitialRead)
        private val failNextRead = AtomicBoolean(failRead)
        val readCaptured = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val pauseNextAck = AtomicBoolean(false)
        val writeCommitted = CompletableDeferred<Unit>()
        val releaseAck = CompletableDeferred<Unit>()
        val writes = AtomicInteger()
        val rejectNextWrite = AtomicBoolean(false)

        override val data: Flow<Preferences> = flow {
            if (failNextRead.compareAndSet(true, false)) throw IOException("disk read failed")
            delegate.data.collect { preferences ->
                if (firstRead.compareAndSet(true, false)) {
                    readCaptured.complete(Unit)
                    releaseRead.await()
                }
                emit(preferences)
            }
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (rejectNextWrite.compareAndSet(true, false)) throw IOException("disk write failed")
            val committed = delegate.updateData(transform)
            writes.incrementAndGet()
            if (pauseNextAck.compareAndSet(true, false)) {
                writeCommitted.complete(Unit)
                releaseAck.await()
            }
            return committed
        }
    }
}
