package me.rerere.baselineprofile

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
@SdkSuppress(minSdkVersion = 29)
class TurnWorkloadBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun streamTenThousandChunks() = benchmark("stream_10000", "turn_stream_10000")

    @Test
    fun assembleThousandMessageHistory() = benchmark("request_1000", "turn_request_1000")

    @Test
    fun planHundredLargeToolResults() = benchmark("compaction_100", "turn_compaction_100")

    @Test
    fun freezeFiftyToolSchemas() = benchmark("schema_50", "turn_schema_50")

    @Test
    fun migrateThousandLargeLegacyNodes() = benchmark("migration_1000", "turn_room_migration_1000")

    @Test
    fun readAndGrepHundredMegabyteToolOutput() = benchmark("output_100mb", "turn_output_read_100mb", "turn_output_grep_100mb")

    @Test
    fun renderHundredActiveAssistantUpdates() = benchmark("compose_100", "turn_compose_active")

    @OptIn(androidx.benchmark.macro.ExperimentalMetricApi::class)
    private fun benchmark(workload: String, vararg sections: String) {
        val target = requireNotNull(InstrumentationRegistry.getArguments().getString("targetAppId"))
        rule.measureRepeated(
            packageName = target,
            metrics = listOf(TurnWorkloadMetric(workload, sections.toList())) +
                if (workload == "compose_100") listOf(FrameTimingMetric()) else emptyList(),
            compilationMode = CompilationMode.Full(),
            startupMode = StartupMode.COLD,
            iterations = 10,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait(Intent().apply {
                component = ComponentName(target, "net.weero.measix.pilot.benchmark.TurnWorkloadActivity")
                putExtra("workload", workload)
            })
            assertTrue("Workload failed to finish: $workload", device.wait(Until.hasObject(By.text("done:$workload")), 120_000))
        }
    }
}
