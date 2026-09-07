package net.weero.measix.pilot.data.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolOutputArchive
import me.rerere.ai.ui.ToolOutputArchiveRef
import me.rerere.ai.ui.ToolRuntimeState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.tools.ToolOutputGrepResult
import net.weero.measix.pilot.data.ai.tools.ToolOutputProtocol
import net.weero.measix.pilot.data.ai.tools.ToolOutputReadResult
import net.weero.measix.pilot.data.ai.tools.ToolOutputStore
import net.weero.measix.pilot.data.ai.tools.formatReadResult
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceEntity
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceType
import net.weero.measix.pilot.data.db.entity.ArtifactState
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import net.weero.measix.pilot.data.imggen.TINY_PNG
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
internal class ArtifactReferenceProjectionTest : ArtifactStoreLifecycleTestBase() {
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
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "shared",
                    toolName = "tool",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("[archived tool output]")),
                    runtimeState = ToolRuntimeState(me.rerere.ai.core.ToolOutputPolicy.ARCHIVABLE_TEXT, archive),
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
            ToolOutputProtocol.TOOL_OUTPUT_MAX_RESPONSE_BYTES)

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
}
