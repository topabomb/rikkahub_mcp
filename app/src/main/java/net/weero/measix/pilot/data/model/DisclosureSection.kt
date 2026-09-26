package net.weero.measix.pilot.data.model

import kotlinx.serialization.Serializable

/** Each supplied section replaces its own state; absent sections retain their previous state. */
@Serializable
enum class DisclosureSection(val wireName: String) {
    MEMORY("memory"),
    SUB_ASSISTANTS("sub_assistants"),
    ENTERPRISE_MEMORY_SEEDS("enterprise_memory_seeds"),
}

@Serializable
enum class ContextAdmissionReason {
    INITIAL,
    EXTERNAL_STATE,
    BASELINE_RESTORE,
}

/** Only bindings installed by the application can claim these state-writing contracts. */
@Serializable
enum class DisclosureBuiltinTool(val section: DisclosureSection, val executionIdentity: String) {
    MEMORY(DisclosureSection.MEMORY, "measix.memory.v1"),
    ASSISTANT_MANAGE(DisclosureSection.SUB_ASSISTANTS, "measix.assistant-manage.v1"),
}
