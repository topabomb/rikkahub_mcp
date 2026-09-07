package net.weero.measix.pilot.benchmark

import android.os.Debug
import android.os.Trace

/** ART reports approximate process-wide managed allocation, not retained or native heap size. */
internal fun allocatedJavaBytes(): Long = requireNotNull(
    Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull(),
) { "ART allocation statistics are unavailable on this device" }

internal fun recordJavaAllocation(section: String, before: Long) {
    val delta = allocatedJavaBytes() - before
    check(delta >= 0) { "ART allocation counter decreased during $section" }
    Trace.setCounter("${section}_java_allocated_bytes_approx", delta)
}

/** Setup stays outside this boundary; timing comes exclusively from the captured Perfetto slice. */
internal inline fun <T> measureWorkload(section: String, body: () -> T): T {
    val before = allocatedJavaBytes()
    Trace.beginSection(section)
    return try { body() } finally {
        Trace.endSection()
        recordJavaAllocation(section, before)
    }
}
