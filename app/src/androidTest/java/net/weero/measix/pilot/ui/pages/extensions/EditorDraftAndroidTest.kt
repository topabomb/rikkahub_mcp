package net.weero.measix.pilot.ui.pages.extensions

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasFocus
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dokar.sonner.rememberToasterState
import io.mockk.coVerify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import net.weero.measix.pilot.data.provider.WorkspaceDocumentsDependencies
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import org.koin.core.context.GlobalContext
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import io.mockk.coEvery
import io.mockk.mockk
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.files.SkillFile
import net.weero.measix.pilot.service.workspace.*
import net.weero.measix.pilot.ui.context.*
import net.weero.measix.pilot.ui.pages.extensions.skills.*
import net.weero.measix.pilot.ui.pages.extensions.workspace.WorkspaceFileEditorPage
import net.weero.measix.pilot.ui.theme.LocalDarkMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorDraftAndroidTest {
    // Real repository IO must resume through queued composition work, not the legacy unconfined dispatcher.
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun workspaceDraftDoesNotEnterActivityBundle() {
        val published = "published\n".repeat(50_000)
        val queries = mockk<WorkspaceQueryService>()
        val commands = mockk<WorkspaceApplicationService>()
        val loaded = CompletableDeferred<WorkspaceTextPreviewResult>()
        var reads = 0
        coEvery { queries.readTextForPreview("id", WorkspaceStorageArea.FILES, "large.txt") } coAnswers {
            reads++
            loaded.await()
        }
        compose.setContent { host { WorkspaceFileEditorPage("id", WorkspaceStorageArea.FILES, "large.txt", commands, queries) } }
        compose.waitUntil(5_000) { reads == 1 }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_save)).assertDoesNotExist()
        loaded.complete(WorkspaceTextPreviewResult.Success(published))
        enterWorkspaceEditing { reads == 1 }
        replaceEditorBody(published + "unsaved")
        assertSmallActivityBundle()
    }

    @Test fun skillEditDraftStaysOutOfBundleAndConfirmationReceivesBody() {
        val published = largeBundleBody(paragraphCount = 2_200)
        var confirmed: String? = null
        compose.setContent { host {
            EditFileDialog(false, SkillFile("large.md", "large.md", published.length.toLong()), published, {}, { confirmed = it })
        } }
        replaceEditorBody(published + "unsaved", inDialog = true)
        compose.onNodeWithText(compose.activity.getString(R.string.skill_detail_page_save)).performClick()
        compose.runOnIdle { assertEquals(published + "unsaved", confirmed) }
        assertEditorBody(published + "unsaved")
        assertSmallActivityBundle()
    }

    @Test fun newSkillBodyStaysOutOfBundle() {
        compose.setContent { host { AddSkillDialog(false, {}, { _, _ -> }) } }
        val body = "---\nname: draft\ndescription: valid\n---\n" + largeBundleBody(paragraphCount = 2_000)
        replaceEditorBody(body, inDialog = true)
        assertEditorBody(body)
        assertSmallActivityBundle()
    }

    @Test fun newSkillFileBodyStaysOutOfBundle() {
        compose.setContent { host { AddFileDialog(false, {}, { _, _ -> }) } }
        val name = compose.onNode(hasSetTextAction())
        name.performTextReplacement("notes.md")
        name.performImeAction()
        val body = largeBundleBody(paragraphCount = 2_000)
        replaceEditorBody(body, inDialog = true)
        assertEditorBody(body)
        assertSmallActivityBundle()
    }

    @Test fun savedStateRegistryRestorationReloadsPublishedContentInsteadOfUnsavedDraft() {
        val queries = mockk<WorkspaceQueryService>()
        val commands = mockk<WorkspaceApplicationService>()
        var reads = 0
        coEvery { queries.readTextForPreview("id", WorkspaceStorageArea.FILES, "text.txt") } answers {
            reads++
            WorkspaceTextPreviewResult.Success("published")
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { host { WorkspaceFileEditorPage("id", WorkspaceStorageArea.FILES, "text.txt", commands, queries) } }
        enterWorkspaceEditing { reads == 1 }
        replaceEditorBody("unsaved")
        restoration.emulateSavedInstanceStateRestore()
        enterWorkspaceEditing { reads == 2 }
        assertEditorBody("published")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_save)).assertIsEnabled()
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun actualActivityRecreationReloadsPublishedOwnerFileWhileBackgroundKeepsUnsavedDraft() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dependencies = context.applicationContext as WorkspaceDocumentsDependencies
        val commands = dependencies.workspaceCommands
        val queries = dependencies.workspaceQueries
        val workspaceId = runBlocking {
            withTimeout(30_000) {
                GlobalContext.get().get<ApplicationRecoveryGate>().awaitReady()
                commands.createWorkspace("Editor recreation ${UUID.randomUUID()}").workspaceId
            }
        }
        val originalActivity = compose.activity
        val originalDestroyed = AtomicBoolean()
        val savedBundleSizes = ConcurrentLinkedQueue<Int>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostSaveInstanceState(activity: Activity, outState: Bundle) {
                if (activity !== originalActivity) return
                val parcel = Parcel.obtain()
                try {
                    parcel.writeBundle(outState)
                    savedBundleSizes.add(parcel.dataSize())
                } finally { parcel.recycle() }
            }
            override fun onActivityDestroyed(activity: Activity) {
                if (activity === originalActivity) originalDestroyed.set(true)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        }
        originalActivity.application.registerActivityLifecycleCallbacks(callbacks)
        var primaryFailure: Throwable? = null
        try {
            runBlocking { withTimeout(10_000) { commands.writeText(workspaceId, "recreate.txt", "published before editing") } }
            compose.setContent { host {
                WorkspaceFileEditorPage(workspaceId, WorkspaceStorageArea.FILES, "recreate.txt", commands, queries)
            } }
            enterWorkspaceEditing()
            assertEditorBody("published before editing")
            // More than one MiB as UTF-16, with a bounded paragraph count for the lifecycle fixture.
            val draft = largeBundleBody(paragraphCount = 400) + "unsaved"
            replaceEditorBody(draft)
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            assertSame(originalActivity, compose.activity)
            assertEditorBody(draft)
            runBlocking { withTimeout(10_000) {
                commands.writeText(workspaceId, "recreate.txt", "latest published owner content")
            } }
            savedBundleSizes.clear()
            compose.activityRule.scenario.recreate()
            assertTrue("Original Activity must be destroyed", originalDestroyed.get())
            assertNotSame(originalActivity, compose.activity)
            assertTrue("Actual lifecycle must save a Bundle", savedBundleSizes.isNotEmpty())
            savedBundleSizes.forEach { size ->
                assertTrue("Activity lifecycle saved editor body: $size", size < 128 * 1024)
            }
            // ComponentActivity has no application UI: install the same page in the new Activity's real registry.
            compose.activityRule.scenario.onActivity { recreated ->
                recreated.setContent { host {
                    WorkspaceFileEditorPage(workspaceId, WorkspaceStorageArea.FILES, "recreate.txt", commands, queries)
                } }
            }
            enterWorkspaceEditing()
            assertEditorBody("latest published owner content")
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_save)).assertIsEnabled()
        } catch (error: Throwable) {
            error.addSuppressed(AssertionError(visibleLabels() + "\n" + compose.onRoot(useUnmergedTree = true).printToString().take(12_000)))
            primaryFailure = error
            throw error
        } finally {
            originalActivity.application.unregisterActivityLifecycleCallbacks(callbacks)
            runBlocking {
                withContext(NonCancellable) {
                    try {
                        withTimeout(15_000) {
                            assertTrue("Test-created Workspace must be deleted", commands.deleteWorkspace(workspaceId))
                            assertNull(queries.getWorkspace(workspaceId))
                        }
                    } catch (cleanup: Throwable) {
                        primaryFailure?.addSuppressed(cleanup) ?: throw cleanup
                    }
                }
            }
        }
    }

    @Test fun realWorkspaceIoFailureKeepsDraftAndRetriesAfterOwnerRepairsParent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dependencies = context.applicationContext as WorkspaceDocumentsDependencies
        val commands = dependencies.workspaceCommands
        val queries = dependencies.workspaceQueries
        val workspaceName = "Editor IO recovery ${UUID.randomUUID()}"
        val workspaceId = runBlocking {
            withTimeout(30_000) {
                GlobalContext.get().get<ApplicationRecoveryGate>().awaitReady()
                commands.createWorkspace(workspaceName).workspaceId
            }
        }
        val path = "parent/edit.txt"
        val published = "published before the filesystem conflict"
        val draft = "unsaved draft must survive a real IO failure\n恢复后保存原草稿"
        var primaryFailure: Throwable? = null
        try {
            val root = runBlocking { withTimeout(10_000) {
                commands.writeText(workspaceId, path, published)
                queries.documentRoots().single { it.workspaceName == workspaceName }.root
            } }
            compose.setContent { host {
                WorkspaceFileEditorPage(workspaceId, WorkspaceStorageArea.FILES, path, commands, queries)
            } }
            enterWorkspaceEditing()
            assertEditorBody(published)
            replaceEditorBody(draft)
            val actualIoFailure = runBlocking { withTimeout(10_000) {
                commands.deleteDocument(root, "parent")
                commands.writeText(workspaceId, "parent", "test-owned blocker")
                // Record the real platform IO diagnostic without replacing the production owner.
                try {
                    commands.writeText(workspaceId, path, draft)
                    throw AssertionError("A regular-file parent must prevent opening its child for writing")
                } catch (error: IOException) {
                    error
                }
            } }
            val originalCause = requireNotNull(actualIoFailure.cause) { "Expected the platform IO cause" }
            val errorText = "${actualIoFailure.javaClass.simpleName}: ${actualIoFailure.message}"
            val causeText = "Caused by: ${originalCause.javaClass.simpleName}: ${originalCause.message}"
            val save = compose.activity.getString(R.string.common_save)
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(save).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(save).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(errorText, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(errorText, substring = true).assertIsDisplayed()
            compose.onNodeWithText(causeText, substring = true).assertIsDisplayed()
            assertEquals(WorkspaceTextPreviewResult.Success("test-owned blocker"), runBlocking {
                withTimeout(10_000) { queries.readTextForPreview(workspaceId, WorkspaceStorageArea.FILES, "parent") }
            })
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.update_card_close)).performClick()
            assertEditorBody(draft)
            compose.onNodeWithContentDescription(save).assertIsEnabled()
            runBlocking { withTimeout(10_000) {
                assertTrue(commands.deleteFile(workspaceId, WorkspaceStorageArea.FILES, "parent", recursive = false))
                commands.writeText(workspaceId, path, published)
                assertEquals(WorkspaceTextPreviewResult.Success(published), queries.readTextForPreview(workspaceId, WorkspaceStorageArea.FILES, path))
            } }
            // Repairing owner state must not replace the existing composition's unsaved text.
            assertEditorBody(draft)
            compose.onNodeWithContentDescription(save).performClick()
            compose.waitUntil(10_000) {
                runBlocking { withTimeout(5_000) {
                    queries.readTextForPreview(workspaceId, WorkspaceStorageArea.FILES, path) == WorkspaceTextPreviewResult.Success(draft)
                } }
            }
            compose.onNodeWithContentDescription(save).assertIsEnabled()
            assertEditorBody(draft)
            compose.onNodeWithText(errorText, substring = true).assertDoesNotExist()
        } catch (error: Throwable) {
            error.addSuppressed(AssertionError(visibleLabels() + "\n" + compose.onRoot(useUnmergedTree = true).printToString().take(12_000)))
            primaryFailure = error
            throw error
        } finally {
            runBlocking {
                withContext(NonCancellable) {
                    try {
                        withTimeout(15_000) {
                            assertTrue("Test-created Workspace must be deleted", commands.deleteWorkspace(workspaceId))
                            assertNull(queries.getWorkspace(workspaceId))
                        }
                    } catch (cleanup: Throwable) {
                        primaryFailure?.addSuppressed(cleanup) ?: throw cleanup
                    }
                }
            }
        }
    }

    @Test fun cancellingDiscardKeepsNativeDraftAndConfirmedSystemBackLeavesWithoutWriting() {
        val queries = mockk<WorkspaceQueryService>()
        val commands = mockk<WorkspaceApplicationService>()
        coEvery { queries.readTextForPreview("id", WorkspaceStorageArea.FILES, "text.txt") } returns
            WorkspaceTextPreviewResult.Success("published")
        val editorPage = Screen.WorkspaceFileEditor("id", WorkspaceStorageArea.FILES.name, "text.txt")
        val backStack = mutableStateListOf<NavKey>(Screen.WorkspaceDetail("id"), editorPage)
        compose.setContent { host(backStack) {
            if (backStack.last() == editorPage) WorkspaceFileEditorPage("id", WorkspaceStorageArea.FILES, "text.txt", commands, queries)
            else androidx.compose.material3.Text("Returned to files")
        } }
        enterWorkspaceEditing()
        replaceEditorBody("unsaved native draft")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_workspace_unsaved)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.common_cancel)).performClick()
        assertEditorBody("unsaved native draft")
        compose.runOnIdle { assertEquals(2, backStack.size) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(compose.activity.getString(R.string.remote_workspace_unsaved)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.common_confirm)).performClick()
        compose.onNodeWithText("Returned to files").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(Screen.WorkspaceDetail("id")), backStack.toList()) }
        coVerify(exactly = 0) { commands.writeText(any(), any(), any()) }
    }

    @Test fun saveBlocksBothBackRoutesAndSuccessfulCommitClearsDiscardRequirement() {
        val queries = mockk<WorkspaceQueryService>()
        val commands = mockk<WorkspaceApplicationService>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val committed = ConcurrentLinkedQueue<String>()
        coEvery { queries.readTextForPreview("id", WorkspaceStorageArea.FILES, "text.txt") } returns
            WorkspaceTextPreviewResult.Success("published")
        coEvery { commands.writeText("id", "text.txt", any()) } coAnswers {
            val body = thirdArg<String>()
            started.complete(Unit)
            release.await()
            committed.add(body)
            WorkspaceFileEntry("text.txt", "text.txt", false, body.length.toLong(), 1L)
        }
        val editorPage = Screen.WorkspaceFileEditor("id", WorkspaceStorageArea.FILES.name, "text.txt")
        val backStack = mutableStateListOf<NavKey>(Screen.WorkspaceDetail("id"), editorPage)
        compose.setContent { host(backStack) {
            if (backStack.last() == editorPage) WorkspaceFileEditorPage("id", WorkspaceStorageArea.FILES, "text.txt", commands, queries)
            else androidx.compose.material3.Text("Returned to files")
        } }
        enterWorkspaceEditing()
        replaceEditorBody("draft to commit")
        val save = compose.activity.getString(R.string.common_save)
        compose.onNodeWithContentDescription(save).performClick()
        compose.waitUntil(5_000) { started.isCompleted }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).assertIsNotEnabled()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(compose.activity.getString(R.string.remote_workspace_unsaved)).assertDoesNotExist()
        assertEditorBody("draft to commit")
        compose.runOnIdle { assertEquals(2, backStack.size) }
        release.complete(Unit)
        compose.waitUntil(5_000) { committed.size == 1 }
        compose.onNodeWithContentDescription(save).assertIsEnabled()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).performClick()
        compose.onNodeWithText("Returned to files").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_workspace_unsaved)).assertDoesNotExist()
        coVerify(exactly = 1) { commands.writeText("id", "text.txt", "draft to commit") }
    }

    @Test fun switchingPathCancelsInFlightSaveAndNewPathCanSave() {
        assertInFlightSaveCancelledOnTargetChange(disposeFirst = false)
    }

    @Test fun disposingEditorCancelsInFlightSaveAndReopenedPageCanSave() {
        assertInFlightSaveCancelledOnTargetChange(disposeFirst = true)
    }

    private fun assertInFlightSaveCancelledOnTargetChange(disposeFirst: Boolean) {
        val queries = mockk<WorkspaceQueryService>()
        val commands = mockk<WorkspaceApplicationService>()
        val path = mutableStateOf("old.txt")
        val visible = mutableStateOf(true)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val releaseOldWrite = CompletableDeferred<Unit>()
        val committed = ConcurrentLinkedQueue<Pair<String, String>>()
        coEvery { queries.readTextForPreview("id", WorkspaceStorageArea.FILES, any()) } answers {
            WorkspaceTextPreviewResult.Success("published ${thirdArg<String>()}")
        }
        coEvery { commands.writeText("id", any(), any()) } coAnswers {
            val target = secondArg<String>()
            val body = thirdArg<String>()
            if (target == "old.txt") {
                started.complete(Unit)
                try {
                    releaseOldWrite.await()
                } catch (error: CancellationException) {
                    cancelled.complete(Unit)
                    throw error
                }
            }
            committed.add(target to body)
            WorkspaceFileEntry(target, target, false, body.length.toLong(), 1L)
        }
        compose.setContent { host {
            if (visible.value) WorkspaceFileEditorPage("id", WorkspaceStorageArea.FILES, path.value, commands, queries)
        } }
        enterWorkspaceEditing()
        replaceEditorBody("old draft")
        val save = compose.activity.getString(R.string.common_save)
        compose.onNodeWithContentDescription(save).performClick()
        compose.waitUntil(5_000) { started.isCompleted }
        compose.onNodeWithContentDescription(save).assertIsNotEnabled()
        assertEditorBody("old draft")
        if (disposeFirst) {
            compose.runOnIdle { visible.value = false }
            compose.waitUntil(5_000) { cancelled.isCompleted }
            compose.onNodeWithContentDescription(save).assertDoesNotExist()
            compose.runOnIdle { path.value = "new.txt"; visible.value = true }
        } else {
            compose.runOnIdle { path.value = "new.txt" }
            compose.waitUntil(5_000) { cancelled.isCompleted }
        }
        // A late completion signal must not revive the cancelled old-target save.
        releaseOldWrite.complete(Unit)
        enterWorkspaceEditing()
        assertEditorBody("published new.txt")
        compose.onNodeWithContentDescription(save).assertIsEnabled()
        assertTrue(committed.isEmpty())
        replaceEditorBody("new draft")
        compose.onNodeWithContentDescription(save).performClick()
        compose.waitUntil(5_000) { committed.size == 1 }
        compose.onNodeWithContentDescription(save).assertIsEnabled()
        assertEquals(listOf("new.txt" to "new draft"), committed.toList())
        coVerify(exactly = 1) { commands.writeText("id", "old.txt", "old draft") }
        coVerify(exactly = 1) { commands.writeText("id", "new.txt", "new draft") }
        coVerify(exactly = 0) { commands.writeText("id", "new.txt", "old draft") }
    }

    private fun nativeEditorDiagnostic(): String {
        val states = mutableListOf<String>()
        fun collect(view: View) {
            if (view is EditText) states += "shown=${view.isShown}, length=${view.length()}, readOnly=${view.keyListener == null}"
            if (view is ViewGroup) repeat(view.childCount) { collect(view.getChildAt(it)) }
        }
        compose.activityRule.scenario.onActivity { collect(it.window.decorView) }
        return "autoAdvance=${compose.mainClock.autoAdvance}; nativeEditors=$states"
    }

    private fun visibleLabels(): String = nativeEditorDiagnostic() + "\n" + compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true,
    ).fetchSemanticsNodes().joinToString(prefix = "Visible labels: ") { node ->
        node.config[SemanticsProperties.Text].joinToString { it.text.take(100) }
    }

    private fun replaceEditorBody(text: String, inDialog: Boolean = false) {
        compose.waitForIdle()
        val editor = onView(isAssignableFrom(EditText::class.java)).let {
            if (inDialog) it.inRoot(isDialog()) else it
        }
        editor.perform(closeSoftKeyboard())
        compose.waitForIdle()
        editor.perform(click()).check(matches(hasFocus()))
        editor.perform(replaceText(text), closeSoftKeyboard())
    }

    private fun assertEditorBody(expected: String) {
        onView(isAssignableFrom(EditText::class.java)).check { view, missing ->
            if (missing != null) throw missing
            assertEquals(expected, (view as EditText).text.toString())
        }
    }

    private fun enterWorkspaceEditing(additionalCondition: () -> Boolean = { true }) {
        compose.waitUntil(15_000) {
            var editorPresent = false
            compose.activityRule.scenario.onActivity { activity ->
                editorPresent = containsVisibleEditor(activity.window.decorView)
            }
            editorPresent && additionalCondition() && compose.onAllNodesWithText(
                compose.activity.getString(R.string.edit),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        // Initial open, restoration and a new path all begin in preview. Enter editing through
        // the user action before exercising the original draft/save/cancellation contracts.
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_save)).assertDoesNotExist()
        onView(isAssignableFrom(EditText::class.java)).check { view, missing ->
            if (missing != null) throw missing
            assertNull("Workspace preview must initially be read-only", (view as EditText).keyListener)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.edit)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.common_save))
                .fetchSemanticsNodes().isNotEmpty()
        }
        onView(isAssignableFrom(EditText::class.java)).check { view, missing ->
            if (missing != null) throw missing
            assertNotNull("Edit action must enable the existing native body", (view as EditText).keyListener)
        }
    }

    private fun containsVisibleEditor(view: View): Boolean = when (view) {
        is EditText -> view.isShown
        is ViewGroup -> (0 until view.childCount).any { containsVisibleEditor(view.getChildAt(it)) }
        else -> false
    }

    // Exercise multi-megabyte saved state without making extreme paragraph count a layout benchmark.
    private fun largeBundleBody(paragraphCount: Int): String {
        val paragraph = "Saved state body ".repeat(100).take(1_499) + "\n"
        return paragraph.repeat(paragraphCount)
    }

    private fun assertSmallActivityBundle() {
        val state = Bundle()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(compose.activity, state)
        }
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(state)
            assertTrue("Saved activity state unexpectedly contains editor body: ${parcel.dataSize()}", parcel.dataSize() < 128 * 1024)
        } finally { parcel.recycle() }
    }

    @Composable private fun host(backStack: MutableList<NavKey>? = null, content: @Composable () -> Unit) {
        MaterialTheme { CompositionLocalProvider(
            LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo(),
            LocalSettings provides Settings.dummy(), LocalDarkMode provides false,
            LocalNavController provides Navigator(backStack ?: remember { mutableStateListOf<NavKey>() }),
            LocalToaster provides rememberToasterState(), content = content,
        ) }
    }
}
