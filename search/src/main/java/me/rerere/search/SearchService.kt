package me.rerere.search

import android.content.Context
import androidx.compose.runtime.Composable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.util.KeyRoulette
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlin.uuid.Uuid

interface SearchService<T : SearchServiceOptions> {
    val name: String

    fun parameters(options: T): InputSchema?

    fun scrapingParameters(options: T): InputSchema?

    @Composable
    fun Description()

    suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T
    ): Result<SearchResult>

    suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T
    ): Result<ScrapedResult>

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T : SearchServiceOptions> getService(options: T): SearchService<T> {
            return when (options) {
                is SearchServiceOptions.TavilyOptions -> TavilySearchService
                is SearchServiceOptions.BingLocalOptions -> BingSearchService
                is SearchServiceOptions.SearXNGOptions -> SearXNGService
                is SearchServiceOptions.CustomJsOptions -> CustomJsSearchService
            } as SearchService<T>
        }

        @Volatile
        internal var httpClient: OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        @Volatile
        internal var keyRoulette: KeyRoulette = KeyRoulette.default()

        fun init(client: OkHttpClient, context: Context? = null) {
            httpClient = client
            keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
        }

        internal val json by lazy {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
    }
}

@Serializable
data class SearchCommonOptions(
    val resultSize: Int = 10
)

@Serializable
data class SearchResult(
    val answer: String? = null,
    val items: List<SearchResultItem>,
) {
    @Serializable
    data class SearchResultItem(
        val title: String,
        val url: String,
        val text: String,
    )
}

@Serializable
data class ScrapedResult(
    val urls: List<ScrapedResultUrl>,
)

@Serializable
data class ScrapedResultUrl(
    val url: String,
    val content: String,
    val metadata: ScrapedResultMetadata? = null,
)

@Serializable
data class ScrapedResultMetadata(
    val title: String? = null,
    val description: String? = null,
    val language: String? = null,
)

@Serializable
sealed class SearchServiceOptions {
    abstract val id: Uuid

    open val displayName: String
        get() = TYPES[this::class] ?: "Unknown"

    companion object {
        val DEFAULT = BingLocalOptions()

        val TYPES = mapOf(
            BingLocalOptions::class to "Bing",
            TavilyOptions::class to "Tavily",
            SearXNGOptions::class to "SearXNG",
            CustomJsOptions::class to "Custom JS",
        )
    }

    @Serializable
    @SerialName("bing_local")
    class BingLocalOptions(
        override val id: Uuid = Uuid.random()
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("tavily")
    data class TavilyOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val depth: String = "advanced",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("searxng")
    data class SearXNGOptions(
        override val id: Uuid = Uuid.random(),
        val url: String = "",
        val engines: String = "",
        val language: String = "",
        val username: String = "",
        val password: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("custom_js")
    data class CustomJsOptions(
        override val id: Uuid = Uuid.random(),
        val name: String = "",
        val searchScript: String = DEFAULT_SEARCH_SCRIPT,
        val scrapeScript: String = "",
    ) : SearchServiceOptions() {
        override val displayName: String
            get() = name.ifBlank { "Custom JS" }
        companion object {
            const val DEFAULT_SCRAPE_SCRIPT = """// Implement scrape(urls) function
// urls is an array of URL strings
// Use fetch(url, options?) for HTTP requests
// fetch() returns { status, ok, text(), json() }
// Return { urls: [{ url, content, metadata?: { title?, description?, language? } }] }

function scrape(urls) {
  return {
    urls: urls.map(function(url) {
      const res = fetch(url);
      const body = res.text();
      return { url: url, content: body };
    })
  };
}"""

            const val DEFAULT_SEARCH_SCRIPT = """// Implement search(query, resultSize) function
// Use fetch(url, options?) for HTTP requests
// fetch() returns { status, ok, text(), json() }
// Return { items: [{ title, url, text }], answer?: string }

function search(query, resultSize) {
  const encoded = encodeURIComponent(query);
  const res = fetch("https://example.com/search?q=" + encoded + "&limit=" + resultSize);
  const data = res.json();
  return {
    items: data.results.map(function(r) {
      return { title: r.title, url: r.url, text: r.snippet };
    })
  };
}"""
        }
    }

}

internal suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { cause, _, _ ->
                    response.closeQuietly()
                }
            }
        })
    }
}
