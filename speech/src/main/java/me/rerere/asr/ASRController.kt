package me.rerere.asr

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/** An original controller owns cleanup until this receipt succeeds; callers may retry failed cleanup. */
class AsrCleanup internal constructor(private val job: Job, private val release: suspend () -> Unit = {}) {
    suspend fun awaitClosed() { job.join(); release() }
}

interface ASRController {
    val state: StateFlow<ASRState>
    fun start(onTranscriptChange: (String) -> Unit)
    fun stop()
    fun dispose(): AsrCleanup
}
