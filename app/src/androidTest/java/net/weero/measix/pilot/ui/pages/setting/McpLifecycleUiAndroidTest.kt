package net.weero.measix.pilot.ui.pages.setting

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dokar.sonner.rememberToasterState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.ai.mcp.*
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.context.Navigator
import net.weero.measix.pilot.ui.theme.MeasixTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

/** Real Settings/Catalog owners, coordinator, HTTP/SSE, query and the production settings page. */
@RunWith(AndroidJUnit4::class)
class McpLifecycleUiAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val koin get() = GlobalContext.get()
    private val commands get() = koin.get<McpApplicationService>()
    private val coordinator get() = koin.get<McpRuntimeCoordinator>()
    private val catalogs get() = koin.get<McpCatalogStore>()

    @Test
    fun realHttpLifecycleKeepsDirectoryAndRecoversNotificationsInProductionPage() {
        awaitRecovery()
        val server = LifecycleMcpHttpServer()
        val config = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "MCP lifecycle review ${Uuid.random().toString().take(6)}"),
            url = server.url,
        )
        try {
            runBlocking { commands.upsert(config) }
            showPage()
            runBlocking { coordinator.prepareTurnCapabilities(Assistant(mcpServers = setOf(config.id))) }
            awaitCapability(config.id) { it.sessionCallable && it.notifications == McpNotificationHealth.Listening }
            compose.onNodeWithText(config.commonOptions.name).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.mcp_status_ready, 1)).assertIsDisplayed()
            screenshot("01-connected")

            val original = requireNotNull(capability(config.id).catalog)
            server.rejectLists = true
            refreshThroughUi()
            awaitCapability(config.id) { it.catalogRefresh is McpCatalogRefresh.Failed }
            assertEquals(original.tools, capability(config.id).catalog?.tools)
            compose.onNodeWithText(compose.activity.getString(R.string.mcp_catalog_refresh_failed)).assertIsDisplayed()
            screenshot("02-refresh-failed-directory-retained")

            server.rejectLists = false
            refreshThroughUi()
            awaitCapability(config.id) { it.catalogRefresh == McpCatalogRefresh.Idle && it.notifications == McpNotificationHealth.Listening }
            server.breakNotificationJson()
            awaitCapability(config.id) { it.notifications is McpNotificationHealth.Unavailable }
            assertTrue(capability(config.id).sessionCallable)
            compose.onNodeWithText(compose.activity.getString(R.string.mcp_notification_unavailable)).assertIsDisplayed()
            val tool = requireNotNull(capability(config.id).catalog).tools.single()
            runBlocking {
                coordinator.callTool(RealmAccess.Personal, config.id, toolName = tool.name,
                    expectedDefinitionDigest = config.mcpDefinitionDigest(), expectedNeedsApproval = false,
                    args = JsonObject(emptyMap()), onArtifactCreated = { error("fixture returns no artifacts") })
            }
            assertEquals(1, server.calls.get())
            screenshot("03-notifications-degraded-tools-callable")

            val priorSubscriptions = server.subscriptions.get()
            refreshThroughUi()
            awaitCapability(config.id) { it.notifications == McpNotificationHealth.Listening && it.catalogRefresh == McpCatalogRefresh.Idle }
            assertTrue(server.subscriptions.get() > priorSubscriptions)
            server.toolName = "read_updated"
            server.notifyToolsChanged()
            awaitCapability(config.id) { it.catalog?.tools?.singleOrNull()?.name == "read_updated" }
            compose.onNodeWithText(compose.activity.getString(R.string.mcp_status_ready, 1)).assertIsDisplayed()
            screenshot("04-notifications-recovered-directory-updated")
        } finally {
            try {
                runBlocking {
                    commands.delete(config.id)
                    withTimeout(15_000) { coordinator.runtimeCapabilities.first { McpRuntimeKey(config.id) !in it } }
                    catalogs.remove(McpCatalogKey(ConfigurationScope.Personal, config.id))
                }
            } finally { server.close() }
        }
    }

    /** Run seed, force-stop the target process, then run verify. Ordinary suites skip this external-process protocol. */
    @Test
    fun confirmedDirectoryRestoresWithoutFailureAfterProcessRestart() {
        val args = InstrumentationRegistry.getArguments()
        val seed = args.getString("mcpColdSeed") == "true"
        val verify = args.getString("mcpColdVerify") == "true"
        assumeTrue("Cold-start evidence requires explicit mcpColdSeed or mcpColdVerify", seed || verify)
        check(seed != verify)
        awaitRecovery()
        val marker = File(compose.activity.filesDir, "mcp-lifecycle-cold-fixture.json")
        if (seed) {
            check(!marker.exists()) { "Previous cold fixture must be verified and cleaned first" }
            LifecycleMcpHttpServer().use { server ->
                val config = McpServerConfig.StreamableHTTPServer(
                    commonOptions = McpCommonOptions(name = "MCP cold-start review ${Uuid.random().toString().take(6)}"),
                    url = server.url,
                )
                marker.writeText(Json.encodeToString<McpServerConfig>(config))
                try {
                    runBlocking {
                        commands.upsert(config)
                        coordinator.prepareTurnCapabilities(Assistant(mcpServers = setOf(config.id)))
                    }
                    awaitCapability(config.id) { it.sessionCallable && it.catalog != null }
                    showPage()
                    screenshot("05-cold-seed-confirmed-directory")
                } catch (failure: Throwable) {
                    runBlocking { commands.delete(config.id); catalogs.remove(McpCatalogKey(ConfigurationScope.Personal, config.id)) }
                    marker.delete()
                    throw failure
                }
            }
            return
        }
        check(marker.exists()) { "Run mcpColdSeed before restarting the process" }
        val config = Json.decodeFromString<McpServerConfig>(marker.readText())
        try {
            showPage()
            awaitCapability(config.id) { it.catalog != null }
            val restored = capability(config.id)
            assertEquals(McpStatus.Idle, restored.status)
            assertEquals(McpCatalogRefresh.Idle, restored.catalogRefresh)
            assertFalse(restored.sessionCallable)
            compose.onNodeWithText(compose.activity.getString(R.string.mcp_catalog_saved, 1)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.mcp_catalog_refresh_failed)).assertDoesNotExist()
            screenshot("06-cold-restored-neutral-directory")
        } finally {
            runBlocking {
                commands.delete(config.id)
                withTimeout(15_000) { coordinator.runtimeCapabilities.first { McpRuntimeKey(config.id) !in it } }
                catalogs.remove(McpCatalogKey(ConfigurationScope.Personal, config.id))
            }
            check(marker.delete())
        }
    }

    private fun awaitRecovery() {
        koin.get<ApplicationRecoveryCoordinator>()
        runBlocking { withTimeout(30_000) { koin.get<ApplicationRecoveryGate>().awaitReady() } }
    }

    private fun showPage() {
        val query = koin.get<McpQueryService>()
        val configuration = koin.get<ConfigurationApplicationService>()
        val application = commands
        compose.setContent {
            MeasixTheme {
                CompositionLocalProvider(
                    LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo(),
                    LocalToaster provides rememberToasterState(),
                    LocalNavController provides Navigator(mutableListOf<NavKey>(Screen.Startup())),
                ) { SettingMcpPage(application, query, configuration) }
            }
        }
        compose.waitForIdle()
    }

    private fun capability(id: ConfigurationReference) = requireNotNull(coordinator.runtimeCapabilities.value[McpRuntimeKey(id)])

    private fun awaitCapability(id: ConfigurationReference, predicate: (McpRuntimeCapability) -> Boolean) {
        compose.waitUntil(20_000) { coordinator.runtimeCapabilities.value[McpRuntimeKey(id)]?.let(predicate) == true }
        compose.waitForIdle()
    }

    private fun refreshThroughUi() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.mcp_refresh_personal)).performClick()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(250, 5_000)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(compose.activity.getExternalFilesDir(null))
        val output = File(root, "mcp-lifecycle-$name.png")
        check(root.isDirectory || root.mkdirs())
        output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}

/** Minimal controllable MCP HTTP/SSE endpoint; every request crosses the real Android transport. */
private class LifecycleMcpHttpServer : AutoCloseable {
    private val listener = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "mcp-ui-fixture").apply { isDaemon = true } }
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val notificationSockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    val calls = AtomicInteger()
    val subscriptions = AtomicInteger()
    @Volatile var rejectLists = false
    @Volatile var toolName = "read_example"
    @Volatile private var closed = false
    val url = "http://127.0.0.1:${listener.localPort}/mcp"
    private val acceptor = thread(name = "mcp-ui-accept", isDaemon = true) {
        while (!closed) {
            val socket = try { listener.accept() } catch (error: java.io.IOException) { if (!closed) throw error else break }
            sockets += socket
            workers.execute { try { handle(socket) } finally { notificationSockets -= socket; sockets -= socket; socket.close() } }
        }
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream().buffered()
        val request = line(input)
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line(input)
            if (header.isEmpty()) break
            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
        }
        when (request.substringBefore(' ')) {
            "GET" -> {
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n: connected\n\n".toByteArray())
                    flush()
                }
                notificationSockets += socket
                subscriptions.incrementAndGet()
                try { while (input.read() != -1) Unit } catch (_: java.io.IOException) { }
            }
            "DELETE" -> respond(socket, "{}")
            else -> {
                val bytes = ByteArray(headers["content-length"]?.toInt() ?: 0)
                var read = 0
                while (read < bytes.size) {
                    val count = input.read(bytes, read, bytes.size - read)
                    check(count > 0)
                    read += count
                }
                val message = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                val id = message["id"]
                if (id == null) { respond(socket, "", status = "202 Accepted"); return }
                val result = when (message["method"]?.jsonPrimitive?.content) {
                    "initialize" -> buildJsonObject {
                        put("protocolVersion", message["params"]!!.jsonObject.getValue("protocolVersion"))
                        putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", true) } }
                        putJsonObject("serverInfo") { put("name", "MCP UI review"); put("version", "1") }
                    }
                    "tools/list" -> {
                        if (rejectLists) {
                            respond(socket, buildJsonObject {
                                put("jsonrpc", "2.0"); put("id", id)
                                putJsonObject("error") { put("code", -32603); put("message", "Fixture refresh failed: retained directory diagnostic") }
                            }.toString())
                            return
                        }
                        buildJsonObject { putJsonArray("tools") { add(buildJsonObject {
                            put("name", toolName); put("description", "Read the review fixture")
                            putJsonObject("inputSchema") { put("type", "object"); putJsonObject("properties") {} }
                        }) } }
                    }
                    "tools/call" -> {
                        calls.incrementAndGet()
                        buildJsonObject { putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "Fixture tool completed") }) } }
                    }
                    else -> JsonObject(emptyMap())
                }
                respond(socket, buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }.toString())
            }
        }
    }

    fun breakNotificationJson() = event("this is not JSON")
    fun notifyToolsChanged() = event("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}")
    private fun event(data: String) {
        val targets = synchronized(notificationSockets) { notificationSockets.toList() }
        check(targets.isNotEmpty()) { "No notification subscription" }
        targets.forEach { socket -> synchronized(socket) {
            socket.getOutputStream().apply { write("event: message\ndata: $data\n\n".toByteArray()); flush() }
        } }
    }

    private fun respond(socket: Socket, body: String, status: String = "200 OK") {
        val bytes = body.toByteArray()
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nMcp-Session-Id: ui-review\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    private fun line(input: InputStream): String = buildString {
        while (true) {
            val next = input.read()
            if (next < 0 || next == 10) break
            if (next != 13) append(next.toChar())
        }
    }

    override fun close() {
        closed = true
        listener.close()
        synchronized(sockets) { sockets.toList() }.forEach { it.close() }
        workers.shutdownNow()
        check(workers.awaitTermination(5, TimeUnit.SECONDS))
        acceptor.join(5_000)
        check(!acceptor.isAlive)
    }
}
