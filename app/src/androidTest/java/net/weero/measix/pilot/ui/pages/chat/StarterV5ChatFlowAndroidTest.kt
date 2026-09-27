package net.weero.measix.pilot.ui.pages.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.R
import net.weero.measix.pilot.RouteActivity
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.ui.hooks.readBooleanPreference
import net.weero.measix.pilot.ui.hooks.writeBooleanPreference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

/** Explicit opt-in on a dedicated, unbound device, against either isolated HTTP fixtures or real Core. */
@RunWith(AndroidJUnit4::class)
class StarterV5ChatFlowAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun syncedV5CardSurvivesFirstSendDetailsRoomReadbackAndActivityReopen() {
        assumeTrue("Use a dedicated unbound AVD and starterV5MockLive=true",
            InstrumentationRegistry.getArguments().getString("starterV5MockLive") == "true")
        runOpeningFlow(core = false)
    }

    @Test
    fun syncedCoreV5CardSurvivesFirstSendDetailsRoomReadbackAndActivityReopen() {
        assumeTrue("Use a dedicated unbound AVD and starterV5CoreLive=true",
            InstrumentationRegistry.getArguments().getString("starterV5CoreLive") == "true")
        runOpeningFlow(core = true)
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private fun runOpeningFlow(core: Boolean) {
        val arguments = InstrumentationRegistry.getArguments()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val koin = GlobalContext.get()
        val sessions = koin.get<EnterpriseSessionController>()
        val enterprise = koin.get<EnterpriseApplicationService>()
        val queries = koin.get<ConfigurationQueryService>()
        val conversations = koin.get<ConversationApplicationService>()
        val query = koin.get<ConversationQueryService>()
        val repository = koin.get<ConversationRepository>()
        koin.get<ApplicationRecoveryCoordinator>()
        runBlocking { withTimeout(30_000) { koin.get<ApplicationRecoveryGate>().awaitReady() } }
        val initial = (sessions.state.value as EnterpriseState.Available).manifest
        // Never replace a binding, resume somebody else's enrollment, or restore private files by copying them.
        check(initial.phase == EnterpriseSessionPhase.SIGNED_OUT && initial.session == null &&
            initial.pendingEnrollment == null && initial.applied == null && initial.lastIdentity == null) {
            "starter_v5_requires_fresh_unbound_device"
        }
        val coreInput = if (core) File(requireNotNull(arguments.getString("coreStarterInput"))) else null
        val fixture = if (coreInput != null) {
            var inputFailure: Throwable? = null
            try { Json.parseToJsonElement(coreInput.readText()).jsonObject }
            catch (error: Throwable) { inputFailure = error; throw error }
            finally {
                if (!coreInput.delete()) {
                    val cleanup = IllegalStateException("core_starter_input_cleanup_failed")
                    inputFailure?.addSuppressed(cleanup) ?: throw cleanup
                }
            }
        } else InstrumentationRegistry.getInstrumentation().context.assets
            .open("contracts/starter-v5-e2e.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val server = if (core) null else StarterV5HttpMock(fixture)
        val snapshot = fixture.getValue("snapshot").jsonObject
        assertEquals(5L, snapshot.getValue("schemaVersion").jsonPrimitive.long)
        val deploymentId = snapshot.getValue("deploymentId").jsonPrimitive.content
        val expectedStarter = fixture["starterId"]?.jsonPrimitive?.content
        val starter = snapshot.getValue("starters").jsonArray.map { it.jsonObject }.single {
            expectedStarter == null || it.getValue("starterId").jsonPrimitive.content == expectedStarter
        }
        val starterId = starter.getValue("starterId").jsonPrimitive.content
        val assistantSource = snapshot.getValue("assistants").jsonArray.map { it.jsonObject }.single {
            it.getValue("assistantDefinitionId") == starter.getValue("assistantDefinitionId")
        }
        val expectedAnswer = if (core) fixture.getValue("expectedAnswer").jsonPrimitive.content else "V5 mock response"
        val opening = starter.getValue("openingSnapshot").jsonObject
        val title = starter.getValue("title").jsonPrimitive.content
        val prompt = starter.getValue("prompt").jsonPrimitive.content
        val system = opening.getValue("systemPrompt").jsonPrimitive.content
        val backgrounds = opening.getValue("initialContexts").jsonArray.map { it.jsonObject }
        val priorNewChat = context.readBooleanPreference("create_new_conversation_on_start", true)
        val priorImageLoader = coil3.SingletonImageLoader.get(context)
        val evidence = File(arguments.getString("additionalTestOutputDir")?.let(::File)
            ?: context.getExternalFilesDir(null), "starter-v5-chat-evidence").apply { check(isDirectory || mkdirs()) }
        var activity: ActivityScenario<RouteActivity>? = null
        var view: ConversationViewLease? = null
        var createdId: Uuid? = null
        var failure: Throwable? = null
        try {
            val material = if (core) fixture.getValue("enrollment").toString() else buildJsonObject {
                put("formatVersion", 1); put("kind", "PLATFORM_ENROLLMENT")
                put("platformUrl", requireNotNull(server).origin); put("code", "starter-v5-dedicated-fixture")
                put("expiresAt", Instant.now().plusSeconds(3600).toString())
            }.toString()
            runBlocking { withTimeout(60_000) { enterprise.confirmJoin(requireNotNull(enterprise.join(material))) } }
            val selection = runBlocking { requireNotNull(sessions.observeSelectedRealmSelection()
                .first { it?.access is RealmAccess.Enterprise }) }
            val access = selection.access as RealmAccess.Enterprise
            assertEquals(deploymentId, access.scope.authority.deploymentId)
            server?.let {
                assertEquals(1, it.enrollments.get())
                assertTrue(it.downloads.get() >= 1)
                assertTrue(it.appliedReports.get() >= 1)
            }
            val resolved = runBlocking { queries.read(access) }
            val assistant = resolved.assistants.values.single { it.name == assistantSource.getValue("displayName").jsonPrimitive.content }
            val candidate = (sessions.state.value as EnterpriseState.Available).configuration!!
            val definition = candidate.starters.single { it.id == starterId }
            assertEquals(backgrounds.size, definition.openingSnapshot!!.initialContexts.size)
            assertEquals(system, definition.openingSnapshot.systemPrompt)
            runBlocking { koin.get<ConfigurationApplicationService>().selectResource(selection, ResourceSelectionSlot.ASSISTANT, assistant.id) }
            assertTrue(runBlocking { query.recentConversations(access, assistant.id, 10) }.isEmpty())
            context.writeBooleanPreference("create_new_conversation_on_start", true)
            coil3.SingletonImageLoader.reset()
            activity = ActivityScenario.launch(Intent(context, RouteActivity::class.java))
            var uiContext: Context = context
            activity.onActivity { uiContext = it }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().size == 1 }
            // Actual empty-chat card -> ChatPage callback -> ChatVM input lock -> application binding.
            compose.onNode(hasScrollToIndexAction() and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
                SemanticsMatcher("visible Starter row") { it.boundsInRoot.left >= 0f && it.boundsInRoot.width > 0f })
                .performScrollToNode(hasText(title))
            compose.onNodeWithText(title).performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasTestTag("chat_input") and hasText(prompt)).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("chat_input").assertTextEquals(prompt)
            compose.onNodeWithText(title).assertIsSelected()
            assertTrue(runBlocking { query.recentConversations(access, assistant.id, 10) }.isEmpty())
            server?.let { assertTrue(it.modelRequests.isEmpty()) }
            capture(evidence, "01-card-prefill.png")
            closeSoftKeyboard()
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasTestTag("chat_send_button") and isEnabled()).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("chat_send_button").performClick()
            val row = awaitUi(30_000) { query.conversationsOfAssistant(assistant.id).first { it.isNotEmpty() }.single() }
            createdId = row.id
            val lease = runBlocking { conversations.initialize(ConversationOpenRequest.OpenExisting(row.id, access)) }
            view = lease
            val completed = awaitUi(60_000) { query.conversationUiModel(lease).first { model ->
                val last = model?.snapshot?.currentMessages()?.lastOrNull()
                model != null && last?.role == MessageRole.ASSISTANT && model.presentation.activeTurnId == null &&
                    (last.terminalStatus != null || last.parts.filterIsInstance<UIMessagePart.Step>().lastOrNull()?.outcome != null)
            }!! }
            val transcript = completed.snapshot.currentMessages()
            val answer = transcript.last()
            assertNull("${answer.terminalReason}: ${answer.terminalDetail}", answer.terminalStatus)
            assertEquals(StepOutcome.Final, answer.parts.filterIsInstance<UIMessagePart.Step>().last().outcome)
            assertEquals(expectedAnswer, answer.toText())
            assertEquals(listOf(prompt), transcript.filter { it.role == MessageRole.USER }.map { it.toText() })
            assertTrue(completed.snapshot.context.messages.values.none { it.hasExternalUpdate })
            val durable = runBlocking { requireNotNull(repository.getConversationSnapshotById(row.id)) }
            val saved = requireNotNull(durable.opening)
            assertEquals(definition, saved.definition)
            assertEquals(snapshot.getValue("releaseId").jsonPrimitive.content, saved.releaseId)
            assertEquals(snapshot.getValue("snapshotHash").jsonPrimitive.content, saved.snapshotHash)
            assertEquals(snapshot.getValue("managedGeneration").jsonPrimitive.long, saved.generation)
            assertEquals(1, durable.contextAdmissions.size)
            // The real-Core harness verifies actual adapter requests independently of device projections.
            if (server != null) {
                val request = synchronized(server.modelRequests) { server.modelRequests.toList() }.single { body -> body.getValue("messages").jsonArray.any {
                    it.jsonObject["role"]?.jsonPrimitive?.content == "system" && it.toString().contains("STARTER_V5_SYSTEM_LITERAL")
                } }
                val messages = request.getValue("messages").jsonArray.map { it.jsonObject }
                val wireSystem = messages.first { it["role"]?.jsonPrimitive?.content == "system" }.getValue("content").jsonPrimitive.content
                assertTrue(wireSystem.contains(system))
                assertFalse(wireSystem.contains("ASSISTANT_FALLBACK_MUST_NOT_WIN"))
                val userWire = messages.single { it["role"]?.jsonPrimitive?.content == "user" &&
                    it.getValue("content").toString().contains("FIRST_BACKGROUND") }.getValue("content").toString()
                assertTrue(userWire.contains("FIRST_BACKGROUND"))
                assertTrue(userWire.contains("{{unchanged}}"))
                assertTrue(userWire.indexOf("FIRST_BACKGROUND") < userWire.indexOf("SECOND_BACKGROUND"))
                assertTrue(userWire.contains(prompt))
                File(evidence, "provider-request.json").writeText(request.toString())
            }
            capture(evidence, "02-first-send.png")
            val details = runBlocking { query.contextDetails(lease, row.id, answer.id) }.requests.single()
            val item = details.items.single { ConversationContextCategory.OPENING in it.categories }
            val original = runBlocking { query.contextContent(lease, row.id, answer.id, details.id, item.key).text }
            val originalBlocks = Json.parseToJsonElement(original).jsonObject.getValue("blocks").jsonArray
            assertEquals(backgrounds, originalBlocks.map { it.jsonObject })
            val systemItem = details.items.single { ConversationContextCategory.SYSTEM in it.categories }
            val admittedSystem = runBlocking { query.contextContent(lease, row.id, answer.id, details.id, systemItem.key).text }
            assertTrue(admittedSystem.contains(system))
            openContext(uiContext, expectedAnswer)
            compose.onNodeWithText(uiContext.getString(R.string.context_opening)).performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText(uiContext.getString(R.string.context_raw_input)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(original).assertDoesNotExist()
            capture(evidence, "03-opening-structured-detail.png")
            compose.onNodeWithText(uiContext.getString(R.string.context_raw_input)).performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText(original).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(original).performScrollTo().assertIsDisplayed()
            capture(evidence, "03-opening-detail.png")
            compose.onNodeWithContentDescription(uiContext.getString(R.string.update_card_close)).performClick()
            view.close(); view = null
            activity.close(); activity = null
            // A direct repository read proves Room, independently of whether the runtime has been evicted.
            val reopened = runBlocking { requireNotNull(repository.getConversationSnapshotById(row.id)) }
            assertEquals(saved, reopened.opening)
            assertEquals(durable.modelContextEntries, reopened.modelContextEntries)
            assertEquals(durable.contextAdmissions, reopened.contextAdmissions)
            context.writeBooleanPreference("create_new_conversation_on_start", false)
            activity = ActivityScenario.launch(Intent(context, RouteActivity::class.java).putExtra("conversationId", row.id.toString()))
            activity.onActivity { uiContext = it }
            compose.waitUntil(30_000) { compose.onAllNodesWithText(expectedAnswer).fetchSemanticsNodes().isNotEmpty() }
            openContext(uiContext, expectedAnswer)
            compose.onNodeWithText(uiContext.getString(R.string.context_opening)).performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText(uiContext.getString(R.string.context_raw_input)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(uiContext.getString(R.string.context_raw_input)).performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText(original).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(original).performScrollTo().assertIsDisplayed()
            capture(evidence, "04-chat-reopened-detail.png")
            File(evidence, "evidence.txt").writeText("schema=5\nhttp_sync=true\nentry=actual_empty_chat_card\n" +
                "real_chat_vm=true\nprovider_adapter=true\nroom_readback=true\nactivity_reopen=true\nrelease=${saved.releaseId}\n" +
                "snapshot_hash=${saved.snapshotHash}\nordered_backgrounds=${backgrounds.size}\ncore_http=$core\nresult=PASS\n")
        } catch (error: Throwable) {
            failure = error
            try {
                capture(evidence, "failure.png")
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                File(evidence, "failure-semantics.txt").writeText(roots.fetchSemanticsNodes().indices
                    .joinToString("\n\n") { roots[it].printToString() })
            } catch (diagnostic: Throwable) { error.addSuppressed(diagnostic) }
            throw error
        } finally {
            val primaryFailure = failure
            fun cleanup(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            cleanup { activity?.close() }
            cleanup { view?.close() }
            cleanup { runBlocking { withTimeout(30_000) {
                val current = (sessions.state.value as EnterpriseState.Available).manifest
                current.session?.let { session ->
                    check(session.identity.authority.deploymentId == deploymentId) { "refuse_foreign_fixture_cleanup" }
                    val access = requireNotNull(sessions.captureExitRequest()).access
                    createdId?.let { id ->
                        val lease = conversations.initialize(ConversationOpenRequest.OpenExisting(id, access))
                        try { conversations.delete(lease.commandTarget) } finally { lease.close() }
                    }
                    val result = enterprise.exit(requireNotNull(enterprise.captureExitRequest()))
                    check(result.maintenanceFailure == null && result.remoteLogoutFailure == null) { "starter_exit_cleanup_failed: $result" }
                }
            } } }
            cleanup { context.writeBooleanPreference("create_new_conversation_on_start", priorNewChat) }
            cleanup {
                val current = coil3.SingletonImageLoader.get(context)
                coil3.SingletonImageLoader.setUnsafe(priorImageLoader)
                if (current !== priorImageLoader) current.shutdown()
            }
            cleanup { server?.close() }
            if (primaryFailure == null) failure?.let { throw it }
        }
    }

    private fun openContext(context: Context, expectedAnswer: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().size == 1 }
        val moreLabel = context.getString(R.string.more_options)
        val answerText = hasText(expectedAnswer, substring = false) and !hasClickAction()
        fun assistantMore(): androidx.compose.ui.semantics.SemanticsNode? {
            val input = compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().singleOrNull() ?: return null
            val inputTop = input.boundsInRoot.top
            // Auxiliary generation deliberately returns the same text. Its title, suggestion and
            // hidden drawer entries are clickable; only the displayed message body is the anchor.
            val answer = compose.onAllNodes(answerText).fetchSemanticsNodes().filter { node ->
                val bounds = node.boundsInRoot
                bounds.width > 0f && bounds.height > 0f && bounds.left >= 0f && bounds.top >= 0f &&
                    bounds.bottom < inputTop && compose.onNode(SemanticsMatcher("visible reply body") {
                        it.id == node.id
                    }).isDisplayed()
            }.singleOrNull() ?: return null
            return compose.onAllNodesWithContentDescription(moreLabel).fetchSemanticsNodes().filter {
                it.boundsInRoot.left >= 0f && it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f &&
                    it.boundsInRoot.top >= answer.boundsInRoot.bottom && it.boundsInRoot.bottom <= inputTop
            }.singleOrNull()
        }
        // Durable completion precedes the assistant action row's appearance. Never choose the
        // earlier USER row simply because its More button is already in the semantics tree.
        compose.waitUntil(30_000) { assistantMore() != null }
        val more = requireNotNull(assistantMore())
        compose.onNode(SemanticsMatcher("assistant message More") { it.id == more.id }).assertIsDisplayed().performClick()
        val contextAction = hasText(context.getString(R.string.context_title)) and hasClickAction()
        // Modal content is composed before its entrance completes; an off-screen semantic node
        // is not yet a tappable menu action.
        compose.waitUntil(30_000) {
            compose.onAllNodes(contextAction).fetchSemanticsNodes().size == 1 && compose.onNode(contextAction).isDisplayed()
        }
        compose.onNode(contextAction).assertIsDisplayed().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText(context.getString(R.string.context_opening)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun <T> awaitUi(timeout: Long, action: suspend () -> T): T {
        val scope = CoroutineScope(Dispatchers.Default)
        val pending = scope.async { withTimeout(timeout) { action() } }
        return try { compose.waitUntil(timeout + 5_000) { pending.isCompleted }; runBlocking { pending.await() } }
        finally { scope.cancel() }
    }

    private fun capture(directory: File, name: String) {
        if (name != "failure.png") {
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}

/** Local Core control and OpenAI runtime endpoints, with captured requests and no upstream forwarding. */
private class StarterV5HttpMock(private val fixture: JsonObject) : AutoCloseable {
    private val socket = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
    val origin = "http://127.0.0.1:${socket.localPort}"
    val deploymentId = fixture.getValue("snapshot").jsonObject.getValue("deploymentId").jsonPrimitive.content
    val enrollments = AtomicInteger()
    val downloads = AtomicInteger()
    val appliedReports = AtomicInteger()
    val modelRequests = Collections.synchronizedList(mutableListOf<JsonObject>())
    private val handlers = Executors.newCachedThreadPool()
    private val worker = thread(name = "starter-v5-http") {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: java.net.SocketException) { break }
            handlers.submit { client.use(::serve) }
        }
    }
    private val expiry = Instant.now().plusSeconds(7200).toString()
    private fun live(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) ->
            if (key.endsWith("ExpiresAt") || key == "expiresAt") JsonPrimitive(expiry) else live(child)
        })
        is JsonArray -> JsonArray(value.map(::live))
        else -> value
    }
    private fun serve(client: Socket) {
        client.soTimeout = 30_000
        val input = client.getInputStream().buffered()
        fun line(): String = buildString {
            while (true) { val b = input.read(); if (b < 0 || b == 10) break; if (b != 13) append(b.toChar()) }
        }
        val path = line().split(' ').getOrNull(1)?.substringBefore('?') ?: return
        val headers = buildMap { while (true) {
            val value = line(); if (value.isEmpty()) break
            put(value.substringBefore(':').lowercase(), value.substringAfter(':').trim())
        } }
        val bytes = ByteArray(headers["content-length"]?.toInt() ?: 0)
        var offset = 0
        while (offset < bytes.size) { val count = input.read(bytes, offset, bytes.size - offset); check(count > 0); offset += count }
        var status = 200
        var type = "application/json"
        var extra = ""
        val body = when {
            path == "/.well-known/measix" -> fixture.getValue("discovery").toString()
            path.endsWith("/enrollments/exchange") -> { enrollments.incrementAndGet(); status = 201; live(fixture.getValue("enrollment-response")).toString() }
            path.endsWith("/bootstrap") -> live(fixture.getValue("bootstrap")).toString()
            path.endsWith("/sessions/refresh") -> live(fixture.getValue("refresh-response")).toString()
            path.endsWith("/managed/state") -> fixture.getValue("bootstrap").jsonObject.getValue("managedState").toString()
            path.endsWith("/managed/snapshots/42") -> {
                downloads.incrementAndGet()
                extra = "ETag: \"${fixture.getValue("snapshot").jsonObject.getValue("snapshotHash").jsonPrimitive.content}\"\r\n"
                fixture.getValue("snapshot").toString()
            }
            path.endsWith("/managed/applied") -> { appliedReports.incrementAndGet(); status = 204; "" }
            path.endsWith("/sessions/logout") -> { status = 204; "" }
            path.startsWith("/runtime/v1/resources/") && path.endsWith("/v1/chat/completions") -> {
                val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                modelRequests += request
                if (request["stream"]?.jsonPrimitive?.booleanOrNull == true) {
                    type = "text/event-stream"
                    "data: {\"id\":\"mock-v5\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"starter-v5-mock\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"V5 mock response\"},\"finish_reason\":null}]}\n\n" +
                        "data: {\"id\":\"mock-v5\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"starter-v5-mock\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                } else """{"id":"mock-v5","object":"chat.completion","created":1,"model":"starter-v5-mock","choices":[{"index":0,"message":{"role":"assistant","content":"V5 mock response"},"finish_reason":"stop"}]}"""
            }
            else -> { status = 404; """{"unexpected":"$path"}""" }
        }
        val content = body.toByteArray(Charsets.UTF_8)
        client.getOutputStream().apply {
            write("HTTP/1.1 $status OK\r\nContent-Type: $type\r\nContent-Length: ${content.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
            write(content); flush()
        }
    }
    override fun close() { socket.close(); worker.join(5000); handlers.shutdown(); check(handlers.awaitTermination(30, TimeUnit.SECONDS)) }
}
