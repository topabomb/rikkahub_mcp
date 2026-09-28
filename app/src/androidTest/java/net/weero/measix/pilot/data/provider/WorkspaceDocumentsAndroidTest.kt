package net.weero.measix.pilot.data.provider

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.net.Uri
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
import me.rerere.workspace.WorkspaceStorageArea
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.entity.WorkspaceEntity
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.workspace.WorkspaceApplicationService
import net.weero.measix.pilot.service.workspace.WorkspaceExportDestination
import net.weero.measix.pilot.service.workspace.WorkspaceExportItem
import net.weero.measix.pilot.service.workspace.WorkspaceExportRequest
import net.weero.measix.pilot.service.workspace.WorkspaceQueryService
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalRuntime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspaceDocumentsAndroidTest {
    @Test
    fun batchExportUsesProviderCreatedNamesAndRejectsSymlinkSources() = fixture { provider, manager, root ->
        val commands = (provider.context!!.applicationContext as WorkspaceDocumentsDependencies).workspaceCommands
        File(manager.filesDir("source"), "same.txt").writeText("new bytes")
        File(manager.filesDir("target"), "same.txt").writeText("existing bytes")
        val outside = File(root, "outside.txt").apply { writeText("secret") }
        Os.symlink(outside.absolutePath, File(manager.filesDir("source"), "link.txt").absolutePath)
        val ids = mutableMapOf<android.net.Uri, String>()
        val destination = object : net.weero.measix.pilot.service.workspace.WorkspaceExportDestination {
            override fun create(name: String): android.net.Uri {
                val id = provider.createDocument("ws/target", "text/plain", name)
                return android.net.Uri.parse("content://test/${ids.size}").also { ids[it] = id }
            }
            override fun displayName(uri: android.net.Uri): String = provider.queryDocument(ids.getValue(uri), null).use {
                check(it.moveToFirst())
                it.getString(it.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME))
            }
            override fun open(uri: android.net.Uri): java.io.OutputStream =
                ParcelFileDescriptor.AutoCloseOutputStream(provider.openDocument(ids.getValue(uri), "w", null))
            override fun delete(uri: android.net.Uri) = provider.deleteDocument(ids.getValue(uri))
        }
        val results = mutableListOf<net.weero.measix.pilot.service.workspace.WorkspaceExportItem>()
        runBlocking {
            commands.exportFiles(net.weero.measix.pilot.service.workspace.WorkspaceExportRequest(
                "Aa", me.rerere.workspace.WorkspaceStorageArea.FILES, listOf("same.txt", "link.txt")), destination, results::add)
        }
        val success = results[0] as net.weero.measix.pilot.service.workspace.WorkspaceExportItem.Exported
        assertNotEquals("same.txt", success.name)
        assertEquals("new bytes", File(manager.filesDir("target"), success.name).readText())
        assertEquals("existing bytes", File(manager.filesDir("target"), "same.txt").readText())
        assertTrue(results[1] is net.weero.measix.pilot.service.workspace.WorkspaceExportItem.Failed)
        assertFalse(File(manager.filesDir("target"), "link.txt").exists())
        assertEquals("secret", outside.readText())
    }

    @Test
    fun batchExportRevalidatesReplacementDeletionAndSymlinkAfterStat() {
        listOf("replace", "delete", "symlink").forEach(::assertBatchExportAfterSourceChange)
    }

    @Test
    fun deletingWorkspaceAfterStatPreservesCompletedExportAndCleansOnlyIncompleteDocument() {
        assertBatchExportAfterSourceChange("workspace-delete")
    }

    private fun assertBatchExportAfterSourceChange(change: String) = fixture { provider, manager, root ->
        val commands = (provider.context!!.applicationContext as WorkspaceDocumentsDependencies).workspaceCommands
        val source = manager.filesDir("source")
        val target = manager.filesDir("target")
        File(source, "complete.txt").writeText("completed bytes")
        File(source, "changing.txt").writeText("old bytes")
        File(source, "later.txt").writeText("later bytes")
        File(target, "changing.txt").writeText("unrelated existing bytes")
        val outside = File(root, "outside.txt").apply { writeText("outside secret") }
        val ids = linkedMapOf<Uri, String>()
        val names = linkedMapOf<Uri, String>()
        val closes = mutableMapOf<Uri, Int>()
        val deleted = mutableListOf<Uri>()
        var changed = false
        val destination = object : WorkspaceExportDestination {
            override fun create(name: String): Uri {
                val id = provider.createDocument("ws/target", "text/plain", name)
                return Uri.parse("content://test/${ids.size}").also { ids[it] = id }
            }

            override fun displayName(uri: Uri): String = provider.queryDocument(ids.getValue(uri), null).use {
                check(it.moveToFirst())
                it.getString(it.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)).also { name -> names[uri] = name }
            }

            override fun open(uri: Uri): OutputStream {
                if (ids.size == 2) {
                    check(!changed)
                    // The destination is opened after source stat, outside the source command gate.
                    runBlocking {
                        if (change == "workspace-delete") {
                            assertTrue(commands.deleteWorkspace("Aa"))
                        } else {
                            commands.deleteFile("Aa", WorkspaceStorageArea.FILES, "changing.txt", recursive = false)
                            when (change) {
                                "replace" -> commands.writeText("Aa", "changing.txt", "replacement bytes are different")
                                "symlink" -> Os.symlink(outside.absolutePath, File(source, "changing.txt").absolutePath)
                                "delete" -> Unit
                                else -> error("Unexpected source change: $change")
                            }
                        }
                    }
                    changed = true
                }
                return object : FilterOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(
                    provider.openDocument(ids.getValue(uri), "w", null)
                )) {
                    override fun close() {
                        closes[uri] = closes.getOrDefault(uri, 0) + 1
                        super.close()
                    }
                }
            }

            override fun delete(uri: Uri) {
                deleted += uri
                provider.deleteDocument(ids.getValue(uri))
            }
        }
        val paths = listOf("complete.txt", "changing.txt", "later.txt")
        val results = mutableListOf<WorkspaceExportItem>()
        runBlocking {
            commands.exportFiles(WorkspaceExportRequest("Aa", WorkspaceStorageArea.FILES, paths), destination, results::add)
        }
        assertTrue(change, changed)
        assertEquals(change, paths, results.map { it.path })
        assertEquals(change, "completed bytes", File(target, (results[0] as WorkspaceExportItem.Exported).name).readText())
        assertEquals(change, "unrelated existing bytes", File(target, "changing.txt").readText())
        assertEquals(change, "outside secret", outside.readText())
        assertEquals(change, ids.keys.associateWith { 1 }, closes)
        if (change == "replace") {
            assertEquals("replacement bytes are different", File(target, (results[1] as WorkspaceExportItem.Exported).name).readText())
            assertTrue(deleted.isEmpty())
        } else {
            val failure = results[1] as WorkspaceExportItem.Failed
            assertFalse(change, failure.cause.message.isNullOrBlank())
            assertTrue(change, failure.cause.suppressed.isEmpty())
            assertEquals(change, listOf(ids.keys.elementAt(1)), deleted)
            assertFalse(change, File(target, names.getValue(deleted.single())).exists())
        }
        if (change == "workspace-delete") {
            assertTrue(results[2] is WorkspaceExportItem.Failed)
            assertEquals(2, ids.size)
            assertFalse(manager.workspaceDir("source").exists())
            provider.queryDocument("ws/source", null).use { assertEquals(0, it.count) }
        } else {
            assertEquals(change, "later bytes", File(target, (results[2] as WorkspaceExportItem.Exported).name).readText())
            assertEquals(change, 3, ids.size)
            assertEquals(change, "completed bytes", File(source, "complete.txt").readText())
            assertEquals(change, "later bytes", File(source, "later.txt").readText())
            when (change) {
                "replace" -> assertEquals("replacement bytes are different", File(source, "changing.txt").readText())
                "delete" -> assertFalse(File(source, "changing.txt").exists())
                "symlink" -> assertEquals(outside.absolutePath, Os.readlink(File(source, "changing.txt").absolutePath))
            }
        }
        val expectedNames = results.filterIsInstance<WorkspaceExportItem.Exported>().map { it.name }.toSet() + "changing.txt"
        assertEquals(change, expectedNames, target.listFiles().orEmpty().map { it.name }.toSet())
    }

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
            var settings = Settings(assistants = emptyList())
            val settingsStore = mockk<SettingsStore>()
            coEvery { settingsStore.snapshotLocal() } answers { settings }
            coEvery { settingsStore.updateLocal(any()) } answers {
                firstArg<(Settings) -> Settings>().invoke(settings).also { settings = it }
            }
            val repository = WorkspaceRepository(database.workspaceDao(), manager, mockk(), settingsStore)
            val ready = ApplicationRecoveryGate().apply { ready() }
            val terminals = mockk<WorkspaceTerminalRuntime>()
            coEvery { terminals.closeWorkspace("source") } returns Unit
            val commands = WorkspaceApplicationService(repository, terminals, mockk(), mockk(), File(root, "temp"), ready)
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
