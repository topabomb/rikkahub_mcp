package me.rerere.baselineprofile

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/** Extracts only this iteration's target process; missing evidence fails instead of reporting zero. */
@OptIn(ExperimentalMetricApi::class)
internal class TurnWorkloadMetric(
    private val workload: String,
    private val sections: List<String>,
) : TraceMetric() {
    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Measurement> = buildList {
        val processName = captureInfo.targetPackageName.replace("'", "''")
        fun counter(name: String, cumulative: Boolean = false): Double {
            val row = traceSession.query("""
                SELECT MAX(counter.value) AS value, COUNT(*) AS samples
                FROM counter
                JOIN process_counter_track ON counter.track_id = process_counter_track.id
                JOIN process USING(upid)
                WHERE process.name = '$processName' AND process_counter_track.name = '$name'
            """.trimIndent()).single()
            val samples = row.long("samples")
            check(if (cumulative) samples > 0 else samples == 1L) {
                "Expected ${if (cumulative) "at least one" else "one"} counter sample for $name, got $samples"
            }
            return row.double("value")
        }
        sections.forEach { section ->
            val row = traceSession.query("""
                SELECT SUM(slice.dur) AS duration_ns, COUNT(*) AS samples
                FROM slice
                JOIN thread_track ON slice.track_id = thread_track.id
                JOIN thread USING(utid)
                JOIN process USING(upid)
                WHERE process.name = '$processName' AND slice.name = '$section' AND slice.dur >= 0
            """.trimIndent()).single()
            val count = row.long("samples")
            check(if (workload == "compose_100") count > 0 else count == 1L) {
                "Unexpected workload trace count for $section: $count"
            }
            // One total per iteration lets AndroidX emit pooled P50/P90/P95/P99, including raw runs.
            add(Measurement("${section}Ms", listOf(row.long("duration_ns") / 1_000_000.0)))
            add(Measurement("${section}Count", count.toDouble()))
            if (workload != "compose_100") {
                add(Measurement("${section}JavaAllocatedBytesApprox",
                    listOf(counter("${section}_java_allocated_bytes_approx"))))
            }
        }
        when (workload) {
            "migration_1000" -> add(Measurement("migratedRows", counter("turn_migration_rows")))
            "output_100mb" -> add(Measurement("toolOutputInputBytes", counter("turn_output_input_bytes")))
            "compose_100" -> {
                add(Measurement("activeAssistantCompositions", counter("turn_active_compositions", cumulative = true)))
                add(Measurement("activeUpdatesJavaAllocatedBytesApprox",
                    listOf(counter("turn_compose_updates_java_allocated_bytes_approx"))))
            }
        }
    }
}
