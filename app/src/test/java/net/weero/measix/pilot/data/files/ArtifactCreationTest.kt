package net.weero.measix.pilot.data.files

import android.content.Context
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.ToolRuntimeState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.request.ContextBudget
import net.weero.measix.pilot.data.ai.request.estimateStableTextTokens
import net.weero.measix.pilot.data.ai.tools.REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER
import net.weero.measix.pilot.data.ai.tools.ToolOutputCompactionCandidate
import net.weero.measix.pilot.data.ai.tools.ToolOutputCompactionPlan
import net.weero.measix.pilot.data.ai.tools.ToolOutputReadResult
import net.weero.measix.pilot.data.ai.tools.ToolOutputStore
import net.weero.measix.pilot.data.ai.tools.estimatedToolOutputMarkerTokens
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceEntity
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceType
import net.weero.measix.pilot.data.db.entity.ArtifactState
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import net.weero.measix.pilot.data.imggen.TINY_PNG
import net.weero.measix.pilot.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
internal class ArtifactCreationTest : ArtifactStoreLifecycleTestBase() {
    @Test
    fun `staged tool output batch uses one all or nothing ownership lease`() = runTest {
        folders += FileFolders.TOOL_OUTPUTS
        val messageId = Uuid.random()
        val candidates = listOf("a".repeat(4096), "b".repeat(4096)).mapIndexed { ordinal, value ->
                val originalTokens = estimateStableTextTokens(value)
                val markerTokens = estimatedToolOutputMarkerTokens("completed", value)
                ToolOutputCompactionCandidate(
                    locator = ToolCallLocator(messageId, Uuid.random(), Uuid.random()),
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
        val minimum = ContextBudget.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS
        val markerTokens = estimateStableTextTokens(REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER)
        fun candidate(netReclaim: Long): ToolOutputCompactionCandidate {
            val text = "x".repeat(((markerTokens + netReclaim) * 4).toInt())
            val originalTokens = estimateStableTextTokens(text)
            return ToolOutputCompactionCandidate(
                locator = ToolCallLocator(Uuid.random(), Uuid.random(), Uuid.random()),
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
        val locator = ToolCallLocator(messageId, Uuid.random(), Uuid.random())
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
                            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
                            toolName = "tool",
                            input = "{}",
                            output = listOf(replacement.marker),
                            runtimeState = ToolRuntimeState(me.rerere.ai.core.ToolOutputPolicy.ARCHIVABLE_TEXT, archive),
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
}
