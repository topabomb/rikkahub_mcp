package net.weero.measix.pilot.data.repository

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.db.DatabaseTransactionRunner
import net.weero.measix.pilot.data.db.dao.MemoryDAO
import net.weero.measix.pilot.data.db.entity.MemoryEntity
import net.weero.measix.pilot.data.model.AssistantMemory
import net.weero.measix.pilot.data.model.MemoryAddress
import net.weero.measix.pilot.data.model.MemoryOwner
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.model.storageId

class MemoryRepository(private val dao: MemoryDAO, private val transactions: DatabaseTransactionRunner) {
    internal suspend fun findToolResult(scope: ConfigurationScope, id: Int): Pair<MemoryAddress, AssistantMemory>? =
        dao.find(scope, id)?.let { row ->
            MemoryAddress(row.scope, MemoryOwner.fromStorageId(row.assistantId)) to AssistantMemory(row.id, row.content)
        }

    fun observe(address: MemoryAddress): Flow<List<AssistantMemory>> =
        dao.observe(address.scope, address.owner.storageId).map { rows -> rows.map { AssistantMemory(it.id, it.content) } }

    suspend fun read(address: MemoryAddress): List<AssistantMemory> =
        dao.read(address.scope, address.owner.storageId).map { AssistantMemory(it.id, it.content) }

    suspend fun add(address: MemoryAddress, content: String): AssistantMemory {
        var id = 0
        commit {
            id = dao.insertMemory(MemoryEntity(assistantId = address.owner.storageId, content = content, scope = address.scope)).toInt()
        }
        return AssistantMemory(id, content)
    }

    suspend fun update(address: MemoryAddress, id: Int, content: String): AssistantMemory {
        commit { check(dao.update(address.scope, address.owner.storageId, id, content) == 1) { "memory_not_found_in_namespace" } }
        return AssistantMemory(id, content)
    }

    suspend fun delete(address: MemoryAddress, id: Int) = commit {
        check(dao.delete(address.scope, address.owner.storageId, id) == 1) { "memory_not_found_in_namespace" }
    }

    suspend fun deleteAll(address: MemoryAddress) = commit { dao.deleteAll(address.scope, address.owner.storageId) }

    /** Keep the caller's authorization locks until Room has finished committing or rolling back. */
    private suspend fun commit(write: suspend () -> Unit) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        withContext(NonCancellable) {
            transactions.run {
                caller.ensureActive()
                write()
                caller.ensureActive()
            }
        }
        caller.ensureActive()
    }
}
