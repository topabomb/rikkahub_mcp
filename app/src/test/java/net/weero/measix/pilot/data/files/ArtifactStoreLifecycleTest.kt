package net.weero.measix.pilot.data.files

import android.content.Context
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.ToolOutputProtocolLimits
import net.weero.measix.pilot.data.ai.ContextTrimmingPolicy
import net.weero.measix.pilot.data.ai.ToolOutputCompactionCandidate
import net.weero.measix.pilot.data.ai.ToolOutputCompactionPlan
import net.weero.measix.pilot.data.ai.estimateStableTextTokens
import net.weero.measix.pilot.data.ai.tools.ToolOutputGrepResult
import net.weero.measix.pilot.data.ai.tools.ToolOutputArchive
import net.weero.measix.pilot.data.ai.tools.ToolOutputArchiveRef
import net.weero.measix.pilot.data.ai.tools.ToolOutputReadResult
import net.weero.measix.pilot.data.ai.tools.ToolOutputStore
import net.weero.measix.pilot.data.ai.tools.ToolRuntimeMetadata
import net.weero.measix.pilot.data.ai.tools.REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER
import net.weero.measix.pilot.data.ai.tools.estimatedToolOutputMarkerTokens
import net.weero.measix.pilot.data.ai.tools.formatReadResult
import net.weero.measix.pilot.data.datastore.EffectiveSettingsSnapshot
import net.weero.measix.pilot.data.datastore.ManagedConfigurationState
import net.weero.measix.pilot.data.datastore.SettingsAccessIndex
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactState
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceEntity
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceType
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.imggen.TINY_PNG
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ArtifactStoreLifecycleTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var payloadStore: ArtifactPayloadStore
    private lateinit var settingsFlow: MutableStateFlow<Settings>
    private lateinit var effectiveSettings: MutableStateFlow<EffectiveSettingsSnapshot>
    private lateinit var store: ArtifactStore
    private val folders = mutableSetOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        payloadStore = ArtifactPayloadStore(context)
        settingsFlow = MutableStateFlow(Settings())
        effectiveSettings = MutableStateFlow(settingsFlow.value.toEffectiveSnapshot())
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.effectiveSettings } returns effectiveSettings
        coEvery { settingsStore.updateLocal(any()) } coAnswers {
            firstArg<(Settings) -> Settings>()(settingsFlow.value).also { updated ->
                settingsFlow.value = updated
                effectiveSettings.value = updated.toEffectiveSnapshot()
            }
        }
        store = ArtifactStore(
            payloadStore = payloadStore,
            artifactDAO = database.artifactDao(),
            artifactReferenceDAO = database.artifactReferenceDao(),
            systemMetaDAO = database.systemMetaDao(),
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            settingsCoordinator = ArtifactSettingsCoordinator(settingsStore),
            transactionRunner = RoomDatabaseTransactionRunner(database),
            fileNameCandidates = { List(4) { "000000" } },
        )
    }

    @After
    fun tearDown() {
        folders.forEach { File(context.filesDir, it).deleteRecursively() }
        File(context.filesDir, ArtifactPayloadStore.STAGING_FOLDER).deleteRecursively()
        database.close()
    }

    @Test
    fun `tool output retained read is conversation scoped and fails closed for missing payload`() = runTest {
        val owned = store.createText(
            text = "one\ntwo\nthree\n",
            displayName = "tool_output.txt",
            mimeType = "text/plain",
            folder = FileFolders.TOOL_OUTPUTS,
            origin = ArtifactOrigin.SYSTEM,
        )
        folders += FileFolders.TOOL_OUTPUTS
        val allowed = Uuid.random()
        val denied = Uuid.random()
        val nodeId = Uuid.random().toString()
        database.conversationDao().insert(
            ConversationEntity(
                id = allowed.toString(),
                assistantId = Uuid.random().toString(),
                title = "tool-output",
                createAt = 1,
                updateAt = 1,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
        database.messageNodeDao().insertAll(
            listOf(MessageNodeEntity(nodeId, allowed.toString(), 0, "[]", 0))
        )
        database.artifactReferenceDao().insertAll(
            listOf(ArtifactReferenceEntity(
                artifactId = owned.entity.id,
                nodeId = nodeId,
                referenceType = ArtifactReferenceType.TOOL_OUTPUT.name,
            ))
        )
        store.publishUnpublished(owned)

        assertEquals(
            listOf("one", "two", "three"),
            store.withToolOutputText(allowed, owned.entity.id) { it.readLines() },
        )
        val read = ToolOutputStore(store).read(
            allowed,
            owned.entity.id,
            1,
            10,
        ) as ToolOutputReadResult.Success
        assertEquals(3, read.totalLines)
        assertNull(store.withToolOutputText(denied, owned.entity.id) { it.readLines() })

        store.file(owned.entity).delete()
        assertNull(store.withToolOutputText(allowed, owned.entity.id) { it.readLines() })
    }

    @Test
    fun `shared tool output survives source deletion and is reclaimed after the last fork reference`() = runTest {
        val archivedText = "shared archived output"
        val owned = store.createText(
            text = archivedText,
            displayName = "tool_output.txt",
            mimeType = "text/plain",
            folder = FileFolders.TOOL_OUTPUTS,
            origin = ArtifactOrigin.SYSTEM,
        )
        folders += FileFolders.TOOL_OUTPUTS
        val archive = ToolOutputArchive(
            ref = owned.entity.id,
            artifact = ToolOutputArchiveRef(owned.entity.relativePath, "text/plain"),
            characters = archivedText.length.toLong(),
            lines = 1,
        )
        val message = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "shared",
                    toolName = "tool",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("[archived tool output]")),
                    metadata = ToolRuntimeMetadata.withArchive(null, archive),
                ),
            ),
        )
        val sourceConversationId = Uuid.random()
        val forkConversationId = Uuid.random()
        listOf(sourceConversationId, forkConversationId).forEachIndexed { index, conversationId ->
            database.conversationDao().insert(
                ConversationEntity(
                    id = conversationId.toString(),
                    assistantId = Uuid.random().toString(),
                    title = "tool-output-$index",
                    createAt = 1,
                    updateAt = 1,
                    chatSuggestions = "[]",
                    isPinned = false,
                ),
            )
            database.messageNodeDao().insertAll(
                listOf(
                    MessageNodeEntity(
                        id = Uuid.random().toString(),
                        conversationId = conversationId.toString(),
                        nodeIndex = 0,
                        messages = JsonInstant.encodeToString(listOf(message)),
                        selectIndex = 0,
                    ),
                ),
            )
        }
        store.ensureReferenceProjection()
        store.publishUnpublished(owned)

        assertEquals(archivedText, store.withToolOutputText(sourceConversationId, owned.entity.id) { it.readText() })
        assertEquals(archivedText, store.withToolOutputText(forkConversationId, owned.entity.id) { it.readText() })

        database.conversationDao().deleteById(sourceConversationId.toString())
        assertNull(store.withToolOutputText(sourceConversationId, owned.entity.id) { it.readText() })
        assertEquals(archivedText, store.withToolOutputText(forkConversationId, owned.entity.id) { it.readText() })
        assertTrue(store.collectGarbage(protectionWindowMillis = 0).isEmpty())

        val payload = store.file(owned.entity)
        database.conversationDao().deleteById(forkConversationId.toString())
        assertEquals(listOf(owned.entity.id), store.collectGarbage(protectionWindowMillis = 0).map { it.id })
        assertNull(database.artifactDao().getById(owned.entity.id))
        assertFalse(payload.exists())
    }

    @Test
    fun `tool output paging and grep stay bounded across giant physical lines`() = runTest {
        val text = (1..20).joinToString("\n") { line -> "Hit-$line-${"界".repeat(30_000)}" }
        val owned = store.createText(
            text = text,
            displayName = "tool_output.txt",
            mimeType = "text/plain",
            folder = FileFolders.TOOL_OUTPUTS,
            origin = ArtifactOrigin.SYSTEM,
        )
        folders += FileFolders.TOOL_OUTPUTS
        val conversationId = Uuid.random()
        val nodeId = Uuid.random().toString()
        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId.toString(),
                assistantId = Uuid.random().toString(),
                title = "tool-output-page",
                createAt = 1,
                updateAt = 1,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
        database.messageNodeDao().insertAll(
            listOf(MessageNodeEntity(nodeId, conversationId.toString(), 0, "[]", 0))
        )
        database.artifactReferenceDao().insertAll(
            listOf(
                ArtifactReferenceEntity(
                    artifactId = owned.entity.id,
                    nodeId = nodeId,
                    referenceType = ArtifactReferenceType.TOOL_OUTPUT.name,
                )
            )
        )
        store.publishUnpublished(owned)

        val outputStore = ToolOutputStore(store)
        val first = outputStore.read(conversationId, owned.entity.id, 1, 20) as ToolOutputReadResult.Success
        assertTrue(first.byteLimited)
        assertEquals(first.endLine + 1, first.nextStartLine)
        assertTrue(formatReadResult(first).toByteArray(Charsets.UTF_8).size <=
            ToolOutputProtocolLimits.TOOL_OUTPUT_MAX_RESPONSE_BYTES)

        val second = outputStore.read(conversationId, owned.entity.id, first.nextStartLine!!, 1)
            as ToolOutputReadResult.Success
        assertEquals(first.endLine + 1, second.lines.single().number)
        val grep = outputStore.grep(conversationId, owned.entity.id, "^hit-(1|2)-[界]+$", true, 1, 10)
            as ToolOutputGrepResult.Success
        assertTrue(grep.matchCount >= 1)
        assertTrue(grep.truncated)
        assertEquals(
            ToolOutputGrepResult.InvalidPattern,
            outputStore.grep(conversationId, owned.entity.id, "Hit-(?=1)", false, 0, 10),
        )
        assertEquals(
            ToolOutputGrepResult.InvalidPattern,
            outputStore.grep(conversationId, owned.entity.id, "(Hit)-\\1", false, 0, 10),
        )
    }

    @Test
    fun `staged tool output batch uses one all or nothing ownership lease`() = runTest {
        folders += FileFolders.TOOL_OUTPUTS
        val messageId = Uuid.random()
        val candidates = listOf("a".repeat(4096), "b".repeat(4096)).mapIndexed { ordinal, value ->
                val originalTokens = estimateStableTextTokens(value)
                val markerTokens = estimatedToolOutputMarkerTokens("completed", value)
                ToolOutputCompactionCandidate(
                    locator = ToolCallLocator(messageId, ordinal),
                    toolName = "tool",
                    terminalStatus = "completed",
                    outputPolicy = me.rerere.ai.core.ToolOutputPolicy.ARCHIVABLE_TEXT,
                    text = value,
                    characters = value.length.toLong(),
                    originalEstimatedTokens = originalTokens,
                    markerEstimatedTokens = markerTokens,
                    netReclaimEstimatedTokens = originalTokens - markerTokens,
                )
            }
        val plan = ToolOutputCompactionPlan(
            candidates = candidates,
            netReclaimedEstimatedTokens = candidates.sumOf { it.netReclaimEstimatedTokens },
        )
        val staged = ToolOutputStore(store).stageCompaction(plan)
        val lease = requireNotNull(staged.lease)
        val artifactIds = staged.replacements.values.map { requireNotNull(it.archive).ref }

        val failure = runCatching { lease.publish() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        lease.discard()
        artifactIds.forEach { artifactId ->
            assertNull(database.artifactDao().getById(artifactId))
        }
    }

    @Test
    fun `regenerable lookup output folds without creating another artifact`() = runTest {
        val minimum = ContextTrimmingPolicy.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS
        val markerTokens = estimateStableTextTokens(REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER)
        fun candidate(netReclaim: Long): ToolOutputCompactionCandidate {
            val text = "x".repeat(((markerTokens + netReclaim) * 4).toInt())
            val originalTokens = estimateStableTextTokens(text)
            return ToolOutputCompactionCandidate(
                locator = ToolCallLocator(Uuid.random(), 0),
                toolName = "read_tool_output",
                terminalStatus = "completed",
                outputPolicy = ToolOutputPolicy.REGENERABLE_TEXT,
                text = text,
                characters = text.length.toLong(),
                originalEstimatedTokens = originalTokens,
                markerEstimatedTokens = markerTokens,
                netReclaimEstimatedTokens = originalTokens - markerTokens,
            )
        }
        fun plan(candidate: ToolOutputCompactionCandidate) = ToolOutputCompactionPlan(
            candidates = listOf(candidate),
            netReclaimedEstimatedTokens = candidate.netReclaimEstimatedTokens,
        )
        val below = candidate(minimum - 1)
        val belowFailure = runCatching { ToolOutputStore(store).stageCompaction(plan(below)) }.exceptionOrNull()
        assertTrue(belowFailure is IllegalArgumentException)

        val candidate = candidate(minimum)
        val staged = ToolOutputStore(store).stageCompaction(plan(candidate))

        val replacement = staged.replacements.getValue(candidate.locator)
        assertEquals(minimum, candidate.netReclaimEstimatedTokens)
        assertEquals(REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER, replacement.marker.text)
        assertNull(replacement.archive)
        assertNull(staged.lease)
        assertTrue(database.artifactDao().listAllStatesByFolder(FileFolders.TOOL_OUTPUTS).first().isEmpty())
    }

    @Test
    fun `staged output gains a projected durable root before publish and remains readable`() = runTest {
        folders += FileFolders.TOOL_OUTPUTS
        val conversationId = Uuid.random()
        val messageId = Uuid.random()
        val nodeId = Uuid.random()
        val locator = ToolCallLocator(messageId, 0)
        val archivedText = "stable output ".repeat(200)
        val originalTokens = estimateStableTextTokens(archivedText)
        val markerTokens = estimatedToolOutputMarkerTokens("completed", archivedText)
        val staged = ToolOutputStore(store).stageCompaction(
            ToolOutputCompactionPlan(
                candidates = listOf(
                    ToolOutputCompactionCandidate(
                        locator = locator,
                        toolName = "tool",
                        terminalStatus = "completed",
                        outputPolicy = me.rerere.ai.core.ToolOutputPolicy.ARCHIVABLE_TEXT,
                        text = archivedText,
                        characters = archivedText.length.toLong(),
                        originalEstimatedTokens = originalTokens,
                        markerEstimatedTokens = markerTokens,
                        netReclaimEstimatedTokens = originalTokens - markerTokens,
                    ),
                ),
                netReclaimedEstimatedTokens = originalTokens - markerTokens,
            ),
        )
        val replacement = staged.replacements.getValue(locator)
        val archive = requireNotNull(replacement.archive)
        assertEquals(1, archive.lines)
        assertEquals(archivedText.length.toLong(), archive.characters)

        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId.toString(),
                assistantId = Uuid.random().toString(),
                title = "empty-tool-output",
                createAt = 1,
                updateAt = 1,
                chatSuggestions = "[]",
                isPinned = false,
            ),
        )
        database.messageNodeDao().insertAll(
            listOf(MessageNodeEntity(nodeId.toString(), conversationId.toString(), 0, "[]", 0)),
        )
        val node = MessageNode(
            id = nodeId,
            messages = listOf(
                UIMessage(
                    id = messageId,
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "call",
                            toolName = "tool",
                            input = "{}",
                            output = listOf(replacement.marker),
                            metadata = ToolRuntimeMetadata.withArchive(null, archive),
                        ),
                    ),
                ),
            ),
        )
        val delta = store.prepareReferenceDelta(listOf(node), emptyList())
        assertEquals(
            archive.ref,
            delta.references.single().artifactId,
        )
        store.applyReferenceDeltaInTransaction(delta)
        requireNotNull(staged.lease).publish()

        val read = ToolOutputStore(store).read(
            conversationId = conversationId,
            ref = archive.ref,
            startLine = 1,
            lineCount = 10,
        ) as ToolOutputReadResult.Success
        assertEquals(1, read.totalLines)
        assertEquals(archivedText, read.lines.single().text)
    }

    @Test
    fun `asset allocation selects the first free candidate in all four priority tiers`() = runTest {
        val candidates = listOf("aaaaaa", "bbbbbbb", "cccccccc", "Dddddddd")
        for (freeIndex in candidates.indices) {
            val folder = folder()
            candidates.take(freeIndex).forEach { stem ->
                payloadStore.file("$folder/$stem.bin").apply { parentFile!!.mkdirs(); writeText(stem) }
            }
            val selectingStore = testStore(payloadStore, candidates)
            val owned = selectingStore.createFromBytes(byteArrayOf(1), "sample.bin", folder = folder, origin = ArtifactOrigin.USER)
            assertEquals("$folder/${candidates[freeIndex]}.bin", owned.entity.relativePath)
            candidates.take(freeIndex).forEach { stem ->
                assertEquals(stem, payloadStore.file("$folder/$stem.bin").readText())
            }
        }
    }

    @Test
    fun `all four occupied candidates use suffixes on the first candidate only`() = runTest {
        val folder = folder()
        val candidates = listOf("aaaaaa", "bbbbbbb", "cccccccc", "Dddddddd")
        (candidates + "aaaaaa-2").forEach { stem ->
            payloadStore.file("$folder/$stem.bin").apply { parentFile!!.mkdirs(); writeText(stem) }
        }
        val owned = testStore(payloadStore, candidates).createFromBytes(
            byteArrayOf(1), "sample.bin", folder = folder, origin = ArtifactOrigin.USER,
        )
        assertEquals("$folder/aaaaaa-3.bin", owned.entity.relativePath)
    }

    @Test
    fun `duplicate candidate text is checked once before suffix fallback`() = runTest {
        val folder = folder()
        val candidates = listOf("aaaaaa", "bbbbbbb", "cccccccc", "cccccccc")
        candidates.distinct().forEach { stem ->
            payloadStore.file("$folder/$stem.bin").apply { parentFile!!.mkdirs(); writeText(stem) }
        }
        val counted = spyk(payloadStore)
        val owned = testStore(counted, candidates).createFromBytes(
            byteArrayOf(1), "sample.bin", folder = folder, origin = ArtifactOrigin.USER,
        )
        assertEquals("$folder/aaaaaa-2.bin", owned.entity.relativePath)
        coVerify(exactly = 1) { counted.pathOccupied(folder, "cccccccc.bin") }
    }

    @Test
    fun `short name allocation skips final staging and every metadata state without rewriting history`() = runTest {
        val folder = folder()
        val oldPath = "$folder/809278de-6677-4bc1-9249-d94c85b0930c.png"
        val oldFile = payloadStore.file(oldPath).apply { parentFile!!.mkdirs(); writeText("history") }
        val oldId = database.artifactDao().insert(entity(oldPath, folder, ArtifactState.ACTIVE, null))
        val existing = payloadStore.file("$folder/000000.png").apply { writeText("existing") }
        val staged = stageBytes(folder, byteArrayOf(7), "000000-2.png")
        val rows = ArtifactState.entries.mapIndexed { index, state ->
            database.artifactDao().insert(entity("$folder/000000-${index + 3}.png", folder, state, null))
        }

        val owned = store.createFromBytes(TINY_PNG, "image.png", "image/png", folder, ArtifactOrigin.USER)

        assertEquals("$folder/000000-6.png", owned.entity.relativePath)
        assertEquals("existing", existing.readText())
        assertEquals("history", oldFile.readText())
        assertEquals(oldPath, database.artifactDao().getById(oldId)?.relativePath)
        assertTrue(payloadStore.stagingExists(staged.stagingToken))
        rows.forEach { assertTrue(database.artifactDao().getById(it) != null) }
    }

    @Test
    fun `concurrent creation with repeated candidates reserves distinct suffixes`() = runTest {
        val folder = folder()
        val created = (0 until 20).map { value ->
            async(Dispatchers.Default) {
                value to store.createFromBytes(byteArrayOf(value.toByte()), "sample.bin", folder = folder, origin = ArtifactOrigin.USER)
            }
        }.awaitAll()
        assertEquals(20, created.map { it.second.entity.relativePath }.toSet().size)
        created.forEach { (value, owned) -> assertEquals(value.toByte(), store.file(owned.entity).readBytes().single()) }
        assertTrue(payloadStore.listStagingTokens().isEmpty())
    }

    @Test
    fun `structural copies preserve source origin while allocating a distinct short name`() = runTest {
        val folder = folder()
        ArtifactOrigin.entries.forEach { origin ->
            val source = store.createFromBytes(byteArrayOf(1), "source.bin", folder = folder, origin = origin)
            val copy = store.createFromUri(source.uri, folder, origin = ArtifactOrigin.USER)
            assertEquals(origin.name, copy.entity.origin)
            assertTrue(source.entity.relativePath != copy.entity.relativePath)
        }
    }

    @Test
    fun `payload atomic reservation never overwrites another staging file`() = runTest {
        val folder = folder()
        val results = (0 until 10).map { value ->
            async(Dispatchers.Default) {
                value to runCatching { stageBytes(folder, byteArrayOf(value.toByte()), "000000.bin") }
            }
        }.awaitAll()
        val winner = results.single { it.second.isSuccess }
        val staged = winner.second.getOrThrow()
        val final = payloadStore.promote(staged)
        assertEquals(winner.first.toByte(), final.readBytes().single())
    }

    @Test
    fun `failed bounded copy removes only its own new staging payload`() = runTest {
        val folder = folder()
        val existing = stageBytes(folder, byteArrayOf(7), "000000.bin")
        val source = payloadStore.file("$folder/source.bin").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val result = runCatching { store.createFromUri(source.toUri(), folder, maxBytes = 1) }
        assertTrue(result.isFailure)
        assertEquals(listOf(existing.stagingToken), payloadStore.listStagingTokens())
        assertTrue(database.artifactDao().listAllStatesByFolder(folder).first().isEmpty())
        assertEquals(listOf<Byte>(1, 2, 3), source.readBytes().toList())
    }

    @Test
    fun `publication collision rollback preserves a file not published by this owner`() = runTest {
        val folder = folder()
        val guardedPayload = spyk(payloadStore)
        coEvery { guardedPayload.promote(any<ArtifactPayloadStore.StagedPayload>()) } coAnswers {
            val staged = firstArg<ArtifactPayloadStore.StagedPayload>()
            payloadStore.file(staged.relativePath).apply { parentFile!!.mkdirs(); writeText("other owner") }
            payloadStore.promote(staged)
        }
        val guardedStore = ArtifactStore(
            payloadStore = guardedPayload,
            artifactDAO = database.artifactDao(),
            artifactReferenceDAO = database.artifactReferenceDao(),
            systemMetaDAO = database.systemMetaDao(),
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            settingsCoordinator = mockk(relaxed = true),
            transactionRunner = RoomDatabaseTransactionRunner(database),
            fileNameCandidates = { List(4) { "000000" } },
        )
        val failure = runCatching {
            guardedStore.createFromBytes(byteArrayOf(1), "sample.bin", folder = folder, origin = ArtifactOrigin.USER)
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("other owner", payloadStore.file("$folder/000000.bin").readText())
        assertTrue(payloadStore.listStagingTokens().isEmpty())
        assertTrue(database.artifactDao().listAllStatesByFolder(folder).first().isEmpty())
    }

    @Test
    fun `cancelled payload writer removes only the staging file it reserved`() = runTest {
        val folder = folder()
        val previous = stageBytes(folder, byteArrayOf(7), "previous.bin")
        val resolver = mockk<android.content.ContentResolver>()
        every { resolver.openInputStream(any()) } returns object : java.io.InputStream() {
            override fun read(): Int = throw kotlin.coroutines.cancellation.CancellationException("source cancelled")
        }
        val testContext = mockk<Context>()
        every { testContext.filesDir } returns context.filesDir
        every { testContext.contentResolver } returns resolver
        val testPayload = ArtifactPayloadStore(testContext)
        val reserved = testPayload.reserve(folder, "new.bin")
        val failure = runCatching {
            testPayload.stageFromUri(reserved, android.net.Uri.parse("content://test/source"))
        }.exceptionOrNull()
        assertTrue(failure is kotlin.coroutines.cancellation.CancellationException)
        assertEquals(listOf(previous.stagingToken), payloadStore.listStagingTokens())
    }

    @Test
    fun `blocking source IO does not hold the artifact lifecycle lock`() = runTest {
        val folder = folder()
        val reading = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val resolver = mockk<android.content.ContentResolver>()
        every { resolver.openInputStream(any()) } returns object : java.io.ByteArrayInputStream(byteArrayOf(3)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reading.countDown()
                check(release.await(15, java.util.concurrent.TimeUnit.SECONDS))
                return super.read(buffer, offset, length)
            }
        }
        val testContext = mockk<Context>()
        every { testContext.filesDir } returns context.filesDir
        every { testContext.contentResolver } returns resolver
        val ioStore = testStore(ArtifactPayloadStore(testContext))
        val first = async(Dispatchers.Default) {
            ioStore.createFromUri(android.net.Uri.parse("content://test/source"), folder, "sample.bin", "application/octet-stream")
        }
        try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                assertTrue(reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            val second = kotlinx.coroutines.withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) {
                    ioStore.createFromBytes(byteArrayOf(4), "sample.bin", folder = folder, origin = ArtifactOrigin.USER)
                }
            }
            assertEquals("$folder/000000-2.bin", second.entity.relativePath)
            assertEquals(4.toByte(), ioStore.file(second.entity).readBytes().single())
        } finally {
            release.countDown()
        }
        assertEquals("$folder/000000.bin", first.await().entity.relativePath)
    }

    @Test
    fun `cancellation after reservation before IO dispatch removes the owned reservation`() = runTest {
        val folder = folder()
        val guarded = spyk(payloadStore)
        coEvery { guarded.reserve(any(), any()) } coAnswers {
            payloadStore.reserve(firstArg(), secondArg()).also {
                kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            }
        }
        val ioStore = testStore(guarded)
        val creation = async {
            ioStore.createFromBytes(byteArrayOf(1), "sample.bin", folder = folder, origin = ArtifactOrigin.USER)
        }
        val failure = runCatching { creation.await() }.exceptionOrNull()
        assertTrue(failure is kotlin.coroutines.cancellation.CancellationException)
        assertTrue(payloadStore.listStagingTokens().isEmpty())
        assertTrue(database.artifactDao().listAllStatesByFolder(folder).first().isEmpty())
    }

    @Test
    fun `create publishes active metadata and payload together`() = runTest {
        val folder = folder()

        val owned = store.createFromBytes(
            bytes = byteArrayOf(1, 2, 3),
            displayName = "sample.bin",
            folder = folder,
            origin = ArtifactOrigin.USER,
        )

        val persisted = requireNotNull(database.artifactDao().getById(owned.entity.id))
        assertEquals(ArtifactState.ACTIVE.name, persisted.state)
        assertNull(persisted.payloadToken)
        assertTrue(store.file(persisted).isFile)
        assertTrue(payloadStore.listStagingTokens().isEmpty())
    }

    @Test
    fun `image preview port rejects artifact after lifecycle deletion`() = runTest {
        val owned = store.createFromBytes(
            bytes = TINY_PNG,
            displayName = "preview.png",
            mimeType = "image/png",
            origin = ArtifactOrigin.USER,
        )

        assertEquals(
            AttachmentRefs.fileToFileUrl(store.file(owned.entity)),
            store.resolveImagePreviewForArtifact(owned.localRef),
        )
        store.abandonUnpublished(owned)
        assertTrue(store.deleteUserRequested(owned.entity.id) is ArtifactDeleteResult.Completed)
        assertNull(store.resolveImagePreviewForArtifact(owned.localRef))
    }

    @Test
    fun `payload paths and staging tokens cannot escape app storage`() {
        assertTrue(runCatching { payloadStore.file("../outside.bin") }.isFailure)
        assertTrue(runCatching { payloadStore.stagingExists("../outside.part") }.isFailure)
    }

    @Test
    fun `startup rolls back a creating row whose staging payload survived`() = runTest {
        val folder = folder()
        val staged = stageBytes(folder, byteArrayOf(9), "recover.bin")
        val id = database.artifactDao().insert(
            entity(staged.relativePath, folder, ArtifactState.CREATING, staged.stagingToken)
        )

        store.reconcileStartup()

        assertNull(database.artifactDao().getById(id))
        assertFalse(payloadStore.finalExists(staged.relativePath))
        assertFalse(payloadStore.stagingExists(staged.stagingToken))
    }

    @Test
    fun `startup rolls back a creating row whose final payload survived`() = runTest {
        val folder = folder()
        val staged = stageBytes(folder, byteArrayOf(9), "promoted.bin")
        val id = database.artifactDao().insert(
            entity(staged.relativePath, folder, ArtifactState.CREATING, staged.stagingToken)
        )
        payloadStore.promote(staged)

        store.reconcileStartup()

        assertNull(database.artifactDao().getById(id))
        assertFalse(payloadStore.finalExists(staged.relativePath))
        assertFalse(payloadStore.stagingExists(staged.stagingToken))
    }

    @Test
    fun `cancellation while waiting for lifecycle ownership never reserves a payload`() = runTest {
        val folder = folder()
        val lockAcquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async {
            store.withLifecycleLock {
                lockAcquired.complete(Unit)
                release.await()
            }
        }
        lockAcquired.await()
        val creator = async {
            store.createFromBytes(byteArrayOf(1), "cancelled.bin", folder = folder, origin = ArtifactOrigin.USER)
        }
        runCurrent()
        assertTrue(payloadStore.listStagingTokens().isEmpty())

        creator.cancelAndJoin()
        release.complete(Unit)
        holder.await()

        assertTrue(payloadStore.listStagingTokens().isEmpty())
        assertTrue(database.artifactDao().listAllStatesByFolder(folder).first().isEmpty())
    }

    @Test
    fun `startup resumes deleting and removes both payload and metadata`() = runTest {
        val folder = folder()
        val owned = store.createFromBytes(byteArrayOf(7), "delete.bin", folder = folder, origin = ArtifactOrigin.USER)
        assertEquals(
            1,
            database.artifactDao().compareAndSetState(
                owned.entity.id,
                ArtifactState.ACTIVE.name,
                ArtifactState.DELETING.name,
                2L,
            ),
        )

        store.reconcileStartup()

        assertNull(database.artifactDao().getById(owned.entity.id))
        assertFalse(store.file(owned.entity).exists())
    }

    @Test
    fun `startup never adopts an untracked upload file without a durable root`() = runTest {
        folders += FileFolders.UPLOAD
        val relativePath = "${FileFolders.UPLOAD}/unrooted.png"
        File(context.filesDir, relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3))
        }

        store.reconcileStartup()

        assertNull(database.artifactDao().getByPathAndState(relativePath, ArtifactState.ACTIVE.name))
    }

    @Test
    fun `startup persists fallback when a settings root lacks artifact metadata`() = runTest {
        folders += FileFolders.UPLOAD
        val relativePath = "${FileFolders.UPLOAD}/untracked-settings-root.png"
        val file = File(context.filesDir, relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3))
        }
        settingsFlow.value = Settings(
            assistants = listOf(Assistant(background = file.toUri().toString())),
        )
        effectiveSettings.value = settingsFlow.value.toEffectiveSnapshot()

        store.reconcileStartup()

        assertTrue(file.isFile)
        assertNull(settingsFlow.value.assistants.single().background)
        assertNull(database.artifactDao().getByPathAndState(relativePath, ArtifactState.ACTIVE.name))
    }

    @Test
    fun `startup persists defaults when every Settings image root lacks metadata and payload`() = runTest {
        folders += FileFolders.UPLOAD
        val missingRoot = File(context.filesDir, "${FileFolders.UPLOAD}/missing-settings-image.png").toUri().toString()
        settingsFlow.value = Settings(
            assistants = listOf(
                Assistant(
                    avatar = Avatar.Image(missingRoot),
                    background = missingRoot,
                ),
            ),
            displaySetting = Settings().displaySetting.copy(userAvatar = Avatar.Image(missingRoot)),
        )
        effectiveSettings.value = settingsFlow.value.toEffectiveSnapshot()

        store.reconcileStartup()
        store.reconcileStartup()

        val recovered = settingsFlow.value
        val assistant = recovered.assistants.single()
        assertNull(assistant.background)
        assertEquals(Avatar.Dummy, assistant.avatar)
        assertEquals(Avatar.Dummy, recovered.displaySetting.userAvatar)
        assertNull(database.artifactDao().getByPathAndState(
            "${FileFolders.UPLOAD}/missing-settings-image.png",
            ArtifactState.ACTIVE.name,
        ))
    }

    @Test
    fun `startup persists sub-assistant avatar and background fallback without metadata`() = runTest {
        folders += FileFolders.UPLOAD
        val avatar = File(context.filesDir, "${FileFolders.UPLOAD}/legacy-sub-avatar.png").apply {
            parentFile?.mkdirs()
            writeBytes(TINY_PNG)
        }
        val background = File(context.filesDir, "${FileFolders.UPLOAD}/legacy-sub-background.png").apply {
            parentFile?.mkdirs()
            writeBytes(TINY_PNG)
        }
        settingsFlow.value = Settings(
            assistants = listOf(
                Assistant(
                    avatar = Avatar.Image(avatar.toUri().toString()),
                    background = background.toUri().toString(),
                    allowAsSubAssistant = true,
                ),
            ),
        )
        effectiveSettings.value = settingsFlow.value.toEffectiveSnapshot()

        store.reconcileStartup()

        assertTrue(avatar.isFile)
        assertTrue(background.isFile)
        assertEquals(Avatar.Dummy, settingsFlow.value.assistants.single().avatar)
        assertNull(settingsFlow.value.assistants.single().background)
        assertNull(database.artifactDao().getByPathAndState("${FileFolders.UPLOAD}/legacy-sub-avatar.png", ArtifactState.ACTIVE.name))
        assertNull(database.artifactDao().getByPathAndState("${FileFolders.UPLOAD}/legacy-sub-background.png", ArtifactState.ACTIVE.name))
    }

    @Test
    fun `read port exposes only active artifacts`() = runTest {
        val folder = folder()
        val active = store.createFromBytes(byteArrayOf(1), "active.bin", folder = folder, origin = ArtifactOrigin.USER)
        val staging = stageBytes(folder, byteArrayOf(2), "creating.bin")
        database.artifactDao().insert(entity(staging.relativePath, folder, ArtifactState.CREATING, staging.stagingToken))
        val deleting = store.createFromBytes(byteArrayOf(3), "deleting.bin", folder = folder, origin = ArtifactOrigin.USER)
        database.artifactDao().compareAndSetState(
            deleting.entity.id,
            ArtifactState.ACTIVE.name,
            ArtifactState.DELETING.name,
            2L,
        )

        val visible = store.list(folder)

        assertEquals(listOf(active.entity.id), visible.map { it.id })
    }

    @Test
    fun `startup fails closed when a message root points to missing active payload`() = runTest {
        val folder = folder()
        val owned = store.createFromBytes(byteArrayOf(4), "rooted.bin", folder = folder, origin = ArtifactOrigin.USER)
        val conversationId = Uuid.random().toString()
        val nodeId = Uuid.random().toString()
        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId,
                assistantId = Uuid.random().toString(),
                title = "rooted",
                createAt = 1,
                updateAt = 1,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
        database.messageNodeDao().insertAll(
            listOf(MessageNodeEntity(
                id = nodeId,
                conversationId = conversationId,
                nodeIndex = 0,
                messages = "[]",
                selectIndex = 0,
            ))
        )
        database.artifactReferenceDao().insertAll(
            listOf(
                ArtifactReferenceEntity(
                    artifactId = owned.entity.id,
                    nodeId = nodeId,
                    referenceType = ArtifactReferenceType.ATTACHMENT.name,
                )
            )
        )
        assertTrue(store.file(owned.entity).delete())

        val failure = runCatching { store.reconcileStartup() }.exceptionOrNull()

        assertTrue(failure is ArtifactDataIntegrityException)
        assertEquals(ArtifactState.ACTIVE.name, database.artifactDao().getById(owned.entity.id)?.state)
    }

    @Test
    fun `startup persists fallback when a settings root points to missing active payload`() = runTest {
        val folder = folder()
        val assistant = Assistant()
        settingsFlow.value = Settings(assistants = listOf(assistant))
        val owned = store.createFromBytes(byteArrayOf(5), "background.bin", folder = folder, origin = ArtifactOrigin.USER)
        store.updateSettingsReferences { current ->
            current.copy(assistants = current.assistants.map { it.copy(background = owned.uri.toString()) })
        }
        assertTrue(store.file(owned.entity).delete())

        store.reconcileStartup()

        assertNull(settingsFlow.value.assistants.single().background)
        assertNull(database.artifactDao().getById(owned.entity.id))
    }

    @Test
    fun `folder deletion resumes mixed active and creating lifecycle states`() = runTest {
        val folder = folder()
        settingsFlow.value = Settings(assistants = listOf(Assistant()))
        val active = store.createFromBytes(
            byteArrayOf(7),
            "active.bin",
            folder = folder,
            origin = ArtifactOrigin.USER,
        )
        store.updateSettingsReferences { current ->
            current.copy(assistants = current.assistants.map { it.copy(background = active.uri.toString()) })
        }
        store.updateSettingsReferences { current ->
            current.copy(assistants = current.assistants.map { it.copy(background = null) })
        }
        val staged = stageBytes(folder, byteArrayOf(8), "creating.bin")
        val creatingId = database.artifactDao().insert(
            entity(staged.relativePath, folder, ArtifactState.CREATING, staged.stagingToken)
        )

        val result = store.deleteUserRequestedFolder(folder)

        assertTrue(result is ArtifactDeleteResult.Completed)
        assertNull(database.artifactDao().getById(active.entity.id))
        assertNull(database.artifactDao().getById(creatingId))
        assertFalse(payloadStore.finalExists(active.entity.relativePath))
        assertFalse(payloadStore.stagingExists(staged.stagingToken))
    }

    @Test
    fun `scoped folder deletion respects cutoff and keeps newer artifacts`() = runTest {
        val folder = folder()
        val oldStaged = stageBytes(folder, byteArrayOf(1), "old.bin")
        payloadStore.promote(oldStaged)
        val oldId = database.artifactDao().insert(
            entity(oldStaged.relativePath, folder, ArtifactState.ACTIVE, token = null, createdAt = 1_000L)
        )
        val freshStaged = stageBytes(folder, byteArrayOf(2), "fresh.bin")
        payloadStore.promote(freshStaged)
        val freshId = database.artifactDao().insert(
            entity(freshStaged.relativePath, folder, ArtifactState.ACTIVE, token = null, createdAt = 5_000L)
        )

        val result = store.deleteUserRequestedFolderCreatedBefore(folder, createdBefore = 2_000L)

        assertEquals(1, result.deleted)
        assertEquals(0, result.cleanupPending)
        assertEquals(0, result.skippedInProgress)
        assertEquals(0, result.failed)
        assertNull(database.artifactDao().getById(oldId))
        assertTrue(database.artifactDao().getById(freshId) != null)
        assertFalse(payloadStore.finalExists(oldStaged.relativePath))
        assertTrue(payloadStore.finalExists(freshStaged.relativePath))
    }

    @Test
    fun `scoped folder deletion skips live ownership instead of discarding it`() = runTest {
        val folder = folder()
        val owned = store.createFromBytes(
            bytes = byteArrayOf(3),
            displayName = "in-flight.bin",
            folder = folder,
            origin = ArtifactOrigin.USER,
        )

        val result = store.deleteUserRequestedFolderCreatedBefore(folder, Long.MAX_VALUE)

        assertEquals(0, result.deleted)
        assertEquals(1, result.skippedInProgress)
        assertEquals(0, result.failed)
        assertTrue(database.artifactDao().getById(owned.entity.id) != null)
        assertTrue(store.file(owned.entity).isFile)
    }

    @Test
    fun `settings root and garbage collection are serialized`() = runTest {
        val folder = folder()
        val assistant = Assistant()
        settingsFlow.value = Settings(assistants = listOf(assistant))
        store.ensureReferenceProjection()
        val owned = store.createFromBytes(byteArrayOf(3), "avatar.bin", folder = folder, origin = ArtifactOrigin.USER)
        val lockAcquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async {
            store.withLifecycleLock {
                lockAcquired.complete(Unit)
                release.await()
            }
        }
        lockAcquired.await()
        val publish = async {
            store.updateSettingsReferences { current ->
                current.copy(assistants = current.assistants.map { it.copy(background = owned.uri.toString()) })
            }
        }
        runCurrent()
        val gc = async { store.collectGarbage(0) }
        release.complete(Unit)
        holder.await()
        publish.await()

        assertTrue(gc.await().isEmpty())
        assertEquals(ArtifactState.ACTIVE.name, database.artifactDao().getById(owned.entity.id)?.state)
        assertTrue(store.file(owned.entity).isFile)
    }

    @Test
    fun `discard rejects a published artifact and succeeds after detach`() = runTest {
        val folder = folder()
        val assistant = Assistant()
        settingsFlow.value = Settings(assistants = listOf(assistant))
        val owned = store.createFromBytes(byteArrayOf(4), "root.bin", folder = folder, origin = ArtifactOrigin.USER)
        store.updateSettingsReferences { current ->
            current.copy(assistants = current.assistants.map { it.copy(background = owned.uri.toString()) })
        }

        val rejected = store.discardUnpublished(owned)
        assertTrue(rejected is ArtifactDeleteResult.Failed)

        store.updateSettingsReferences { current ->
            current.copy(assistants = current.assistants.map { it.copy(background = null) })
        }
        assertTrue(store.discardUnpublished(owned) is ArtifactDeleteResult.Failed)
        assertTrue(store.deleteUserRequested(owned.entity.id) is ArtifactDeleteResult.Completed)
        assertNull(database.artifactDao().getById(owned.entity.id))
    }

    @Test
    fun `live unpublished ownership blocks garbage collection and explicit deletion`() = runTest {
        val folder = folder()
        store.ensureReferenceProjection()
        val owned = store.createFromBytes(byteArrayOf(6), "draft.bin", folder = folder, origin = ArtifactOrigin.USER)

        assertTrue(store.collectGarbage(0).isEmpty())
        val deletion = store.deleteUserRequested(owned.entity.id)

        assertTrue(deletion is ArtifactDeleteResult.Rejected)
        assertEquals(
            ArtifactDeleteResult.RejectionReason.IN_PROGRESS,
            (deletion as ArtifactDeleteResult.Rejected).reason,
        )
        assertTrue(store.file(owned.entity).isFile)
    }

    @Test
    fun `batch publication validates every root before consuming any ownership token`() = runTest {
        val folder = folder()
        val rooted = store.createFromBytes(
            byteArrayOf(1),
            "rooted.bin",
            folder = folder,
            origin = ArtifactOrigin.USER,
        )
        val unrooted = store.createFromBytes(
            byteArrayOf(2),
            "unrooted.bin",
            folder = folder,
            origin = ArtifactOrigin.USER,
        )
        val conversationId = Uuid.random().toString()
        val nodeId = Uuid.random().toString()
        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId,
                assistantId = Uuid.random().toString(),
                title = "batch-root",
                createAt = 1,
                updateAt = 1,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
        database.messageNodeDao().insertAll(
            listOf(
                MessageNodeEntity(
                    id = nodeId,
                    conversationId = conversationId,
                    nodeIndex = 0,
                    messages = "[]",
                    selectIndex = 0,
                )
            )
        )
        database.artifactReferenceDao().insertAll(
            listOf(
                ArtifactReferenceEntity(
                    artifactId = rooted.entity.id,
                    nodeId = nodeId,
                    referenceType = ArtifactReferenceType.ATTACHMENT.name,
                )
            )
        )

        val failure = runCatching {
            store.publishAllUnpublished(listOf(rooted, unrooted))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        val rootedDelete = store.deleteUserRequested(rooted.entity.id)
        val unrootedDelete = store.deleteUserRequested(unrooted.entity.id)
        assertEquals(
            ArtifactDeleteResult.RejectionReason.IN_PROGRESS,
            (rootedDelete as ArtifactDeleteResult.Rejected).reason,
        )
        assertEquals(
            ArtifactDeleteResult.RejectionReason.IN_PROGRESS,
            (unrootedDelete as ArtifactDeleteResult.Rejected).reason,
        )
    }

    @Test
    fun `startup deleting recovery detaches settings root before payload removal`() = runTest {
        val folder = folder()
        settingsFlow.value = Settings(assistants = listOf(Assistant()))
        val owned = store.createFromBytes(byteArrayOf(8), "rooted-delete.bin", folder = folder, origin = ArtifactOrigin.USER)
        store.updateSettingsReferences { current ->
            current.copy(assistants = current.assistants.map { it.copy(background = owned.uri.toString()) })
        }
        assertEquals(
            1,
            database.artifactDao().compareAndSetState(
                owned.entity.id,
                ArtifactState.ACTIVE.name,
                ArtifactState.DELETING.name,
                3L,
            ),
        )

        store.reconcileStartup()

        assertNull(database.artifactDao().getById(owned.entity.id))
        assertNull(settingsFlow.value.assistants.single().background)
        assertFalse(store.file(owned.entity).exists())
    }

    @Test
    fun `explicit delete resumes an interrupted deleting state`() = runTest {
        val folder = folder()
        settingsFlow.value = Settings(assistants = listOf(Assistant()))
        val owned = store.createFromBytes(
            bytes = byteArrayOf(9),
            displayName = "retry-delete.bin",
            folder = folder,
            origin = ArtifactOrigin.USER,
        )
        store.updateSettingsReferences { current ->
            current.copy(assistants = current.assistants.map { it.copy(background = owned.uri.toString()) })
        }
        store.publishUnpublished(owned)
        assertEquals(
            1,
            database.artifactDao().compareAndSetState(
                owned.entity.id,
                ArtifactState.ACTIVE.name,
                ArtifactState.DELETING.name,
                4L,
            ),
        )

        val result = store.deleteUserRequested(owned.entity.id)

        assertTrue(result is ArtifactDeleteResult.Completed)
        assertNull(database.artifactDao().getById(owned.entity.id))
        assertNull(settingsFlow.value.assistants.single().background)
        assertFalse(store.file(owned.entity).exists())
    }

    @Test
    fun `concurrent explicit deletes invoke physical payload deletion exactly once`() = runTest {
        val folder = folder()
        val relativePath = "$folder/concurrent.bin"
        val artifactId = database.artifactDao().insert(
            entity(relativePath, folder, ArtifactState.ACTIVE, token = null)
        )
        val physicalDeletes = AtomicInteger()
        val countingPayloadStore = mockk<ArtifactPayloadStore>()
        every { countingPayloadStore.file(relativePath) } returns File(context.filesDir, relativePath)
        coEvery { countingPayloadStore.deleteStaging(null) } returns true
        coEvery { countingPayloadStore.deleteFinal(relativePath) } coAnswers {
            physicalDeletes.incrementAndGet()
            true
        }
        val localSettings = MutableStateFlow(Settings())
        val localEffectiveSettings = MutableStateFlow(localSettings.value.toEffectiveSnapshot())
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.effectiveSettings } returns localEffectiveSettings
        coEvery { settingsStore.updateLocal(any()) } coAnswers {
            firstArg<(Settings) -> Settings>()(localSettings.value).also { updated ->
                localSettings.value = updated
                localEffectiveSettings.value = updated.toEffectiveSnapshot()
            }
        }
        val countingStore = ArtifactStore(
            payloadStore = countingPayloadStore,
            artifactDAO = database.artifactDao(),
            artifactReferenceDAO = database.artifactReferenceDao(),
            systemMetaDAO = database.systemMetaDao(),
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            settingsCoordinator = ArtifactSettingsCoordinator(settingsStore),
            transactionRunner = RoomDatabaseTransactionRunner(database),
            fileNameCandidates = { List(4) { "000000" } },
        )

        val results = (0 until 20).map {
            async(Dispatchers.Default) { countingStore.deleteUserRequested(artifactId) }
        }.awaitAll()

        assertEquals(1, physicalDeletes.get())
        assertEquals(1, results.count { it is ArtifactDeleteResult.Completed })
        assertTrue(results.filterIsInstance<ArtifactDeleteResult.Rejected>().all {
            it.reason == ArtifactDeleteResult.RejectionReason.ALREADY_DELETED
        })
        assertNull(database.artifactDao().getById(artifactId))
    }

    @Test
    fun `corrupt backfill node does not replace projection or mark it current`() = runTest {
        val conversationId = Uuid.random().toString()
        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId,
                assistantId = Uuid.random().toString(),
                title = "corrupt",
                createAt = 1,
                updateAt = 1,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
        database.messageNodeDao().upsertAll(
            listOf(
                MessageNodeEntity(
                    id = Uuid.random().toString(),
                    conversationId = conversationId,
                    nodeIndex = 0,
                    messages = "not-json",
                    selectIndex = 0,
                )
            )
        )

        val failure = runCatching { store.ensureReferenceProjection() }.exceptionOrNull()

        assertTrue(failure is ArtifactProjectionException)
        assertFalse(store.isReferenceProjectionCurrent())
    }

    private suspend fun stageBytes(folder: String, bytes: ByteArray, fileName: String): ArtifactPayloadStore.StagedPayload =
        payloadStore.stageFromBytes(payloadStore.reserve(folder, fileName), bytes)

    private fun testStore(
        payload: ArtifactPayloadStore,
        candidates: List<String> = List(4) { "000000" },
    ) = ArtifactStore(
        payloadStore = payload,
        artifactDAO = database.artifactDao(),
        artifactReferenceDAO = database.artifactReferenceDao(),
        systemMetaDAO = database.systemMetaDao(),
        conversationDAO = database.conversationDao(),
        messageNodeDAO = database.messageNodeDao(),
        settingsCoordinator = mockk(relaxed = true),
        transactionRunner = RoomDatabaseTransactionRunner(database),
        fileNameCandidates = { candidates },
    )

    private fun folder(): String = "artifact-test-${Uuid.random()}".also(folders::add)

    private fun entity(
        relativePath: String,
        folder: String,
        state: ArtifactState,
        token: String?,
        createdAt: Long = 1L,
    ) = ArtifactEntity(
        folder = folder,
        relativePath = relativePath,
        displayName = File(relativePath).name,
        mimeType = "application/octet-stream",
        sizeBytes = 1,
        createdAt = createdAt,
        updatedAt = 1,
        state = state.name,
        payloadToken = token,
        origin = ArtifactOrigin.USER.name,
    )
}

private fun Settings.toEffectiveSnapshot(): EffectiveSettingsSnapshot = EffectiveSettingsSnapshot(
    settings = this,
    access = SettingsAccessIndex(),
    revision = 0L,
    managedState = ManagedConfigurationState.ABSENT,
)
