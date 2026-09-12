package net.weero.measix.pilot.service.portal

import kotlinx.serialization.json.*

/** A JS Document must bind once before it can send business messages through its original host. */
internal class PortalPageBinding {
    private var instance: String? = null

    fun receive(raw: String): String? {
        // JSON escaping can expand one request character to six bytes; the business decoder keeps its own limit.
        val envelope = PortalProtocol.decode(raw, PortalProtocol.MAX_REQUEST_BYTES * 6 + 128)
        if (envelope.keys != setOf("instance", "payload")) throw PortalFailure("document_replaced")
        val identity = envelope["instance"] as? JsonPrimitive ?: throw PortalFailure("document_replaced")
        if (!identity.isString || !identity.content.matches(Regex("[0-9a-f]{32}"))) throw PortalFailure("document_replaced")
        val payload = envelope["payload"]
        if (payload == JsonNull) {
            if (instance != null) throw PortalFailure("document_replaced")
            instance = identity.content
            return null
        }
        if (instance != identity.content || payload !is JsonPrimitive || !payload.isString) {
            throw PortalFailure("document_replaced")
        }
        return payload.content
    }
}
