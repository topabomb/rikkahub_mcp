package net.weero.measix.pilot.data.enterprise

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import me.rerere.common.configuration.ConfigurationReference
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnterpriseSpeechTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = Instant.parse("2029-01-01T00:00:00Z").toEpochMilli()
    private fun audio() = java.io.File("src/main/assets/enterprise/speech-example.mp3").readBytes()

    @Test fun `installed service consumes encoded speech and multipart recording through the real decoders`() = runBlocking {
        val (source, sessions, access) = installed()
        val lease = sessions.captureBindings(access)
        val packet = requireNotNull(source.candidate(access.scope)).packet
        val local = LocalEnterpriseSpeechService(source, sessions, ::audio)
        val transport = EnterpriseSpeechTransport(local::execute, calls = { error("example must not open a network Call") })
        fun target(id: String) = target(access, lease.version, lease.binding(id))
        try {
            val tts = packet.configuration.tts.first()
            assertArrayEquals(audio(), transport.synthesize(target(tts.id), tts, "朗读测试" ).audioData)
            val asr = packet.configuration.asr.first()
            val recording = temporary.newFile("recording.wav")
            val wave = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
            wave.put("RIFF".toByteArray()).putInt(40).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(24_000).putInt(48_000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(4).putShort(100).putShort(-100)
            recording.writeBytes(wave.array())
            assertTrue(transport.transcribe(target(asr.id), asr, recording).contains("4 字节录音"))
            recording.writeText("not recorded audio")
            try { transport.transcribe(target(asr.id), asr, recording); fail("Invalid file accepted") }
            catch (expected: IllegalStateException) { assertEquals("enterprise_speech_http_400", expected.message) }
        } finally { lease.release() }
    }

    @Test fun `generation barrier rejects before reading a stale recording request and remains typed`() = runBlocking {
        val (source, sessions, access) = installed()
        val lease = sessions.captureBindings(access)
        try {
            val packet = requireNotNull(source.candidate(access.scope)).packet
            val tts = packet.configuration.tts.first()
            source.importPackage(EnterprisePackageCodec.encode(packet.copy(configuration = packet.configuration.copy(generation = packet.configuration.generation + 1))).inputStream())
            val target = target(access, lease.version, lease.binding(tts.id))
            val writes = AtomicInteger()
            val body = object : RequestBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun writeTo(sink: BufferedSink) { writes.incrementAndGet(); error("stale body forwarded") }
            }
            val local = LocalEnterpriseSpeechService(source, sessions) { error("stale synthesis started") }
            local.execute(target, target.request("/audio/speech", body)).use { response ->
                assertEquals(428, response.code)
                assertEquals("application/problem+json", response.body.contentType().toString())
                val bodyText = response.body.string()
                val value = net.weero.measix.pilot.utils.StrictJsonValue.parse(bodyText, 16 * 1024 * 1024) as kotlinx.serialization.json.JsonObject
                assertEquals(kotlinx.serialization.json.JsonPrimitive("about:blank"), value["type"])
                assertEquals(kotlinx.serialization.json.JsonPrimitive("Managed snapshot required"), value["title"])
                assertEquals(kotlinx.serialization.json.JsonPrimitive(428), value["status"])
                assertEquals(packet.configuration.generation + 1, ManagedSnapshotRequired.parse(bodyText).targetGeneration)
            }
            assertEquals(0, writes.get())
            val transport = EnterpriseSpeechTransport(local::execute, calls = { error("network forbidden") })
            try { transport.synthesize(target, tts, "no replay"); fail("Barrier ignored") }
            catch (barrier: ManagedSnapshotRequired) { assertEquals(packet.configuration.generation + 1, barrier.targetGeneration) }
        } finally { lease.release() }
    }

    @Test fun `private HTTP carries the original runtime identity and does not replay a barrier or redirect`() = runBlocking {
        val packet = exampleEnterprisePackage()
        val tts = packet.configuration.tts.first()
        val access = RealmAccess.Enterprise(packet.identity.scope, "session-test")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        val redirects = AtomicInteger()
        val seen = java.util.concurrent.atomic.AtomicReference<com.sun.net.httpserver.Headers>()
        val status = AtomicInteger(428)
        val problem = java.util.concurrent.atomic.AtomicReference(requireNotNull(javaClass.getResourceAsStream("/contracts/runtime/managed-snapshot-required.json")).bufferedReader().use { it.readText() })
        server.createContext("/speech") { exchange ->
            requests.incrementAndGet()
            seen.set(exchange.requestHeaders)
            exchange.requestBody.use { it.readBytes() }
            val bytes = problem.get().toByteArray()
            if (status.get() == 302) exchange.responseHeaders.set("Location", "/redirected")
            exchange.sendResponseHeaders(status.get(), bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.createContext("/redirected") { exchange -> redirects.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close() }
        server.start()
        try {
            val binding = EnterpriseRuntimeBinding(tts.id, EnterpriseRuntimeProtocol.OPENAI_TTS,
                "http://127.0.0.1:${server.address.port}/speech", "test-credential")
            val target = target(access, EnterpriseAppliedVersion("r", 3, "c", "b"), binding)
            val transport = EnterpriseSpeechTransport(local = { _, _ -> error("private source became example") })
            try { transport.synthesize(target, tts, "first"); fail("Expected barrier") }
            catch (barrier: ManagedSnapshotRequired) { assertEquals(42L, barrier.targetGeneration) }
            assertEquals(1, requests.get())
            assertEquals("3", seen.get().getFirst("X-Measix-Managed-Generation"))
            assertEquals(target.interactionId, seen.get().getFirst("X-Measix-Interaction-Id"))
            assertEquals("Bearer test-credential", seen.get().getFirst("Authorization"))
            assertNull(seen.get().getFirst("X-Measix-Resource-Id"))
            problem.set(problem.get().replace("\"forwarded\": false", "\"forwarded\": false, \"forwarded\": true"))
            try { transport.synthesize(target, tts, "second"); fail("Duplicate field accepted") }
            catch (expected: IllegalStateException) { assertFalse(expected is ManagedSnapshotRequired) }
            status.set(302)
            try { transport.synthesize(target, tts, "third"); fail("Redirect accepted") }
            catch (expected: IllegalStateException) { assertEquals("enterprise_speech_http_302", expected.message) }
            assertEquals(3, requests.get())
            assertEquals(0, redirects.get())
        } finally { server.stop(0) }
    }

    private fun target(access: RealmAccess.Enterprise, version: EnterpriseAppliedVersion, binding: EnterpriseRuntimeBinding) =
        EnterpriseSpeechTarget(access, ConfigurationReference.Enterprise(access.scope.authority, binding.resourceId), binding, version, "int_${Uuid.random()}")

    private suspend fun installed(): Triple<LocalEnterpriseSource, EnterpriseSessionController, RealmAccess.Enterprise> {
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder())) { now }
        val sourceRoot = temporary.newFolder()
        val source = LocalEnterpriseSource(
            { requireNotNull(javaClass.getResourceAsStream("/${LocalEnterpriseSource.EXAMPLE_ASSET}")) }, sessions,
            LocalEnrollmentAuthority(sourceRoot, { now }),
            { requireNotNull(javaClass.getResourceAsStream("/${LocalEnterpriseSource.IDENTITY_ASSET}")) },
            LocalEnterpriseConfigurationStore(sourceRoot), { now },
        )
        val session = requireNotNull(source.enrollExample().manifest.session)
        return Triple(source, sessions, RealmAccess.Enterprise(session.identity.scope, session.id))
    }
}
