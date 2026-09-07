package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FavoriteModelServiceTest {
    @Test
    fun `move resolves stable ids against the latest list`() {
        val first = ConfigurationReference.random()
        val concurrent = ConfigurationReference.random()
        val target = ConfigurationReference.random()

        val moved = moveFavoriteModel(
            current = listOf(first, concurrent, target),
            fromModelId = first,
            toModelId = target,
        )

        assertEquals(listOf(concurrent, target, first), moved)
    }

    @Test
    fun `move leaves latest list untouched when either id disappeared`() {
        val current = listOf(ConfigurationReference.random(), ConfigurationReference.random())

        val result = moveFavoriteModel(
            current = current,
            fromModelId = ConfigurationReference.random(),
            toModelId = current.first(),
        )

        assertSame(current, result)
    }
}
