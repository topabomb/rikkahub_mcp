package net.weero.measix.pilot.ui.pages.enterprise

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.EnterpriseExitRequest
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionPhase
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.enterprise.RealmSwitchRequest
import net.weero.measix.pilot.service.EnterpriseApplicationService
import net.weero.measix.pilot.service.EnterpriseOverview
import net.weero.measix.pilot.service.InstalledEnterpriseSource
import net.weero.measix.pilot.service.portal.PortalDocument
import net.weero.measix.pilot.service.portal.PortalFailure
import net.weero.measix.pilot.service.portal.PortalWebView
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.Navigator
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Exercises the native consumer and Activity lifecycle; protocol and real WebView tests have separate owners. */
@RunWith(AndroidJUnit4::class)
class EnterprisePageAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val viewModels = ViewModelStore()

    @After
    fun clearViewModels() {
        compose.runOnUiThread { viewModels.clear() }
    }

    @Test
    fun nativeExampleEnrollmentAndSwitchUseThePresentedSelectionAndReturnToFreshChat() {
        val enrolled = overview()
        val fixture = Fixture(overview(access = null))
        val switchStarted = CompletableDeferred<RealmSwitchRequest>()
        val releaseSwitch = CompletableDeferred<Unit>()
        val personal = RealmSelection(RealmAccess.Personal, 2L)
        coEvery { fixture.service.joinExample() } coAnswers { fixture.state.value = enrolled }
        coEvery { fixture.service.switchRealm(any()) } coAnswers {
            switchStarted.complete(firstArg())
            releaseSwitch.await()
            personal
        }
        try {
            fixture.show()
            click(R.string.enterprise_join_example)
            compose.waitUntil(5_000) { fixture.vm.overview.value == enrolled }
            coVerify(exactly = 1) { fixture.service.joinExample() }
            click(R.string.enterprise_switch_personal)
            compose.waitUntil(5_000) { switchStarted.isCompleted }
            coVerify(exactly = 1) {
                fixture.service.switchRealm(RealmSwitchRequest(requireNotNull(enrolled.selection), RealmAccess.Personal))
            }
            compose.runOnUiThread { assertEquals(listOf(Screen.Enterprise), fixture.backStack) }
            releaseSwitch.complete(Unit)
            compose.waitUntil(5_000) { !fixture.vm.busy.value }
            compose.runOnUiThread { assertEquals(listOf(Screen.Startup()), fixture.backStack) }
        } finally {
            releaseSwitch.complete(Unit)
        }
    }

    @Test
    fun installedEnterpriseListUsesTheChosenSourceIdentity() {
        val fixture = Fixture(overview(access = null))
        val installed = InstalledEnterpriseSource(access("installed").scope, "Private enterprise", "Private user")
        coEvery { fixture.service.installedSources() } returns listOf(installed)
        coEvery { fixture.service.joinInstalled(installed.scope) } coAnswers { fixture.state.value = overview() }
        fixture.show()
        click(R.string.enterprise_installed_sources)
        compose.onNodeWithText("Private enterprise").assertIsDisplayed()
        compose.onNodeWithText("Private user").assertIsDisplayed()
        compose.onNodeWithText("Private enterprise").performClick()
        compose.waitUntil(5_000) { fixture.vm.overview.value?.access != null }
        coVerify(exactly = 1) { fixture.service.joinInstalled(installed.scope) }
    }

    @Test
    fun exitConfirmationKeepsTheOriginalSessionSelectionAndEnterpriseName() {
        val original = overview(name = "Original enterprise")
        val fixture = Fixture(original)
        val frozen = EnterpriseExitRequest(requireNotNull(original.access), requireNotNull(original.selection))
        val exitStarted = CompletableDeferred<Unit>()
        val releaseExit = CompletableDeferred<Unit>()
        coEvery { fixture.service.captureExitRequest() } returns frozen
        coEvery { fixture.service.exit(any()) } coAnswers {
            exitStarted.complete(Unit)
            releaseExit.await()
        }
        try {
            fixture.show()
            click(R.string.enterprise_exit)
            compose.waitUntil(5_000) { fixture.vm.exitRequest.value != null }
            val confirmation = text(R.string.enterprise_exit_confirm, "Original enterprise")
            compose.onNodeWithText(confirmation).assertIsDisplayed()
            coVerify(exactly = 0) { fixture.service.exit(any()) }

            val replacement = overview(access = access("replacement-session"), name = "Replacement enterprise", revision = 7L)
            fixture.state.value = replacement
            compose.waitUntil(5_000) { fixture.vm.overview.value == replacement }
            compose.onNodeWithText(confirmation).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.confirm)).performClick()
            compose.waitUntil(5_000) { exitStarted.isCompleted }
            coVerify(exactly = 1) { fixture.service.captureExitRequest() }
            coVerify(exactly = 1) { fixture.service.exit(frozen) }
            compose.runOnUiThread { assertEquals(listOf(Screen.Enterprise), fixture.backStack) }
            releaseExit.complete(Unit)
            compose.waitUntil(5_000) { !fixture.vm.busy.value }
            compose.runOnUiThread { assertEquals(listOf(Screen.Startup()), fixture.backStack) }
        } finally {
            releaseExit.complete(Unit)
        }
    }

    @Test
    fun stoppingDuringPortalOpenClosesAndAwaitsTheLateHostWithoutInstallingItsView() =
        verifyLateHostCleanup()

    @Test
    fun lateHostCleanupFailurePreservesCancellationAndDoesNotBecomeAPageFailure() =
        verifyLateHostCleanup(PortalFailure("timeout"))

    private fun verifyLateHostCleanup(cleanupFailure: Exception? = null) {
        val fixture = Fixture(overview())
        val started = CompletableDeferred<Unit>()
        val releaseOpen = CompletableDeferred<Unit>()
        val awaitingClose = CompletableDeferred<Unit>()
        val releaseClose = CompletableDeferred<Unit>()
        val completion = AtomicReference<Throwable?>()
        val worker = AtomicReference<Job>()
        val document = mockk<PortalDocument>()
        val host = mockk<PortalWebView>()
        every { host.document } returns document
        every { host.close() } just Runs
        every { host.view } answers { error("A late Portal view must not be installed") }
        coEvery { document.awaitClosed() } coAnswers {
            awaitingClose.complete(Unit)
            releaseClose.await()
        }
        coEvery { fixture.service.openPortal(any(), any(), any()) } coAnswers {
            worker.set(requireNotNull(currentCoroutineContext()[Job]).also { job ->
                job.invokeOnCompletion { completion.set(it) }
            })
            started.complete(Unit)
            withContext(NonCancellable) { releaseOpen.await() }
            host
        }
        try {
            fixture.show()
            click(R.string.enterprise_portal)
            compose.waitUntil(5_000) { started.isCompleted }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.runOnUiThread {
                assertNull(fixture.vm.portal.value)
                assertTrue(worker.get().isCancelled)
            }
            releaseOpen.complete(Unit)
            compose.waitUntil(5_000) { awaitingClose.isCompleted }
            assertFalse("The cancelled opening must retain cleanup ownership", worker.get().isCompleted)
            verify(exactly = 1) { host.close() }
            verify(exactly = 0) { host.view }
            if (cleanupFailure == null) releaseClose.complete(Unit)
            else releaseClose.completeExceptionally(cleanupFailure)
            compose.waitUntil(5_000) { worker.get().isCompleted }
            compose.waitUntil(5_000) { completion.get() != null }
            assertTrue(completion.get() is CancellationException)
            if (cleanupFailure != null) assertTrue(completion.get()!!.suppressed.any { suppressed ->
                generateSequence(suppressed) { it.cause }.last() === cleanupFailure
            })
            assertTrue(worker.get().isCancelled)
            coVerify(exactly = 1) { document.awaitClosed() }
            coVerify(exactly = 1) {
                fixture.service.openPortal(any(), requireNotNull(fixture.state.value.selection), any())
            }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle {
                assertNull(fixture.vm.portal.value)
                assertNull(fixture.vm.error.value)
            }
        } finally {
            releaseOpen.complete(Unit)
            releaseClose.complete(Unit)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }

    @Test
    fun failedOpeningFromTheStoppedPageCannotDismissOrReportIntoItsReplacement() {
        val fixture = Fixture(overview())
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstWorker = AtomicReference<Job>()
        val secondWorker = AtomicReference<Job>()
        val attempts = AtomicInteger()
        coEvery { fixture.service.openPortal(any(), any(), any()) } coAnswers {
            if (attempts.incrementAndGet() == 1) {
                firstWorker.set(requireNotNull(currentCoroutineContext()[Job]))
                firstStarted.complete(Unit)
                withContext(NonCancellable) { releaseFirst.await() }
                throw PortalFailure("source_unavailable")
            }
            secondWorker.set(requireNotNull(currentCoroutineContext()[Job]))
            secondStarted.complete(Unit)
            awaitCancellation()
        }
        try {
            fixture.show()
            click(R.string.enterprise_portal)
            compose.waitUntil(5_000) { firstStarted.isCompleted }
            val original = fixture.vm.portal.value
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            click(R.string.enterprise_portal)
            compose.waitUntil(5_000) { secondStarted.isCompleted }
            val replacement = requireNotNull(fixture.vm.portal.value)
            assertNotEquals(original, replacement)
            releaseFirst.complete(Unit)
            compose.waitUntil(5_000) { firstWorker.get().isCompleted }
            compose.runOnUiThread {
                assertEquals(replacement, fixture.vm.portal.value)
                assertNull(fixture.vm.error.value)
                fixture.vm.dismissPortal(replacement)
            }
            compose.waitUntil(5_000) { secondWorker.get().isCompleted }
            coVerify(exactly = 2) { fixture.service.openPortal(any(), any(), any()) }
        } finally {
            releaseFirst.complete(Unit)
            compose.runOnUiThread { fixture.vm.portal.value?.let(fixture.vm::dismissPortal) }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }

    private fun click(resource: Int) {
        compose.onNode(hasText(text(resource)) and hasClickAction()).performScrollTo().performClick()
    }

    private fun text(resource: Int, vararg args: Any): String = compose.activity.getString(resource, *args)

    private fun access(session: String = "original-session") = RealmAccess.Enterprise(
        ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "deployment"), "user"), session,
    )

    private fun overview(
        access: RealmAccess.Enterprise? = access(),
        name: String = "Example enterprise",
        revision: Long = 1L,
    ) = EnterpriseOverview(
        selection = RealmSelection(access ?: RealmAccess.Personal, revision),
        phase = if (access == null) EnterpriseSessionPhase.SIGNED_OUT else EnterpriseSessionPhase.READY,
        enterpriseName = name.takeIf { access != null },
        userName = "Example user".takeIf { access != null },
        access = access,
        isLocal = access != null,
        generation = 1L.takeIf { access != null },
        lastSyncMillis = null,
        failure = null,
        exitFailure = null,
        switching = false,
    )

    private inner class Fixture(initial: EnterpriseOverview) {
        val state = MutableStateFlow(initial)
        val service = mockk<EnterpriseApplicationService>()
        val backStack = mutableListOf<NavKey>(Screen.Enterprise)
        lateinit var vm: EnterpriseVM

        fun show() {
            every { service.observe() } returns state
            compose.runOnUiThread {
                vm = EnterpriseVM(service)
                viewModels.put("enterprise", vm)
            }
            val navigator = Navigator(backStack)
            compose.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalNavController provides navigator) { EnterprisePage(vm) }
                }
            }
            compose.waitUntil(5_000) { vm.overview.value == state.value }
        }
    }
}
