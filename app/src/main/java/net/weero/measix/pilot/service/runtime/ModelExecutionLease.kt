package net.weero.measix.pilot.service.runtime

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestCredentials

/** Request-local connection inputs; neither variant belongs in persisted or UI-visible Settings. */
internal sealed interface ModelRequestTarget {
    class Remote(
        val provider: ProviderSetting,
        val headers: List<CustomHeader> = emptyList(),
        val credentials: RequestCredentials = RequestCredentials.UserSettings,
    ) : ModelRequestTarget
    data object LocalExample : ModelRequestTarget
}

/** The Turn owner retains this lease across user pauses and awaits release outside admission locks. */
internal class ModelExecutionLease(
    private val releaseOwner: suspend () -> Unit = {},
    private val admit: suspend ((ModelRequestTarget) -> Unit) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val releaseMutex = Mutex()
    private var released = false

    /** Admission starts the original worker's child request; no network wait holds the policy lock. */
    suspend fun <T> execute(operation: suspend (ModelRequestTarget) -> T): T = coroutineScope {
        val requestContext = currentCoroutineContext()
        var request: Deferred<T>? = null
        admit { target ->
            requestContext.ensureActive()
            check(!closed.get()) { "model_execution_lease_closed" }
            check(request == null) { "model_request_already_admitted" }
            request = async(Dispatchers.IO, start = CoroutineStart.LAZY) { operation(target) }.also { it.start() }
        }
        requireNotNull(request) { "model_request_not_admitted" }.await()
    }

    suspend fun release() {
        closed.set(true)
        withContext(NonCancellable) {
            releaseMutex.withLock {
                if (!released) {
                    releaseOwner()
                    released = true
                }
            }
        }
    }
}
