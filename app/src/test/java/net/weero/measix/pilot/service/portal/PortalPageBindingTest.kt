package net.weero.measix.pilot.service.portal

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PortalPageBindingTest {
    private val original = "a".repeat(32)
    private val replacement = "b".repeat(32)
    private fun envelope(instance: String, payload: String? = null) = buildJsonObject {
        put("instance", instance)
        put("payload", payload?.let(::JsonPrimitive) ?: JsonNull)
    }.toString()

    @Test
    fun onlyTheBoundDocumentCanDeliverBusinessMessages() {
        val binding = PortalPageBinding()
        assertThrows(PortalFailure::class.java) { binding.receive(envelope(original, "before bootstrap")) }
        assertNull(binding.receive(envelope(original)))
        assertEquals("original request", binding.receive(envelope(original, "original request")))
        assertThrows(PortalFailure::class.java) { binding.receive(envelope(replacement, "replacement request")) }
        assertThrows(PortalFailure::class.java) { binding.receive(envelope(replacement)) }
        assertThrows(PortalFailure::class.java) { binding.receive(envelope(original)) }
    }

    @Test
    fun unwrappedOrMalformedTransportCannotBecomeABusinessRequest() {
        val binding = PortalPageBinding()
        assertNull(binding.receive(envelope(original)))
        listOf("{}", "[]", "null", "{\"instance\":\"$original\",\"payload\":{}}",
            "{\"instance\":\"$original\",\"instance\":\"$replacement\",\"payload\":null}",
            "{\"instance\":\"$original\",\"payload\":\"request\",\"extra\":true}").forEach {
            assertThrows(PortalFailure::class.java) { binding.receive(it) }
        }
    }

    @Test
    fun transportRejectsOversizedEnvelopesBeforeParsing() {
        val failure = assertThrows(PortalFailure::class.java) {
            PortalPageBinding().receive(" ".repeat(PortalProtocol.MAX_REQUEST_BYTES * 6 + 129))
        }
        assertEquals("resource_limit", failure.code)
    }
}
