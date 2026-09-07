package net.weero.measix.pilot.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.FavoriteEntity
import net.weero.measix.pilot.data.db.entity.FolderEntity
import net.weero.measix.pilot.data.db.entity.GenMediaEntity
import net.weero.measix.pilot.data.db.entity.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigurationScopePersistenceTest {
    @Test
    fun allRootOwnersRetainSourceDeploymentAndUserAcrossDatabaseReopen() = runBlocking(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "configuration-scope-roots"
        context.deleteDatabase(name)
        val scopes = listOf(
            ConfigurationScope.Personal,
            ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "dep_example"), "用户~alice"),
            ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "dep_example"), "bob"),
            ConfigurationScope.Enterprise(EnterpriseAuthority("platform:example", "dep_example"), "用户~alice"),
            ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "dep_other"), "用户~alice"),
        )
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val writer = open()
            try {
                val db = writer
                scopes.forEachIndexed { index, scope ->
                    val id = index + 1
                    db.conversationDao().insert(ConversationEntity(
                        id = "conversation-$id", assistantId = "shared-assistant", title = "kept",
                        createAt = 1, updateAt = 2, chatSuggestions = "[]", isPinned = false, scope = scope,
                    ))
                    db.memoryDao().insertMemory(MemoryEntity(id = id, assistantId = "__global__", content = "memory-$id", scope = scope))
                    db.artifactDao().insert(ArtifactEntity(
                        id = id.toLong(), folder = "upload", relativePath = "upload/$id.txt", displayName = "$id.txt",
                        mimeType = "text/plain", sizeBytes = 1, createdAt = 1, updatedAt = 2, scope = scope,
                    ))
                    db.genMediaDao().insert(GenMediaEntity(
                        id = id, path = "images/$id.png", modelId = "shared-model", prompt = "kept", createAt = 1, scope = scope,
                    ))
                    db.folderDao().insert(FolderEntity(
                        id = "folder-$id", assistantId = "shared-assistant", name = "kept", createAt = 1, scope = scope,
                    ))
                    db.favoriteDao().upsert(FavoriteEntity(
                        id = "favorite-$id", type = "node", refKey = "node:conversation-$id:node-$id",
                        refJson = "{}", snapshotJson = "{}", createdAt = 1, updatedAt = 2, scope = scope,
                    ))
                }
            } finally {
                writer.close()
            }
            val reader = open()
            try {
                val db = reader
                scopes.forEachIndexed { index, scope ->
                    val id = index + 1
                    assertEquals(scope, db.conversationDao().getConversationById("conversation-$id")?.scope)
                    assertEquals(scope, db.memoryDao().find(scope, id)?.scope)
                    assertEquals(scope, db.artifactDao().getById(id.toLong())?.scope)
                    assertEquals(scope, db.genMediaDao().getById(id)?.scope)
                    assertEquals(scope, db.folderDao().getFolderById("folder-$id")?.scope)
                    assertEquals(scope, db.favoriteDao().getByRefKey("node:conversation-$id:node-$id")?.scope)
                }
            } finally {
                reader.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
