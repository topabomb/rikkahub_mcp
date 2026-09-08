package me.rerere.ai.provider

import me.rerere.ai.util.KeyRoulette
import me.rerere.common.http.PrivateRequest
import okhttp3.Request

/** Credentials belong to one request; fixed credentials never enter user key rotation or its cache. */
sealed interface RequestCredentials {
    data object UserSettings : RequestCredentials

    class Fixed internal constructor(internal val value: String?) : RequestCredentials {
        override fun toString(): String = "Fixed([redacted])"
    }

    companion object {
        fun fixed(value: String?): RequestCredentials = Fixed(value)
    }
}

internal fun Request.Builder.authenticate(
    credentials: RequestCredentials,
    header: String,
    prefix: String = "",
    userKeys: String,
    providerId: String,
    roulette: KeyRoulette,
): Request.Builder = apply {
    when (credentials) {
        RequestCredentials.UserSettings -> addHeader(header, prefix + roulette.next(userKeys, providerId))
        is RequestCredentials.Fixed -> {
            tag(PrivateRequest::class.java, PrivateRequest)
            credentials.value?.let {
                check(build().headers.values(header).isEmpty()) { "request_authentication_header_conflict" }
                header(header, prefix + it)
            }
        }
    }
}
