package net.weero.measix.pilot.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamBatch13BoundaryTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun `provider settings UI does not own provider SDK or model registry`() {
        val settingUi = File(
            root,
            "app/src/main/java/net/weero/measix/pilot/ui/pages/setting",
        ).walkTopDown().filter { it.isFile && it.extension == "kt" }
        val files = settingUi + sequenceOf(
            File(root, "app/src/main/java/net/weero/measix/pilot/ui/components/ai/ProviderBalanceText.kt"),
        )
        files.forEach { file ->
            val source = file.readText()
            assertFalse("${file.name} imports ProviderManager", source.contains("ProviderManager"))
            assertFalse("${file.name} imports ModelRegistry", source.contains("ModelRegistry"))
            assertFalse(
                "${file.name} injects provider application service",
                source.contains("koinInject<ProviderSettingsApplicationService>"),
            )
        }
        val boundary = File(
            root,
            "app/src/main/java/net/weero/measix/pilot/service/ProviderSettingsApplicationService.kt",
        ).readText()
        assertFalse(
            "provider edit draft must not be hidden behind a pass-through UI facade",
            boundary.contains("ProviderEditorUiModel"),
        )
        assertTrue(boundary.contains("requestFingerprint = setting.balanceRequestFingerprint()"))
        assertFalse(
            "balance cache identity must not retain plaintext credentials",
            boundary.contains("apiKey = setting.apiKey"),
        )
    }

    @Test
    fun `terminal page never owns a raw terminal session`() {
        val source = File(
            root,
            "app/src/main/java/net/weero/measix/pilot/ui/pages/extensions/workspace/WorkspaceTerminalPage.kt",
        ).readText()
        assertFalse(source.contains("com.termux.terminal.TerminalSession"))
        assertFalse(source.contains("finishIfRunning"))
        assertFalse(source.contains("createWorkspaceTerminalSession"))
    }


    @Test
    fun `all UI workspace consumers use application and query ports with UI models`() {
        val directory = File(
            root,
            "app/src/main/java/net/weero/measix/pilot/ui",
        )
        directory.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val source = file.readText()
            assertFalse("${file.name} imports WorkspaceRepository", source.contains("WorkspaceRepository"))
            assertFalse("${file.name} imports WorkspaceEntity", source.contains("WorkspaceEntity"))
        }

        val workspaceTools = File(
            root,
            "app/src/main/java/net/weero/measix/pilot/data/ai/tools/WorkspaceTools.kt",
        ).readText()
        assertFalse("model tools must not bypass the workspace command owner", workspaceTools.contains("WorkspaceRepository"))
        assertTrue(workspaceTools.contains("WorkspaceApplicationService"))
        assertTrue(workspaceTools.contains("executeTool(workspaceId)"))
    }

    @Test
    fun `terminal preparation uses the application command gate instead of a second mutex registry`() {
        val runtime = File(
            root,
            "app/src/main/java/net/weero/measix/pilot/service/workspace/WorkspaceTerminalRuntime.kt",
        ).readText()
        val application = File(
            root,
            "app/src/main/java/net/weero/measix/pilot/service/workspace/WorkspaceApplicationService.kt",
        ).readText()
        assertFalse(runtime.contains("preparationGates"))
        assertTrue(runtime.contains("prepareUnderCommandGate"))
        assertTrue(application.contains("terminals.create(workspace.root) { preparation ->"))
        assertTrue(application.contains("gated(workspaceId)"))
    }

    @Test
    fun `provider session identity follows master and child conversation owners`() {
        val master = File(
            root,
            "app/src/main/java/net/weero/measix/pilot/service/MasterTurnCoordinator.kt",
        ).readText()
        val target = File(
            root,
            "app/src/main/java/net/weero/measix/pilot/service/runtime/DelegationCoordinator.kt",
        ).readText()
        val backgroundProbe = File(
            root,
            "app/src/main/java/net/weero/measix/pilot/service/ProviderSettingsApplicationService.kt",
        ).readText()
        org.junit.Assert.assertTrue(master.contains("providerSessionId = conversationId.toString()"))
        org.junit.Assert.assertTrue(target.contains("providerSessionId = childConversationId.toString()"))
        assertFalse(backgroundProbe.contains("providerSessionId ="))
    }

    @Test
    fun `provider connection result labels and placeholders exist in every locale`() {
        val localeDirectories = listOf("values", "values-zh", "values-ja", "values-ko-rKR", "values-ru")
        localeDirectories.forEach { directory ->
            val strings = File(root, "app/src/main/res/$directory/strings.xml").readText()
            assertTrue(strings.contains("name=\"setting_provider_page_test_non_streaming\""))
            assertTrue(strings.contains("name=\"setting_provider_page_test_streaming\""))
            assertTrue(strings.contains("name=\"setting_provider_page_test_tool_call\""))
            assertTrue(strings.contains("name=\"skills_page_import_resource_limit\""))
            val called = Regex(
                "<string name=\"setting_provider_page_test_tool_called\">([^<]+)</string>",
            ).find(strings)?.groupValues?.get(1).orEmpty()
            assertTrue("$directory must preserve tool-name placeholder", called.contains("%1\$s"))
            assertTrue("$directory must preserve tool-input placeholder", called.contains("%2\$s"))
            val noTool = Regex(
                "<string name=\"setting_provider_page_test_no_tool\">([^<]+)</string>",
            ).find(strings)?.groupValues?.get(1).orEmpty()
            assertTrue("$directory must preserve response placeholder", noTool.contains("%s"))
        }

        val queryBoundary = File(
            root,
            "app/src/main/java/net/weero/measix/pilot/service/workspace/WorkspaceQueryService.kt",
        ).readText()
        assertTrue(queryBoundary.contains("val shellStatus: WorkspaceShellStatus"))
        assertFalse("persistence enum names must not cross the query boundary", queryBoundary.contains("val shellStatus: String"))

        val services = File(
            root,
            "app/src/main/java/net/weero/measix/pilot/service/workspace",
        ).walkTopDown().filter { it.isFile && it.extension == "kt" }
        services.forEach { file ->
            assertFalse("${file.name} compares a raw persisted status", file.readText().contains("\"READY\""))
        }
    }

    @Test
    fun `terminal creation failure placeholder exists in every locale`() {
        val localeDirectories = listOf("values", "values-zh", "values-ja", "values-ko-rKR", "values-ru")
        localeDirectories.forEach { directory ->
            val strings = File(root, "app/src/main/res/$directory/strings.xml").readText()
            val message = Regex(
                "<string name=\"workspace_terminal_create_failed\">([^<]+)</string>",
            ).find(strings)?.groupValues?.get(1).orEmpty()
            assertTrue("$directory must preserve terminal failure placeholder", message.contains("%1\$s"))
        }
    }
}
