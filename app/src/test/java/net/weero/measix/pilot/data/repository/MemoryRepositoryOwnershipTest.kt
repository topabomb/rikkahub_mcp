package net.weero.measix.pilot.data.repository

import kotlinx.coroutines.flow.flowOf
import net.weero.measix.pilot.data.db.dao.MemoryDAO
import net.weero.measix.pilot.data.db.entity.MemoryEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Memory owner 隔离回归测试。
 *
 * 覆盖：edit/delete 同时校验 owner + id；
 * 越权操作拒绝；绝不读取或修改其他 namespace。
 */
class MemoryRepositoryOwnershipTest {
    /**
     * 测试用 Fake DAO，记录调用参数。
     */
    private class FakeMemoryDAO : MemoryDAO {
        val memories = mutableListOf<MemoryEntity>()
        var updateMemoryContentCalled: Triple<Int, String, String>? = null
        var deleteMemoryCalled: Pair<Int, String>? = null

        override fun getMemoriesOfAssistantFlow(assistantId: String): kotlinx.coroutines.flow.Flow<List<MemoryEntity>> = flowOf(emptyList())
        override suspend fun getMemoriesOfAssistant(assistantId: String) =
            memories.filter { it.assistantId == assistantId }

        override fun getAllMemoriesFlow(): kotlinx.coroutines.flow.Flow<List<MemoryEntity>> = flowOf(emptyList())
        override suspend fun getAllMemories() = memories.toList()

        override suspend fun getMemoryById(id: Int): MemoryEntity? =
            memories.find { it.id == id }

        override suspend fun insertMemory(memory: MemoryEntity): Long {
            val newId = ((memories.maxOfOrNull { it.id } ?: 0) + 1).toLong()
            memories.add(memory.copy(id = newId.toInt()))
            return newId
        }

        override suspend fun updateMemoryContent(id: Int, content: String, assistantId: String): Int {
            updateMemoryContentCalled = Triple(id, content, assistantId)
            val idx = memories.indexOfFirst { it.id == id && it.assistantId == assistantId }
            if (idx >= 0) {
                memories[idx] = memories[idx].copy(content = content)
                return 1
            }
            return 0
        }

        override suspend fun deleteMemory(id: Int, assistantId: String): Int {
            deleteMemoryCalled = Pair(id, assistantId)
            val before = memories.size
            memories.removeAll { it.id == id && it.assistantId == assistantId }
            return before - memories.size
        }

        override suspend fun deleteMemoriesOfAssistant(assistantId: String) {
            memories.removeAll { it.assistantId == assistantId }
        }
    }

    @Test
    fun `updateContent - passes owner id to DAO`() = runTest {
        val dao = FakeMemoryDAO()
        val repo = MemoryRepository(dao)
        val assistantId = "assistant-1"
        dao.memories.add(MemoryEntity(id = 1, assistantId = assistantId, content = "old"))

        repo.updateContent(id = 1, content = "new", assistantId = assistantId)

        val (id, content, owner) = dao.updateMemoryContentCalled!!
        assertEquals(1, id)
        assertEquals("new", content)
        assertEquals(assistantId, owner)
    }

    @Test
    fun `updateContent - wrong owner throws error`() = runTest {
        val dao = FakeMemoryDAO()
        val repo = MemoryRepository(dao)
        dao.memories.add(MemoryEntity(id = 1, assistantId = "assistant-1", content = "old"))

        // 尝试用错误的 owner 更新
        var threw = false
        try {
            repo.updateContent(id = 1, content = "new", assistantId = "assistant-2")
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `updateContent - non-existent memory throws error`() = runTest {
        val dao = FakeMemoryDAO()
        val repo = MemoryRepository(dao)

        var threw = false
        try {
            repo.updateContent(id = 999, content = "new", assistantId = "assistant-1")
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `deleteMemory - passes owner id to DAO`() = runTest {
        val dao = FakeMemoryDAO()
        val repo = MemoryRepository(dao)
        val assistantId = "assistant-1"
        dao.memories.add(MemoryEntity(id = 1, assistantId = assistantId, content = "data"))

        repo.deleteMemory(id = 1, assistantId = assistantId)

        val (id, owner) = dao.deleteMemoryCalled!!
        assertEquals(1, id)
        assertEquals(assistantId, owner)
    }

    @Test
    fun `deleteMemory - wrong owner throws error`() = runTest {
        val dao = FakeMemoryDAO()
        val repo = MemoryRepository(dao)
        dao.memories.add(MemoryEntity(id = 1, assistantId = "assistant-1", content = "data"))

        var threw = false
        try {
            repo.deleteMemory(id = 1, assistantId = "assistant-2")
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `deleteMemory - non-existent memory throws error`() = runTest {
        val dao = FakeMemoryDAO()
        val repo = MemoryRepository(dao)

        var threw = false
        try {
            repo.deleteMemory(id = 999, assistantId = "assistant-1")
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `deleteMemoryById - looks up owner then deletes with constraint`() = runTest {
        val dao = FakeMemoryDAO()
        val repo = MemoryRepository(dao)
        val assistantId = "assistant-1"
        dao.memories.add(MemoryEntity(id = 1, assistantId = assistantId, content = "data"))

        repo.deleteMemoryById(id = 1)

        val (id, owner) = dao.deleteMemoryCalled!!
        assertEquals(1, id)
        assertEquals(assistantId, owner)
    }

    @Test
    fun `deleteMemoryById - non-existent memory is no-op`() = runTest {
        val dao = FakeMemoryDAO()
        val repo = MemoryRepository(dao)

        repo.deleteMemoryById(id = 999)

        assertNull(dao.deleteMemoryCalled)
    }

    @Test
    fun `addMemory - stores with correct assistantId`() = runTest {
        val dao = FakeMemoryDAO()
        val repo = MemoryRepository(dao)
        val assistantId = "assistant-1"

        val result = repo.addMemory(assistantId, "new memory")

        assertEquals("new memory", result.content)
        assertEquals(1, dao.memories.size)
        assertEquals(assistantId, dao.memories[0].assistantId)
    }

    @Test
    fun `getMemoriesOfAssistant - only returns own namespace`() = runTest {
        val dao = FakeMemoryDAO()
        val repo = MemoryRepository(dao)
        dao.memories.add(MemoryEntity(id = 1, assistantId = "assistant-1", content = "A"))
        dao.memories.add(MemoryEntity(id = 2, assistantId = "assistant-2", content = "B"))
        dao.memories.add(MemoryEntity(id = 3, assistantId = "assistant-1", content = "C"))

        val result = repo.getMemoriesOfAssistant("assistant-1")

        assertEquals(2, result.size)
        assertTrue(result.all { it.id in listOf(1, 3) })
    }

    @Test
    fun `global memory uses special id`() = runTest {
        val dao = FakeMemoryDAO()
        val repo = MemoryRepository(dao)
        dao.memories.add(MemoryEntity(id = 1, assistantId = MemoryRepository.GLOBAL_MEMORY_ID, content = "global"))

        val result = repo.getGlobalMemories()

        assertEquals(1, result.size)
        assertEquals("global", result[0].content)
    }
}
