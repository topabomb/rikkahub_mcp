package net.weero.measix.pilot.data.provider

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.workspace.WorkspaceStorageArea
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.workspace.WorkspaceDocumentTreeDestination
import net.weero.measix.pilot.service.workspace.WorkspaceExportDestination
import net.weero.measix.pilot.service.workspace.WorkspaceExportItem
import net.weero.measix.pilot.service.workspace.WorkspaceExportRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Same-UID resolver integration; system picker selection and persisted URI grants are separate UI gates. */
@RunWith(AndroidJUnit4::class)
class WorkspaceExportFlowAndroidTest {
    @Test
    fun cancellationDuringRealCopyDeletesOnlyPartialDocumentAndReleasesOwnerGate() =
        cancelDuringRealCopy(failDelete = false)

    @Test
    fun cancellationDuringRealCopyPreservesOriginalCleanupFailureAndCompletedDocument() =
        cancelDuringRealCopy(failDelete = true)

    private fun cancelDuringRealCopy(failDelete: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dependencies = context.applicationContext as WorkspaceDocumentsDependencies
        val commands = dependencies.workspaceCommands
        val queries = dependencies.workspaceQueries
        val resolver = context.contentResolver
        val authority = "${context.packageName}.documents"
        val suffix = UUID.randomUUID().toString()
        val sourceName = "Cancelled export source $suffix"
        val targetName = "Cancelled export target $suffix"
        val createdIds = mutableListOf<String>()
        val sourceBytes = linkedMapOf(
            "ok.bin" to ByteArray(1024) { it.toByte() },
            "partial.bin" to ByteArray(192 * 1024) { (it * 31).toByte() },
            "next.bin" to "must not start".toByteArray(),
        )
        val partialWritten = CompletableDeferred<Int>()
        val releaseWrite = CountDownLatch(1)
        val writeInterrupted = AtomicBoolean(false)
        val deleteFailure = IOException("Test destination refused partial document cleanup")
        var primaryFailure: Throwable? = null
        runBlocking {
            try {
                withTimeout(30_000) {
                    GlobalContext.get().get<ApplicationRecoveryGate>().awaitReady()
                    val source = commands.createWorkspace(sourceName).workspaceId.also(createdIds::add)
                    val target = commands.createWorkspace(targetName).workspaceId.also(createdIds::add)
                    for ((name, bytes) in sourceBytes) {
                        bytes.inputStream().use {
                            commands.importFile(source, WorkspaceStorageArea.FILES, "", name, it)
                        }
                    }
                    commands.writeText(target, "unrelated.txt", "preserved destination")
                    val roots = queries.documentRoots()
                    val sourceRoot = roots.single { it.workspaceName == sourceName }.root
                    val targetRoot = roots.single { it.workspaceName == targetName }.root
                    fun tree(root: String) = DocumentsContract.buildTreeDocumentUri(authority, "ws/$root")
                    suspend fun read(root: String, name: String): ByteArray = runInterruptible(Dispatchers.IO) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree(root), "ws/$root/$name")
                        requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
                    }
                    val delegate = WorkspaceDocumentTreeDestination(resolver, tree(targetRoot))
                    val created = linkedMapOf<Uri, String>()
                    val closes = mutableMapOf<Uri, Int>()
                    val deleted = mutableListOf<Uri>()
                    val destination = object : WorkspaceExportDestination {
                        override fun create(name: String): Uri = delegate.create(name).also { created[it] = name }
                        override fun displayName(uri: Uri): String = delegate.displayName(uri)
                        override fun open(uri: Uri): OutputStream = object : FilterOutputStream(delegate.open(uri)) {
                            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                out.write(bytes, offset, length)
                                if (created.getValue(uri) == "partial.bin") {
                                    partialWritten.complete(length)
                                    try {
                                        releaseWrite.await()
                                    } catch (interrupted: InterruptedException) {
                                        writeInterrupted.set(true)
                                        throw interrupted
                                    }
                                }
                            }

                            override fun close() {
                                closes[uri] = (closes[uri] ?: 0) + 1
                                super.close()
                            }
                        }

                        override fun delete(uri: Uri) {
                            deleted += uri
                            if (failDelete) throw deleteFailure
                            delegate.delete(uri)
                        }
                    }
                    val results = mutableListOf<WorkspaceExportItem>()
                    var cancellation: CancellationException? = null
                    val export = launch {
                        try {
                            commands.exportFiles(
                                WorkspaceExportRequest(source, WorkspaceStorageArea.FILES, sourceBytes.keys.toList()),
                                destination, results::add,
                            )
                        } catch (cancelled: CancellationException) {
                            cancellation = cancelled
                            throw cancelled
                        }
                    }
                    try {
                        val copiedCount = partialWritten.await()
                        assertTrue(copiedCount in 1 until sourceBytes.getValue("partial.bin").size)
                        export.cancel()
                        export.join()
                        assertTrue(export.isCancelled)
                        assertTrue("Real runInterruptible copy must interrupt the blocked output write", writeInterrupted.get())
                        val cancelled = requireNotNull(cancellation)
                        if (failDelete) assertSame(deleteFailure, cancelled.suppressed.single())
                        else assertTrue(cancelled.suppressed.isEmpty())
                        assertEquals(listOf("ok.bin", "partial.bin"), created.values.toList())
                        val partialUri = created.entries.single { it.value == "partial.bin" }.key
                        assertEquals(listOf(partialUri), deleted)
                        assertEquals(created.keys.associateWith { 1 }, closes)
                        val success = results.single() as WorkspaceExportItem.Exported
                        assertEquals("ok.bin", success.path)
                        assertEquals(created.entries.single { it.value == "ok.bin" }.key, success.uri)
                        assertArrayEquals(sourceBytes.getValue("ok.bin"), read(targetRoot, success.name))
                        val expectedNames = setOf("unrelated.txt", success.name) +
                            if (failDelete) setOf("partial.bin") else emptySet()
                        assertEquals(expectedNames, queries.listFiles(target, WorkspaceStorageArea.FILES, "").map { it.name }.toSet())
                        if (failDelete) {
                            assertArrayEquals(sourceBytes.getValue("partial.bin").copyOf(copiedCount), read(targetRoot, "partial.bin"))
                        }
                        assertArrayEquals("preserved destination".toByteArray(), read(targetRoot, "unrelated.txt"))
                        for ((name, bytes) in sourceBytes) assertArrayEquals(bytes, read(sourceRoot, name))
                        commands.writeText(source, "after-cancel.txt", "source gate released")
                        commands.writeText(target, "after-cancel.txt", "target gate released")
                        assertArrayEquals("source gate released".toByteArray(), read(sourceRoot, "after-cancel.txt"))
                        assertArrayEquals("target gate released".toByteArray(), read(targetRoot, "after-cancel.txt"))
                    } finally {
                        export.cancel()
                        releaseWrite.countDown()
                        withContext(NonCancellable) { export.join() }
                    }
                }
            } catch (error: Throwable) {
                primaryFailure = error
                throw error
            } finally {
                releaseWrite.countDown()
                withContext(NonCancellable) {
                    var cleanupFailure: Throwable? = null
                    for (id in createdIds.asReversed()) {
                        try {
                            withTimeout(15_000) {
                                assertTrue("Test-created workspace was not deleted: $id", commands.deleteWorkspace(id))
                                assertNull(queries.getWorkspace(id))
                            }
                        } catch (error: Throwable) {
                            cleanupFailure?.addSuppressed(error) ?: run { cleanupFailure = error }
                        }
                    }
                    cleanupFailure?.let { error -> primaryFailure?.addSuppressed(error) ?: throw error }
                }
            }
        }
    }

    @Test
    fun registeredProviderExportsPreserveCollisionsAndSameWorkspaceExportReleasesOwnerGate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dependencies = context.applicationContext as WorkspaceDocumentsDependencies
        val commands = dependencies.workspaceCommands
        val queries = dependencies.workspaceQueries
        val authority = "${context.packageName}.documents"
        val provider = requireNotNull(context.packageManager.resolveContentProvider(authority, 0))
        assertEquals(WorkspaceDocumentsProvider::class.java.name, provider.name)
        val resolver = context.contentResolver
        val createdIds = mutableListOf<String>()
        val suffix = UUID.randomUUID().toString()
        val sourceName = "Export integration source $suffix"
        val targetName = "Export integration target $suffix"
        val sourceBytes = byteArrayOf(0, 1, 2, 127, -128, -1) + "source 原字节".toByteArray()
        val existingBytes = "existing target 保留".toByteArray()
        var primaryFailure: Throwable? = null
        runBlocking {
            try {
                withTimeout(30_000) {
                    GlobalContext.get().get<ApplicationRecoveryGate>().awaitReady()
                    val source = commands.createWorkspace(sourceName).workspaceId.also(createdIds::add)
                    val target = commands.createWorkspace(targetName).workspaceId.also(createdIds::add)
                    sourceBytes.inputStream().use {
                        commands.importFile(source, WorkspaceStorageArea.FILES, "", "same.bin", it)
                    }
                    existingBytes.inputStream().use {
                        commands.importFile(target, WorkspaceStorageArea.FILES, "", "same.bin", it)
                    }
                    val roots = queries.documentRoots()
                    val sourceRoot = roots.single { it.workspaceName == sourceName }.root
                    val targetRoot = roots.single { it.workspaceName == targetName }.root
                    val sourceTree = DocumentsContract.buildTreeDocumentUri(authority, "ws/$sourceRoot")
                    val targetTree = DocumentsContract.buildTreeDocumentUri(authority, "ws/$targetRoot")

                    suspend fun assertBytes(tree: Uri, root: String, name: String, expected: ByteArray) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "ws/$root/$name")
                        val bytes = runInterruptible(Dispatchers.IO) {
                            requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
                        }
                        assertArrayEquals(expected, bytes)
                    }

                    suspend fun exportTo(tree: Uri): WorkspaceExportItem.Exported {
                        val results = mutableListOf<WorkspaceExportItem>()
                        commands.exportFiles(
                            WorkspaceExportRequest(source, WorkspaceStorageArea.FILES, listOf("same.bin")),
                            WorkspaceDocumentTreeDestination(resolver, tree),
                            results::add,
                        )
                        assertEquals(1, results.size)
                        val result = results.single()
                        if (result is WorkspaceExportItem.Failed) throw AssertionError("Resolver export failed", result.cause)
                        assertTrue(result is WorkspaceExportItem.Exported)
                        return result as WorkspaceExportItem.Exported
                    }

                    val copied = exportTo(targetTree)
                    assertNotEquals("same.bin", copied.name)
                    assertEquals(authority, copied.uri.authority)
                    assertEquals("ws/$targetRoot/${copied.name}", DocumentsContract.getDocumentId(copied.uri))
                    assertBytes(targetTree, targetRoot, copied.name, sourceBytes)
                    assertBytes(targetTree, targetRoot, "same.bin", existingBytes)
                    assertBytes(sourceTree, sourceRoot, "same.bin", sourceBytes)

                    // Reentering the production provider for the source itself exercises the same owner stripe.
                    val selfCopy = exportTo(sourceTree)
                    assertNotEquals("same.bin", selfCopy.name)
                    assertBytes(sourceTree, sourceRoot, selfCopy.name, sourceBytes)
                    assertBytes(sourceTree, sourceRoot, "same.bin", sourceBytes)
                    assertEquals(setOf("same.bin", selfCopy.name), queries.listFiles(source, WorkspaceStorageArea.FILES, "").map { it.name }.toSet())
                    assertEquals(setOf("same.bin", copied.name), queries.listFiles(target, WorkspaceStorageArea.FILES, "").map { it.name }.toSet())

                    // Subsequent writes must acquire both original command gates normally.
                    commands.writeText(source, "after-export.txt", "source gate released")
                    commands.writeText(target, "after-export.txt", "target gate released")
                    assertBytes(sourceTree, sourceRoot, "after-export.txt", "source gate released".toByteArray())
                    assertBytes(targetTree, targetRoot, "after-export.txt", "target gate released".toByteArray())
                }
            } catch (error: Throwable) {
                primaryFailure = error
                throw error
            } finally {
                withContext(NonCancellable) {
                    var cleanupFailure: Throwable? = null
                    for (id in createdIds.asReversed()) {
                        try {
                            withTimeout(15_000) {
                                assertTrue("Test-created workspace was not deleted: $id", commands.deleteWorkspace(id))
                                assertNull(queries.getWorkspace(id))
                            }
                        } catch (error: Throwable) {
                            cleanupFailure?.addSuppressed(error) ?: run { cleanupFailure = error }
                        }
                    }
                    cleanupFailure?.let { error -> primaryFailure?.addSuppressed(error) ?: throw error }
                }
            }
        }
    }
}
