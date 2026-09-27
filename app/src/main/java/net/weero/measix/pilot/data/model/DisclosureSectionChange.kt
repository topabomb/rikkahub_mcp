package net.weero.measix.pilot.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.common.configuration.ConfigurationReference

/** Difference from the facts visible to the model, including confirmed tool effects, at admission. */
@Serializable
internal data class DisclosureSectionChange(
    val addedIds: List<String> = emptyList(),
    val updatedBefore: List<JsonArray> = emptyList(),
    val removedBefore: List<JsonArray> = emptyList(),
    val attributesBefore: Map<String, JsonPrimitive> = emptyMap(),
    /** Present only when surviving seed rows changed their relative order. */
    val orderBefore: List<String>? = null,
) {
    init {
        val ids = addedIds + (updatedBefore + removedBefore).map { row ->
            require(row.size in 2..3 && row.all { it is JsonPrimitive }) { "invalid_disclosure_change_row" }
            (row[0] as JsonPrimitive).content
        }
        require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) { "invalid_disclosure_change_ids" }
        require(orderBefore == null || orderBefore.size >= 2 && orderBefore.all { it.isNotBlank() } &&
            orderBefore.distinct().size == orderBefore.size) { "invalid_disclosure_change_order" }
        require(ids.isNotEmpty() || attributesBefore.isNotEmpty() || orderBefore != null) { "empty_disclosure_change" }
    }

    fun validateSection(section: DisclosureSection) {
        val identities = addedIds + (updatedBefore + removedBefore).map { (it[0] as JsonPrimitive).content } +
            orderBefore.orEmpty()
        require(identities.all { id ->
            if (section == DisclosureSection.MEMORY) id.toIntOrNull()?.let { it > 0 && it.toString() == id } == true
            else ConfigurationReference.parse(id).toString() == id
        }) { "invalid_disclosure_change_identity" }
        val allowedAttributes = when (section) {
            DisclosureSection.MEMORY -> setOf("enabled", "scope")
            DisclosureSection.SUB_ASSISTANTS -> setOf("mode")
            DisclosureSection.ENTERPRISE_MEMORY_SEEDS -> emptySet()
        }
        require(attributesBefore.keys.all { it in allowedAttributes }) { "invalid_disclosure_change_attribute" }
        attributesBefore.forEach { (key, value) ->
            require(if (key == "enabled") !value.isString && value.booleanOrNull != null else value.isString) {
                "invalid_disclosure_change_attribute_value"
            }
        }
        require(orderBefore == null || section == DisclosureSection.ENTERPRISE_MEMORY_SEEDS) {
            "invalid_disclosure_change_order_section"
        }
        (updatedBefore + removedBefore).forEach { row ->
            require(row.size == if (section == DisclosureSection.SUB_ASSISTANTS) 3 else 2) {
                "invalid_disclosure_change_row_shape"
            }
            val id = row[0] as JsonPrimitive
            require(if (section == DisclosureSection.MEMORY) !id.isString && (id.intOrNull ?: 0) > 0 else id.isString) {
                "invalid_disclosure_change_row_identity"
            }
            require(row.drop(1).all { (it as JsonPrimitive).isString }) { "invalid_disclosure_change_row_text" }
        }
    }
}
