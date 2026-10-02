package net.weero.measix.pilot.data.enterprise

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessage
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.ai.mcp.McpCommonOptions
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.EnterpriseApplicationService
import net.weero.measix.pilot.service.EnterpriseSynchronizationService
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/** Copy this unchanged probe into the historical checkout; never alter its production sources. */
@RunWith(AndroidJUnit4::class)
class PlatformCoverInstallationLiveAndroidTest {
    @Test fun preservesAppliedIdentityHistoryAndPersonalSettingsAcrossCoverInstallation() = runBlocking {
        val mode = InstrumentationRegistry.getArguments().getString("coverInstallation")
        assumeTrue(mode != null)
        require(mode == "seed" || mode == "verify")
        withTimeout(60_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val baselineFile = File(context.cacheDir, "cover-installation-baseline.json")
            val koin = GlobalContext.get()
            koin.get<ApplicationRecoveryGate>().awaitReady()
            val sessions = koin.get<EnterpriseSessionController>()
            val settings = koin.get<SettingsStore>()
            val query = koin.get<ConversationQueryService>()
            val commands = koin.get<ConversationCommandCoordinator>()
            val state = sessions.state.value as EnterpriseState.Available
            val session = requireNotNull(state.manifest.session)
            val enterprise = RealmAccess.Enterprise(session.identity.scope, session.id)
            val applied = requireNotNull(state.manifest.applied)
            // Check restored state before any network synchronization can repair or replace it.
            val execution = sessions.platformConfiguration(enterprise).candidate!!.execution as EnterpriseExecution.Platform
            val baseline = if (mode == "seed") {
                check(!baselineFile.exists()) { "Use a new dedicated upgrade device" }
                val mcp = McpServerConfig.StreamableHTTPServer(
                    commonOptions = McpCommonOptions(name = "V4 personal MCP retained", enable = false),
                    url = "http://127.0.0.1:9221/mcp",
                )
                settings.updateLocal { it.copy(dynamicColor = false, themeId = "ocean", mcpServers = it.mcpServers + mcp) }
                val personal = settings.userSettings.value
                val enterpriseAssistant = requireNotNull(state.configuration).assistants.first { it.enabled }
                val ids = JSONArray()
                for ((access, assistant) in listOf(
                    enterprise to ConfigurationReference.Enterprise(enterprise.scope.authority, enterpriseAssistant.id),
                    RealmAccess.Personal to personal.assistantId,
                )) {
                    val label = if (access is RealmAccess.Enterprise) "enterprise" else "personal"
                    val conversation = Conversation(
                        assistantId = assistant,
                        scope = access.scope,
                        title = "V4 retained $label history",
                        messageNodes = listOf(
                            MessageNode.of(UIMessage.user("V4 $label question remains readable after upgrade.")),
                            MessageNode.of(UIMessage.assistant("V4 $label saved answer remains unchanged.")),
                        ),
                    )
                    sessions.withRealmAccess(access) { commands.create(conversation) }
                    ids.put(conversation.id.toString())
                }
                JSONObject().put("conversationIds", ids).put("mcpId", mcp.id.toString())
            } else JSONObject(baselineFile.readText())
            val facts = JSONObject()
                .put("sessionId", session.id).put("principal", enterprise.scope.toString())
                .put("generation", applied.generation).put("revision", applied.revision)
                .put("configurationHash", applied.configurationHash).put("executionHash", applied.executionHash)
                .put("releaseId", execution.releaseId).put("snapshotHash", execution.snapshotHash)
            val personal = settings.userSettings.value
            assertFalse(personal.dynamicColor)
            assertEquals("ocean", personal.themeId)
            val mcp = personal.mcpServers.single { it.id.toString() == baseline.getString("mcpId") }
                as McpServerConfig.StreamableHTTPServer
            assertEquals("V4 personal MCP retained", mcp.commonOptions.name)
            assertFalse(mcp.commonOptions.enable)
            assertEquals("http://127.0.0.1:9221/mcp", mcp.url)
            val histories = JSONArray()
            val ids = baseline.getJSONArray("conversationIds")
            for (index in 0 until ids.length()) {
                val id = Uuid.parse(ids.getString(index))
                val access = if (index == 0) enterprise else RealmAccess.Personal
                sessions.withRealmAccess(access) {
                    commands.withRootHeaders(access.scope, listOf(id)) {
                        val saved = requireNotNull(query.aggregateSnapshot(id))
                        assertEquals(access.scope, saved.header.scope)
                        assertEquals(2, saved.nodes.size)
                        val nodes = JSONArray()
                        saved.nodes.forEach { node ->
                            nodes.put(JSONObject().put("id", node.id.toString()).put("role", node.role.name)
                                .put("messageId", node.currentMessage.id.toString()).put("text", node.currentMessage.toText()))
                        }
                        histories.put(JSONObject().put("id", id.toString()).put("title", saved.header.title)
                            .put("assistant", saved.header.assistantId.toString()).put("nodes", nodes))
                    }
                }
            }
            facts.put("histories", histories)
            if (mode == "seed") {
                baseline.put("facts", facts)
                baselineFile.writeText(baseline.toString())
            } else {
                val before = baseline.getJSONObject("facts")
                for (key in facts.keys()) assertEquals("Preserve $key", before.get(key).toString(), facts.get(key).toString())
                koin.get<EnterpriseApplicationService>().synchronize(enterprise)
                assertEquals(applied.generation,
                    koin.get<EnterpriseSynchronizationService>().prepareExecution(enterprise).generation)
            }
            File(context.getExternalFilesDir(null), "cover-installation-$mode.json").writeText(JSONObject()
                .put("mode", mode).put("passed", true).put("generation", applied.generation)
                .put("verifiedBeforeSynchronization", true).put("historyConversations", histories.length())
                .put("executionAdmittedAfterSynchronization", mode == "verify")
                .put("personalSettingsAndMcpPreserved", true).toString(2))
        }
    }
}
