package net.weero.measix.pilot.service.runtime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * The single keyed lock owner for loading, installing, evicting and mutating conversation state.
 * A coroutine may re-enter the same stripe because coordinator operations call registry operations
 * while already holding the conversation boundary.
 */
class ConversationOperationLocks {
    private val stripes = Array(64) { Mutex() }

    suspend fun <T> withLock(conversationId: Uuid, block: suspend () -> T): T =
        withLocks(listOf(conversationId), block)

    /**
     * Locks every involved conversation in stable stripe order. This is the tree-operation
     * boundary: parent deletion and child start/create cannot interleave between inspection and
     * commit. Stripe deduplication is intentional because unrelated ids may hash to one stripe.
     */
    suspend fun <T> withLocks(conversationIds: Collection<Uuid>, block: suspend () -> T): T {
        val requested = conversationIds.map(::stripeIndex).toSortedSet()
        val held = coroutineContext[HeldStripes]?.indices.orEmpty()
        val missing = requested.filterNot(held::contains)
        if (missing.isEmpty()) return block()
        check(held.isEmpty() || missing.first() > held.max()) {
            "conversation locks must be acquired from the complete id set"
        }
        return acquire(missing, 0, held, block)
    }

    private suspend fun <T> acquire(
        indices: List<Int>,
        offset: Int,
        held: Set<Int>,
        block: suspend () -> T,
    ): T {
        if (offset == indices.size) return block()
        val index = indices[offset]
        return stripes[index].withLock {
            val nextHeld = held + index
            withContext(HeldStripes(nextHeld)) {
                acquire(indices, offset + 1, nextHeld, block)
            }
        }
    }

    private fun stripeIndex(conversationId: Uuid): Int =
        (conversationId.hashCode() and Int.MAX_VALUE) % stripes.size

    private class HeldStripes(
        val indices: Set<Int>,
    ) : AbstractCoroutineContextElement(HeldStripes) {
        companion object Key : CoroutineContext.Key<HeldStripes>
    }
}
