package net.weero.measix.pilot.ui.pages.remoteworkspace

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.Settings
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
import org.koin.compose.KoinIsolatedContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

/** Uses the production entry and native Activity restoration, rather than supplying a retained VM. */
@RunWith(AndroidJUnit4::class)
class RemoteWorkspaceNavigationRecoveryAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixture: Fixture? = null
    private val file = RemoteFile("notes.txt", false, 8, null, "\"v1\"")
    private val document = RemoteTextDocument(file, WorkspaceText("original", false, "\n"))

    @After fun dispose() {
        compose.runOnUiThread { compose.activity.viewModelStore.clear() }
        fixture?.isolated?.close()
    }

    private inner class Fixture {
        private val context = compose.activity.applicationContext
        private val deployment = "dep_550e8400-e29b-41d4-a716-446655440000"
        val selection = RealmSelection(RealmAccess.Enterprise(
            ConfigurationScope.Enterprise(EnterpriseAuthority(deployment), "user"), "original-session"), 1)
        val opening = Screen.RemoteWorkspace(selection = selection)
        private val connection = PlatformConnection("https://workspace.example", PlatformDiscovery(
            PlatformDiscoveryProduct.MEASIX_AGENT_PLATFORM, "1", deployment, "Workspace test enterprise",
            "/api/client/v1", "/api/runtime/v1", listOf(4, 5)))
        val handle = RemoteWorkspaceHandle(Uuid.random(), selection, connection,
            "spc_550e8400-e29b-41d4-a716-446655440000", 1)
        val service = mockk<RemoteWorkspaceService>(relaxed = true)
        val valid = AtomicBoolean(true)
        val refreshes = AtomicInteger()
        val summary = MutableStateFlow<RemoteWorkspaceSummary?>(RemoteWorkspaceSummary(selection, RemoteWorkspaceStatus.AVAILABLE))
        val instances = CopyOnWriteArrayList<RemoteWorkspaceVM>()
        val observedEntry = AtomicReference<Screen.RemoteWorkspace?>()
        val registry = DelayedPickerRegistry()
        private val pickerOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        val isolated = koinApplication { modules(module {
            viewModel { parameters ->
                RemoteWorkspaceVM(parameters.getOrNull(), service, context).also { instances += it }
            }
        }) }
        private lateinit var initial: Screen.RemoteWorkspace
        val vm: RemoteWorkspaceVM get() = instances.last()

        init {
            every { service.summary } returns summary
            coEvery { service.open(selection) } returns handle
            coEvery { service.refresh(selection) } coAnswers { refreshes.incrementAndGet(); Unit }
            coEvery { service.isValid(handle) } answers { valid.get() }
            coEvery { service.list(handle, any()) } answers { RemoteDirectory(secondArg(), listOf(file), null, null) }
            coEvery { service.readText(handle, file) } returns document
            every { service.acceptExport(handle, file, any(), any()) } returns CompletableDeferred(Unit)
        }

        @Composable fun Host() {
            KoinIsolatedContext(isolated) {
                MaterialTheme {
                    val backStack = rememberNavBackStack(Screen.Enterprise, initial)
                    SideEffect { observedEntry.set(backStack.lastOrNull() as? Screen.RemoteWorkspace) }
                    CompositionLocalProvider(
                        LocalNavController provides Navigator(backStack),
                        LocalSettings provides Settings(),
                        LocalActivityResultRegistryOwner provides pickerOwner,
                    ) {
                        NavDisplay(
                            backStack = backStack,
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator(),
                            ),
                            entryProvider = entryProvider<NavKey> {
                                entry<Screen.Enterprise> { Text("Spaces") }
                                remoteWorkspaceEntry()
                            },
                        )
                    }
                }
            }
        }

        fun show(entry: Screen.RemoteWorkspace = opening) {
            fixture = this
            initial = entry
            installContent()
            compose.waitUntil(5_000) { instances.isNotEmpty() }
            if (entry.selection != null) compose.waitUntil(5_000) { vm.state.value.directory != null }
        }

        private fun installContent() {
            compose.activityRule.scenario.onActivity { activity -> activity.setContent { Host() } }
        }

        fun recreate() {
            val previousActivity = compose.activity
            observedEntry.set(null)
            compose.activityRule.scenario.recreate()
            installContent()
            compose.waitUntil(5_000) { observedEntry.get() != null }
            compose.waitForIdle()
            assertNotSame(previousActivity, compose.activity)
            assertEquals(opening.id, observedEntry.get()!!.id)
            assertNull(observedEntry.get()!!.selection)
        }

        fun edit(text: String) {
            compose.onNodeWithText(file.name).performClick()
            compose.onNodeWithText(label(R.string.edit)).performClick()
            onView(isAssignableFrom(EditText::class.java)).perform(replaceText(text), closeSoftKeyboard())
        }

        fun launchDownload() {
            compose.onNode(hasContentDescription(label(R.string.more_options)) and hasAnyAncestor(hasText(file.name))).performClick()
            compose.onNodeWithText(label(R.string.remote_workspace_download)).performClick()
            compose.runOnIdle { assertEquals(Intent.ACTION_CREATE_DOCUMENT, registry.intent?.action) }
        }

        fun returnDownload() = compose.runOnIdle {
            registry.dispatchResult(requireNotNull(registry.requestCode), Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://documents/retained-picker")))
        }
    }

    @Test fun activityRecreationRetainsOriginalViewModelHandleAndUnsubmittedEditorDraft() {
        val f = Fixture()
        f.show()
        f.edit("draft never submitted")
        val original = f.vm
        val editor = original.state.value.editorSession
        assertNotNull(editor)
        f.recreate()
        compose.runOnIdle {
            assertSame(original, f.vm)
            assertSame(f.handle, f.vm.state.value.handle)
            assertSame(editor, f.vm.state.value.editorSession)
            assertEquals("draft never submitted", editor!!.editor.snapshot())
            assertTrue(editor.editing)
            assertFalse(f.vm.state.value.revoked)
        }
        onView(isAssignableFrom(EditText::class.java)).check(matches(withText("draft never submitted")))
        assertEquals(1, f.instances.size)
        coVerify(exactly = 1) { f.service.open(f.selection) }
        coVerify(exactly = 1) { f.service.readText(f.handle, file) }
        coVerify(exactly = 0) { f.service.save(any(), any(), any(), any()) }
    }

    @Test fun activityRecreationDeliversPendingSafResultToTheOriginalManagementHandle() {
        val f = Fixture()
        f.show()
        f.launchDownload()
        val original = f.vm
        val refreshes = f.refreshes.get()
        f.recreate()
        assertSame(original, f.vm)
        compose.waitUntil(5_000) { f.refreshes.get() > refreshes }
        f.returnDownload()
        compose.waitUntil(5_000) { f.vm.state.value.results.singleOrNull()?.outcome == RemoteOutcome.SUCCEEDED }
        verify(exactly = 1) { f.service.acceptExport(f.handle, file, any(), any()) }
        verify(exactly = 0) { f.service.discardExport(any(), any()) }
        coVerify(exactly = 1) { f.service.open(f.selection) }
        assertEquals(1, f.instances.size)
    }

    @Test fun activityRecreationKeepsAnUnknownSaveAndNeverReplaysItsSubmittedDraft() {
        val f = Fixture()
        val answer = CompletableDeferred<RemoteOperationResult>()
        coEvery { f.service.save(f.handle, document, "submitted draft", file.path) } coAnswers { answer.await() }
        f.show()
        f.edit("submitted draft")
        val original = f.vm
        compose.runOnIdle { original.save(f.handle, document, "submitted draft", file.path) }
        compose.waitUntil(5_000) { original.state.value.save != null }
        f.recreate()
        compose.runOnIdle {
            assertSame(original, f.vm)
            assertTrue(original.state.value.running)
            assertSame(f.handle, original.state.value.save!!.handle)
            answer.complete(RemoteOperationResult(file.path, RemoteOutcome.UNKNOWN, "write result unavailable"))
        }
        compose.waitUntil(5_000) { !original.state.value.running }
        onView(isAssignableFrom(EditText::class.java)).check(matches(withText("submitted draft")))
        compose.onNodeWithText(label(R.string.remote_workspace_save_close)).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(RemoteOutcome.UNKNOWN, original.state.value.save!!.result!!.outcome)
            assertEquals("submitted draft", original.state.value.save!!.text)
        }
        coVerify(exactly = 1) { f.service.save(f.handle, document, "submitted draft", file.path) }
        coVerify(exactly = 1) { f.service.open(f.selection) }
        coVerify(exactly = 1) { f.service.readText(f.handle, file) }
    }

    @Test fun serializedEntryWithoutARetainedOwnerRequiresExplicitReopening() {
        val f = Fixture()
        val restored = Json.decodeFromString(Screen.RemoteWorkspace.serializer(),
            Json.encodeToString(Screen.RemoteWorkspace.serializer(), f.opening))
        assertEquals(f.opening.id, restored.id)
        assertNull(restored.selection)
        f.show(restored)
        compose.onNodeWithText(label(R.string.remote_workspace_revoked)).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(f.vm.state.value.revoked)
            assertNull(f.vm.state.value.handle)
            assertNull(f.vm.state.value.editorSession)
            f.vm.retry()
        }
        coVerify(exactly = 0) { f.service.open(any()) }
        coVerify(exactly = 0) { f.service.refresh(any()) }
        coVerify(exactly = 0) { f.service.list(any(), any()) }
    }

    @Test fun replacementSessionAndActivityRecreationCannotReviveTheOldHandleOrSafRequest() {
        val f = Fixture()
        f.show()
        f.launchDownload()
        val original = f.vm
        val replacement = RealmSelection((f.selection.access as RealmAccess.Enterprise).copy(sessionId = "replacement-session"), 2)
        compose.runOnIdle {
            f.valid.set(false)
            f.summary.value = RemoteWorkspaceSummary(replacement, RemoteWorkspaceStatus.AVAILABLE)
        }
        compose.waitUntil(5_000) { original.state.value.revoked && original.state.value.handle == null }
        f.recreate()
        compose.runOnIdle {
            assertSame(original, f.vm)
            assertTrue(f.vm.state.value.revoked)
            assertNull(f.vm.state.value.handle)
            assertNull(f.vm.state.value.editorSession)
        }
        f.returnDownload()
        compose.waitForIdle()
        verify(exactly = 0) { f.service.acceptExport(any(), any(), any(), any()) }
        verify(exactly = 1) { f.service.discardExport(any(), any()) }
        coVerify(exactly = 1) { f.service.open(f.selection) }
        coVerify(exactly = 0) { f.service.open(replacement) }
        coVerify(atLeast = 1) { f.service.close(f.handle) }
    }

    private fun label(id: Int): String = compose.activity.getString(id)

    private class DelayedPickerRegistry : ActivityResultRegistry() {
        var requestCode: Int? = null
        var intent: Intent? = null
        override fun <I, O> onLaunch(
            requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
        ) {
            this.requestCode = requestCode
            // Creating the Intent validates the real contract without launching another Activity.
            intent = contract.createIntent(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext, input)
        }
    }
}
