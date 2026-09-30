package net.weero.measix.pilot.ui.pages.extensions.workspace

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dokar.sonner.rememberToasterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.workspace.WorkspaceStorageArea
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.provider.WorkspaceDocumentsDependencies
import net.weero.measix.pilot.data.provider.ExportTargetDocumentsProvider
import net.weero.measix.pilot.data.provider.controlExportTarget
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.workspace.*
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.context.Navigator
import net.weero.measix.pilot.ui.theme.LocalDarkMode
import net.weero.measix.pilot.utils.fileSizeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.androidx.compose.koinViewModel
import org.koin.core.context.GlobalContext
import org.koin.core.parameter.parametersOf
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.UUID
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual page/VM lifecycle and controlled picker delivery; target grants use a separate test APK UID. */
@RunWith(AndroidJUnit4::class)
class WorkspaceExportLifecycleAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val observedVm = AtomicReference<WorkspaceDetailVM>()

    @Test
    fun resumingFilesPageRefreshesMetadataAfterOwnerCommitsWhileStopped() = fixture { files ->
        val picker = showPage(files.source)
        val originalActivity = compose.activity
        val originalVm = requireNotNull(observedVm.get())
        compose.onNode(
            hasText(compose.activity.getString(R.string.workspace_detail_tab_files)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        ).performClick().assertIsSelected()
        compose.onNodeWithText("first.txt").performScrollTo().assertIsDisplayed()
        val original = originalVm.state.value.entries.single { it.path == "first.txt" }
        val locale = compose.activity.resources.configuration.locales[0]
        fun metadata(entry: me.rerere.workspace.WorkspaceFileEntry): String {
            val modified = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)
                .format(Instant.ofEpochMilli(entry.updatedAt).atZone(ZoneId.systemDefault()))
            return "${entry.sizeBytes.fileSizeToString()} · $modified"
        }
        val originalMetadata = metadata(original)
        compose.onNodeWithText(originalMetadata).assertIsDisplayed()

        // The same list owner stays in memory while its page is stopped, as when an editor or
        // external document app covers it. Only the application command commits the file change.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val commands = (files.context.applicationContext as WorkspaceDocumentsDependencies).workspaceCommands
        val body = "Owner committed while page stopped.\n".repeat(64)
        val committed = runBlocking { withTimeout(15_000) {
            commands.writeText(files.source, "first.txt", body)
        } }
        assertEquals(body.toByteArray(Charsets.UTF_8).size.toLong(), committed.sizeBytes)
        assertNotEquals(original.sizeBytes, committed.sizeBytes)
        assertEquals("A stopped page must still have its original read projection", original,
            originalVm.state.value.entries.single { it.path == "first.txt" })

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(15_000) {
            originalVm.state.value.let { state ->
                !state.loading && state.entries.singleOrNull { it.path == "first.txt" } == committed
            }
        }
        compose.runOnIdle {
            assertSame(originalActivity, compose.activity)
            assertSame(originalVm, observedVm.get())
        }
        compose.onNodeWithText("first.txt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(metadata(committed)).assertIsDisplayed()
        compose.onNodeWithText(originalMetadata).assertDoesNotExist()
        assertTrue("Resuming the list must not open a document picker", picker.launches.isEmpty())
        assertNull(originalVm.exportState.value)
        assertEquals(WorkspaceTextPreviewResult.Success(body), runBlocking { withTimeout(10_000) {
            files.queries.readTextForPreview(files.source, WorkspaceStorageArea.FILES, "first.txt")
        } })
    }

    @Test
    fun leavingFilesTabClearsSelectionAndReturningAllowsFreshExportSelection() = fixture { files ->
        val picker = showPage(files.source)
        selectFile("first.txt")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_export)).assertIsEnabled()
        compose.onNode(
            hasText(compose.activity.getString(R.string.workspace_detail_tab_basic)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        ).performClick().assertIsSelected()
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_export_selected, 1)).assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_export)).assertDoesNotExist()
        selectFile("first.txt")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_export)).assertIsEnabled()
        assertTrue("Changing tabs must not launch an export picker", picker.launches.isEmpty())
        compose.runOnIdle { assertNull(observedVm.get().exportState.value) }
    }

    @Test
    fun directoryChangeBeforePickerReplyKeepsTheCapturedSourceRequest() = fixture { files ->
        val commands = (files.context.applicationContext as WorkspaceDocumentsDependencies).workspaceCommands
        runBlocking { withTimeout(15_000) {
            commands.writeText(files.source, "nested/first.txt", "new directory selection")
        } }
        val picker = showPage(files.source)
        selectFile("first.txt")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_export)).performClick()
        val pending = picker.launches.single()
        val originalVm = observedVm.get()

        // Delay the external result while the same page changes its current directory/selection.
        compose.onNodeWithText("first.txt").performClick()
        compose.onNodeWithText("nested").performScrollTo().performClick()
        compose.waitUntil(15_000) { observedVm.get().state.value.let { !it.loading && it.path == "nested" } }
        selectFile("first.txt", expectFilesTabSelected = true)
        compose.runOnIdle {
            assertSame(originalVm, observedVm.get())
            assertTrue(picker.dispatchResult(pending.first, Activity.RESULT_OK, Intent().setData(files.targetTree)))
        }
        compose.waitUntil(30_000) { observedVm.get().exportState.value?.running == false }
        val result = requireNotNull(observedVm.get().exportState.value)
        assertEquals(WorkspaceExportRequest(files.source, WorkspaceStorageArea.FILES, listOf("first.txt")), result.request)
        assertEquals("first.txt", (result.items.single() as WorkspaceExportItem.Exported).path)
        assertEquals("nested", observedVm.get().state.value.path)
        assertEquals(setOf("unrelated.txt", "first.txt"), files.targetNames())
        assertArrayEquals("old picker selection".toByteArray(), files.readTarget("first.txt"))
        assertArrayEquals("untouched".toByteArray(), files.readTarget("unrelated.txt"))
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_export_result, 1, 0)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.common_confirm)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_export_selected, 1)).assertDoesNotExist()
        assertEquals(1, picker.launches.size)
    }

    @Test
    fun recreatedPageRejectsLatePickerResultWithoutExportingItsNewSelection() = fixture { files ->
        val firstPicker = showPage(files.source)
        selectFile("first.txt")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_export)).performClick()
        val oldRequest = firstPicker.launches.single()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, oldRequest.second.action)
        val originalActivity = compose.activity
        val originalVm = observedVm.get()

        compose.activityRule.scenario.recreate()
        assertEquals(Lifecycle.State.DESTROYED, originalActivity.lifecycle.currentState)
        assertNotSame(originalActivity, compose.activity)
        val restoredPicker = showPage(files.source)
        selectFile("second.txt", expectFilesTabSelected = true)
        compose.runOnIdle { assertSame(originalVm, observedVm.get()) }

        // The actual saved registry retains the launch code, but the composition's request was
        // deliberately not saved. A late result must not borrow the newly selected file.
        compose.runOnIdle {
            assertTrue(restoredPicker.dispatchResult(oldRequest.first, Activity.RESULT_OK, Intent().setData(files.targetTree)))
        }
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_export_selected, 1)).assertIsDisplayed()
        compose.runOnIdle { assertNull(observedVm.get().exportState.value) }
        assertEquals(setOf("unrelated.txt"), files.targetNames())

        // A fresh launch proves that callbacks are still registered after recreation, rather
        // than making the old-result assertion pass because every result was disconnected.
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_export)).performClick()
        val freshRequest = restoredPicker.launches.single()
        assertEquals("The restored launcher must still own the original request code", oldRequest.first, freshRequest.first)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, freshRequest.second.action)
        compose.runOnIdle {
            assertTrue(restoredPicker.dispatchResult(freshRequest.first, Activity.RESULT_OK, Intent().setData(files.targetTree)))
        }
        compose.waitUntil(30_000) { observedVm.get().exportState.value?.running == false }
        val result = requireNotNull(observedVm.get().exportState.value)
        assertEquals(listOf("second.txt"), result.request.paths)
        assertEquals("second.txt", (result.items.single() as WorkspaceExportItem.Exported).path)
        assertEquals(setOf("unrelated.txt", "second.txt"), files.targetNames())
        assertArrayEquals(files.secondBytes, files.readTarget("second.txt"))
        assertArrayEquals("untouched".toByteArray(), files.readTarget("unrelated.txt"))
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_export_result, 1, 0)).assertIsDisplayed()
    }

    @Test
    fun recreatedPageObservesTheSameInFlightVmExportAndItsSinglePublishedResult() = fixture { files ->
        showPage(files.source)
        val originalVm = observedVm.get()
        val request = WorkspaceExportRequest(files.source, WorkspaceStorageArea.FILES, listOf("second.txt"))
        val delegate = WorkspaceDocumentTreeDestination(files.context.contentResolver, files.targetTree)
        val written = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val created = AtomicInteger()
        val closed = AtomicInteger()
        val destination = object : WorkspaceExportDestination {
            override fun create(name: String): Uri = delegate.create(name).also { created.incrementAndGet() }
            override fun displayName(uri: Uri): String = delegate.displayName(uri)
            override fun open(uri: Uri): OutputStream = object : FilterOutputStream(delegate.open(uri)) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    out.write(bytes, offset, length)
                    written.countDown()
                    releaseWrite.await()
                }
                override fun close() { closed.incrementAndGet(); super.close() }
            }
            override fun delete(uri: Uri) = delegate.delete(uri)
        }
        try {
            // This case starts at the real VM command to control the IO window. Picker dispatch
            // is covered above; production owner/Repository/Manager still perform the copy.
            compose.runOnIdle { originalVm.exportFiles(request, destination) }
            compose.waitUntil(30_000) { written.count == 0L }
            val originalActivity = compose.activity
            compose.activityRule.scenario.recreate()
            assertEquals(Lifecycle.State.DESTROYED, originalActivity.lifecycle.currentState)
            assertNotSame(originalActivity, compose.activity)
            showPage(files.source)
            compose.runOnIdle {
                assertSame(originalVm, observedVm.get())
                val exporting = requireNotNull(observedVm.get().exportState.value)
                assertSame(request, exporting.request)
                assertTrue(exporting.running)
                assertTrue(exporting.items.isEmpty())
            }
            compose.onNodeWithText(compose.activity.getString(R.string.workspace_export_progress, 0, 1)).assertIsDisplayed()
            assertEquals(1, created.get())
            assertEquals(0, closed.get())

            releaseWrite.countDown()
            compose.waitUntil(30_000) { observedVm.get().exportState.value?.running == false }
            val complete = requireNotNull(observedVm.get().exportState.value)
            assertSame(request, complete.request)
            assertFalse(complete.cancelled)
            assertNull(complete.cleanupDiagnostic)
            val exported = complete.items.single() as WorkspaceExportItem.Exported
            assertEquals("second.txt", exported.path)
            assertEquals(1, created.get())
            assertEquals(1, closed.get())
            assertArrayEquals(files.secondBytes, files.readTarget(exported.name))
            assertEquals(setOf("unrelated.txt", exported.name), files.targetNames())
            assertArrayEquals("untouched".toByteArray(), files.readTarget("unrelated.txt"))
            compose.onNodeWithText(compose.activity.getString(R.string.workspace_export_result, 1, 0)).assertIsDisplayed()
        } finally {
            releaseWrite.countDown()
            compose.runOnIdle { originalVm.cancelExport() }
            compose.waitUntil(15_000) { originalVm.exportState.value?.running != true }
        }
    }

    @Test
    fun closingActivityCancelsItsVmExportAndCleansOnlyTheIncompleteDocument() = fixture { files ->
        val commands = (files.context.applicationContext as WorkspaceDocumentsDependencies).workspaceCommands
        runBlocking { withTimeout(15_000) { commands.writeText(files.source, "third.txt", "must not export") } }
        showPage(files.source)
        val vm = observedVm.get()
        val activity = compose.activity
        val request = WorkspaceExportRequest(files.source, WorkspaceStorageArea.FILES,
            listOf("first.txt", "second.txt", "third.txt"))
        val delegate = WorkspaceDocumentTreeDestination(files.context.contentResolver, files.targetTree)
        val written = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val created = ConcurrentHashMap<String, Uri>()
        val closed = ConcurrentHashMap<Uri, AtomicInteger>()
        val deleted = CopyOnWriteArrayList<Uri>()
        val partialBytes = AtomicInteger()
        val destination = object : WorkspaceExportDestination {
            override fun create(name: String): Uri = delegate.create(name).also { created[name] = it }
            override fun displayName(uri: Uri): String = delegate.displayName(uri)
            override fun open(uri: Uri): OutputStream = object : FilterOutputStream(delegate.open(uri)) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    out.write(bytes, offset, length)
                    if (uri == created["second.txt"]) {
                        partialBytes.addAndGet(length)
                        written.countDown()
                        try { releaseWrite.await() }
                        catch (error: InterruptedException) { interrupted.countDown(); throw error }
                    }
                }
                override fun close() {
                    closed.computeIfAbsent(uri) { AtomicInteger() }.incrementAndGet()
                    super.close()
                }
            }
            override fun delete(uri: Uri) { deleted += uri; delegate.delete(uri) }
        }
        var primaryFailure: Throwable? = null
        try {
            compose.runOnIdle { vm.exportFiles(request, destination) }
            compose.waitUntil(30_000) { written.count == 0L }
            assertTrue(partialBytes.get() > 0)
            assertTrue(partialBytes.get() < files.secondBytes.size)
            assertEquals(1, closed.getValue(created.getValue("first.txt")).get())
            assertFalse(closed.containsKey(created.getValue("second.txt")))

            // No cancel command or latch release causes this interruption: destroying the
            // real Activity clears its production VM and cancels the owned export job.
            compose.activityRule.scenario.close()
            assertEquals(Lifecycle.State.DESTROYED, activity.lifecycle.currentState)
            val cancelled = runBlocking { withTimeout(30_000) {
                requireNotNull(vm.exportState.first { it != null && !it.running })
            } }
            assertEquals(1L, releaseWrite.count)
            assertEquals(0L, interrupted.count)
            assertSame(request, cancelled.request)
            assertTrue(cancelled.cancelled)
            assertNull(cancelled.cleanupDiagnostic)
            assertEquals("first.txt", (cancelled.items.single() as WorkspaceExportItem.Exported).path)
            assertEquals(setOf("first.txt", "second.txt"), created.keys)
            assertEquals(listOf(created.getValue("second.txt")), deleted.toList())
            assertEquals(created.values.toSet(), closed.keys)
            assertTrue(closed.values.all { it.get() == 1 })
            assertEquals(setOf("unrelated.txt", "first.txt"), files.targetNames())
            assertArrayEquals("old picker selection".toByteArray(), files.readTarget("first.txt"))
            assertArrayEquals("untouched".toByteArray(), files.readTarget("unrelated.txt"))
            runBlocking { withTimeout(15_000) {
                assertEquals(setOf("first.txt", "second.txt", "third.txt"),
                    files.queries.listFiles(files.source, WorkspaceStorageArea.FILES, "").map { it.name }.toSet())
                commands.writeText(files.source, "after-close.txt", "source gate released")
                commands.writeText(files.target, "after-close.txt", "target gate released")
            } }
            assertArrayEquals("target gate released".toByteArray(), files.readTarget("after-close.txt"))
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            // Failure cleanup only; all lifecycle-cancellation assertions precede release.
            releaseWrite.countDown()
            vm.cancelExport()
            try {
                runBlocking { withTimeout(15_000) { vm.exportState.first { it?.running != true } } }
            } catch (error: Throwable) { primaryFailure?.addSuppressed(error) ?: throw error }
        }
    }

    @Test
    fun revokedTargetErrorIsVisibleAndPageReselectionRetriesAfterRegrantWithoutOverwrite() = fixture { files ->
        val commands = (files.context.applicationContext as WorkspaceDocumentsDependencies).workspaceCommands
        val targetPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        val run = UUID.randomUUID().toString()
        val tree = DocumentsContract.buildTreeDocumentUri(ExportTargetDocumentsProvider.AUTHORITY, run)
        val originalId = "$run/first.txt"
        val originalBytes = "unrelated target document $run".toByteArray()
        val sourceBodies = linkedMapOf("first.txt" to "old picker selection", "retry.txt" to "retry bytes $run 中文")
        fun control(command: String, documentId: String? = null) =
            controlExportTarget(files.context, targetPackage, run, command, originalBytes, documentId)
        fun targetIds() = requireNotNull(control("inspect").getStringArrayList("ids")).toSet()
        fun readTarget(id: String) = requireNotNull(control("inspect", id).getByteArray("bytes"))
        var vm: WorkspaceDetailVM? = null
        var primaryFailure: Throwable? = null
        try {
            control("setup")
            control("grant")
            runBlocking { withTimeout(15_000) { commands.writeText(files.source, "retry.txt", sourceBodies.getValue("retry.txt")) } }
            val picker = showPage(files.source)
            val pageVm = requireNotNull(observedVm.get()).also { vm = it }
            val request = WorkspaceExportRequest(files.source, WorkspaceStorageArea.FILES, sourceBodies.keys.toList())
            val delegate = WorkspaceDocumentTreeDestination(files.context.contentResolver, tree)
            val created = linkedMapOf<String, Uri>()
            val destination = object : WorkspaceExportDestination {
                override fun create(name: String): Uri = delegate.create(name).also { created[name] = it }
                override fun displayName(uri: Uri): String = delegate.displayName(uri)
                override fun open(uri: Uri): OutputStream {
                    if (uri == created["retry.txt"]) control("revoke")
                    return delegate.open(uri)
                }
                override fun delete(uri: Uri) = delegate.delete(uri)
            }
            // The first attempt enters through the real VM to control only the revocation window.
            // Recovery below uses real page selection and its unwrapped production destination.
            compose.runOnIdle { pageVm.exportFiles(request, destination) }
            compose.waitUntil(30_000) { pageVm.exportState.value?.running == false }
            val failed = requireNotNull(pageVm.exportState.value)
            assertSame(request, failed.request)
            assertFalse(failed.cancelled)
            assertNull(failed.cleanupDiagnostic)
            assertEquals(request.paths, failed.items.map { it.path })
            val completed = failed.items[0] as WorkspaceExportItem.Exported
            assertNotEquals("first.txt", completed.name)
            val error = (failed.items[1] as WorkspaceExportItem.Failed).cause
            val openRejection = generateSequence<Throwable>(error) { it.cause }.first { it is SecurityException }
            val cleanupRejection = generateSequence(error.suppressed.single()) { it.cause }.first { it is SecurityException }
            assertFalse(openRejection.message.isNullOrBlank())
            assertFalse(cleanupRejection.message.isNullOrBlank())
            val beforeRetry = setOf(originalId, DocumentsContract.getDocumentId(completed.uri),
                DocumentsContract.getDocumentId(created.getValue("retry.txt")))
            assertEquals(beforeRetry, targetIds())
            assertArrayEquals(originalBytes, readTarget(originalId))
            assertArrayEquals(sourceBodies.getValue("first.txt").toByteArray(), readTarget(DocumentsContract.getDocumentId(completed.uri)))
            assertArrayEquals(byteArrayOf(), readTarget(DocumentsContract.getDocumentId(created.getValue("retry.txt"))))

            compose.onNodeWithText(compose.activity.getString(R.string.workspace_export_result, 1, 1)).assertIsDisplayed()
            val diagnostic = compose.onNode(hasText("SecurityException", substring = true))
            diagnostic.performScrollTo().assertIsDisplayed()
            diagnostic.assertTextContains("retry.txt:", substring = true)
                .assertTextContains(requireNotNull(openRejection.message), substring = true)
                .assertTextContains("Suppressed:", substring = true)
                .assertTextContains(requireNotNull(cleanupRejection.message), substring = true)
            compose.onNodeWithText(compose.activity.getString(R.string.common_confirm)).performClick()
            compose.runOnIdle { assertNull(pageVm.exportState.value) }

            selectFile("first.txt")
            compose.onNodeWithText("retry.txt").performScrollTo().performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.workspace_export_selected, 2)).assertIsDisplayed()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_export)).performClick()
            val launched = picker.launches.single()
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, launched.second.action)
            control("grant")
            compose.runOnIdle {
                assertTrue(picker.dispatchResult(launched.first, Activity.RESULT_OK, Intent().setData(tree)))
            }
            compose.waitUntil(30_000) { pageVm.exportState.value?.running == false }
            val retried = requireNotNull(pageVm.exportState.value)
            assertEquals(request, retried.request)
            assertNotSame(request, retried.request)
            assertFalse(retried.cancelled)
            assertNull(retried.cleanupDiagnostic)
            assertEquals(request.paths, retried.items.map { it.path })
            assertTrue(retried.items.all { it is WorkspaceExportItem.Exported })
            val exported = retried.items.filterIsInstance<WorkspaceExportItem.Exported>()
            val newIds = exported.map { DocumentsContract.getDocumentId(it.uri) }.toSet()
            assertEquals(2, newIds.size)
            assertTrue(newIds.intersect(beforeRetry).isEmpty())
            assertEquals(beforeRetry + newIds, targetIds())
            exported.forEach { item ->
                assertArrayEquals(sourceBodies.getValue(item.path).toByteArray(), readTarget(DocumentsContract.getDocumentId(item.uri)))
            }
            assertArrayEquals(originalBytes, readTarget(originalId))
            assertArrayEquals(sourceBodies.getValue("first.txt").toByteArray(), readTarget(DocumentsContract.getDocumentId(completed.uri)))
            assertArrayEquals(byteArrayOf(), readTarget(DocumentsContract.getDocumentId(created.getValue("retry.txt"))))
            compose.onNodeWithText(compose.activity.getString(R.string.workspace_export_result, 2, 0)).assertIsDisplayed()
            compose.onNode(hasText("SecurityException", substring = true)).assertDoesNotExist()
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                compose.activityRule.scenario.close()
                runBlocking { withTimeout(15_000) { vm?.exportState?.first { it?.running != true } } }
            } catch (error: Throwable) { cleanupFailure = error }
            try { control("cleanup") }
            catch (error: Throwable) { cleanupFailure?.addSuppressed(error) ?: run { cleanupFailure = error } }
            cleanupFailure?.let { error -> primaryFailure?.addSuppressed(error) ?: throw error }
        }
    }

    private fun showPage(source: String): DelayedPickerRegistry {
        lateinit var registry: DelayedPickerRegistry
        observedVm.set(null)
        compose.activityRule.scenario.onActivity { activity ->
            registry = DelayedPickerRegistry(activity)
            activity.savedStateRegistry.consumeRestoredStateForKey(PICKER_STATE)?.let(registry::onRestoreInstanceState)
            activity.savedStateRegistry.registerSavedStateProvider(PICKER_STATE) {
                Bundle().also(registry::onSaveInstanceState)
            }
            val pickerOwner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
            activity.setContent {
                MaterialTheme {
                    CompositionLocalProvider(
                        LocalActivityResultRegistryOwner provides pickerOwner,
                        LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo(),
                        LocalSettings provides Settings.dummy(),
                        LocalDarkMode provides false,
                        LocalNavController provides Navigator(remember { mutableStateListOf<NavKey>() }),
                        LocalToaster provides rememberToasterState(),
                    ) {
                        val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(source) })
                        SideEffect { observedVm.set(vm) }
                        WorkspaceDetailPage(source)
                    }
                }
            }
        }
        compose.waitUntil(15_000) { observedVm.get()?.state?.value?.let { !it.loading && it.workspace != null } == true }
        compose.waitForIdle()
        return registry
    }

    private fun selectFile(name: String, expectFilesTabSelected: Boolean = false) {
        val filesTab = compose.onNode(
            hasText(compose.activity.getString(R.string.workspace_detail_tab_files)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        )
        if (expectFilesTabSelected) filesTab.assertIsSelected()
        filesTab.performClick().assertIsSelected()
        compose.onNodeWithText(name).performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_export_selected, 1)).assertIsDisplayed()
    }

    private fun fixture(block: (Files) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dependencies = context.applicationContext as WorkspaceDocumentsDependencies
        val commands = dependencies.workspaceCommands
        val queries = dependencies.workspaceQueries
        val createdIds = mutableListOf<String>()
        var primaryFailure: Throwable? = null
        try {
            val files = runBlocking { withTimeout(30_000) {
                GlobalContext.get().get<ApplicationRecoveryGate>().awaitReady()
                val suffix = UUID.randomUUID().toString()
                val source = commands.createWorkspace("Export lifecycle source $suffix").workspaceId.also(createdIds::add)
                val targetName = "Export lifecycle target $suffix"
                val target = commands.createWorkspace(targetName).workspaceId.also(createdIds::add)
                val second = ByteArray(192 * 1024) { (it * 17).toByte() }
                commands.writeText(source, "first.txt", "old picker selection")
                second.inputStream().use { commands.importFile(source, WorkspaceStorageArea.FILES, "", "second.txt", it) }
                commands.writeText(target, "unrelated.txt", "untouched")
                val root = queries.documentRoots().single { it.workspaceName == targetName }.root
                Files(context, source, target, root, second, queries)
            } }
            block(files)
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            try { compose.activityRule.scenario.close() }
            catch (error: Throwable) { cleanupFailure = error }
            runBlocking { withContext(NonCancellable) {
                for (id in createdIds.asReversed()) {
                    try {
                        withTimeout(15_000) {
                            assertTrue("Test-created workspace must be deleted: $id", commands.deleteWorkspace(id))
                            assertNull(queries.getWorkspace(id))
                        }
                    } catch (error: Throwable) {
                        cleanupFailure?.addSuppressed(error) ?: run { cleanupFailure = error }
                    }
                }
                cleanupFailure?.let { error -> primaryFailure?.addSuppressed(error) ?: throw error }
            } }
        }
    }

    private class Files(
        val context: Context,
        val source: String,
        val target: String,
        val targetRoot: String,
        val secondBytes: ByteArray,
        val queries: WorkspaceQueryService,
    ) {
        val targetTree: Uri = DocumentsContract.buildTreeDocumentUri("${context.packageName}.documents", "ws/$targetRoot")
        fun targetNames(): Set<String> = runBlocking { withTimeout(10_000) {
            queries.listFiles(target, WorkspaceStorageArea.FILES, "").map { it.name }.toSet()
        } }
        fun readTarget(name: String): ByteArray = runBlocking { withTimeout(10_000) {
            runInterruptible(Dispatchers.IO) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(targetTree, "ws/$targetRoot/$name")
                requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
            }
        } }
    }

    private class DelayedPickerRegistry(private val context: Context) : ActivityResultRegistry() {
        val launches = mutableListOf<Pair<Int, Intent>>()
        override fun <I, O> onLaunch(
            requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
        ) {
            launches += requestCode to contract.createIntent(context, input)
        }
    }

    private companion object {
        const val PICKER_STATE = "workspace-export-picker-registry"
    }
}
