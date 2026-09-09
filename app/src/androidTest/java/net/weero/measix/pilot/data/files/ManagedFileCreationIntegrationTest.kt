package net.weero.measix.pilot.data.files

import net.weero.measix.pilot.data.configuration.ConfigurationScope

import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.toPixelMap
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import net.weero.measix.pilot.data.datastore.UserSettingsMigration
import net.weero.measix.pilot.data.datastore.ScopedUserPreferences
import net.weero.measix.pilot.data.configuration.AssistantUsagePreferences
import net.weero.measix.pilot.data.configuration.UsageValue
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.service.runtime.toPresentationSnapshot
import net.weero.measix.pilot.data.model.Assistant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.utils.JsonInstant
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.data.repository.GenMediaRepository
import net.weero.measix.pilot.data.db.entity.GenMediaEntity
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactState
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Android filesystem publication and real Room uniqueness, not a mocked DAO contract. */
@RunWith(AndroidJUnit4::class)
class ManagedFileCreationIntegrationTest {
    @get:org.junit.Rule
    val compose = androidx.compose.ui.test.junit4.v2.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private lateinit var root: File
    private lateinit var database: AppDatabase
    private lateinit var appScope: AppScope
    private lateinit var store: ArtifactStore
    private lateinit var payloadContext: Context
    private lateinit var settings: SettingsStore
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var settingsCoordinator: ArtifactSettingsCoordinator

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        root = Files.createTempDirectory(application.cacheDir.toPath(), "managed-file-test-").toFile()
        payloadContext = object : ContextWrapper(application) {
            override fun getFilesDir(): File = root
        }
        database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        appScope = AppScope()
        preferences = PreferenceDataStoreFactory.create(scope = appScope, migrations = listOf(UserSettingsMigration()),
            produceFile = { File(root, "settings.preferences_pb") })
        settings = SettingsStore(payloadContext, appScope, dataStore = preferences)
        settingsCoordinator = ArtifactSettingsCoordinator(settings)
        store = newStore()
    }

    private fun newStore(): ArtifactStore = ArtifactStore(
        payloadStore = ArtifactPayloadStore(payloadContext),
        artifactDAO = database.artifactDao(),
        artifactReferenceDAO = database.artifactReferenceDao(),
        systemMetaDAO = database.systemMetaDao(),
        conversationDAO = database.conversationDao(),
        messageNodeDAO = database.messageNodeDao(),
        settingsCoordinator = settingsCoordinator,
        transactionRunner = RoomDatabaseTransactionRunner(database),
        fileNameCandidates = { listOf("aaaaaa", "bbbbbbb", "cccccccc", "Dddddddd") },
    )

    @After
    fun tearDown() {
        runBlocking { appScope.coroutineContext[Job]!!.cancelAndJoin() }
        database.close()
        check(root.deleteRecursively())
    }

    @Test fun sharedConfigurationImagesRequireDurableConfigurationRoots() = runBlocking {
        store.ensureReferenceProjection()
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "configuration-image-session")))
        sessions.recover()
        val files = FileManagementApplicationService(store, GeneratedMediaStore(root, GenMediaRepository(database.genMediaDao()), store),
            ApplicationRecoveryGate().apply { ready() }, sessions)
        val owned = store.createFromBytes(ConfigurationScope.Personal, pngBytes(), "shared.png", "image/png", origin = ArtifactOrigin.USER)
        assertTrue(runCatching { files.resolveConfigurationImage(owned.uri.toString()) }.isFailure)
        val assistant = net.weero.measix.pilot.data.model.Assistant(background = owned.uri.toString())
        store.updateSettingsReferences { it.copy(assistants = it.assistants + assistant) }
        val image = requireNotNull(files.resolveConfigurationImage(owned.uri.toString()))
        assertArrayEquals(pngBytes(), image.readBytes())
        val packet = payloadContext.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET).use(EnterprisePackageCodec::decode)
        sessions.enrollLocal(packet.identity, { packet.identity }, { packet })
        assertArrayEquals(pngBytes(), image.readBytes())
        store.updateSettingsReferences { it.copy(assistants = it.assistants.filterNot { a -> a.id == assistant.id }) }
        assertTrue(runCatching { image.requireAccess() }.isFailure)
        assertTrue(runCatching { image.readBytes() }.isFailure)
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Test
    fun mountedInputThumbnailRecoversWhenRejectedSubmissionReturnsOwnership() {
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "input-painter")))
        val selected = runBlocking { sessions.recover(); requireNotNull(sessions.observeSelectedRealmSelection().first()) }
        val view = ConversationViewLease(kotlin.uuid.Uuid.random(), selected.access, selected.revision) {}
        val draft = ArtifactUseCase(store, ApplicationRecoveryGate().apply { ready() }, sessions).openDraftScope(view)
        val input = File(root, "red.png")
        android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                bitmap.eraseColor(android.graphics.Color.RED)
                input.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
        val imported = runBlocking { draft.importUrisOrThrow(listOf(android.net.Uri.fromFile(input))).single() }
        val part = UIMessagePart.Image(imported.uri.toString())
        val state = net.weero.measix.pilot.ui.hooks.ChatInputState().apply { messageContent = listOf(part) }
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val original = coil3.SingletonImageLoader.get(payloadContext)
        val loader = coil3.ImageLoader.Builder(payloadContext).components {
            add(coil3.intercept.Interceptor { chain ->
                if (chain.request.data is ImageSource && first.compareAndSet(true, false)) {
                    entered.complete(Unit)
                    try { resume.await(); chain.proceed() } finally { finished.complete(Unit) }
                } else chain.proceed()
            })
            add(ImageSourceInterceptor); add(ImageSourceKeyer); add(ImageSourceFetcherFactory)
        }.build()
        coil3.SingletonImageLoader.setUnsafe(loader)
        try {
            compose.setContent { androidx.compose.material3.MaterialTheme {
                net.weero.measix.pilot.ui.components.ai.MediaFileInputRow(state, draft)
            } }
            compose.waitUntil(30_000) { entered.isCompleted }
            val submission = runBlocking { draft.claimSubmission(draft.target, listOf(part)) }
            resume.complete(Unit)
            compose.waitUntil(30_000) { finished.isCompleted }
            compose.waitForIdle()
            runBlocking { draft.returnUnaccepted(submission) }
            compose.waitUntil(30_000) {
                val pixels = compose.onRoot().captureToImage().toPixelMap()
                var red = 0
                for (y in 0 until pixels.height step 4) for (x in 0 until pixels.width step 4) {
                    val color = pixels[x, y]
                    if (color.red > 0.8f && color.green < 0.2f && color.blue < 0.2f) red++
                }
                red > 30
            }
            compose.runOnIdle { assertEquals(listOf(part), state.messageContent) }
        } finally {
            resume.complete(Unit)
            coil3.SingletonImageLoader.setUnsafe(original)
            loader.shutdown(); draft.close(); view.close()
        }
    }

    @Test
    fun draftImageReadsFollowCreationOwnershipAndRejectCachedReadsAfterRelease() = runBlocking {
        store.ensureReferenceProjection()
        val packet = payloadContext.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET).use(EnterprisePackageCodec::decode)
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "draft-images")))
        sessions.enrollLocal(packet.identity, { packet.identity }, { packet })
        val selected = requireNotNull(sessions.observeSelectedRealmSelection().first())
        val view = ConversationViewLease(kotlin.uuid.Uuid.random(), selected.access, selected.revision) {}
        val draft = ArtifactUseCase(store, ApplicationRecoveryGate().apply { ready() }, sessions).openDraftScope(view)
        val png = pngBytes()
        val input = File(root, "input.png").apply { writeBytes(png) }
        val imported = draft.importUrisOrThrow(listOf(android.net.Uri.fromFile(input))).single()
        val part = UIMessagePart.Image(imported.uri.toString())
        val image = requireNotNull(draft.describeInputs(listOf(part))[part.url]?.image)
        assertEquals(image, draft.describeInputs(listOf(part))[part.url]?.image)
        val loader = coil3.ImageLoader.Builder(payloadContext).components {
            add(ImageSourceInterceptor); add(ImageSourceKeyer); add(ImageSourceFetcherFactory)
        }.build()
        val request = coil3.request.ImageRequest.Builder(payloadContext).data(image).size(2, 2).build()
        try {
            assertArrayEquals(png, image.readBytes())
            assertTrue(loader.execute(request) is coil3.request.SuccessResult)
            assertEquals(coil3.decode.DataSource.MEMORY_CACHE, (loader.execute(request) as coil3.request.SuccessResult).dataSource)
            val artifact = requireNotNull(store.resolveManagedReference(net.weero.measix.pilot.data.ai.attachments.AttachmentRefs.parseFileUrl(part.url)!!))
            assertEquals(null, store.resolveImagePreviewForArtifact(selected.access.scope, artifact))
            val submission = draft.claimSubmission(draft.target, listOf(part))
            assertTrue(runCatching { image.readBytes() }.isFailure)
            assertTrue(loader.execute(request) is coil3.request.ErrorResult)
            draft.returnUnaccepted(submission)
            assertArrayEquals(png, image.readBytes())
            draft.discard(imported.uri)
            assertTrue(runCatching { image.readBytes() }.isFailure)
            assertTrue(loader.execute(request) is coil3.request.ErrorResult)

            val existing = store.createFromBytes(selected.access.scope, png, "existing.png", "image/png", origin = ArtifactOrigin.USER)
            store.abandonUnpublished(existing)
            val foreign = store.createFromBytes(ConfigurationScope.Personal, png, "foreign.png", "image/png", origin = ArtifactOrigin.USER)
            store.abandonUnpublished(foreign)
            val inputs = listOf(UIMessagePart.Image(existing.uri.toString()), UIMessagePart.Image(foreign.uri.toString()), UIMessagePart.Image(android.net.Uri.fromFile(input).toString()))
            val previews = draft.describeInputs(inputs)
            assertEquals(setOf(existing.uri.toString()), previews.keys)
            val borrowed = requireNotNull(previews[existing.uri.toString()]?.image)
            assertArrayEquals(png, borrowed.readBytes())
            draft.close()
            assertTrue(runCatching { borrowed.readBytes() }.isFailure)
            assertArrayEquals(png, store.readImage(selected.access.scope, existing.entity.id))
        } finally { loader.shutdown(); draft.close(); view.close() }
    }

    @Test fun renderedDocumentsReadOnlyTheirSourceAndNeverShareBrowserStorage() = runBlocking {
        store.ensureReferenceProjection()
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "rendered-session")))
        sessions.recover()
        val files = FileManagementApplicationService(store, GeneratedMediaStore(root, GenMediaRepository(database.genMediaDao()), store),
            ApplicationRecoveryGate().apply { ready() }, sessions)
        val own = store.createFromBytes(ConfigurationScope.Personal, pngBytes(), "configuration.png", "image/png", origin = ArtifactOrigin.USER)
        val foreign = store.createFromBytes(scopeFor(1), pngBytes(), "foreign.png", "image/png", origin = ArtifactOrigin.USER)
        store.abandonUnpublished(foreign)
        store.updateSettingsReferences { it.copy(assistants = it.assistants + Assistant(background = own.uri.toString())) }
        val attachment = requireNotNull(files.resolveContentAttachment(RenderedContentSource.UserConfiguration, requireNotNull(own.localRef.toolPath())))
        val copied = java.io.ByteArrayOutputStream()
        assertEquals("image/png", files.copyAttachmentTo(attachment, copied))
        assertArrayEquals(pngBytes(), copied.toByteArray())
        assertEquals(null, files.resolveContentAttachment(RenderedContentSource.Static, own.uri.toString()))
        val html = """<html><head><base href="https://external.example/"></head><body>
            <img id="file" src="${own.uri}"><img id="upload" src="${own.localRef.toolPath()}">
            <img id="foreign" src="${foreign.uri}"><a href="/UPLOAD/invalid">Unsupported path</a>
            <script>window.beforeCookie=document.cookie;document.cookie='marker=private;path=/';
            try { window.beforeStorage=localStorage.getItem('marker');localStorage.setItem('marker','private'); }
            catch(e) { window.beforeStorage='disabled'; }</script></body></html>"""
        val document = androidx.compose.runtime.mutableStateOf(RenderedContent(RenderedContentSource.UserConfiguration, html))
        val states = java.util.concurrent.CopyOnWriteArrayList<Pair<RenderedContentSource, Boolean>>()
        val queries = io.mockk.mockk<ConversationQueryService>()
        compose.setContent {
            val current = document.value
            val state = net.weero.measix.pilot.ui.components.webview.rememberRenderedContentState(current, files, queries)
            androidx.compose.runtime.SideEffect { states += current.source to (state != null) }
            if (state != null) androidx.compose.runtime.key(state) {
                net.weero.measix.pilot.ui.components.webview.WebView(state)
            }
        }
        fun find(view: android.view.View): android.webkit.WebView? = when (view) {
            is android.webkit.WebView -> view
            is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        suspend fun evaluate(view: android.webkit.WebView, script: String): String = withContext(Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation -> view.evaluateJavascript(script) {
                if (continuation.isActive) continuation.resumeWith(Result.success(it))
            } }
        }
        suspend fun ready(previous: android.webkit.WebView? = null): android.webkit.WebView {
            var current: android.webkit.WebView? = null
            compose.waitUntil(10_000) { current = find(compose.activity.window.decorView); current != null && current !== previous }
            val view = requireNotNull(current)
            try {
                kotlinx.coroutines.withTimeout(15_000) {
                    while (evaluate(view, "document.readyState==='complete' && document.images.length===3 && Array.from(document.images).every(i=>i.complete)") != "true") kotlinx.coroutines.delay(50)
                }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("Document failed to load: " + evaluate(view, "JSON.stringify({url:location.href,ready:document.readyState,text:document.body.innerText.slice(0,300)})"), error)
            }
            return view
        }
        val first = ready()
        assertEquals("[2,2,0]", evaluate(first, "JSON.stringify(Array.from(document.images).map(i=>i.naturalWidth))").let {
            net.weero.measix.pilot.utils.JsonInstant.decodeFromString<String>(it)
        })
        val firstOrigin = evaluate(first, "location.origin")
        assertEquals("\"disabled\"", evaluate(first, "window.beforeStorage"))
        assertEquals("\"marker=private\"", evaluate(first, "document.cookie"))
        compose.runOnIdle { document.value = RenderedContent(RenderedContentSource.Static, html) }
        val second = ready(first)
        org.junit.Assert.assertNotSame(first, second)
        org.junit.Assert.assertNotEquals(firstOrigin, evaluate(second, "location.origin"))
        assertEquals("\"\"", evaluate(second, "window.beforeCookie"))
        assertEquals("[0,0,0]", net.weero.measix.pilot.utils.JsonInstant.decodeFromString<String>(evaluate(second, "JSON.stringify(Array.from(document.images).map(i=>i.naturalWidth))")))
        val closed = ConversationViewLease(kotlin.uuid.Uuid.random(), RealmAccess.Personal, 0L) {}.apply { close() }
        val denied = RenderedContentSource.Conversation(closed)
        compose.runOnIdle { document.value = RenderedContent(denied, "<html>Private replacement</html>") }
        compose.waitUntil(5_000) { states.lastOrNull()?.first == denied }
        assertTrue(states.filter { it.first == denied }.none { it.second })
        compose.runOnIdle { assertEquals(null, find(compose.activity.window.decorView)) }
        store.updateSettingsReferences { it.copy(assistants = emptyList()) }
        assertTrue(runCatching { files.copyAttachmentTo(attachment, java.io.ByteArrayOutputStream()) }.isFailure)
    }

    @Test
    fun publicationRefreshesAnAlreadyObservedPreviewWithoutAnotherDatabaseWrite() = runBlocking {
        store.ensureReferenceProjection()
        val enterprise = scopeFor(1)
        val image = store.createFromBytes(enterprise, pngBytes(), "pending.png", "image/png", origin = ArtifactOrigin.USER)
        val conversationId = kotlin.uuid.Uuid.random().toString()
        val nodeId = kotlin.uuid.Uuid.random().toString()
        database.conversationDao().insert(net.weero.measix.pilot.data.db.entity.ConversationEntity(
            id = conversationId, assistantId = net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID.toString(),
            title = "publication", createAt = 1, updateAt = 1, chatSuggestions = "[]", isPinned = false, scope = enterprise,
        ))
        database.messageNodeDao().insertAll(listOf(net.weero.measix.pilot.data.db.entity.MessageNodeEntity(nodeId, conversationId, 0, "[]", 0)))
        database.artifactReferenceDao().insertAll(listOf(net.weero.measix.pilot.data.db.entity.ArtifactReferenceEntity(
            artifactId = image.entity.id, nodeId = nodeId,
            referenceType = net.weero.measix.pilot.data.db.entity.ArtifactReferenceType.TOOL_OUTPUT.name,
        )))
        val previews = kotlinx.coroutines.channels.Channel<String?>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val observer = launch {
            store.lifecycleChanges().collect { previews.send(store.resolveImagePreviewForArtifact(enterprise, image.localRef)?.uri) }
        }
        try {
            kotlinx.coroutines.withTimeout(20_000) { assertEquals(null, previews.receive()) }
            store.publishUnpublished(image)
            kotlinx.coroutines.withTimeout(20_000) {
                while (previews.receive() != image.uri.toString()) { }
            }
        } finally { observer.cancelAndJoin(); previews.close() }
    }

    @Test
    fun conversationPreviewsAndChildDeliverablesRejectForeignOrUnpublishedMedia() = runBlocking {
        store.ensureReferenceProjection()
        val packet = payloadContext.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET).use(EnterprisePackageCodec::decode)
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "preview-session")))
        sessions.enrollLocal(packet.identity, { packet.identity }, { packet })
        val selected = requireNotNull(sessions.observeSelectedRealmSelection().first())
        val enterprise = selected.access.scope
        val files = FileManagementApplicationService(store,
            GeneratedMediaStore(root, GenMediaRepository(database.genMediaDao()), store),
            ApplicationRecoveryGate().apply { ready() }, sessions)
        val png = pngBytes()
        val personal = store.createFromBytes(ConfigurationScope.Personal, png, "personal.png", "image/png", origin = ArtifactOrigin.USER)
        val own = store.createFromBytes(enterprise, png, "enterprise.png", "image/png", origin = ArtifactOrigin.USER)
        val document = store.createFromBytes(enterprise, "document".toByteArray(), "document.txt", "text/plain", origin = ArtifactOrigin.USER)
        assertEquals(null, store.resolveImagePreviewForArtifact(enterprise, own.localRef))
        assertEquals(null, store.resolveMediaPreviewForArtifact(enterprise, document.localRef))
        listOf(personal, own, document).forEach { store.abandonUnpublished(it) }
        assertEquals(own.uri.toString(), store.resolveImagePreviewForArtifact(enterprise, own.localRef)?.uri)
        assertEquals(document.uri.toString(), store.resolveMediaPreviewForFile(enterprise, store.file(document.entity), "text/plain")?.uri)
        assertEquals(null, store.resolveMediaPreviewForArtifact(enterprise, document.localRef.copy(mimeType = "application/pdf")))
        assertTrue(runCatching { store.resolveImagePreviewForArtifact(enterprise, personal.localRef) }.exceptionOrNull() is ArtifactProjectionException)
        assertTrue(runCatching { store.resolveMediaPreviewForFile(ConfigurationScope.Personal, store.file(document.entity)) }.exceptionOrNull() is ArtifactProjectionException)
        fun stamped(artifact: OwnedArtifact): UIMessagePart = net.weero.measix.pilot.data.ai.attachments.AttachmentRefs.withMetadata(
            UIMessagePart.Image(artifact.uri.toString()),
            net.weero.measix.pilot.data.ai.attachments.AttachmentRefs.mergeMetadata(null, mapOf(
                net.weero.measix.pilot.data.ai.attachments.AttachmentRefs.METADATA_KEY to
                    kotlinx.serialization.json.JsonPrimitive(net.weero.measix.pilot.data.ai.attachments.AttachmentRefs.format(kotlin.uuid.Uuid.random())))),
        )
        val outside = store.createText(enterprise, "archived tool result", folder = FileFolders.TOOL_OUTPUTS, origin = ArtifactOrigin.SYSTEM)
        store.abandonUnpublished(outside)
        assertEquals(null, store.resolveMediaPreviewForFile(enterprise, store.file(outside.entity)))
        val message = UIMessage(role = MessageRole.USER, parts = listOf(stamped(personal), stamped(own)))
        val tool = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            localCallId = kotlin.uuid.Uuid.random(), stepId = kotlin.uuid.Uuid.random(), providerCallId = "inspect", toolName = "inspect_attachments",
            input = """{"attachments":["${personal.localRef.toolPath()}","${own.localRef.toolPath()}"]}""",
        )))
        val conversation = net.weero.measix.pilot.data.model.Conversation.ofId(
            kotlin.uuid.Uuid.random(), net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID,
            messages = listOf(message, tool).map { it.toMessageNode() },
        ).copy(scope = enterprise)
        val snapshot = ConversationRuntimeSnapshot(conversation.toSnapshot(), null).toPresentationSnapshot()
        val view = ConversationViewLease(conversation.id, selected.access, selected.revision) {}
        val projector = ConversationAttachmentPreviewProjector(store, files)
        val previews = projector.project(snapshot, view)
        assertTrue(own.uri.toString() in previews.values.map { it.uri })
        assertFalse(personal.uri.toString() in previews.values.map { it.uri })
        assertTrue(own.localRef.toolPath() in previews.keys)
        assertFalse(personal.localRef.toolPath() in previews.keys)
        val imageSource = requireNotNull(previews[own.localRef.toolPath()]?.image)
        assertEquals(imageSource, projector.project(snapshot, view)[own.localRef.toolPath()]?.image)
        assertArrayEquals(png, imageSource.readBytes())
        view.close()
        assertTrue(runCatching { imageSource.requireAccess() }.isFailure)
        assertTrue(runCatching { imageSource.readBytes() }.isFailure)
        val reopened = ConversationViewLease(conversation.id, selected.access, selected.revision) {}
        val reopenedSource = requireNotNull(projector.project(snapshot, reopened)[own.localRef.toolPath()]?.image)
        assertFalse(imageSource == reopenedSource)
        assertArrayEquals(png, reopenedSource.readBytes())
        fun deliverable(artifact: OwnedArtifact) = net.weero.measix.pilot.data.ai.subassistant.SubAssistantExtractedArtifacts(
            artifacts = listOf(net.weero.measix.pilot.data.ai.subassistant.SubAssistantDeliverableArtifact(
                ref = net.weero.measix.pilot.data.ai.attachments.AttachmentRefs.format(kotlin.uuid.Uuid.random()),
                type = "document", mime = artifact.entity.mimeType, artifact = artifact.localRef,
            )), omitted = 0, hasNonTextOutput = true,
        )
        assertEquals(1, net.weero.measix.pilot.data.ai.subassistant.validateDeliverableArtifacts(enterprise, deliverable(document), store).artifacts.size)
        assertTrue(runCatching { net.weero.measix.pilot.data.ai.subassistant.validateDeliverableArtifacts(ConfigurationScope.Personal, deliverable(document), store) }.exceptionOrNull() is ArtifactProjectionException)
        assertTrue(store.deleteUserRequested(enterprise, own.entity.id) is ArtifactDeleteResult.Completed)
        assertTrue(projector.project(snapshot, reopened).isEmpty())
    }

    @Test
    fun attachmentExportsRetainOriginalPageAndCopyUnderArtifactLifetime() = runBlocking {
        store.ensureReferenceProjection()
        val packet = payloadContext.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET).use(EnterprisePackageCodec::decode)
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "export-session")))
        sessions.enrollLocal(packet.identity, { packet.identity }, { packet })
        val selected = requireNotNull(sessions.observeSelectedRealmSelection().first())
        val commands = FileManagementApplicationService(store, GeneratedMediaStore(root, GenMediaRepository(database.genMediaDao()), store),
            ApplicationRecoveryGate().apply { ready() }, sessions)
        val bytes = ByteArray(200_000) { (it % 127).toByte() }
        val artifact = store.createFromBytes(selected.access.scope, bytes, "report.pdf", "application/pdf", origin = ArtifactOrigin.USER)
        val personal = store.createFromBytes(ConfigurationScope.Personal, bytes, "personal.pdf", "application/pdf", origin = ArtifactOrigin.USER)
        val view = ConversationViewLease(kotlin.uuid.Uuid.random(), selected.access, selected.revision) {}
        fun preview(id: Long) = AttachmentPreview(artifact.uri.toString(), null, AttachmentPreview.FileTarget(net.weero.measix.pilot.service.RenderedContentSource.Conversation(view), id, "report.pdf"))
        assertTrue(runCatching { commands.copyAttachmentTo(preview(artifact.entity.id), java.io.ByteArrayOutputStream()) }.isFailure)
        store.abandonUnpublished(artifact)
        store.abandonUnpublished(personal)
        assertTrue(runCatching { commands.copyAttachmentTo(preview(personal.entity.id), java.io.ByteArrayOutputStream()) }.isFailure)

        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val copied = java.io.ByteArrayOutputStream()
        val output = object : java.io.OutputStream() {
            override fun write(value: Int) { error("streaming buffer expected") }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                started.complete(Unit)
                runBlocking { release.await() }
                copied.write(buffer, offset, length)
            }
        }
        val copy = async(Dispatchers.IO) { store.copyMediaTo(selected.access.scope, artifact.entity.id, output) }
        started.await()
        val deleting = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { store.deleteUserRequested(selected.access.scope, artifact.entity.id) }
        assertFalse(deleting.isCompleted)
        release.complete(Unit)
        assertEquals("application/pdf", copy.await())
        assertArrayEquals(bytes, copied.toByteArray())
        assertTrue(deleting.await() is ArtifactDeleteResult.Completed)
        assertTrue(runCatching { commands.copyAttachmentTo(preview(artifact.entity.id), java.io.ByteArrayOutputStream()) }.isFailure)

        val retained = store.createFromBytes(selected.access.scope, bytes, "report.pdf", "application/pdf", origin = ArtifactOrigin.USER)
        store.abandonUnpublished(retained)
        var intent: android.content.Intent? = null
        val exportCache = File(root, "export-cache").apply { mkdirs() }
        val exportingContext = object : ContextWrapper(compose.activity) {
            override fun getCacheDir(): File = exportCache
            override fun startActivity(value: android.content.Intent) { intent = value }
        }
        MediaExportService(commands).openAttachment(exportingContext, preview(retained.entity.id))
        val uri = requireNotNull(intent?.data)
        assertEquals("content", uri.scheme)
        assertEquals("application/pdf", intent?.type)
        assertTrue(store.deleteUserRequested(selected.access.scope, retained.entity.id) is ArtifactDeleteResult.Completed)
        assertArrayEquals(bytes, exportingContext.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        sessions.switchRealm(RealmSwitchRequest(selected, RealmAccess.Personal)) {}
        intent = null
        assertTrue(runCatching { MediaExportService(commands).openAttachment(exportingContext, preview(personal.entity.id)) }.isFailure)
        assertEquals(null, intent)
        assertEquals(1, File(exportCache, "temp").listFiles()!!.size)
        view.close()
    }

    @Test
    fun managedImageDecoderRejectsForeignMissingRevokedAndLateCachedResults() = runBlocking {
        store.ensureReferenceProjection()
        val bytes = pngBytes()
        val packet = payloadContext.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET).use(EnterprisePackageCodec::decode)
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "image-session")))
        sessions.enrollLocal(packet.identity, { packet.identity }, { packet })
        val selected = requireNotNull(sessions.observeSelectedRealmSelection().first())
        val generated = GeneratedMediaStore(root, GenMediaRepository(database.genMediaDao()), store)
        val commands = FileManagementApplicationService(store, generated, ApplicationRecoveryGate().apply { ready() }, sessions)
        suspend fun createKeys(scope: ConfigurationScope): List<ManagedFileKey> {
            val artifact = store.createFromBytes(scope, bytes, "image.png", "image/png", origin = ArtifactOrigin.USER)
            store.abandonUnpublished(artifact)
            val media = generated.commit(scope,
                me.rerere.ai.ui.ImageGenerationItem(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), "image/png"),
                "image", "model")
            return listOf(ManagedFileKey.Artifact(artifact.entity.id, selected), ManagedFileKey.Generated(media.mediaId.toInt(), selected))
        }
        val foreign = createKeys(ConfigurationScope.Personal)
        val keys = createKeys(selected.access.scope)
        for (key in foreign) assertTrue(runCatching { commands.imageSource(key).readBytes() }.isFailure)
        val unpublished = store.createFromBytes(selected.access.scope, bytes, "pending.png", "image/png", origin = ArtifactOrigin.USER)
        assertTrue(runCatching { commands.imageSource(ManagedFileKey.Artifact(unpublished.entity.id, selected)).readBytes() }.isFailure)
        store.discardUnpublished(unpublished).requireDiscarded("test cleanup")
        val loader = coil3.ImageLoader.Builder(payloadContext).components {
            add(net.weero.measix.pilot.service.ImageSourceInterceptor)
            add(net.weero.measix.pilot.service.ImageSourceKeyer)
            add(net.weero.measix.pilot.service.ImageSourceFetcherFactory)
        }.build()
        fun request(key: ManagedFileKey) = coil3.request.ImageRequest.Builder(payloadContext).data(commands.imageSource(key)).size(2, 2).build()
        try {
            for (key in keys) {
                assertArrayEquals(bytes, commands.imageSource(key).readBytes())
                assertTrue(loader.execute(request(key)) is coil3.request.SuccessResult)
                val hit = loader.execute(request(key)) as coil3.request.SuccessResult
                assertEquals(coil3.decode.DataSource.MEMORY_CACHE, hit.dataSource)
            }
            // A decoder/cache result already exists when the original realm is revoked.
            val decoded = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val delayed = loader.newBuilder().components {
                add(coil3.intercept.Interceptor { chain ->
                    val result = chain.proceed()
                    decoded.complete(Unit)
                    release.await()
                    result
                })
            }.build()
            try {
                val delivery = async { delayed.execute(request(keys.last())) }
                decoded.await()
                sessions.switchRealm(RealmSwitchRequest(selected, RealmAccess.Personal)) {}
                release.complete(Unit)
                assertTrue(delivery.await() is coil3.request.ErrorResult)
                for (key in keys) assertTrue(loader.execute(request(key)) is coil3.request.ErrorResult)
                val personal = requireNotNull(sessions.observeSelectedRealmSelection().first())
                sessions.switchRealm(RealmSwitchRequest(personal, selected.access)) {}
                for (key in keys) assertTrue(loader.execute(request(key)) is coil3.request.ErrorResult)
                val current = requireNotNull(sessions.observeSelectedRealmSelection().first())
                for (key in keys) {
                    val activeKey = when (key) {
                        is ManagedFileKey.Artifact -> key.copy(selection = current)
                        is ManagedFileKey.Generated -> key.copy(selection = current)
                    }
                    assertTrue(loader.execute(request(activeKey)) is coil3.request.SuccessResult)
                    when (activeKey) {
                        is ManagedFileKey.Artifact -> commands.deleteArtifact(activeKey)
                        is ManagedFileKey.Generated -> commands.deleteGenerated(activeKey)
                    }
                    assertTrue(loader.execute(request(activeKey)) is coil3.request.ErrorResult)
                }
            } finally { release.complete(Unit); delayed.shutdown() }
        } finally { loader.shutdown() }
    }

    @Test
    fun fileDirectoriesStatisticsAndDeletionUseTheSelectedPrincipal() = runBlocking {
        store.ensureReferenceProjection()
        val packet = payloadContext.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET).use(EnterprisePackageCodec::decode)
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "enterprise")))
        sessions.recover()
        val originalPersonal = requireNotNull(sessions.observeSelectedRealmSelection().first())
        sessions.enrollLocal(packet.identity, { packet.identity }, { packet })
        val enterpriseSelection = requireNotNull(sessions.observeSelectedRealmSelection().first())
        val enterprise = enterpriseSelection.access.scope
        val other = scopeFor(3)
        val scopes = listOf(ConfigurationScope.Personal, enterprise, other)
        val uploads = scopes.mapIndexed { index, scope ->
            store.createFromBytes(scope, ByteArray(index + 1), "file-$index.txt", "text/plain", origin = ArtifactOrigin.USER)
                .also(store::abandonUnpublished)
        }
        val generated = GeneratedMediaStore(root, GenMediaRepository(database.genMediaDao()), store)
        val mediaIds = scopes.mapIndexed { index, scope ->
            val file = File(root, "images/$index.png").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(index + 4)) }
            withContext(Dispatchers.IO) {
                database.genMediaDao().insert(GenMediaEntity(path = "images/${file.name}", modelId = "model", prompt = "scope-$index", createAt = 1L, scope = scope)).toInt()
            }
        }
        File(root, "images/unregistered.png").writeBytes(ByteArray(90))
        val gate = ApplicationRecoveryGate().also { it.ready() }
        val query = FileManagementQueryService(store, generated, gate, sessions)
        val commands = FileManagementApplicationService(store, generated, gate, sessions)
        val directory = query.observeDirectory().first { it.selection != null }
        assertEquals(listOf(uploads[1].entity.id), directory.uploads.map { (it.key as ManagedFileKey.Artifact).artifactId })
        assertEquals(listOf(mediaIds[1]), directory.generated.map { (it.key as ManagedFileKey.Generated).mediaId })
        assertEquals(ManagedStorageUiModel(2, 7), query.observeStorageStats().first { it != null })
        val page = generated.pagingSource(enterprise).load(androidx.paging.PagingSource.LoadParams.Refresh(null, 20, false))
        assertEquals(listOf(mediaIds[1]), (page as androidx.paging.PagingSource.LoadResult.Page).data.map { it.id })
        assertEquals(null, query.inspectArtifact(ManagedFileKey.Artifact(uploads[0].entity.id, enterpriseSelection)))
        assertEquals(ArtifactDeleteOutcome.AlreadyDeleted, commands.deleteArtifact(ManagedFileKey.Artifact(uploads[0].entity.id, enterpriseSelection)))
        assertFalse(commands.deleteGenerated(ManagedFileKey.Generated(mediaIds[0], enterpriseSelection)))
        assertEquals(1, query.candidateCount(enterpriseSelection, FileCleanupCategory.UPLOAD, FileCleanupRange.All))
        assertEquals(1, commands.cleanup(enterpriseSelection, FileCleanupCategory.UPLOAD, FileCleanupRange.All).deleted)
        assertEquals(1, commands.cleanup(enterpriseSelection, FileCleanupCategory.GENERATED_IMAGES, FileCleanupRange.All).deleted)
        assertEquals(emptyList<Any>(), store.list(enterprise))
        assertTrue(store.file(uploads[0].entity).isFile)
        assertTrue(store.file(uploads[2].entity).isFile)
        assertTrue(File(root, "images/0.png").isFile)
        assertTrue(File(root, "images/2.png").isFile)
        assertTrue(File(root, "images/unregistered.png").isFile)
        sessions.switchRealm(RealmSwitchRequest(enterpriseSelection, RealmAccess.Personal)) {}
        assertTrue(runCatching { commands.deleteArtifact(ManagedFileKey.Artifact(uploads[0].entity.id, originalPersonal)) }.exceptionOrNull() is EnterpriseConfigurationException)
        assertEquals(ManagedStorageUiModel(2, 5), query.observeStorageStats().first { it != null })
    }

    @Test
    fun configurationRootsSurviveOwnerRecreationAndDetachAcrossDomains() = runBlocking {
        store.ensureReferenceProjection()
        val owned = store.createText(ConfigurationScope.Personal, "preset payload")
        val uri = owned.uri.toString()
        store.updateSettingsReferences { it.copy(assistants = listOf(Assistant(background = uri))) }
        val before = settings.snapshotUserDocument()
        val enterprise = scopeFor(1)
        val usage = AssistantUsagePreferences(ConfigurationReference.random(), background = UsageValue(uri),
            presetMessages = UsageValue(listOf(UIMessage(role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("keep"), UIMessagePart.Document(uri, "preset.txt", "text/plain"))))))
        preferences.edit { it[SettingsStore.USER_SETTINGS] = JsonInstant.encodeToString(before.copy(
            preferences = before.preferences.copy(scopes = before.preferences.scopes +
                ScopedUserPreferences(enterprise, assistantUsage = listOf(usage))))) }
        val reopened = newStore()
        reopened.reconcileStartup()
        assertTrue(reopened.collectGarbage(0).isEmpty())
        assertEquals("preset payload", reopened.file(owned.entity).readText())
        assertTrue(reopened.deleteUserRequested(ConfigurationScope.Personal, owned.entity.id) is ArtifactDeleteResult.Completed)
        val after = settings.snapshotUserDocument()
        assertTrue(ArtifactReferencePolicy.roots(after).isEmpty())
        val retained = after.preferences.scopes.single { it.scope == enterprise }.assistantUsage.single()
        assertEquals(UsageValue<String?>(null), retained.background)
        assertEquals(listOf(UIMessagePart.Text("keep")), retained.presetMessages!!.value.single().parts)
        assertFalse(reopened.file(owned.entity).exists())
        assertEquals(null, database.artifactDao().getById(owned.entity.id))
    }

    @Test
    fun concurrentCreatesKeepEveryPayloadAndHistoricalFile() = runBlocking {
        val oldName = "809278de-6677-4bc1-9249-d94c85b0930c.txt"
        val historical = File(root, "upload/$oldName").apply {
            parentFile!!.mkdirs()
            writeText("historical")
        }
        val results = (0 until 12).map { index ->
            async(Dispatchers.IO) {
                index to store.createFromBytes(scopeFor(index),
                    "payload-$index".toByteArray(), "original.txt", "text/plain", origin = ArtifactOrigin.USER,
                )
            }
        }.awaitAll()

        assertEquals(12, results.map { it.second.localRef.relativePath }.toSet().size)
        results.forEach { (index, owned) ->
            assertEquals(ArtifactState.ACTIVE.name, database.artifactDao().getById(owned.entity.id)!!.state)
            assertEquals(scopeFor(index), newStore().get(owned.entity.id)?.scope)
            assertArrayEquals("payload-$index".toByteArray(), store.file(owned.localRef).readBytes())
            assertEquals(store.file(owned.localRef), store.resolveToolPath(owned.localRef.toolPath()!!))
        }
        assertEquals(
            (listOf("aaaaaa", "bbbbbbb", "cccccccc", "Dddddddd").map { "upload/$it.txt" } +
                (2..9).map { "upload/aaaaaa-$it.txt" }).toSet(),
            results.map { it.second.localRef.relativePath }.toSet(),
        )
        assertEquals("historical", historical.readText())
        assertEquals(oldName, historical.name)
        assertTrue(File(root, ArtifactPayloadStore.STAGING_FOLDER).listFiles().orEmpty().isEmpty())
    }

    private fun pngBytes(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        return try {
            java.io.ByteArrayOutputStream().use { out ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out))
                out.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun scopeFor(index: Int): ConfigurationScope = if (index % 2 == 0) ConfigurationScope.Personal else
        ConfigurationScope.Enterprise(me.rerere.common.configuration.EnterpriseAuthority("local:example", "deployment"), "user-$index")

    @Test
    fun metadataWithoutPayloadStillReservesItsName() = runBlocking {
        val first = store.createFromBytes(ConfigurationScope.Personal, byteArrayOf(1), "a.txt", "text/plain", origin = ArtifactOrigin.USER)
        assertTrue(store.file(first.localRef).delete())
        assertTrue(database.artifactDao().existsByPath(first.localRef.relativePath))

        val second = store.createFromBytes(ConfigurationScope.Personal, byteArrayOf(2), "b.txt", "text/plain", origin = ArtifactOrigin.USER)

        assertEquals("upload/bbbbbbb.txt", second.localRef.relativePath)
        assertFalse(store.file(first.localRef).exists())
        assertArrayEquals(byteArrayOf(2), store.file(second.localRef).readBytes())
        assertEquals(first.localRef.relativePath, database.artifactDao().getById(first.entity.id)!!.relativePath)
    }

}
