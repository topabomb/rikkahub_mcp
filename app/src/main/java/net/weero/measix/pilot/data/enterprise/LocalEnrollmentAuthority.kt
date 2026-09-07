package net.weero.measix.pilot.data.enterprise

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.uuid.Uuid

@Serializable
private data class LocalEnrollmentTicket(
    val digest: String,
    val identity: EnterpriseIdentity,
    val expiresAtMillis: Long,
    val consumed: Boolean,
)

@Serializable
private data class LocalEnrollmentLedger(val schemaVersion: Int, val tickets: List<LocalEnrollmentTicket>)

/** Simulated service authority for one-use codes. It never owns or publishes the Android session. */
internal class LocalEnrollmentAuthority(
    private val root: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val beforeCommit: () -> Unit = {},
) {
    private val mutex = Mutex()
    private val file get() = AtomicFile(File(root, "enrollments.json"))

    suspend fun issue(identity: EnterpriseIdentity): EnrollmentMaterial.LocalExample = mutex.withLock {
        EnterprisePackageCodec.validateIdentity(identity)
        currentCoroutineContext().ensureActive()
        val material = withContext(Dispatchers.IO + NonCancellable) {
            val now = nowMillis()
            val active = read().tickets.filter { it.expiresAtMillis > now }
            if (active.size >= MAX_TICKETS) fail("local_enrollment_limit")
            val code = Uuid.random().toString()
            val expiry = Math.addExact(now, LIFETIME_MILLIS)
            write(LocalEnrollmentLedger(1, active + LocalEnrollmentTicket(digest(code), identity, expiry, false)))
            EnrollmentMaterial.LocalExample(identity.authority.sourceNamespace, identity.authority.deploymentId, code, Instant.ofEpochMilli(expiry))
        }
        currentCoroutineContext().ensureActive()
        material
    }

    /** Resolves the ticket's fixed principal without reserving or consuming the credential. */
    suspend fun resolveIdentity(material: EnrollmentMaterial.LocalExample): EnterpriseIdentity = mutex.withLock {
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            validatedTicket(read(), material).identity
        }
    }

    /** Session owner holds its admission lock before this lock. Consumption is never rolled back by a client failure. */
    suspend fun redeem(material: EnrollmentMaterial.LocalExample, expectedIdentity: EnterpriseIdentity): EnterpriseIdentity = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val identity = withContext(Dispatchers.IO + NonCancellable) {
            val ledger = read()
            val ticket = validatedTicket(ledger, material)
            if (ticket.identity != expectedIdentity) fail("enterprise_enrollment_rejected")
            write(ledger.copy(tickets = ledger.tickets.map { if (it.digest == ticket.digest) it.copy(consumed = true) else it }))
            ticket.identity
        }
        currentCoroutineContext().ensureActive()
        identity
    }

    private fun validatedTicket(ledger: LocalEnrollmentLedger, material: EnrollmentMaterial.LocalExample): LocalEnrollmentTicket {
        val codeDigest = digest(material.code)
        val ticket = ledger.tickets.singleOrNull { it.digest == codeDigest }
            ?: fail("enterprise_enrollment_rejected")
        if (material.sourceNamespace != ticket.identity.authority.sourceNamespace ||
            material.deploymentId != ticket.identity.authority.deploymentId) fail("enterprise_enrollment_rejected")
        if (ticket.expiresAtMillis <= nowMillis()) fail("enterprise_enrollment_expired")
        if (ticket.consumed) fail("enterprise_enrollment_consumed")
        return ticket
    }

    private fun read(): LocalEnrollmentLedger {
        val atomic = file
        if (!atomic.baseFile.exists() && !File(root, "enrollments.json.bak").exists() && !File(root, "enrollments.json.new").exists()) {
            return LocalEnrollmentLedger(1, emptyList())
        }
        return try {
            val bytes = atomic.openRead().use(::readBounded)
            if (bytes.size > MAX_LEDGER_BYTES) fail("local_enrollment_store_invalid")
            val text = bytes.toString(Charsets.UTF_8)
            if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) fail("local_enrollment_store_invalid")
            EnterprisePackageCodec.json.decodeFromString<LocalEnrollmentLedger>(text).also { ledger ->
                if (ledger.schemaVersion != 1 || ledger.tickets.size > MAX_TICKETS ||
                    ledger.tickets.map { it.digest }.distinct().size != ledger.tickets.size) fail("local_enrollment_store_invalid")
                ledger.tickets.forEach { ticket ->
                    if (!Regex("[0-9a-f]{64}").matches(ticket.digest)) fail("local_enrollment_store_invalid")
                    EnterprisePackageCodec.validateIdentity(ticket.identity)
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            fail("local_enrollment_store_invalid")
        }
    }

    private fun write(ledger: LocalEnrollmentLedger) {
        val bytes = EnterprisePackageCodec.json.encodeToString(ledger).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_LEDGER_BYTES) fail("local_enrollment_store_invalid")
        if (!root.isDirectory && !root.mkdirs()) fail("local_enrollment_store_write_failed")
        var stream: FileOutputStream? = null
        val atomic = file
        try {
            beforeCommit()
            stream = atomic.startWrite()
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
            if (!atomic.baseFile.inputStream().use(::readBounded).contentEquals(bytes)) {
                fail("local_enrollment_store_write_failed")
            }
        } catch (error: Exception) {
            atomic.failWrite(stream)
            if (error is CancellationException) throw error
            fail("local_enrollment_store_write_failed")
        }
    }

    private fun digest(code: String): String = MessageDigest.getInstance("SHA-256")
        .digest(code.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun readBounded(input: InputStream): ByteArray {
        val buffer = ByteArray(MAX_LEDGER_BYTES + 1)
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            count += read
        }
        if (count > MAX_LEDGER_BYTES) fail("local_enrollment_store_invalid")
        return buffer.copyOf(count)
    }

    private fun fail(reason: String): Nothing = throw EnterpriseConfigurationException(reason)

    companion object {
        private const val LIFETIME_MILLIS = 10L * 60 * 1000
        private const val MAX_TICKETS = 128
        private const val MAX_LEDGER_BYTES = 512 * 1024
    }
}
