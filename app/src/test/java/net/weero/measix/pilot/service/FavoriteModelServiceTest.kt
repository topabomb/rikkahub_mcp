package net.weero.measix.pilot.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class FavoriteModelServiceTest {
    @Test
    fun `move resolves stable ids against the latest list`() {
        val first = Uuid.random()
        val concurrent = Uuid.random()
        val target = Uuid.random()

        val moved = moveFavoriteModel(
            current = listOf(first, concurrent, target),
            fromModelId = first,
            toModelId = target,
        )

        assertEquals(listOf(concurrent, target, first), moved)
    }

    @Test
    fun `move leaves latest list untouched when either id disappeared`() {
        val current = listOf(Uuid.random(), Uuid.random())

        val result = moveFavoriteModel(
            current = current,
            fromModelId = Uuid.random(),
            toModelId = current.first(),
        )

        assertSame(current, result)
    }
}
