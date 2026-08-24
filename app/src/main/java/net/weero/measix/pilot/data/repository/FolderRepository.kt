package net.weero.measix.pilot.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.weero.measix.pilot.data.db.dao.ConversationDAO
import net.weero.measix.pilot.data.db.dao.FolderDAO
import net.weero.measix.pilot.data.db.entity.FolderEntity
import net.weero.measix.pilot.data.model.Folder
import java.time.Instant
import kotlin.uuid.Uuid

class FolderRepository(
    private val folderDAO: FolderDAO,
    private val conversationDAO: ConversationDAO,
) {
    fun getFoldersOfAssistant(assistantId: Uuid): Flow<List<Folder>> {
        return folderDAO.getFoldersOfAssistant(assistantId.toString())
            .map { list -> list.map { it.toFolder() } }
    }

    suspend fun createFolder(assistantId: Uuid, name: String): Folder {
        val folder = Folder(
            assistantId = assistantId,
            name = name,
            createAt = Instant.now(),
        )
        folderDAO.insert(folder.toEntity())
        return folder
    }

    suspend fun renameFolder(id: Uuid, name: String) {
        folderDAO.rename(id.toString(), name)
    }

    suspend fun getConversationIds(id: Uuid): List<Uuid> =
        conversationDAO.getIdsByFolder(id.toString()).map(Uuid::parse)

    /** 会话归属必须先经 ConversationCommandCoordinator 清空；这里只删除 folder metadata。 */
    suspend fun deleteEmptyFolder(id: Uuid) {
        check(conversationDAO.getIdsByFolder(id.toString()).isEmpty()) {
            "Folder still owns conversations: $id"
        }
        folderDAO.deleteById(id.toString())
    }
}

private fun FolderEntity.toFolder(): Folder = Folder(
    id = Uuid.parse(id),
    assistantId = Uuid.parse(assistantId),
    name = name,
    sortIndex = sortIndex,
    createAt = Instant.ofEpochMilli(createAt),
)

private fun Folder.toEntity(): FolderEntity = FolderEntity(
    id = id.toString(),
    assistantId = assistantId.toString(),
    name = name,
    sortIndex = sortIndex,
    createAt = createAt.toEpochMilli(),
)
