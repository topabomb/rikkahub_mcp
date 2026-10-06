package me.rerere.common.configuration

import java.nio.ByteBuffer
import java.util.Base64

/** A reversible durable identity namespace which can never be admitted as a platform principal. */
object RetiredLocalEnterpriseIdentity {
    private const val PREFIX = "retired-local."

    fun isReserved(deploymentId: String): Boolean = deploymentId.startsWith(PREFIX)

    fun encode(sourceNamespace: String, deploymentId: String): String {
        require(sourceNamespace.matches(Regex("local:[A-Za-z0-9._-]{1,128}"))) { "invalid_retired_local_source" }
        require(deploymentId.matches(Regex("[A-Za-z0-9._-]{1,256}"))) { "invalid_retired_local_deployment" }
        val source = sourceNamespace.toByteArray(Charsets.UTF_8)
        val deployment = deploymentId.toByteArray(Charsets.UTF_8)
        val framed = ByteBuffer.allocate(8 + source.size + deployment.size)
            .putInt(source.size).put(source).putInt(deployment.size).put(deployment).array()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(framed)
    }

    fun decode(value: String): Pair<String, String> {
        require(isReserved(value)) { "retired_local_identity_required" }
        require(value.length <= 768) { "invalid_retired_local_identity" }
        val buffer = ByteBuffer.wrap(Base64.getUrlDecoder().decode(value.removePrefix(PREFIX)))
        fun read(): String {
            require(buffer.remaining() >= 4) { "invalid_retired_local_identity" }
            val size = buffer.int
            require(size in 1..256 && size <= buffer.remaining()) { "invalid_retired_local_identity" }
            val bytes = ByteArray(size).also { buffer.get(it) }
            return bytes.toString(Charsets.UTF_8).also {
                require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "invalid_retired_local_identity" }
            }
        }
        val source = read()
        val deployment = read()
        require(!buffer.hasRemaining() && encode(source, deployment) == value) { "invalid_retired_local_identity" }
        return source to deployment
    }
}
