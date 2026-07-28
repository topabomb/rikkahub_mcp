package net.weero.measix.pilot.utils

import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonLenientDecodeTest {
    @Test
    fun `removed custom js search provider is skipped while supported providers survive`() {
        val bingJson = JsonInstant.encodeToString<SearchServiceOptions>(
            SearchServiceOptions.BingLocalOptions(),
        )
        val storedJson = """
            [
              {
                "type": "custom_js",
                "id": "00000000-0000-0000-0000-000000000000",
                "name": "Legacy provider",
                "searchScript": "function search() {}"
              },
              $bingJson
            ]
        """.trimIndent()

        val decoded = JsonInstant.decodeListLenient<SearchServiceOptions>(storedJson)

        assertEquals(1, decoded.size)
        assertTrue(decoded.single() is SearchServiceOptions.BingLocalOptions)
    }
}
