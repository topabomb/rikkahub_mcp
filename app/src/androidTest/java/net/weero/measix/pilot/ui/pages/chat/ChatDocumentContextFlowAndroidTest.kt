package net.weero.measix.pilot.ui.pages.chat

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.R
import net.weero.measix.pilot.RouteActivity
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.ui.hooks.readBooleanPreference
import net.weero.measix.pilot.ui.hooks.writeBooleanPreference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

/** Only the system picker result and HTTP are fixtures; ChatPage, import, admission and Room are real. */
@RunWith(AndroidJUnit4::class)
class ChatDocumentContextFlowAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Test fun documentTimeAndTemplateKeepTheirSourcesThroughUploadSendAndReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val sessions = koin.get<EnterpriseSessionController>()
        val query = koin.get<ConversationQueryService>()
        val conversations = koin.get<ConversationApplicationService>()
        koin.get<ApplicationRecoveryCoordinator>()
        runBlocking { withTimeout(30_000) { koin.get<ApplicationRecoveryGate>().awaitReady() } }
        val before = runBlocking { settings.userSettings.first { !it.init } }
        val priorRealm = runBlocking { sessions.readPresentation() }.selection
        val priorConversation = runBlocking { settings.lastConversation(ConfigurationScope.Personal) }
        val oldNewChat = context.readBooleanPreference("create_new_conversation_on_start", true)
        val previousImageLoader = coil3.SingletonImageLoader.get(context)
        val marker = "DOCUMENT_CONTEXT_UI_${Uuid.random()}"
        val model = Model(modelId = "deepseek-document-mock", displayName = "Document Context Mock")
        val assistant = Assistant(name = "Document Context UI", chatModelId = model.id, systemPrompt = marker,
            streamOutput = false, localTools = emptyList(), enableMemory = false, enableTimeReminder = true,
            messageTemplate = "USER_TEMPLATE[{{ message }}]")
        val server = DocumentContextMockServer(marker)
        val provider = ProviderSetting.OpenAI(name = "Document Context HTTP", models = listOf(model),
            apiKey = "local-test-only", baseUrl = "http://127.0.0.1:${server.port}/v1")
        val document = File(context.cacheDir, "context-literals-${Uuid.random()}.txt")
        val originalDocument = "Document literals: {{name}} / {{ message }}\n````\nFour backticks stay unchanged.\n````\nEND_DOCUMENT"
        document.writeText(originalDocument, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", document)
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); addCategory(Intent.CATEGORY_DEFAULT); addDataType("*/*")
        }
        val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK,
            Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)), true)
        var activity: ActivityScenario<RouteActivity>? = null
        var view: ConversationViewLease? = null
        var testFailure: Throwable? = null
        try {
            runBlocking {
                sessions.selectPersonalFixture()
                assertTrue(query.recentConversations(RealmAccess.Personal, assistant.id, Int.MAX_VALUE).isEmpty())
                settings.updateLocal { it.copy(providers = it.providers + provider, assistants = it.assistants + assistant,
                    assistantId = assistant.id, chatModelId = model.id, fastModelId = model.id, titleModelId = model.id,
                    enableSuggestion = false) }
            }
            context.writeBooleanPreference("create_new_conversation_on_start", true)
            coil3.SingletonImageLoader.reset()
            activity = ActivityScenario.launch(Intent(context, RouteActivity::class.java))
            var uiContext: Context = context
            activity.onActivity { uiContext = it }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty() }
            val inputMore = compose.onAllNodesWithContentDescription(uiContext.getString(R.string.more_options))
                .fetchSemanticsNodes().maxBy { it.boundsInRoot.bottom }
            compose.onNode(SemanticsMatcher("input More") { it.id == inputMore.id }).performClick()
            compose.onNodeWithText(uiContext.getString(R.string.upload_file)).performScrollTo().performClick()
            compose.waitUntil(30_000) { monitor.hits == 1 && compose.onAllNodesWithText(document.name).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, monitor.hits)
            capture(context, "01-uploaded-draft.png")
            val userText = "Read the attached document as literal data."
            compose.onNodeWithTag("chat_input").performTextInput(userText)
            closeSoftKeyboard()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("chat_send_button") and isEnabled()).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("chat_send_button").performClick()
            compose.waitUntil(60_000) { compose.onAllNodesWithText("Document context answer", substring = true).fetchSemanticsNodes().isNotEmpty() }
            val row = awaitUi { query.conversationsOfAssistant(assistant.id).map { it.getOrThrow() }.first { it.isNotEmpty() }.single() }
            val lease = runBlocking { conversations.initialize(ConversationOpenRequest.OpenExisting(row.id, RealmAccess.Personal)) }
            view = lease
            awaitUi { query.conversationUiModel(lease).first { it != null && it.presentation.activeTurnId == null } }
            val saved = runBlocking { requireNotNull(query.aggregateSnapshot(row.id)) }
            val user = saved.currentMessages().single { it.role == MessageRole.USER }
            assertEquals(listOf(userText), user.parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
            val durableDocument = user.parts.filterIsInstance<UIMessagePart.Document>().single()
            assertEquals(document.name, durableDocument.fileName)
            assertNotEquals(uri.toString(), durableDocument.url)
            val admission = saved.contextAdmissions.single()
            val attachmentEntries = saved.modelContextEntries.filter { it.payload.source is ConversationContextSource.Attachment }
            assertEquals(setOf(AttachmentContextInput.DOCUMENT_TEXT, AttachmentContextInput.REFERENCE_ONLY),
                attachmentEntries.map { (it.payload.source as ConversationContextSource.Attachment).input }.toSet())
            val attachment = attachmentEntries.single {
                (it.payload.source as ConversationContextSource.Attachment).input == AttachmentContextInput.DOCUMENT_TEXT }
            val source = attachment.payload.source as ConversationContextSource.Attachment
            assertEquals(AttachmentContextInput.DOCUMENT_TEXT, source.input)
            assertEquals(user.id, source.message.messageId)
            assertEquals(user.parts.indexOf(durableDocument), source.partIndex)
            assertEquals(durableDocument.url, source.originalReference)
            val attachmentUse = admission.uses.single { it.entryId == attachment.id }
            val documentPosition = attachmentUse.placement as ContextPlacement.MessagePart
            assertEquals(source.message, documentPosition.message)
            val time = saved.modelContextEntries.single { it.payload.source is ConversationContextSource.MessageTime }
            val timeSource = time.payload.source as ConversationContextSource.MessageTime
            assertEquals(user.id, timeSource.message.messageId)
            assertEquals(user.createdAt.toString(), timeSource.messageTime)
            assertNull(timeSource.previous)
            assertNull(timeSource.previousTime)
            assertEquals(ContextPlacement.BeforeMessage(source.message), admission.uses.single { it.entryId == time.id }.placement)
            assertFalse(projectConversationContextSummary(saved).messages.values.any { it.updates.isNotEmpty() })
            compose.onAllNodesWithContentDescription(uiContext.getString(R.string.context_updated_accessibility)).assertCountEquals(0)
            val details = runBlocking { query.contextDetails(lease, row.id, admission.owner.messageId) }.requests.single()
            val documentItem = details.items.single { it.entryId == attachment.id }
            val timeItem = details.items.single { it.entryId == time.id }
            assertEquals(listOf(ConversationContextCategory.ATTACHMENT), documentItem.categories)
            assertEquals(listOf(ConversationContextCategory.TIME), timeItem.categories)
            assertTrue(requireNotNull(documentItem.location).contains(documentPosition.toString()))
            fun content(item: ConversationContextItemUiModel) = runBlocking {
                query.contextContent(lease, row.id, admission.owner.messageId, details.id, item.key)
            }
            val documentContent = content(documentItem)
            val documentBody = documentContent.text
            val timeBody = content(timeItem).text
            assertTrue(documentBody.startsWith("<UploadFile name=\"${document.name}\""))
            assertTrue(documentBody.contains("\n`````\n$originalDocument\n`````\n</UploadFile>"))
            assertTrue(documentBody.contains("{{name}} / {{ message }}"))
            assertFalse(documentBody.contains("USER_TEMPLATE["))
            assertTrue(timeBody.startsWith("<time_reminder>Message time: "))
            assertTrue(timeBody.endsWith("</time_reminder>"))
            assertFalse(timeBody.contains("USER_TEMPLATE["))
            assertEquals(java.time.LocalDateTime.parse(timeSource.messageTime), java.time.OffsetDateTime.parse(
                timeBody.removePrefix("<time_reminder>Message time: ").removeSuffix("</time_reminder>")).toLocalDateTime())
            val wire = server.requests.single()
            val messages = wire.getValue("messages").jsonArray.map { it.jsonObject }
            fun textParts(message: JsonObject): List<String> = when (val content = message["content"]) {
                is JsonPrimitive -> listOf(content.content)
                is JsonArray -> content.map { it.jsonObject.getValue("text").jsonPrimitive.content }
                else -> emptyList()
            }
            val wireUser = messages.single { textParts(it).contains("USER_TEMPLATE[$userText]") }
            assertEquals(documentBody, textParts(wireUser)[documentPosition.partIndex])
            assertEquals(1, messages.flatMap(::textParts).count { it == timeBody })
            assertTrue(messages.indexOfFirst { timeBody in textParts(it) } < messages.indexOf(wireUser))
            assertEquals(1, messages.flatMap(::textParts).count { it.startsWith("USER_TEMPLATE[") })
            assertEquals(1, messages.flatMap(::textParts).count { it == documentBody })
            assertNotNull(documentContent.source)
            compose.onAllNodesWithText(documentBody).assertCountEquals(0)
            compose.onAllNodesWithText(timeBody).assertCountEquals(0)
            capture(context, "02-document-and-time-kept-out-of-transcript.png")

            activity.close(); activity = null
            lease.close(); view = null
            context.writeBooleanPreference("create_new_conversation_on_start", false)
            runBlocking { settings.rememberConversation(ConfigurationScope.Personal, row.id) }
            activity = ActivityScenario.launch(Intent(context, RouteActivity::class.java))
            activity.onActivity { uiContext = it }
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Document context answer", substring = true).fetchSemanticsNodes().isNotEmpty() }
            val reopened = runBlocking { conversations.initialize(ConversationOpenRequest.OpenExisting(row.id, RealmAccess.Personal)) }
            view = reopened
            val restored = runBlocking { requireNotNull(query.aggregateSnapshot(row.id)) }
            assertEquals(saved.contextAdmissions, restored.contextAdmissions)
            assertEquals(saved.modelContextEntries, restored.modelContextEntries)
            val restoredDetails = runBlocking { query.contextDetails(reopened, row.id, admission.owner.messageId) }.requests.single()
            assertEquals(details, restoredDetails)
            assertEquals(documentBody, runBlocking { query.contextContent(reopened, row.id, admission.owner.messageId,
                details.id, documentItem.key).text })
            assertEquals(timeBody, runBlocking { query.contextContent(reopened, row.id, admission.owner.messageId,
                details.id, timeItem.key).text })
            compose.onAllNodesWithText(documentBody).assertCountEquals(0)
            compose.onAllNodesWithText(timeBody).assertCountEquals(0)
            capture(context, "03-reopened-document-chat.png")
            compose.onAllNodesWithContentDescription(uiContext.getString(R.string.context_updated_accessibility)).assertCountEquals(0)
            assertEquals(1, server.requests.size)
            assertNull(server.failure)
            File(outputDirectory(context), "request.json").writeText(wire.toString(), Charsets.UTF_8)
        } catch (error: Throwable) {
            testFailure = error
            try { capture(context, "failure.png")
                File(outputDirectory(context), "failure-semantics.txt").writeText(
                    compose.onAllNodes(isRoot()).fetchSemanticsNodes().joinToString("\n") { root ->
                        compose.onNode(SemanticsMatcher("root") { it.id == root.id }).printToString()
                    }
                )
            } catch (diagnostic: Throwable) { error.addSuppressed(diagnostic) }
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) { try { action() } catch (error: Throwable) {
                val primary = testFailure ?: cleanupFailure
                if (primary == null) cleanupFailure = error else if (primary !== error) primary.addSuppressed(error)
            } }
            cleanup { instrumentation.removeMonitor(monitor) }
            cleanup { activity?.close() }; cleanup { view?.close() }
            cleanup { runBlocking {
                query.recentConversations(RealmAccess.Personal, assistant.id, Int.MAX_VALUE).forEach { row ->
                    val lease = conversations.initialize(ConversationOpenRequest.OpenExisting(row.id, RealmAccess.Personal))
                    try { withTimeout(30_000) { conversations.stopGeneration(lease.commandTarget) }; conversations.delete(lease.commandTarget) }
                    finally { lease.close() }
                }
            } }
            cleanup { runBlocking {
                settings.updateLocal { it.copy(providers = it.providers.filterNot { value -> value.id == provider.id },
                    assistants = it.assistants.filterNot { value -> value.id == assistant.id },
                    assistantId = before.assistantId, chatModelId = before.chatModelId, fastModelId = before.fastModelId,
                    titleModelId = before.titleModelId, enableSuggestion = before.enableSuggestion) }
                settings.rememberConversation(ConfigurationScope.Personal, priorConversation)
                if (priorRealm?.access is RealmAccess.Enterprise) sessions.selectEnterpriseFixture()
            } }
            cleanup { context.writeBooleanPreference("create_new_conversation_on_start", oldNewChat) }
            cleanup { val loader = coil3.SingletonImageLoader.get(context)
                coil3.SingletonImageLoader.setUnsafe(previousImageLoader)
                if (loader !== previousImageLoader) loader.shutdown()
            }
            cleanup { check(!document.exists() || document.delete()) { "fixture_document_delete_failed" } }
            cleanup { server.close() }
            cleanupFailure?.let { throw it }
        }
    }

    private fun <T> awaitUi(action: suspend () -> T): T {
        val scope = CoroutineScope(Dispatchers.Default)
        val pending = scope.async { withTimeout(30_000) { action() } }
        return try { compose.waitUntil(35_000) { pending.isCompleted }; runBlocking { pending.await() } }
        finally { scope.cancel() }
    }

    private fun capture(context: Context, name: String) {
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(outputDirectory(context), name).outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
    }

    private fun outputDirectory(context: Context): File {
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        return (output?.let { File(it, "document-context-ui-evidence") }
            ?: File(context.getExternalFilesDir(null), "document-context-ui-evidence")).apply { check(isDirectory || mkdirs()) }
    }
}

private class DocumentContextMockServer(private val marker: String) : AutoCloseable {
    private val socket = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
    val port = socket.localPort
    val requests: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    @Volatile var failure: Throwable? = null
    private val worker = thread(name = "document-context-http", isDaemon = true) {
        try { while (!socket.isClosed) socket.accept().use(::respond) }
        catch (error: Throwable) { if (!socket.isClosed) failure = error }
    }
    private fun respond(client: Socket) {
        client.soTimeout = 30_000
        val input = client.getInputStream().buffered()
        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) { val value = input.read(); check(value >= 0) { "mock_request_header_incomplete" }
                if (value == 10) break; if (value != 13) bytes.write(value) }
            return bytes.toString(Charsets.US_ASCII.name())
        }
        check(line().startsWith("POST ")) { "mock_request_method" }
        val headers = mutableMapOf<String, String>()
        while (true) { val value = line(); if (value.isEmpty()) break
            headers[value.substringBefore(':').lowercase()] = value.substringAfter(':').trim() }
        val request = Json.parseToJsonElement(input.readNBytes(requireNotNull(headers["content-length"]).toInt()).decodeToString()).jsonObject
        val main = request.getValue("messages").jsonArray.any { item -> item.jsonObject.let {
            it["role"]?.jsonPrimitive?.content == "system" && it["content"].toString().contains(marker) } }
        if (main) requests += request
        val response = buildJsonObject {
            put("id", "document-local-mock"); put("object", "chat.completion"); put("created", 1); put("model", request.getValue("model"))
            putJsonArray("choices") { addJsonObject { put("index", 0); put("finish_reason", "stop")
                putJsonObject("message") { put("role", "assistant"); put("content", if (main) "Document context answer" else "Document context title") } } }
            putJsonObject("usage") { put("prompt_tokens", 10); put("completion_tokens", 5); put("total_tokens", 15) }
        }.toString().encodeToByteArray()
        client.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            write(response); flush()
        }
    }
    override fun close() { socket.close(); worker.join(5_000) }
}
