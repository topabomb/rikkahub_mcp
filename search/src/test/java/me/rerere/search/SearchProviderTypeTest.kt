package me.rerere.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchProviderTypeTest {
    @Test
    fun `registry contains only supported providers`() {
        assertEquals(
            listOf("Bing", "Tavily", "SearXNG"),
            SearchProviderType.entries.map { it.displayName },
        )
    }

    @Test
    fun `provider factory creates matching options and service`() {
        SearchProviderType.entries.forEach { type ->
            val options = type.createOptions()

            assertEquals(type, options.providerType)
            assertEquals(type.displayName, options.displayName)
            assertTrue(options.id.toString().isNotBlank())
        }

        assertSame(
            BingSearchService,
            SearchService.getService(SearchProviderType.BING.createOptions()),
        )
        assertSame(
            TavilySearchService,
            SearchService.getService(SearchProviderType.TAVILY.createOptions()),
        )
        assertSame(
            SearXNGService,
            SearchService.getService(SearchProviderType.SEARXNG.createOptions()),
        )
    }
}
