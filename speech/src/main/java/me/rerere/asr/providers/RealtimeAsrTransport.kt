package me.rerere.asr.providers

import okhttp3.Request
import okhttp3.WebSocket

/** Ephemeral application route; authentication is never copied into saved provider settings. */
class RealtimeAsrTransport(
    val sockets: WebSocket.Factory,
    val request: Request,
)
