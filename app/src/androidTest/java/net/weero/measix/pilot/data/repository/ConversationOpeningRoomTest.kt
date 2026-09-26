package net.weero.measix.pilot.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.db.createAppDatabase
import net.weero.measix.pilot.data.db.dao.ModelContextConflictException
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.ConversationOpeningEntity
import net.weero.measix.pilot.data.model.ConversationOpeningCodec
import net.weero.measix.pilot.data.model.largeConversationOpening
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ConversationOpeningRoomTest {
    @Test fun largeOpeningReopensExactlyAndInsertOnceRejectsReplacementWithoutChangingBytes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "opening-room-${Uuid.random()}"
        val conversationId = Uuid.random().toString()
        val opening = largeConversationOpening()
        val encoded = ConversationOpeningCodec.encode(opening)
        assertTrue(encoded.toByteArray().size > 2 * 1024 * 1024)
        val row = ConversationOpeningEntity(conversationId, encoded)
        try {
            val database = createAppDatabase(context, name)
            try {
                database.conversationDao().insert(ConversationEntity(conversationId, opening.assistant.toString(),
                    "Opening", 1, 1, "[]", false, scope = ConfigurationScope.Enterprise(opening.assistant.authority, "user")))
                val dao = database.conversationModelContextDao()
                dao.insertOpeningOnce(row)
                dao.insertOpeningOnce(row)
                assertEquals(row, dao.getOpening(conversationId))
            } finally { database.close() }
            val reopened = createAppDatabase(context, name)
            try {
                val dao = reopened.conversationModelContextDao()
                assertEquals(opening, ConversationOpeningCodec.decode(requireNotNull(dao.getOpening(conversationId)).payload))
                val replacement = row.copy(payload = ConversationOpeningCodec.encode(opening.copy(releaseId = "different")))
                assertThrows(ModelContextConflictException::class.java) { runBlocking { dao.insertOpeningOnce(replacement) } }
                assertEquals(encoded, requireNotNull(dao.getOpening(conversationId)).payload)
                reopened.conversationDao().deleteById(conversationId)
                assertNull(dao.getOpening(conversationId))
                reopened.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
