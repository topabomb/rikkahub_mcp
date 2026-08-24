package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.db.entity.FavoriteEntity
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.FavoriteRepository
import org.junit.Assert.assertFalse
import org.junit.Test

class FavoriteServiceTest {
    @Test
    fun `concurrent toggles preserve every mutation`() = runTest {
        val repository = mockk<FavoriteRepository>()
        val favorite = AtomicBoolean(false)
        coEvery { repository.existsByRefKey(any()) } coAnswers { favorite.get() }
        coEvery { repository.deleteByRefKey(any()) } coAnswers {
            favorite.set(false)
            1
        }
        coEvery { repository.addNodeFavorite(any()) } coAnswers {
            favorite.set(true)
            mockk<FavoriteEntity>()
        }
        val gate = ApplicationRecoveryGate().apply { ready() }
        val service = FavoriteService(repository, gate)
        val conversationId = Uuid.random()
        val node = MessageNode.of(UIMessage.user("favorite"))

        (0 until 100).map {
            async(Dispatchers.Default) { service.toggleNode(conversationId, "title", node) }
        }.awaitAll()

        assertFalse(favorite.get())
    }
}
