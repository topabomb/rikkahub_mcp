package net.weero.measix.pilot.service

import kotlinx.coroutines.CompletableDeferred

/** Prevents live conversation or assistant mutations from racing startup recovery. */
class AssistantDataRecoveryGate private constructor(initiallyReady: Boolean) {
    private val ready = CompletableDeferred<Unit>()

    constructor() : this(false)

    init {
        if (initiallyReady) ready.complete(Unit)
    }

    suspend fun awaitReady() = ready.await()

    internal fun complete() {
        ready.complete(Unit)
    }

    companion object {
        fun completed(): AssistantDataRecoveryGate = AssistantDataRecoveryGate(true)
    }
}
