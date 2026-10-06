package net.weero.measix.pilot.data.ai.mcp

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.enterprise.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class McpDirectRuntimeAdmissionTest {
    @Test fun `live managed rules and full contract are rechecked after the initial admission`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = AppScope(dispatcher)
        val packet = exampleEnterprisePackage()
        val resource = packet.configuration.mcpServers.first()
        val execution = platformCandidate(packet).execution as EnterpriseExecution.Platform
        val access = RealmAccess.Enterprise(packet.identity.scope, "ses_${Uuid.random()}")
        val original = McpConnectionDefinition.ManagedPlatform(access, packet.identity.reference(resource.id), resource.name,
            execution, requireNotNull(resource.authOwnership), EnterpriseAppliedVersion(Uuid.random().toString(),
                packet.configuration.generation, "configuration", "execution"), "int_${Uuid.random()}", resource) { "token" }
        var current: McpConnectionDefinition? = original
        val catalogs = MutableStateFlow<Map<McpCatalogKey, McpCatalogSnapshot>>(emptyMap())
        val catalogStore = mockk<McpCatalogStore> { every { this@mockk.catalogs } returns catalogs }
        val states = McpRuntimeStateStore()
        fun runtime(config: McpConnectionDefinition.ManagedPlatform, read: () -> McpConnectionDefinition?): McpServerRuntime {
            val key = McpRuntimeKey(config.id, access, config.interactionId)
            return states.getOrCreate(key) {
                McpServerRuntime(key, object : McpRuntimeDefinition {
                    override suspend fun <T> withCurrent(use: McpDefinitionUse, operation: suspend (McpConnectionDefinition?) -> T): T = operation(read())
                }, catalogStore, scope, mockk { every { isOnline } returns MutableStateFlow(true) }, states,
                    mockk(), mockk(), Semaphore(1), dispatcher, MutableStateFlow(true), McpServerRuntimePolicy { 0L },
                    { _, _ -> }, {}, {})
            }
        }
        val runtime = runtime(original) { current }
        val peerDefinition = McpConnectionDefinition.ManagedPlatform(access, original.id, original.name, execution,
            original.authOwnership, original.version, "int_${Uuid.random()}", resource) { "token" }
        val peer = runtime(peerDefinition) { peerDefinition }
        val tool = McpCatalogTool(Json.parseToJsonElement(
            """{"name":"read","inputSchema":{"type":"object"},"_meta":{"version":1}}"""
        ).jsonObject)
        val digest = original.mcpDefinitionDigest()
        suspend fun directory(tools: List<McpCatalogTool>, revision: Long, hydrate: Boolean = true) {
            val snapshot = McpCatalogSnapshot(access.scope, original.id, revision, digest, "catalog-$revision", tools, original.managed)
            catalogs.value = mapOf(original.catalogKey to snapshot)
            if (hydrate) runtime.hydrateCatalog(snapshot) else peer.hydrateCatalog(snapshot)
        }
        suspend fun admit(hash: String? = null, needsApproval: Boolean = false, approved: Boolean = false) =
            runtime.admitInvocation("read", digest, needsApproval, hash, approved)
        try {
            directory(listOf(tool), 1)
            assertTrue(admit() is McpToolCallAdmission.Candidate)
            val initial = catalogs.value.getValue(original.catalogKey)
            catalogs.value = mapOf(original.catalogKey to initial.copy(definitionDigest = "new-publication",
                managed = original.managed.copy(generation = original.managed.generation + 1)))
            assertTrue("An unrelated publication leaves the original confirmed directory usable", admit() is McpToolCallAdmission.Candidate)
            catalogs.value = mapOf(original.catalogKey to initial)
            val restricted = resource.copy(toolAccessMode = PlatformMcpDefinitionToolAccessMode.ALLOWLIST,
                allowedTools = listOf(PlatformMcpToolGrant("read", tool.contractHash(), PlatformMcpToolGrantApprovalPolicy.AUTO)))
            current = original.withToolAccess(restricted, null)
            assertTrue(admit() is McpToolCallAdmission.Rejected)
            assertTrue(admit(tool.contractHash()) is McpToolCallAdmission.Candidate)
            val admitted = admit(tool.contractHash()) as McpToolCallAdmission.Candidate
            val confirmation = restricted.copy(allowedTools = restricted.allowedTools.map {
                it.copy(approvalPolicy = PlatformMcpToolGrantApprovalPolicy.REQUIRE_CONFIRMATION)
            })
            current = original.withToolAccess(confirmation, null)
            val rejected = runtime.completeInvocationAdmission(admitted.config, "read", digest, false, tool.contractHash(), false)
                as McpToolCallPreparation.Rejected
            assertEquals(McpToolFailureKind.TOOL_UNAVAILABLE, rejected.kind)
            assertTrue(admit(tool.contractHash(), true, false) is McpToolCallAdmission.Rejected)
            assertTrue(admit(tool.contractHash(), true, true) is McpToolCallAdmission.Candidate)
            val binding = PlatformAssistantMcpBinding(resource.id, PlatformAssistantMcpBindingToolSelection.ALLOWLIST, listOf("other"))
            current = original.withToolAccess(confirmation, binding)
            assertTrue(admit(tool.contractHash(), true, true) is McpToolCallAdmission.Rejected)
            current = original.withToolAccess(restricted, null)
            val changed = McpCatalogTool(JsonObject(tool.definition + ("_meta" to buildJsonObject { put("version", 2) })))
            // Another interaction publishes a new directory; this runtime keeps its original provider schema.
            directory(listOf(changed), 2, hydrate = false)
            assertTrue(admit(tool.contractHash()) is McpToolCallAdmission.Rejected)
            val refreshed = runtime.completeInvocationAdmission(admitted.config, "read", digest, false, tool.contractHash(), false)
                as McpToolCallPreparation.Rejected
            assertEquals(McpToolFailureKind.TOOL_UNAVAILABLE, refreshed.kind)
            current = original
            assertTrue("ALL has no reviewed-contract lock", admit() is McpToolCallAdmission.Candidate)
            directory(emptyList(), 3, hydrate = false)
            // The original interaction does not inspect this removal before the next publication replaces the head.
            val head = catalogs.value.getValue(original.catalogKey)
            catalogs.value = mapOf(original.catalogKey to head.copy(definitionDigest = "new-publication",
                managed = original.managed.copy(generation = original.managed.generation + 1)))
            assertTrue("A later publication cannot resurrect a tool already removed by a peer", admit() is McpToolCallAdmission.Rejected)
            val retained = states.capabilities.value.getValue(runtime.key)
            assertEquals(3L, retained.catalog?.revision)
            assertTrue(retained.catalog!!.tools.isEmpty())
            assertEquals("restored last-known-good catalog; session is not connected",
                (retained.status as McpStatus.CatalogStale).message)
            assertFalse("A peer directory must not make this session callable", retained.sessionCallable)
            // A failed refresh restoring its old snapshot cannot undo a peer's confirmed removal.
            states.publish(runtime, McpRuntimeCapability(McpStatus.CatalogStale(1, 1, "refresh failed"), initial, true))
            val restored = states.capabilities.value.getValue(runtime.key)
            assertEquals(McpStatus.CatalogStale(0, 3, "refresh failed"), restored.status)
            assertEquals(3L, restored.catalog?.revision)
            assertTrue("Connection state still belongs to the publishing runtime", restored.sessionCallable)
            assertTrue(admit() is McpToolCallAdmission.Rejected)
            current = null
            assertTrue(admit() is McpToolCallAdmission.Rejected)
        } finally { scope.cancel() }
    }
}
