package net.weero.measix.pilot.data.enterprise

import java.io.File
import java.time.Instant
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Test projection of the current Core contract. It deliberately has no private bindings or local source. */
internal data class EnterprisePackage(
    val identity: EnterpriseIdentity,
    val configuration: EnterpriseConfiguration,
)

internal fun enterpriseTestStore(
    root: File,
    credentialCipher: EnterpriseCredentialCipher = EnterpriseCredentialCipher {
        SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    },
    checkpoint: (EnterpriseStorageCheckpoint) -> Unit = {},
) = EnterpriseAppliedStore(root, credentialCipher, checkpoint)

private fun platformCase(name: String): String = requireNotNull(
    EnterprisePackage::class.java.getResourceAsStream("/contracts/platform/cases.json"),
).bufferedReader().use { reader ->
    Json.parseToJsonElement(reader.readText()).jsonArray
        .first { it.jsonObject.getValue("name").jsonPrimitive.content == name }
        .jsonObject.getValue("value").toString()
}

private fun basePlatformCandidate(): EnterpriseCandidate {
    val discovery = PlatformWireCodec.decode<PlatformDiscovery>(platformCase("discovery"))
    val connection = PlatformConnection("https://platform.test", discovery)
    val bootstrap = PlatformWireCodec.decode<PlatformBootstrap>(platformCase("bootstrap"))
    val identity = EnterpriseIdentity(connection.authority, bootstrap.deployment.name,
        bootstrap.user.userId, bootstrap.user.displayName)
    val snapshot = PlatformWireCodec.decode<PlatformManagedSnapshot>(platformCase("v4-full"))
    return PlatformSnapshotMapper.map(connection, identity, snapshot)
}

internal fun exampleEnterprisePackage(): EnterprisePackage = basePlatformCandidate().let {
    EnterprisePackage(it.identity, it.configuration)
}

internal fun platformCandidate(packet: EnterprisePackage): EnterpriseCandidate {
    val base = basePlatformCandidate()
    val execution = base.execution as EnterpriseExecution.Platform
    val discovery = execution.connection.discovery.copy(
        deploymentId = packet.identity.authority.deploymentId,
        deploymentName = packet.identity.enterpriseName,
    )
    return EnterpriseCandidate(packet.identity, packet.configuration,
        execution.copy(connection = PlatformConnection(execution.connection.origin, discovery)))
}

internal fun EnterprisePackage.toCandidate(): EnterpriseCandidate = platformCandidate(this)

internal suspend fun EnterpriseSessionController.enrollFixture(
    packet: EnterprisePackage,
    expiresAt: Instant = Instant.parse("2099-01-01T00:00:00Z"),
): EnterpriseState.Available {
    val candidate = platformCandidate(packet)
    val execution = candidate.execution as EnterpriseExecution.Platform
    val attempt = beginPlatformEnrollment()
    val response = PlatformWireCodec.decode<PlatformEnrollmentExchangeResponse>(platformCase("enrollment-response")).copy(
        sessionId = "ses_${kotlin.uuid.Uuid.random()}",
        deploymentId = packet.identity.authority.deploymentId,
        userId = packet.identity.userId,
        accessTokenExpiresAt = expiresAt.toString(),
        refreshExpiresAt = expiresAt.plusSeconds(7 * 24 * 60 * 60).toString(),
        sessionIdleExpiresAt = expiresAt.toString(),
    )
    val sessionId = acceptPlatformEnrollment(attempt, execution.connection, response)
    val bootstrap = PlatformWireCodec.decode<PlatformBootstrap>(platformCase("bootstrap")).let { value ->
        value.copy(
            deployment = value.deployment.copy(deploymentId = packet.identity.authority.deploymentId,
                name = packet.identity.enterpriseName),
            user = value.user.copy(userId = packet.identity.userId, displayName = packet.identity.userName),
            session = value.session.copy(sessionId = sessionId, expiresAt = expiresAt.toString(),
                sessionIdleExpiresAt = expiresAt.toString()),
        )
    }
    val access = completePlatformBootstrap(sessionId, bootstrap)
    synchronize(access, candidate)
    switchRealm(
        RealmSwitchRequest(requireNotNull(readPresentation().selection), access),
    ) {}
    return state.value as EnterpriseState.Available
}

class EnterprisePlatformFixtureTest {
    @org.junit.Test
    fun `fixture is the current platform candidate without local execution`() {
        val packet = exampleEnterprisePackage()
        val candidate = platformCandidate(packet)
        candidate.validate()
        org.junit.Assert.assertTrue(candidate.execution is EnterpriseExecution.Platform)
        org.junit.Assert.assertTrue(packet.configuration.gateways.isEmpty())
        org.junit.Assert.assertTrue(packet.configuration.assistants.all {
            !it.allowAsSubAssistant && it.allowedSubAssistantIds.isEmpty()
        })
    }
}
