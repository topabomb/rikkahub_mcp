package net.weero.measix.pilot.data.enterprise

import net.weero.measix.pilot.utils.StrictJsonValue

/** A verified pre-forward generation barrier must never become an automatic tool replay. */
internal class ManagedSnapshotRequired(val targetGeneration: Long, val requestId: String) :
    IllegalStateException("managed_snapshot_required") {
    companion object {
        fun find(error: Throwable): ManagedSnapshotRequired? = generateSequence(error) { it.cause }
            .filterIsInstance<ManagedSnapshotRequired>().firstOrNull()

        fun parse(body: String): ManagedSnapshotRequired {
            val value = StrictJsonValue.parse(body, 16 * 1024 * 1024) as? kotlinx.serialization.json.JsonObject
                ?: error("invalid_managed_snapshot_barrier")
            fun string(key: String) = (value[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            val generation = (value["targetManagedGeneration"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeUnless { it.isString }?.content?.toLongOrNull()
            check(string("code") == "managed_snapshot_required" && generation != null && generation > 0 &&
                value["forwarded"] == kotlinx.serialization.json.JsonPrimitive(false) &&
                string("requestId")?.matches(Regex("req_[A-Za-z0-9_-]{1,128}")) == true) { "invalid_managed_snapshot_barrier" }
            return ManagedSnapshotRequired(requireNotNull(generation), requireNotNull(string("requestId")))
        }
    }
}
