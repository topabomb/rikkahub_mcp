package me.rerere.speech

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

/** An application-owned complete endpoint and authentication, separate from provider settings. */
class SpeechHttpTransport(
    val client: OkHttpClient,
    private val endpoint: String,
    private val headers: Map<String, String>,
) {
    fun request(body: RequestBody): Request = Request.Builder().url(endpoint)
        .tag(me.rerere.common.http.PrivateRequest::class.java, me.rerere.common.http.PrivateRequest).apply {
        headers.forEach { (name, value) -> header(name, value) }
    }.post(body).build()
}
