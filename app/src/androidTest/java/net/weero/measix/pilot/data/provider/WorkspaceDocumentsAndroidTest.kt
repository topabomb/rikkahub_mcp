package net.weero.measix.pilot.data.provider

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.system.Os
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.rerere.workspace.WorkspaceManager
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.entity.WorkspaceEntity
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.workspace.WorkspaceApplicationService
import net.weero.measix.pilot.service.workspace.WorkspaceQueryService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspaceDocumentsAndroidTest {
    @Test
    fun registeredDocumentsUseTheRealOwnersForOpenCopyMoveAndDelete() = fixture { provider, manager, _ ->
        val source = provider.createDocument("ws/source", Document.MIME_TYPE_DIR, "资料")
        val document = provider.createDocument(source, "text/plain", " 内容\\数据 .txt ")
        provider.openDocument(document, "w", null).use { descriptor ->
            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { it.write("original".toByteArray()) }
        }
        provider.createDocument("ws/source", "text/plain", ".workspace-copy-user.txt")
        provider.queryChildDocuments("ws/source", null, sortOrder = null).use { assertEquals(2, it.count) }
        val copied = provider.copyDocument(source, "ws/target")
        assertEquals("ws/target/资料", copied)
        assertEquals("original", File(manager.filesDir("target"), "资料/ 内容\\数据 .txt ").readText())
        val renamed = provider.renameDocument(document, "renamed.txt")
        val moved = provider.moveDocument(renamed, source, "ws/target")
        assertTrue(provider.isChildDocument("root", moved))
        assertTrue(provider.isChildDocument("ws/target", moved))
        assertFalse(provider.isChildDocument("ws/source", moved))
        assertFalse(provider.isChildDocument(moved, moved))
        provider.queryDocument(document, null).use { assertEquals(0, it.count) }
        provider.deleteDocument(copied)
        assertFalse(File(manager.filesDir("target"), "资料").exists())
        assertEquals("original", File(manager.filesDir("target"), "renamed.txt").readText())
    }

    @Test
    fun forgedRootsAndLinksCannotEscapeOrCreateWorkspacesAndFailedCopiesAreRemoved() = fixture { provider, manager, root ->
        provider.queryDocument("ws/unknown", null).use { assertEquals(0, it.count) }
        assertFalse(provider.isChildDocument("root", "ws/unknown/fake"))
        assertThrows(Exception::class.java) { provider.createDocument("ws/unknown", "text/plain", "fake") }
        assertFalse(manager.workspaceDir("unknown").exists())
        listOf("ws/../secret", "ws/source/../secret", "ws/source//secret", "ws/source/").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { provider.queryDocument(id, null) }
        }
        val outside = File(root, "outside").apply { mkdirs() }
        File(outside, "secret").writeText("preserved")
        val source = File(manager.filesDir("source"), "tree").apply { mkdirs() }
        File(source, "ordinary").writeText("copy")
        Os.symlink(outside.absolutePath, File(source, "link").absolutePath)
        assertThrows(Exception::class.java) { provider.openDocument("ws/source/tree/link/secret", "wt", null) }
        assertThrows(Exception::class.java) { provider.copyDocument("ws/source/tree", "ws/target") }
        assertTrue(manager.filesDir("target").listFiles().orEmpty().isEmpty())
        assertTrue(manager.tempDir("target").listFiles().orEmpty().isEmpty())
        provider.deleteDocument("ws/source/tree")
        assertFalse(source.exists())
        assertEquals("preserved", File(outside, "secret").readText())
        assertTrue(manager.filesDir("target").renameTo(File(root, "retired-target-files")))
        provider.queryDocument("ws/target", null).use { assertEquals(0, it.count) }
        provider.queryChildDocuments("root", null, sortOrder = null).use { assertEquals(1, it.count) }
        assertFalse(manager.filesDir("target").exists())
    }

    @Test
    fun cancellationBeforeProviderHandoffClosesTheAcquiredDescriptor() = fixture { _, manager, _ ->
        File(manager.filesDir("source"), "value").writeText("value")
        val signal = CancellationSignal()
        val descriptor = manager.openDocument("source", "value", ParcelFileDescriptor.MODE_READ_ONLY)
        val commands = mockk<WorkspaceApplicationService>()
        coEvery { commands.openDocument("source", "value", any()) } answers { signal.cancel(); descriptor }
        val provider = provider(commands, mockk())
        assertThrows(android.os.OperationCanceledException::class.java) { provider.openDocument("ws/source/value", "r", signal) }
        assertFalse(descriptor.fileDescriptor.valid())
    }

    private fun fixture(block: (WorkspaceDocumentsProvider, WorkspaceManager, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "workspace-documents-${UUID.randomUUID()}").apply { mkdirs() }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val manager = WorkspaceManager(File(root, "workspaces"))
            val repository = WorkspaceRepository(database.workspaceDao(), manager, mockk(), mockk())
            val ready = ApplicationRecoveryGate().apply { ready() }
            val commands = WorkspaceApplicationService(repository, mockk(), mockk(), mockk(), File(root, "temp"), ready)
            val queries = WorkspaceQueryService(repository, mockk(), mockk(), ready)
            runBlocking {
                // These distinct IDs collide in the existing stripe table; transfer must lock it only once.
                database.workspaceDao().upsert(WorkspaceEntity("Aa", "Source", "source", createdAt = 0, updatedAt = 0))
                database.workspaceDao().upsert(WorkspaceEntity("BB", "Target", "target", createdAt = 0, updatedAt = 0))
            }
            manager.ensureWorkspace("source")
            manager.ensureWorkspace("target")
            block(provider(commands, queries), manager, root)
        } finally { database.close(); root.deleteRecursively() }
    }

    private fun provider(commands: WorkspaceApplicationService, queries: WorkspaceQueryService): WorkspaceDocumentsProvider {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = object : Application(), WorkspaceDocumentsDependencies {
            init { attachBaseContext(context) }
            override val workspaceCommands = commands
            override val workspaceQueries = queries
        }
        return WorkspaceDocumentsProvider().apply {
            attachInfo(object : ContextWrapper(context) { override fun getApplicationContext() = app }, ProviderInfo().apply {
                authority = context.packageName + ".documents"
                exported = true
                grantUriPermissions = true
                readPermission = "android.permission.MANAGE_DOCUMENTS"
                writePermission = readPermission
            })
        }
    }
}
