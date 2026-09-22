package net.weero.measix.pilot.service

import net.weero.measix.pilot.data.enterprise.PlatformBudgetCapability
import net.weero.measix.pilot.data.enterprise.PlatformBudgetCapabilityView
import net.weero.measix.pilot.data.enterprise.PlatformBudgetLimitState
import net.weero.measix.pilot.data.enterprise.PlatformBudgetMode
import net.weero.measix.pilot.data.enterprise.PlatformBudgetPeriod
import net.weero.measix.pilot.data.enterprise.PlatformBudgetSource
import net.weero.measix.pilot.data.enterprise.PlatformBudgetStatus
import net.weero.measix.pilot.data.enterprise.PlatformMeterQuantity
import net.weero.measix.pilot.data.enterprise.PlatformUsageCompleteness
import net.weero.measix.pilot.data.enterprise.PlatformUsageMeter
import net.weero.measix.pilot.data.enterprise.PlatformUserBudgetView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnterpriseBudgetProjectionTest {
    @Test
    fun `limited capability selects the most constrained limit without aggregating meters`() {
        val requestLimit = limit(
            period = PlatformBudgetPeriod.DAY,
            meter = PlatformUsageMeter.REQUESTS,
            limit = "1000",
            used = "830",
            reserved = "50",
            remaining = "120",
        )
        val tokenLimit = limit(
            period = PlatformBudgetPeriod.DAY,
            meter = PlatformUsageMeter.TOTAL_TOKENS,
            limit = "50000000",
            used = "6537538",
            reserved = "0",
            remaining = "43462462",
        )
        val model = capability(
            capability = PlatformBudgetCapability.MODEL,
            mode = PlatformBudgetMode.LIMITED,
            limits = listOf(tokenLimit, requestLimit),
            inFlightRequests = 3,
        )

        val projection = projectEnterpriseBudget(budget(model))
        val item = projection.items.single { it.capability == EnterpriseBudgetCapabilityKind.MODEL }

        assertEquals(EnterpriseBudgetAvailability.NEAR_LIMIT, item.availability)
        assertEquals(EnterpriseBudgetMeterKind.REQUESTS, item.primaryLimit?.meter)
        assertEquals(0.88f, item.primaryLimit!!.occupiedFraction, 0.0001f)
        assertEquals(1, item.additionalLimitCount)
        assertEquals(3L, projection.totalInFlightRequests)
    }

    @Test
    fun `unlimited capability keeps only its two most meaningful cumulative meters`() {
        val model = capability(
            capability = PlatformBudgetCapability.MODEL,
            usageMeters = listOf(
                meter(PlatformUsageMeter.INPUT_TOKENS, "6427000", PlatformUsageCompleteness.UNKNOWN),
                meter(PlatformUsageMeter.REQUESTS, "108"),
                meter(PlatformUsageMeter.TOTAL_TOKENS, "6649841"),
            ),
        )

        val item = projectEnterpriseBudget(budget(model)).items
            .single { it.capability == EnterpriseBudgetCapabilityKind.MODEL }

        assertEquals(EnterpriseBudgetAvailability.UNLIMITED, item.availability)
        assertEquals(
            listOf(EnterpriseBudgetMeterKind.TOTAL_TOKENS, EnterpriseBudgetMeterKind.REQUESTS),
            item.usageSummary.map(EnterpriseBudgetUsageUiModel::meter),
        )
        assertTrue(item.primaryLimit == null)
    }

    @Test
    fun `limited capability without a limit is explicitly unavailable`() {
        val tts = capability(
            capability = PlatformBudgetCapability.TTS,
            mode = PlatformBudgetMode.LIMITED,
            limits = emptyList(),
        )

        val item = projectEnterpriseBudget(budget(tts)).items
            .single { it.capability == EnterpriseBudgetCapabilityKind.TTS }

        assertEquals(EnterpriseBudgetAvailability.UNAVAILABLE, item.availability)
        assertTrue(item.usageSummary.isEmpty())
    }

    private fun budget(replacement: PlatformBudgetCapabilityView): PlatformUserBudgetView = PlatformUserBudgetView(
        userId = "usr_123e4567-e89b-42d3-a456-426614174000",
        timezone = "Asia/Shanghai",
        items = PlatformBudgetCapability.entries.map { capability ->
            if (capability == replacement.capability) replacement else capability(capability)
        },
        asOf = timestamp,
    )

    private fun capability(
        capability: PlatformBudgetCapability,
        mode: PlatformBudgetMode = PlatformBudgetMode.UNLIMITED,
        limits: List<PlatformBudgetLimitState> = emptyList(),
        usageMeters: List<PlatformMeterQuantity> = emptyList(),
        inFlightRequests: Long = 0,
    ) = PlatformBudgetCapabilityView(
        capability = capability,
        mode = mode,
        source = PlatformBudgetSource.DEFAULT,
        revision = 0,
        effectiveFrom = timestamp,
        asOf = timestamp,
        inFlightRequests = inFlightRequests,
        limits = limits,
        usageMeters = usageMeters,
        status = PlatformBudgetStatus.AVAILABLE,
    )

    private fun limit(
        period: PlatformBudgetPeriod,
        meter: PlatformUsageMeter,
        limit: String,
        used: String,
        reserved: String,
        remaining: String,
    ) = PlatformBudgetLimitState(
        period = period,
        meter = meter,
        limit = limit,
        used = used,
        reserved = reserved,
        remaining = remaining,
        overage = "0",
        scopeStart = "2026-09-22T00:00:00Z",
        resetAt = "2026-09-23T00:00:00Z",
    )

    private fun meter(
        meter: PlatformUsageMeter,
        quantity: String,
        completeness: PlatformUsageCompleteness = PlatformUsageCompleteness.EXACT,
    ) = PlatformMeterQuantity(meter, quantity, completeness)

    private companion object {
        const val timestamp = "2026-09-22T06:00:00Z"
    }
}
