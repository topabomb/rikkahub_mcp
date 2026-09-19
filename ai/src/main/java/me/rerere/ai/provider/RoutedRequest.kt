package me.rerere.ai.provider

import me.rerere.common.http.withExplicitRoute
import okhttp3.OkHttpClient

internal fun OkHttpClient.forCredentials(credentials: RequestCredentials): OkHttpClient =
    if (credentials is RequestCredentials.Routed) withExplicitRoute(credentials.endpoint, credentials.token) else this
