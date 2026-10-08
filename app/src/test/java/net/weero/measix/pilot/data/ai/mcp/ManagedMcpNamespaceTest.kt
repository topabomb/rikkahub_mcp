package net.weero.measix.pilot.data.ai.mcp

import java.math.BigInteger
import java.security.MessageDigest
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedMcpNamespaceTest {
    @Test fun `fixed Base62 payload preserves all original unsigned digest bits`() {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val radix = BigInteger.valueOf(62)
        val references = (0..31).map { ConfigurationReference.Enterprise(EnterpriseAuthority("namespace-test"), "mcp_$it") }
        references.forEach { reference ->
            val namespace = managedMcpNamespace(reference)
            assertTrue(namespace, namespace.matches(Regex("e[0-9a-zA-Z]{11}")))
            val decoded = namespace.drop(1).fold(BigInteger.ZERO) { number, character ->
                number.multiply(radix).add(BigInteger.valueOf(alphabet.indexOf(character).toLong()))
            }
            val original = MessageDigest.getInstance("SHA-256").digest(reference.toString().toByteArray(Charsets.UTF_8))
            assertEquals(BigInteger(1, original.copyOfRange(0, 8)), decoded)
            assertEquals(namespace, managedMcpNamespace(reference.copy()))
            assertNotEquals(namespace, managedMcpNamespace(reference.copy(authority = EnterpriseAuthority("other-deployment"))))
        }
        assertEquals(references.size, references.map(::managedMcpNamespace).toSet().size)
    }
}
