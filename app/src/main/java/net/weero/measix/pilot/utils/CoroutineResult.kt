package net.weero.measix.pilot.utils

import kotlinx.coroutines.CancellationException

/** Result boundary for suspend call chains: failures are values, structured cancellation is not. */
inline fun <T> runCatchingPreservingCancellation(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
