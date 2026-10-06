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
import kotlinx.serialization.json.encodeToJsonElement
import net.weero.measix.pilot.utils.StrictJsonValue

/** Core responses may add fields; only the Android projection is interpreted and validated. */
internal object PlatformWireCodec {
    val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }
    private val commandJson = Json(json) { ignoreUnknownKeys = false }
    private val commandSchemas = setOf(
        PlatformEnrollmentExchangeRequest.serializer().descriptor.serialName,
        PlatformRefreshRequest.serializer().descriptor.serialName,
        PlatformManagedAppliedReport.serializer().descriptor.serialName,
        PlatformWorkspaceFileMutation.serializer().descriptor.serialName,
    )

    inline fun <reified T> decode(raw: String): T {
        var element = StrictJsonValue.parse(raw, EnterpriseConfigurationCodec.MAX_BYTES)
        if (T::class == PlatformManagedSnapshot::class) {
            requireSnapshotSchema(element)
            if ((element as JsonObject)["schemaVersion"]?.let { (it as JsonPrimitive).longOrNull } == 4L) {
                // Project from the validated v4 DTO so ignored fields cannot acquire
                // v5 meaning when the legacy bindings are adapted.
                requireTypes(element, serializer<PlatformManagedSnapshotV4>().descriptor, requireComplete = true)
                val projection = json.encodeToJsonElement(json.decodeFromJsonElement<PlatformManagedSnapshotV4>(element)) as JsonObject
                element = JsonObject(projection.toMutableMap().apply {
                    put("mcp", JsonArray((projection["mcp"] as JsonArray).map { server ->
                        JsonObject((server as JsonObject) + mapOf(
                            "toolAccessMode" to JsonPrimitive("ALL"), "allowedTools" to JsonArray(emptyList()),
                        ))
                    }))
                    put("assistants", JsonArray((projection["assistants"] as JsonArray).map { assistant ->
                        val fields = (assistant as JsonObject).toMutableMap()
                        val ids = (fields.remove("mcpServerIds") as JsonArray).distinct()
                        fields["mcpBindings"] = JsonArray(ids.map { id -> JsonObject(mapOf(
                            "mcpServerId" to id, "toolSelection" to JsonPrimitive("ALL"),
                            "toolNames" to JsonArray(emptyList()),
                        )) })
                        JsonObject(fields)
                    }))
                })
            }
        }
        val descriptor = serializer<T>().descriptor
        val response = descriptor.serialName !in commandSchemas
        requireTypes(element, descriptor, allowExtensions = response, requireComplete = true)
        return (if (response) json else commandJson).decodeFromJsonElement(element)
    }

    fun requireSnapshotSchema(element: JsonElement) {
        val version = (element as? JsonObject)?.get("schemaVersion") as? JsonPrimitive
        val schema = version?.takeUnless { it.isString }?.longOrNull
        if (schema == null || schema <= 0) {
            throw EnterpriseConfigurationException("invalid_platform_snapshot_schema_version")
        }
        PlatformSnapshotCompatibility.requireSupportedVersion(schema)
    }

    // Legacy workspace recognition validates supplied types and owns its smaller required-field set.
    fun requireTypes(element: JsonElement, descriptor: SerialDescriptor, path: String = "$",
        allowExtensions: Boolean = true, requireComplete: Boolean = false) {
        if (element == JsonNull) throw EnterpriseConfigurationException("null_platform_field", path)
        val primitive = element as? JsonPrimitive
        val valid = when (descriptor.kind) {
            PrimitiveKind.STRING -> primitive?.isString == true
            SerialKind.ENUM -> {
                if (primitive?.isString != true) false
                else if (descriptor.getElementIndex(primitive.content) < 0) {
                    throw EnterpriseConfigurationException("invalid_platform_field_value", path)
                } else true
            }
            PrimitiveKind.BOOLEAN -> primitive?.let { !it.isString && it.booleanOrNull != null } == true
            PrimitiveKind.LONG, PrimitiveKind.INT -> primitive?.let { !it.isString && it.longOrNull != null } == true
            PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> primitive?.let { !it.isString && it.doubleOrNull?.isFinite() == true } == true
            StructureKind.LIST -> element is JsonArray && element.withIndex().all { (index, child) ->
                requireTypes(child, descriptor.getElementDescriptor(0), "$path[$index]", allowExtensions, requireComplete)
                true
            }
            StructureKind.CLASS -> element is JsonObject && run {
                if (requireComplete) for (index in 0 until descriptor.elementsCount) {
                    val name = descriptor.getElementName(index)
                    if (!descriptor.isElementOptional(index) && name !in element) {
                        throw EnterpriseConfigurationException("missing_platform_field", "$path.$name")
                    }
                }
                element.forEach { (name, child) ->
                    val index = descriptor.getElementIndex(name)
                    if (index < 0) {
                        if (!allowExtensions) throw EnterpriseConfigurationException("unknown_platform_field", path)
                    } else requireTypes(child, descriptor.getElementDescriptor(index), "$path.$name", allowExtensions, requireComplete)
                }
                true
            }
            else -> false
        }
        if (!valid) throw EnterpriseConfigurationException("invalid_platform_field_type", path)
    }
}

internal fun platformWireTimestamp(value: String): Boolean = try {
    Instant.parse(value)
    true
} catch (_: java.time.format.DateTimeParseException) {
    false
}
