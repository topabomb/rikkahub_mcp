package me.rerere.common.http

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Request-local marker with no principal or secret; copied with redirect and retry requests. */
data object PrivateRequest

val Request.isPrivate: Boolean get() = tag(PrivateRequest::class.java) != null

/** Does not mutate the shared logger level, so concurrent personal requests retain their policy. */
fun Interceptor.excludingPrivateRequests(): Interceptor = Interceptor { chain ->
    if (chain.request().isPrivate) chain.proceed(chain.request()) else intercept(chain)
}

/** Network boundary: reject a redirect before OkHttp can copy private headers to another origin. */
class PrivateRequestBoundaryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.isPrivate && response.code in setOf(300, 301, 302, 303, 307, 308)) {
            val destination = response.header("Location")?.let(request.url::resolve)
            if (destination != null && (destination.scheme != request.url.scheme ||
                    destination.host != request.url.host || destination.port != request.url.port)) {
                response.close()
                throw IOException("private_request_cross_origin_redirect")
            }
        }
        return response
    }
}
