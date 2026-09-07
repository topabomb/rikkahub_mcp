package net.weero.measix.pilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.DatabaseTransactionRunner
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.dao.MemoryDAO
import net.weero.measix.pilot.data.db.entity.MemoryEntity
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore
import net.weero.measix.pilot.data.enterprise.EnterprisePackageCodec
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.LocalEnterpriseSource
import net.weero.measix.pilot.data.model.MemoryAddress
import net.weero.measix.pilot.data.model.MemoryOwner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ScopedMemoryRepositoryAndroidTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val assistant = MemoryOwner.Assistant(ConfigurationReference.random())
    private val authority = EnterpriseAuthority("local:example", "dep_example")

    @Test
    fun namespaceReadsAndObservationsKeepEveryPrincipalAndOwnerSeparateInIdOrder() = runBlocking {
        withDatabase { database ->
            val repository = MemoryRepository(database.memoryDao(), RoomDatabaseTransactionRunner(database))
            val addresses = addresses()
            addresses.forEachIndexed { index, address ->
                // Deliberately insert the larger ID first; display order is independent of input and content order.
                val ownerId = if (address.owner == MemoryOwner.RealmShared) "__global__" else assistant.id.toString()
                database.memoryDao().insertMemory(MemoryEntity(index * 10 + 2, ownerId, "a-$index", address.scope))
                database.memoryDao().insertMemory(MemoryEntity(index * 10 + 1, ownerId, "z-$index", address.scope))
            }
            addresses.forEachIndexed { index, address ->
                val rows = repository.read(address)
                assertEquals(listOf(index * 10 + 1, index * 10 + 2), rows.map { it.id })
                assertEquals(listOf("z-$index", "a-$index"), rows.map { it.content })
                assertEquals(rows, withTimeout(10_000) { repository.observe(address).first() })
                val added = repository.add(address, "new-$index")
                repository.update(address, added.id, "updated-$index")
                assertEquals("updated-$index", repository.read(address).last().content)
                repository.delete(address, added.id)
                assertEquals(rows, repository.read(address))
            }
        }
    }

    @Test
    fun wrongScopeOwnerAndIdCannotMutateRowsAndPersonalAssistantCleanupPreservesEnterpriseMemory() = runBlocking {
        withDatabase { database ->
            val repository = MemoryRepository(database.memoryDao(), RoomDatabaseTransactionRunner(database))
            val entries = addresses().associateWith { repository.add(it, "kept-$it") }
            val personalAssistant = MemoryAddress(ConfigurationScope.Personal, assistant)
            val target = entries.getValue(personalAssistant)
            val invalidAddresses = entries.keys.filter { it != personalAssistant } +
                MemoryAddress(ConfigurationScope.Personal, MemoryOwner.Assistant(ConfigurationReference.random()))
            invalidAddresses.forEach { address ->
                expectMissing { repository.update(address, target.id, "wrong") }
                expectMissing { repository.delete(address, target.id) }
            }
            expectMissing { repository.update(personalAssistant, Int.MAX_VALUE, "wrong") }
            expectMissing { repository.delete(personalAssistant, Int.MAX_VALUE) }
            entries.forEach { (address, row) -> assertEquals(listOf(row), repository.read(address)) }

            repository.deleteAll(personalAssistant)
            entries.forEach { (address, row) ->
                assertEquals(if (address == personalAssistant) emptyList() else listOf(row), repository.read(address))
            }
        }
    }

    @Test
    fun cancellationAfterInsertBeforeCommitRollsBackTheActualRoomTransaction() = runBlocking {
        withDatabase { database ->
            coroutineScope {
                val inserted = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val cancelled = AtomicBoolean(false)
                val realDao = database.memoryDao()
                val pausedDao = object : MemoryDAO by realDao {
                    override suspend fun insertMemory(memory: MemoryEntity): Long {
                        val id = realDao.insertMemory(memory)
                        inserted.complete(Unit)
                        release.await()
                        return id
                    }
                }
                val repository = MemoryRepository(pausedDao, RoomDatabaseTransactionRunner(database))
                val address = MemoryAddress(ConfigurationScope.Enterprise(authority, "alice"), assistant)
                val writer = launch(Dispatchers.Default) {
                    try { repository.add(address, "must roll back") }
                    catch (error: CancellationException) { cancelled.set(true); throw error }
                }
                try {
                    withTimeout(10_000) { inserted.await() }
                    writer.cancel()
                    assertFalse(writer.isCompleted)
                    release.complete(Unit)
                    withTimeout(10_000) { writer.join() }
                    assertTrue(cancelled.get())
                    assertTrue(repository.read(address).isEmpty())
                    assertEquals(0L, database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM MemoryEntity").use {
                        assertTrue(it.moveToFirst()); it.getLong(0)
                    })
                } finally {
                    release.complete(Unit)
                    withContext(NonCancellable) { withTimeout(10_000) { writer.cancel(); writer.join() } }
                }
            }
        }
    }

    @Test
    fun cancellationAfterCommitDecisionKeepsRealmAccessLockedUntilRoomFinishes() = runBlocking {
        val clientRoot = File(context.noBackupFilesDir, "scoped-memory-${Uuid.random()}").apply { check(mkdirs()) }
        try {
            withDatabase { database ->
                coroutineScope {
                    val sessions = EnterpriseSessionController(EnterpriseAppliedStore(clientRoot))
                    val packet = context.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET).use(EnterprisePackageCodec::decode)
                    val ready = sessions.enrollLocal(packet.identity, { packet.identity }, { packet })
                    val scope = requireNotNull(ready.manifest.session).identity.scope
                    val access = sessions.captureRealmAccess(scope)
                    val address = MemoryAddress(scope, assistant)
                    val commitDecided = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val transactionFinished = AtomicBoolean(false)
                    val cancelled = AtomicBoolean(false)
                    val transactions = DatabaseTransactionRunner { block ->
                        database.withTransaction {
                            block()
                            // The repository has checked its caller and handed the successful write to Room.
                            commitDecided.complete(Unit)
                            release.await()
                        }
                        transactionFinished.set(true)
                    }
                    val repository = MemoryRepository(database.memoryDao(), transactions)
                    val writer = launch(Dispatchers.Default) {
                        try { sessions.withRealmAccess(access) { repository.add(address, "committed") } }
                        catch (error: CancellationException) { cancelled.set(true); throw error }
                    }
                    var exitJob: Job? = null
                    try {
                        withTimeout(10_000) { commitDecided.await() }
                        writer.cancel()
                        val exit = async(start = CoroutineStart.UNDISPATCHED) {
                            sessions.beginExit().also { assertTrue(transactionFinished.get()) }
                        }
                        exitJob = exit
                        assertFalse(writer.isCompleted)
                        assertFalse(exit.isCompleted)
                        assertFalse(transactionFinished.get())
                        release.complete(Unit)
                        withTimeout(10_000) { writer.join(); assertNotNull(exit.await()) }
                        assertTrue(cancelled.get())
                        assertTrue(transactionFinished.get())
                        assertEquals(listOf("committed"), repository.read(address).map { it.content })
                    } finally {
                        release.complete(Unit)
                        withContext(NonCancellable) {
                            withTimeout(10_000) { writer.cancel(); writer.join(); exitJob?.cancel(); exitJob?.join() }
                        }
                    }
                }
            }
        } finally {
            clientRoot.deleteRecursively()
        }
    }

    private fun addresses(): List<MemoryAddress> = listOf(
        ConfigurationScope.Personal,
        ConfigurationScope.Enterprise(authority, "alice"),
        ConfigurationScope.Enterprise(authority, "bob"),
        ConfigurationScope.Enterprise(EnterpriseAuthority("platform:example", "dep_example"), "alice"),
    ).flatMap { scope -> listOf(MemoryOwner.RealmShared, assistant).map { MemoryAddress(scope, it) } }

    private suspend fun expectMissing(operation: suspend () -> Unit) {
        try { operation(); fail("Expected a namespace miss") }
        catch (error: IllegalStateException) { assertEquals("memory_not_found_in_namespace", error.message) }
    }

    private suspend fun withDatabase(operation: suspend (AppDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try { operation(database) } finally { database.close() }
    }
}
