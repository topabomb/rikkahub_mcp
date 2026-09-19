package net.weero.measix.pilot.data.enterprise

import java.time.Instant
import kotlinx.serialization.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import net.weero.measix.pilot.utils.StrictJsonValue

/** Current Core objects omit optional fields; explicit null is never an absent field. */
internal object PlatformWireCodec {
    val json = Json { encodeDefaults = true; explicitNulls = false }

    inline fun <reified T> decode(raw: String): T {
        val element = StrictJsonValue.parse(raw, EnterprisePackageCodec.MAX_BYTES)
        requireTypes(element, serializer<T>().descriptor)
        return json.decodeFromJsonElement(element)
    }

    fun requireTypes(element: JsonElement, descriptor: SerialDescriptor) {
        if (element == JsonNull) throw EnterpriseConfigurationException("null_platform_field")
        val primitive = element as? JsonPrimitive
        val valid = when (descriptor.kind) {
            PrimitiveKind.STRING, SerialKind.ENUM -> primitive?.isString == true
            PrimitiveKind.BOOLEAN -> primitive?.let { !it.isString && it.booleanOrNull != null } == true
            PrimitiveKind.LONG, PrimitiveKind.INT -> primitive?.let { !it.isString && it.longOrNull != null } == true
            PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> primitive?.let { !it.isString && it.doubleOrNull?.isFinite() == true } == true
            StructureKind.LIST -> element is JsonArray && element.all { requireTypes(it, descriptor.getElementDescriptor(0)); true }
            StructureKind.CLASS -> element is JsonObject && element.all { (name, child) ->
                val index = descriptor.getElementIndex(name)
                if (index < 0) throw EnterpriseConfigurationException("unknown_platform_field")
                requireTypes(child, descriptor.getElementDescriptor(index))
                true
            }
            else -> false
        }
        if (!valid) throw EnterpriseConfigurationException("invalid_platform_field_type")
    }
}

internal fun platformWireTimestamp(value: String): Boolean = try {
    Instant.parse(value)
    true
} catch (_: java.time.format.DateTimeParseException) {
    false
}
