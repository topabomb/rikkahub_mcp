package net.weero.measix.pilot.ui.pages.remoteworkspace

import android.widget.EditText
import android.content.ContentValues
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.remoteworkspace.*
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.context.Navigator
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** UI recovery paths use a real VM and native editor; HTTP contracts have separate tests. */
@RunWith(AndroidJUnit4::class)
class RemoteWorkspacePageAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    @After fun dispose() { compose.runOnUiThread { store.clear() } }

    private val file = RemoteFile("notes.txt", false, 8, null, "\"v1\"")
    private val document = RemoteTextDocument(file, WorkspaceText("original", false, "\n"))

    private inner class Fixture {
        private val deployment = "dep_550e8400-e29b-41d4-a716-446655440000"
        val selection = RealmSelection(RealmAccess.Enterprise(
            ConfigurationScope.Enterprise(EnterpriseAuthority(deployment), "user"), "workspace-test-session"), 1)
        private val connection = PlatformConnection("https://workspace.example", PlatformDiscovery(
            PlatformDiscoveryProduct.MEASIX_AGENT_PLATFORM, "1", deployment, "Workspace test enterprise",
            "/api/client/v1", "/api/runtime/v1", listOf(4, 5)))
        val handle = RemoteWorkspaceHandle(Uuid.random(), selection, connection,
            "spc_550e8400-e29b-41d4-a716-446655440000", 1)
        val service = mockk<RemoteWorkspaceService>(relaxed = true)
        val summary = MutableStateFlow<RemoteWorkspaceSummary?>(RemoteWorkspaceSummary(selection, RemoteWorkspaceStatus.AVAILABLE))
        lateinit var vm: RemoteWorkspaceVM
        init {
            every { service.summary } returns summary
            coEvery { service.open(selection) } returns handle
            coEvery { service.isValid(handle) } returns true
            coEvery { service.list(handle, any()) } answers { RemoteDirectory(secondArg(), listOf(file), null, null) }
            coEvery { service.readText(handle, file) } returns document
            compose.runOnUiThread {
                vm = RemoteWorkspaceVM(selection, service, compose.activity)
                store.put("remote", vm)
            }
        }
        fun show(restoration: StateRestorationTester? = null) {
            compose.runOnUiThread { vm.retry() }
            val content: @androidx.compose.runtime.Composable () -> Unit = {
                MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme())
                    androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
                    CompositionLocalProvider(LocalNavController provides Navigator(mutableListOf(Screen.Enterprise)),
                        LocalSettings provides Settings()) { RemoteWorkspacePage(selection, vm) }
                }
            }
            if (restoration == null) compose.setContent(content) else restoration.setContent(content)
            compose.waitUntil(5_000) { vm.state.value.handle === handle }
        }
    }

    @Test fun browseFilterCreateAndWorkspaceDetailsRemainAvailableWithoutMcp() {
        val f = Fixture()
        val folder = RemoteFile("reports", true, null, null, null)
        val hidden = RemoteFile(".hidden.txt", false, 10, null, "\"hidden\"")
        val nested = RemoteFile("reports/result.txt", false, 16, "2026-09-30T10:00:00Z", "\"report\"")
        val created = mutableListOf<RemoteFile>()
        val longName = RemoteFile("这是一个用于检查窄屏与大字体的很长的远程文件名称.pdf", false, null, null, "\"long\"")
        coEvery { f.service.list(f.handle, "") } answers { RemoteDirectory("", listOf(folder, file, hidden, longName) + created, 1024, 4096) }
        coEvery { f.service.list(f.handle, "reports") } returns RemoteDirectory("reports", listOf(nested), null, null)
        coEvery { f.service.createDirectory(f.handle, "new-folder") } answers {
            created += RemoteFile("new-folder", true, null, null, null)
            RemoteOperationResult("new-folder", RemoteOutcome.SUCCEEDED)
        }
        f.show()
        compose.onNodeWithText("reports").assertIsDisplayed()
        compose.onNodeWithText(".hidden.txt").assertDoesNotExist()
        capture("remote-workspace-files.png")
        compose.onNodeWithContentDescription(text(R.string.remote_workspace_filter)).performClick()
        compose.onNodeWithContentDescription(text(R.string.remote_workspace_filter)).performTextReplacement("notes")
        compose.onNodeWithText(file.name).assertIsDisplayed()
        compose.onNodeWithText("reports").assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.remote_workspace_filter)).performTextReplacement("")
        compose.onNodeWithContentDescription(text(R.string.remote_workspace_filter)).performImeAction()
        onView(isRoot()).perform(closeSoftKeyboard())
        compose.waitUntil(5_000) { compose.onNodeWithText("reports").isDisplayed() }
        compose.onNodeWithText("reports").performClick()
        compose.waitUntil(5_000) { compose.onNodeWithText("result.txt").isDisplayed() }
        compose.onNodeWithText("result.txt").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_workspace_root)).performClick()
        compose.onNodeWithText("＋").performClick()
        compose.onNodeWithText(text(R.string.remote_workspace_new_folder)).performClick()
        compose.onNodeWithText(text(R.string.remote_workspace_name)).performTextReplacement("new-folder")
        compose.onNodeWithText(text(R.string.common_confirm)).performClick()
        compose.waitUntil(5_000) { f.vm.state.value.directory?.files?.any { it.name == "new-folder" } == true }
        onView(isRoot()).perform(closeSoftKeyboard())
        compose.onNodeWithContentDescription(text(R.string.common_confirm)).performClick()
        compose.onNodeWithTag("remote-file-list").performScrollToNode(hasText("new-folder"))
        compose.onNodeWithText("new-folder").assertIsDisplayed()
        coVerify(exactly = 1) { f.service.createDirectory(f.handle, "new-folder") }
        compose.onNodeWithTag("remote-browser-menu").performClick()
        compose.onNodeWithText(text(R.string.remote_workspace_details)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_workspace_owner, "https://workspace.example")).assertIsDisplayed()
        capture("remote-workspace-details.png")
    }

    @Test fun previewStartsReadOnlyAndExplicitEditKeepsDraftAfterFailedSave() {
        val f = Fixture()
        coEvery { f.service.save(f.handle, document, "preserve my changes", file.path) } returns
            RemoteOperationResult(file.path, RemoteOutcome.FAILED, "HTTP 409 file_version_conflict")
        f.show()
        compose.onNodeWithText(file.name).performClick()
        onView(isAssignableFrom(EditText::class.java)).check { view, error ->
            if (error != null) throw error
            assertNull((view as EditText).keyListener)
            assertEquals("original", view.text.toString())
        }
        compose.onNodeWithText(text(R.string.remote_workspace_save_close)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.edit)).performClick()
        onView(isAssignableFrom(EditText::class.java)).perform(replaceText("preserve my changes"), closeSoftKeyboard())
        compose.onNodeWithText(text(R.string.remote_workspace_save_close)).performClick()
        compose.waitUntil(5_000) { f.vm.state.value.save?.result?.outcome == RemoteOutcome.FAILED }
        onView(isAssignableFrom(EditText::class.java)).check(matches(withText("preserve my changes")))
        capture("remote-workspace-editor-conflict.png")
        compose.onNodeWithContentDescription(text(R.string.more_options)).performClick()
        compose.onNodeWithText(text(R.string.remote_workspace_save_as)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_workspace_reread)).assertIsDisplayed()
        coVerify(exactly = 1) { f.service.save(f.handle, document, "preserve my changes", file.path) }
    }

    @Test fun failedTextReadShowsDiagnosticAndRetryWithoutClaimingUnsupportedFormat() {
        val f = Fixture()
        coEvery { f.service.readText(f.handle, file) } throws java.io.IOException("HTTP 503 preview read unavailable")
        f.show()
        compose.onNodeWithText(file.name).performClick()
        compose.onNodeWithText(text(R.string.enterprise_budget_refresh)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_workspace_preview_unsupported)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.remote_workspace_details)).performClick()
        compose.onNodeWithText("IOException: HTTP 503 preview read unavailable", substring = true).assertIsDisplayed()
        compose.runOnIdle { f.summary.value = RemoteWorkspaceSummary(f.selection, RemoteWorkspaceStatus.FAILED) }
        coEvery { f.service.refresh(f.selection) } coAnswers {
            f.summary.value = RemoteWorkspaceSummary(f.selection, RemoteWorkspaceStatus.AVAILABLE)
            coEvery { f.service.readText(f.handle, file) } returns document
        }
        compose.onNodeWithText(text(R.string.enterprise_budget_refresh)).performClick()
        compose.waitUntil(5_000) { f.vm.state.value.editorSession?.document != null }
        onView(isAssignableFrom(EditText::class.java)).check(matches(withText("original")))
        compose.onNodeWithText(text(R.string.remote_workspace_operation_failed)).assertDoesNotExist()
        coVerify(exactly = 2) { f.service.readText(f.handle, file) }
        coVerifyOrder { f.service.readText(f.handle, file); f.service.refresh(f.selection); f.service.readText(f.handle, file) }
    }

    @Test fun authorizationRevocationClearsOldFilesAndAnOpenMutationPrompt() {
        val f = Fixture()
        f.show()
        compose.onNodeWithText(file.name).assertIsDisplayed()
        compose.onNodeWithText("＋").performClick()
        compose.onNodeWithText(text(R.string.remote_workspace_new_folder)).performClick()
        compose.onNodeWithText(text(R.string.remote_workspace_name)).performTextReplacement("must-not-create")
        compose.runOnIdle {
            coEvery { f.service.isValid(f.handle) } returns false
            f.summary.value = null
        }
        compose.waitUntil(5_000) { f.vm.state.value.revoked }
        compose.onNodeWithText(file.name).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.remote_workspace_name)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.remote_workspace_revoked)).assertIsDisplayed()
        capture("remote-workspace-revoked.png")
        coVerify(exactly = 0) { f.service.createDirectory(any(), any()) }
    }

    @Test fun frozenSaveSurvivesCompositionRecreationAndConflictRetainsSubmittedText() {
        val f = Fixture()
        val answer = CompletableDeferred<RemoteOperationResult>()
        coEvery { f.service.save(f.handle, document, "submitted draft", file.path) } coAnswers { answer.await() }
        val restoration = StateRestorationTester(compose)
        f.show(restoration)
        compose.runOnIdle { f.vm.openPreview(file); f.vm.save(f.handle, document, "submitted draft", file.path) }
        compose.waitUntil(5_000) { f.vm.state.value.save != null }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertTrue(f.vm.state.value.running)
            assertFalse(answer.isCancelled)
            answer.complete(RemoteOperationResult(file.path, RemoteOutcome.FAILED, "HTTP 409 file_version_conflict"))
        }
        compose.waitUntil(5_000) { !f.vm.state.value.running }
        onView(isAssignableFrom(EditText::class.java)).check(matches(withText("submitted draft")))
        compose.onNodeWithContentDescription(text(R.string.more_options)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_workspace_save_as)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_workspace_reread)).assertIsDisplayed()
        coVerify(exactly = 1) { f.service.save(f.handle, document, "submitted draft", file.path) }
        coVerify(exactly = 0) { f.service.readText(any(), any()) }
    }

    @Test fun unsubmittedDraftAndEditingModeSurviveCompositionRecreationWithoutRereading() {
        val f = Fixture()
        val restoration = StateRestorationTester(compose)
        f.show(restoration)
        compose.onNodeWithText(file.name).performClick()
        compose.onNodeWithText(text(R.string.edit)).performClick()
        onView(isAssignableFrom(EditText::class.java)).perform(replaceText("draft never submitted"), closeSoftKeyboard())
        val owner = f.vm.state.value.editorSession
        assertNotNull(owner)
        assertNull(f.vm.state.value.save)
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertSame(owner, f.vm.state.value.editorSession)
            assertEquals("draft never submitted", owner!!.editor.snapshot())
            assertTrue(owner.editing)
            assertNull(f.vm.state.value.save)
        }
        onView(isAssignableFrom(EditText::class.java)).check { view, error ->
            if (error != null) throw error
            assertEquals("draft never submitted", (view as EditText).text.toString())
            assertNotNull(view.keyListener)
        }
        compose.onNodeWithText(text(R.string.remote_workspace_save_close)).assertIsDisplayed()
        coVerify(exactly = 1) { f.service.readText(f.handle, file) }
        coVerify(exactly = 0) { f.service.save(any(), any(), any(), any()) }
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithText(text(R.string.remote_workspace_unsaved)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.common_confirm)).performClick()
        compose.runOnIdle { assertNull(f.vm.state.value.editorSession) }
        compose.onNodeWithText(file.name).performClick()
        onView(isAssignableFrom(EditText::class.java)).check(matches(withText("original")))
        compose.runOnIdle { assertNotSame(owner, f.vm.state.value.editorSession) }
        coVerify(exactly = 2) { f.service.readText(f.handle, file) }
    }

    @Test fun authorizationRevocationDiscardsTheUnsubmittedNativeEditorBuffer() {
        val f = Fixture()
        f.show()
        compose.onNodeWithText(file.name).performClick()
        compose.onNodeWithText(text(R.string.edit)).performClick()
        onView(isAssignableFrom(EditText::class.java)).perform(replaceText("private unsaved draft"), closeSoftKeyboard())
        compose.runOnIdle {
            assertEquals("private unsaved draft", f.vm.state.value.editorSession!!.editor.snapshot())
            coEvery { f.service.isValid(f.handle) } returns false
            f.summary.value = null
        }
        compose.waitUntil(5_000) { f.vm.state.value.revoked }
        compose.runOnIdle {
            assertNull(f.vm.state.value.preview)
            assertNull(f.vm.state.value.editorSession)
            assertNull(f.vm.state.value.save)
        }
        compose.onNodeWithText(text(R.string.remote_workspace_revoked)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_workspace_save_close)).assertDoesNotExist()
        coVerify(exactly = 0) { f.service.save(any(), any(), any(), any()) }
    }

    @Test fun unknownRequiresFreshReadAndExplicitAcknowledgementWithoutReplayingWrite() {
        val f = Fixture()
        coEvery { f.service.verifyUnknown(f.handle, file.path) } returns file
        f.show()
        compose.runOnIdle {
            f.vm.execute(listOf(file.path), f.handle) { _, _ -> RemoteOperationResult(file.path, RemoteOutcome.UNKNOWN) }
        }
        compose.waitUntil(5_000) { !f.vm.state.value.running && f.vm.state.value.results.isNotEmpty() }
        compose.runOnIdle { f.vm.execute(listOf("another.txt"), f.handle) { _, _ -> RemoteOperationResult("another.txt", RemoteOutcome.SUCCEEDED) } }
        compose.waitUntil(5_000) { !f.vm.state.value.running && f.vm.state.value.results.any { it.path == "another.txt" } }
        compose.runOnIdle { f.vm.clearResults(); assertEquals(RemoteOutcome.UNKNOWN, f.vm.state.value.results.single().outcome) }
        compose.onNodeWithText(text(R.string.remote_workspace_details)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_workspace_read_verify)).performClick()
        compose.onNodeWithText("original").performScrollTo().assertIsDisplayed()
        coVerify(exactly = 0) { f.service.acknowledgeVerified(any(), any()) }
        compose.onNodeWithText(compose.activity.getString(R.string.remote_workspace_verify)).performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) { f.service.verifyUnknown(f.handle, file.path) }
        coVerify(exactly = 1) { f.service.acknowledgeVerified(f.handle, file.path) }
        compose.runOnIdle { assertTrue(f.vm.state.value.results.isEmpty()) }
        coVerify(exactly = 0) { f.service.upload(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun renameWaitsForOverwriteConfirmationAndUsesTheConfirmedVersion() {
        val f = Fixture()
        val target = RemoteFile("existing.txt", false, 16, null, "\"target-v2\"")
        coEvery { f.service.list(f.handle, "") } returns RemoteDirectory("", listOf(file, target), null, null)
        coEvery { f.service.mutate(f.handle, RemoteFileAction.MOVE, file, target.path, target, false) } returns
            RemoteOperationResult(target.path, RemoteOutcome.SUCCEEDED)
        f.show()
        compose.runOnIdle { f.vm.rename(f.handle, file, target.path) }
        compose.waitUntil(5_000) { f.vm.state.value.overwrite != null }
        compose.onNodeWithText(compose.activity.getString(R.string.remote_workspace_overwrite)).assertIsDisplayed()
        coVerify(exactly = 0) { f.service.mutate(any(), any(), any(), any(), any(), any()) }
        compose.onNodeWithText(compose.activity.getString(R.string.common_confirm)).performClick()
        compose.waitUntil(5_000) { !f.vm.state.value.running }
        coVerify(exactly = 1) { f.service.mutate(f.handle, RemoteFileAction.MOVE, file, target.path, target, false) }
    }

    @Test fun folderChoiceRejectsTheOriginalLocationAndFilteringDoesNotLeakIntoAnotherDirectory() {
        val f = Fixture()
        val folder = RemoteFile("reports", true, null, null, null)
        val nested = RemoteFile("reports/result.txt", false, 12, null, "\"result\"")
        coEvery { f.service.list(f.handle, "") } returns RemoteDirectory("", listOf(file, folder), null, null)
        coEvery { f.service.list(f.handle, "reports") } returns RemoteDirectory("reports", listOf(nested), null, null)
        f.show()
        compose.onNodeWithContentDescription(text(R.string.remote_workspace_filter)).performClick()
        compose.onNodeWithContentDescription(text(R.string.remote_workspace_filter)).performTextReplacement("notes")
        compose.onNodeWithContentDescription(text(R.string.remote_workspace_filter)).performImeAction()
        compose.onNodeWithText("reports").assertDoesNotExist()
        compose.runOnIdle { f.vm.browse("reports") }
        compose.waitUntil(5_000) { compose.onNodeWithText("result.txt").isDisplayed() }
        compose.onNodeWithText(text(R.string.remote_workspace_filter)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.waitUntil(5_000) { compose.onNodeWithText(file.name).isDisplayed() }
        compose.onNode(hasContentDescription(text(R.string.more_options)) and hasAnyAncestor(hasText(file.name))).performClick()
        compose.onNodeWithText(text(R.string.remote_workspace_copy)).performClick()
        compose.waitUntil(5_000) { compose.onNodeWithText(text(R.string.remote_workspace_select_folder)).isDisplayed() }
        compose.onNodeWithText(text(R.string.remote_workspace_select_folder)).assertIsNotEnabled()
        compose.onNode(hasText("reports") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { compose.onNodeWithText(text(R.string.remote_workspace_no_subfolders)).isDisplayed() }
        compose.onNodeWithText(text(R.string.remote_workspace_select_folder)).assertIsEnabled()
        coVerify(exactly = 0) { f.service.mutate(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun batchShowsTheActualActiveItemAndCancellationKeepsPendingItemsUnstarted() {
        val f = Fixture()
        val pending = CompletableDeferred<RemoteOperationResult>()
        every { f.service.outcomeAfterCancellation(f.handle, file.path) } returns RemoteOutcome.CANCELLED
        f.show()
        compose.runOnIdle { f.vm.execute(listOf(file.path, "second.txt"), f.handle) { _, _ -> pending.await() } }
        compose.waitUntil(5_000) { f.vm.state.value.activeIndex == 0 }
        compose.onNodeWithText(compose.activity.getString(R.string.remote_workspace_active_item, 1, 2, file.name)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        compose.waitUntil(5_000) { !f.vm.state.value.running }
        compose.runOnIdle {
            assertNull(f.vm.state.value.activeIndex)
            assertEquals(listOf(RemoteOutcome.CANCELLED, RemoteOutcome.NOT_STARTED), f.vm.state.value.results.map { it.outcome })
        }
        compose.onNodeWithText(text(R.string.remote_workspace_details)).performClick()
        compose.onNodeWithText(text(R.string.remote_workspace_not_started)).assertIsDisplayed()
    }

    @Test fun oversizedDestinationIsReportedWithoutStartingAMutationOrCrashing() {
        val f = Fixture()
        f.show()
        compose.runOnIdle {
            f.vm.mutate(RemoteFileAction.COPY, listOf(file), "x".repeat(4096), f.handle)
            assertFalse(f.vm.state.value.running)
            assertTrue(f.vm.state.value.error!!.contains("invalid_workspace_path"))
        }
        coVerify(exactly = 0) { f.service.mutate(any(), any(), any(), any(), any(), any()) }
    }

    private fun text(id: Int) = compose.activity.getString(id)

    /** Device-review artifacts are written through MediaStore, so adb can retrieve them without app-private access. */
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val resolver = compose.activity.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/CodexRemoteWorkspace")
        }))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        } finally { bitmap.recycle() }
    }
}
