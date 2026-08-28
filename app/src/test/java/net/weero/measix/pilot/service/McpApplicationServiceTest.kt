package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.ai.mcp.McpCommonOptions
import net.weero.measix.pilot.data.ai.mcp.McpRefreshReceipt
import net.weero.measix.pilot.data.ai.mcp.McpRuntimeCoordinator
import net.weero.measix.pilot.data.ai.mcp.McpOAuthState
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.ai.mcp.McpToolPolicy
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.toEffectiveSettingsSnapshot
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

class McpApplicationServiceTest {
    private val manager = mockk<McpRuntimeCoordinator>(relaxed = true)
    private val settingsStore = mockk<SettingsStore>()
    private lateinit var local: Settings
    private lateinit var effective: MutableStateFlow<net.weero.measix.pilot.data.datastore.EffectiveSettingsSnapshot>
    private lateinit var service: McpApplicationService

    @Before
    fun setUp() {
        local = Settings()
        effective = MutableStateFlow(local.toEffectiveSettingsSnapshot())
        every { settingsStore.effectiveSettings } returns effective
        coEvery { settingsStore.updateLocal(any()) } coAnswers {
            firstArg<(Settings) -> Settings>()(local).also { committed ->
                local = committed
                effective.value = committed.toEffectiveSettingsSnapshot(effective.value.revision + 1)
            }
        }
        coEvery { manager.withConfigurationMutation(any()) } coAnswers {
            firstArg<suspend () -> Unit>().invoke()
        }
        service = McpApplicationService(manager, settingsStore)
    }

    @Test
    fun `same-name overwrite clears oauth when the resource changes and preserves tool policy`() = runTest {
        val oauth = McpOAuthState(enabled = true, accessToken = "current-token")
        val existing = remote(
            name = "Remote tools",
            url = "https://old.example/mcp",
            oauth = oauth,
            policies = listOf(McpToolPolicy(name = "measure", enable = false, needsApproval = true)),
        )
        local = Settings(mcpServers = listOf(existing))
        effective.value = local.toEffectiveSettingsSnapshot()

        service.overwriteByName(
            listOf(
                remote(
                    name = "remote tools",
                    url = "https://new.example/mcp",
                )
            )
        )

        val saved = local.mcpServers.single() as McpServerConfig.StreamableHTTPServer
        assertEquals(existing.id, saved.id)
        assertEquals("https://new.example/mcp", saved.url)
        assertEquals(null, saved.commonOptions.oauth)
        assertEquals(false, saved.commonOptions.toolPolicies.single().enable)
        assertEquals(true, saved.commonOptions.toolPolicies.single().needsApproval)
    }

    @Test
    fun `same-resource overwrite preserves latest oauth`() = runTest {
        val oauth = McpOAuthState(enabled = true, accessToken = "current-token")
        val existing = remote(name = "Remote tools", oauth = oauth)
        local = Settings(mcpServers = listOf(existing))
        effective.value = local.toEffectiveSettingsSnapshot()

        service.overwriteByName(listOf(remote(name = "remote tools")))

        assertEquals(oauth, local.mcpServers.single().commonOptions.oauth)
    }

    @Test
    fun `upsert clears oauth when static headers change`() = runTest {
        val oauth = McpOAuthState(enabled = true, accessToken = "current-token")
        val existing = remote(name = "Remote tools", oauth = oauth)
        local = Settings(mcpServers = listOf(existing))
        effective.value = local.toEffectiveSettingsSnapshot()

        service.upsert(
            remote(
                id = existing.id,
                name = existing.commonOptions.name,
                headers = listOf("Authorization" to "Bearer replacement-token"),
            )
        )

        assertEquals(null, local.mcpServers.single().commonOptions.oauth)
    }

    @Test
    fun `upsert rejects case-insensitive duplicate server names`() = runTest {
        local = Settings(mcpServers = listOf(remote(name = "Remote tools")))
        effective.value = local.toEffectiveSettingsSnapshot()

        val error = try {
            service.upsert(remote(name = " remote tools "))
            null
        } catch (caught: IllegalArgumentException) {
            caught
        }
        assertTrue(error is IllegalArgumentException)
        assertEquals(1, local.mcpServers.size)
    }

    @Test
    fun `delete atomically removes server and assistant references`() = runTest {
        val server = remote(name = "Remote tools")
        val assistant = Assistant(mcpServers = setOf(server.id))
        local = Settings(mcpServers = listOf(server), assistants = listOf(assistant))
        effective.value = local.toEffectiveSettingsSnapshot()

        service.delete(server.id)

        assertTrue(local.mcpServers.isEmpty())
        assertTrue(local.assistants.single().mcpServers.isEmpty())
    }

    @Test
    fun `refresh returns the coordinator operation receipt unchanged`() = runTest {
        val receipt = McpRefreshReceipt(requestedServerCount = 3, settledServerCount = 1)
        coEvery { manager.refreshAllRegisteredServers() } returns receipt

        assertEquals(receipt, service.refreshAll())
    }

    @Test
    fun `single server restart returns the coordinator operation receipt unchanged`() = runTest {
        val server = remote(name = "Remote tools")
        local = Settings(mcpServers = listOf(server))
        effective.value = local.toEffectiveSettingsSnapshot()
        val receipt = McpRefreshReceipt(requestedServerCount = 1, settledServerCount = 0)
        coEvery { manager.restartServer(server.id) } returns receipt

        assertEquals(receipt, service.restart(server.id))
    }

    private fun remote(
        id: Uuid = Uuid.random(),
        name: String,
        url: String = "https://example.test/mcp",
        oauth: McpOAuthState? = null,
        policies: List<McpToolPolicy> = emptyList(),
        headers: List<Pair<String, String>> = emptyList(),
    ) = McpServerConfig.StreamableHTTPServer(
        id = id,
        commonOptions = McpCommonOptions(
            name = name,
            headers = headers,
            oauth = oauth,
            toolPolicies = policies,
        ),
        url = url,
    )
}
