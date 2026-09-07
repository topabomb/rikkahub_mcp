package net.weero.measix.pilot.testkit

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Deterministic clock for critical state tests. Advance explicitly at test-controlled points;
 * production seams that accept a clock must never see wall-clock drift in tests.
 */
class TestClock(start: Instant = Instant.fromEpochSeconds(1_700_000_000)) : Clock {
    private var current: Instant = start

    override fun now(): Instant = current

    fun advanceBySeconds(seconds: Long) {
        current = Instant.fromEpochSeconds(current.epochSeconds + seconds)
    }

    fun set(instant: Instant) {
        current = instant
    }
}

/**
 * Deterministic identity source. Ids are derived from stable labels so assertions and fixtures
 * read as `turn-1` / `step-1` / `tool-1` instead of random UUIDs.
 */
class TestIdGenerator {
    private val assigned = LinkedHashMap<String, Uuid>()
    private var counter = 0

    /** Stable Uuid for [label]; the same label always yields the same id. */
    fun id(label: String): Uuid = assigned.getOrPut(label) { derive(label) }

    private fun derive(label: String): Uuid {
        val index = ++counter
        val hex = index.toString(16).padStart(32, '0')
        return Uuid.parse(
            "${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20)}",
        )
    }
}
