package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.ai.mcp.McpCatalogStore
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.repository.MemoryRepository
import me.rerere.common.configuration.EnterpriseAuthority
import org.junit.Test

class EnterpriseIdentityDataDisposerTest {
    @Test
    fun `delegates one principal scope to every durable owner`() = runTest {
        val conversations = mockk<ConversationApplicationService> {
            coEvery { clearEnterpriseScope(any()) } returns Unit
        }
        val files = mockk<FileManagementApplicationService> {
            coEvery { clearEnterpriseScope(any()) } returns Unit
        }
        val memories = mockk<MemoryRepository> {
            coEvery { clearEnterpriseScope(any()) } returns Unit
        }
        val settings = mockk<SettingsStore> {
            coEvery { clearEnterprisePreferences(any()) } returns Unit
        }
        val catalogs = mockk<McpCatalogStore> {
            coEvery { clearEnterpriseScope(any()) } returns Unit
        }
        val scope = ConfigurationScope.Enterprise(
            EnterpriseAuthority("dep_12345678-1234-4123-8123-123456789abc"),
            "usr_12345678-1234-4123-8123-123456789abc",
        )

        EnterpriseIdentityDataDisposer(conversations, files, memories, settings, catalogs).clear(scope)

        coVerify(exactly = 1) { catalogs.clearEnterpriseScope(scope) }
        coVerify(exactly = 1) { conversations.clearEnterpriseScope(scope) }
        coVerify(exactly = 1) { files.clearEnterpriseScope(scope) }
        coVerify(exactly = 1) { memories.clearEnterpriseScope(scope) }
        coVerify(exactly = 1) { settings.clearEnterprisePreferences(scope) }
    }
}
