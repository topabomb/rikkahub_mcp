package net.weero.measix.pilot.data.provider

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Target-side grants are real framework permissions held by an independently hosted test APK. */
@RunWith(AndroidJUnit4::class)
class WorkspaceExportTargetGrantAndroidTest {
    @Test
    fun revokedTargetTreeRetainsCompletedFilesAndCleanupFailureThenRegrantRetriesWithoutOverwrite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val targetPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        val run = UUID.randomUUID().toString()
        val tree = DocumentsContract.buildTreeDocumentUri(ExportTargetDocumentsProvider.AUTHORITY, run)
        val originalId = "$run/first.txt"
        val originalBytes = "unrelated original document $run".toByteArray()
        val sourceBodies = linkedMapOf(
            "first.txt" to "first complete $run 中文",
            "second.txt" to "second retry $run",
            "third.txt" to "third must still be attempted $run",
        )
        val dependencies = context.applicationContext as WorkspaceDocumentsDependencies
        val commands = dependencies.workspaceCommands
        val queries = dependencies.workspaceQueries
        var workspace: String? = null
        var primary: Throwable? = null
        fun control(command: String, documentId: String? = null) =
            controlExportTarget(context, targetPackage, run, command, originalBytes, documentId)
        fun bytes(uri: Uri): ByteArray = requireNotNull(
            control("inspect", DocumentsContract.getDocumentId(uri)).getByteArray("bytes"))
        fun ids(): Set<String> = requireNotNull(control("inspect").getStringArrayList("ids")).toSet()
        try {
            control("setup")
            runBlocking { withTimeout(30_000) {
                GlobalContext.get().get<ApplicationRecoveryGate>().awaitReady()
                val source = commands.createWorkspace("Export target grant $run").workspaceId.also { workspace = it }
                sourceBodies.forEach { (name, body) -> commands.writeText(source, name, body) }
                val request = WorkspaceExportRequest(source, WorkspaceStorageArea.FILES, sourceBodies.keys.toList())
                val delegate = WorkspaceDocumentTreeDestination(context.contentResolver, tree)
                // No implicit grant travels with the control broadcast. The first create must fail.
                val denied = runCatching { runInterruptible(Dispatchers.IO) { delegate.create("ungranted.txt") } }.exceptionOrNull()
                assertNotNull(denied)
                assertSecurity(requireNotNull(denied))
                assertEquals(setOf(originalId), ids())
                val targetIdentity = control("grant")
                val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, run)
                context.contentResolver.query(rootUri, arrayOf("fixture_uid", "fixture_pid"), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals(targetIdentity.getInt("uid"), it.getInt(0))
                    assertEquals(targetIdentity.getInt("pid"), it.getInt(1))
                    assertNotEquals(Process.myUid(), it.getInt(0))
                }

                val attempts = mutableListOf<String>()
                val created = linkedMapOf<String, Uri>()
                val deleted = mutableListOf<Uri>()
                val closed = mutableMapOf<Uri, Int>()
                val destination = object : WorkspaceExportDestination {
                    override fun create(name: String): Uri {
                        attempts += name
                        return delegate.create(name).also { created[name] = it }
                    }
                    override fun displayName(uri: Uri): String = delegate.displayName(uri)
                    override fun open(uri: Uri): OutputStream {
                        // Only timing is controlled: framework permission enforcement rejects the next open.
                        if (uri == created["second.txt"]) control("revoke")
                        return object : FilterOutputStream(delegate.open(uri)) {
                            override fun close() {
                                closed[uri] = (closed[uri] ?: 0) + 1
                                super.close()
                            }
                        }
                    }
                    override fun delete(uri: Uri) { deleted += uri; delegate.delete(uri) }
                }
                val firstResults = mutableListOf<WorkspaceExportItem>()
                commands.exportFiles(request, destination, firstResults::add)
                assertEquals(sourceBodies.keys.toList(), attempts)
                assertEquals(sourceBodies.keys.toList(), firstResults.map { it.path })
                val complete = firstResults[0] as WorkspaceExportItem.Exported
                assertEquals(created.getValue("first.txt"), complete.uri)
                assertNotEquals("first.txt", complete.name)
                val partial = created.getValue("second.txt")
                assertEquals(setOf("first.txt", "second.txt"), created.keys)
                assertEquals(listOf(partial), deleted)
                assertEquals(mapOf(complete.uri to 1), closed)
                val failedOpen = (firstResults[1] as WorkspaceExportItem.Failed).cause
                assertSecurity(failedOpen)
                assertEquals(1, failedOpen.suppressed.size)
                assertSecurity(failedOpen.suppressed.single())
                assertSecurity((firstResults[2] as WorkspaceExportItem.Failed).cause)
                val beforeRetry = setOf(originalId, DocumentsContract.getDocumentId(complete.uri), DocumentsContract.getDocumentId(partial))
                assertEquals(beforeRetry, ids())
                assertArrayEquals(originalBytes, control("inspect", originalId).getByteArray("bytes"))
                assertArrayEquals(sourceBodies.getValue("first.txt").toByteArray(), bytes(complete.uri))
                assertArrayEquals(byteArrayOf(), bytes(partial))

                // Reuse the same capability and original request after a real regrant, not a fake destination.
                control("grant")
                val retried = mutableListOf<WorkspaceExportItem>()
                commands.exportFiles(request, delegate, retried::add)
                assertEquals(request.paths, retried.map { it.path })
                assertTrue(retried.all { it is WorkspaceExportItem.Exported })
                val exported = retried.filterIsInstance<WorkspaceExportItem.Exported>()
                val newIds = exported.map { DocumentsContract.getDocumentId(it.uri) }.toSet()
                assertEquals(3, newIds.size)
                assertTrue(newIds.intersect(beforeRetry).isEmpty())
                assertEquals(beforeRetry + newIds, ids())
                exported.forEach { item ->
                    assertArrayEquals(sourceBodies.getValue(item.path).toByteArray(), bytes(item.uri))
                    context.contentResolver.openInputStream(item.uri)!!.use {
                        assertArrayEquals(sourceBodies.getValue(item.path).toByteArray(), it.readBytes())
                    }
                }
                assertArrayEquals(originalBytes, control("inspect", originalId).getByteArray("bytes"))
                assertArrayEquals(sourceBodies.getValue("first.txt").toByteArray(), bytes(complete.uri))
                assertArrayEquals(byteArrayOf(), bytes(partial))
                assertEquals(sourceBodies.keys, queries.listFiles(source, WorkspaceStorageArea.FILES, "").map { it.name }.toSet())
                commands.writeText(source, "after-retry.txt", "source gate released")
            } }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            runBlocking { withContext(NonCancellable) {
                var cleanup: Throwable? = null
                try { control("cleanup") } catch (failure: Throwable) { cleanup = failure }
                try { workspace?.let { id -> withTimeout(15_000) {
                    assertTrue(commands.deleteWorkspace(id))
                    assertNull(queries.getWorkspace(id))
                } } } catch (failure: Throwable) { cleanup?.addSuppressed(failure) ?: run { cleanup = failure } }
                cleanup?.let { primary?.addSuppressed(it) ?: throw it }
            } }
        }
    }

    private fun assertSecurity(failure: Throwable) {
        assertTrue(failure.toString(), generateSequence(failure) { it.cause }.any { it is SecurityException })
        assertFalse(failure.message.isNullOrBlank())
    }

}

internal fun controlExportTarget(context: Context, targetPackage: String, run: String, command: String,
                                originalBytes: ByteArray, documentId: String? = null): Bundle {
    val completed = CountDownLatch(1)
    val response = AtomicReference<Bundle>()
    val intent = Intent("net.weero.measix.pilot.test.EXPORT_TARGET").apply {
        component = ComponentName(targetPackage, ExportTargetControlReceiver::class.java.name)
        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
        putExtra("run", run)
        putExtra("command", command)
        putExtra("targetPackage", context.packageName)
        putExtra("bytes", originalBytes)
        documentId?.let { putExtra("documentId", it) }
    }
    context.sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            response.set(getResultExtras(false))
            completed.countDown()
        }
    }, Handler(Looper.getMainLooper()), Activity.RESULT_CANCELED, null, null)
    assertTrue("Target provider control timed out: $command", completed.await(15, TimeUnit.SECONDS))
    val result = requireNotNull(response.get())
    assertEquals(targetPackage, result.getString("package"))
    assertTrue(result.getInt("uid") > 0)
    assertNotEquals(Process.myUid(), result.getInt("uid"))
    assertNotEquals(Process.myPid(), result.getInt("pid"))
    assertTrue("$command: ${result.getString("errorType")}: ${result.getString("errorMessage")}", result.getBoolean("success"))
    return result
}
