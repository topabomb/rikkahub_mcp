package net.weero.measix.pilot.data.files

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** Serializes supplemental file-domain writers with aggregate backup snapshots. */
object BackupSnapshotBarrier {
    private val permit = Semaphore(1, true)

    suspend fun <T> withLock(block: suspend () -> T): T {
        val acquired = AtomicBoolean(false)
        return try {
            runInterruptible(Dispatchers.IO) {
                permit.acquire()
                acquired.set(true)
            }
            block()
        } finally {
            if (acquired.compareAndSet(true, false)) permit.release()
        }
    }

    fun <T> withBlockingLock(block: () -> T): T {
        permit.acquire()
        return try {
            block()
        } finally {
            permit.release()
        }
    }
}
