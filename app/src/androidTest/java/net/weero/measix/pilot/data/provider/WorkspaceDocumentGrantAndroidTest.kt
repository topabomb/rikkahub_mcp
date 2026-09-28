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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real framework grants and fresh ContentResolver opens in a different UID; no picker simulation. */
@RunWith(AndroidJUnit4::class)
class WorkspaceDocumentGrantAndroidTest {
    @Test
    fun revokedReadGrantRejectsNextCrossUidOpenAndRegrantRestoresOnlyThatDocument() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val consumerPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        assertNotEquals(context.packageName, consumerPackage)
        val dependencies = context.applicationContext as WorkspaceDocumentsDependencies
        val commands = dependencies.workspaceCommands
        val queries = dependencies.workspaceQueries
        val expected = "Granted document ${UUID.randomUUID()} 中文".toByteArray(Charsets.UTF_8)
        var workspace: String? = null
        var grantedUri: Uri? = null
        var failure: Throwable? = null
        try {
            val uri = runBlocking { withTimeout(30_000) {
                GlobalContext.get().get<ApplicationRecoveryGate>().awaitReady()
                val name = "Cross UID grant ${UUID.randomUUID()}"
                val id = commands.createWorkspace(name).workspaceId.also { workspace = it }
                commands.writeText(id, "granted.txt", expected.toString(Charsets.UTF_8))
                commands.writeText(id, "sibling.txt", "must remain private")
                val root = queries.documentRoots().single { it.workspaceName == name }.root
                DocumentsContract.buildDocumentUri("${context.packageName}.documents", "ws/$root/granted.txt")
            } }
            grantedUri = uri
            val sibling = DocumentsContract.buildDocumentUri(
                uri.authority, DocumentsContract.getDocumentId(uri).substringBeforeLast('/') + "/sibling.txt"
            )

            assertDenied(probe(context, consumerPackage, uri))
            context.grantUriPermission(consumerPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertArrayEquals(expected, assertOpened(probe(context, consumerPackage, uri)))
            assertDenied(probe(context, consumerPackage, uri, write = true))
            assertDenied(probe(context, consumerPackage, sibling))

            context.revokeUriPermission(consumerPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The receiver closes every descriptor; this is a new open after revocation.
            assertDenied(probe(context, consumerPackage, uri))
            context.grantUriPermission(consumerPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertArrayEquals(expected, assertOpened(probe(context, consumerPackage, uri)))
            assertDenied(probe(context, consumerPackage, sibling))
            context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(expected, it.readBytes()) }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            runBlocking { withContext(NonCancellable) {
                var cleanupFailure: Throwable? = null
                try { grantedUri?.let { context.revokeUriPermission(consumerPackage, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                catch (error: Throwable) { cleanupFailure = error }
                try {
                    workspace?.let { id -> withTimeout(15_000) {
                        assertTrue(commands.deleteWorkspace(id))
                        assertNull(queries.getWorkspace(id))
                    } }
                } catch (error: Throwable) { cleanupFailure?.addSuppressed(error) ?: run { cleanupFailure = error } }
                cleanupFailure?.let { failure?.addSuppressed(it) ?: throw it }
            } }
        }
    }

    private fun probe(context: Context, consumerPackage: String, uri: Uri, write: Boolean = false): Bundle {
        val completed = CountDownLatch(1)
        val result = AtomicReference<Bundle>()
        val request = Intent("net.weero.measix.pilot.test.OPEN_DOCUMENT").apply {
            component = ComponentName(consumerPackage, DocumentGrantProbeReceiver::class.java.name)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
            // A String extra cannot implicitly grant access, unlike Intent data/ClipData + grant flags.
            putExtra("uri", uri.toString())
            putExtra("write", write)
        }
        context.sendOrderedBroadcast(request, null, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                result.set(getResultExtras(false))
                completed.countDown()
            }
        }, Handler(Looper.getMainLooper()), Activity.RESULT_CANCELED, null, null)
        assertTrue("Cross-UID receiver did not return", completed.await(15, TimeUnit.SECONDS))
        val response = requireNotNull(result.get()) { "Cross-UID receiver returned no result" }
        assertEquals(consumerPackage, response.getString("package"))
        assertTrue(response.getInt("uid") > 0)
        assertNotEquals("Probe must run outside target UID", Process.myUid(), response.getInt("uid"))
        assertNotEquals("Probe must run outside instrumentation process", Process.myPid(), response.getInt("pid"))
        return response
    }

    private fun assertDenied(result: Bundle) {
        assertFalse(result.getBoolean("opened"))
        assertEquals(result.getString("errorMessage"), SecurityException::class.java.name, result.getString("errorType"))
        assertFalse(result.getString("errorMessage").isNullOrBlank())
        assertNull(result.getByteArray("bytes"))
    }

    private fun assertOpened(result: Bundle): ByteArray {
        assertTrue(result.getString("errorMessage"), result.getBoolean("opened"))
        assertNull(result.getString("errorType"))
        return requireNotNull(result.getByteArray("bytes"))
    }
}
