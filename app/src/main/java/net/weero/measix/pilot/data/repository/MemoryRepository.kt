package net.weero.measix.pilot.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.weero.measix.pilot.data.db.dao.MemoryDAO
import net.weero.measix.pilot.data.db.entity.MemoryEntity
import net.weero.measix.pilot.data.model.AssistantMemory

class MemoryRepository(private val memoryDAO: MemoryDAO) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map { AssistantMemory(it.id, it.content) }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .map { AssistantMemory(it.id, it.content) }
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(id: Int, content: String, assistantId: String): AssistantMemory {
        val affected = memoryDAO.updateMemoryContent(id, content, assistantId)
        if (affected == 0) {
            // owner + id 约束不满足：记录不存在或不属于该助手
            error("Memory record #$id does not belong to assistant $assistantId or does not exist")
        }
        return AssistantMemory(
            id = id,
            content = content,
        )
    }

    suspend fun addMemory(assistantId: String, content: String): AssistantMemory {
        val memory = AssistantMemory(
            id = 0,
            content = content,
        )
        val newMemory = memory.copy(
            id = memoryDAO.insertMemory(
                MemoryEntity(
                    assistantId = assistantId,
                    content = memory.content
                )
            ).toInt()
        )
        return newMemory
    }

    suspend fun deleteMemory(id: Int, assistantId: String) {
        val affected = memoryDAO.deleteMemory(id, assistantId)
        if (affected == 0) {
            // owner + id 约束不满足：记录不存在或不属于该助手
            error("Memory record #$id does not belong to assistant $assistantId or does not exist")
        }
    }

    /**
     * UI 专用：按 ID 删除记忆，先查询 owner 再走约束删除。
     * LLM 工具必须使用 [deleteMemory] 携带 assistantId，不能使用此方法。
     */
    suspend fun deleteMemoryById(id: Int) {
        val memory = memoryDAO.getMemoryById(id) ?: return
        memoryDAO.deleteMemory(id, memory.assistantId)
    }
}
