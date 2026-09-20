package net.weero.measix.pilot.ui.pages.enterprise

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.EnterpriseExitRequest
import net.weero.measix.pilot.data.enterprise.EnterpriseAddressChangeRequest
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionPhase
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.enterprise.RealmSwitchRequest
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.service.portal.PortalDocument
import net.weero.measix.pilot.service.portal.PortalFailure
import net.weero.measix.pilot.service.portal.PortalWebView
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.context.Navigator
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.AdaptiveHingeBounds
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
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
    fun configurationDetailsShowTheEnterpriseAddressAndCopyAction() {
        val origin = "https://core.example"
        val initial = overview()
        val fixture = Fixture(initial.copy(
            platformOrigin = origin,
            configurationDetails = requireNotNull(initial.configurationDetails).copy(platformOrigin = origin),
        ))
        fixture.show()

        click(R.string.enterprise_configuration_details_open)
        compose.onNodeWithText(text(R.string.enterprise_configuration_diagnostics_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.enterprise_address)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(origin).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.copy)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.copied)).assertIsDisplayed()
    }

    @Test
    fun connectedCardEditsTheAddressWithoutStartingEnrollment() {
        val oldOrigin = "https://old.example"
        val newOrigin = "https://new.example"
        val initial = overview().copy(platformOrigin = oldOrigin)
        val fixture = Fixture(initial)
        val request = EnterpriseAddressChangeRequest(initial.access!!, initial.selection!!)
        coEvery { fixture.service.changeAddress(request, newOrigin) } just Runs
        fixture.show()

        click(R.string.edit)
        compose.onNodeWithText(text(R.string.enterprise_address_edit_title)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement(newOrigin)
        compose.onNode(hasText(text(R.string.confirm)) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { !fixture.vm.busy.value }

        coVerify(exactly = 1) { fixture.service.changeAddress(request, newOrigin) }
        coVerify(exactly = 0) { fixture.service.confirmJoin(any()) }
        compose.onNodeWithText(text(R.string.enterprise_address_changed)).assertIsDisplayed()
    }

    @Test
    fun connectedCardEditsTheAddressWhilePersonalSpaceIsSelected() {
        val oldOrigin = "https://old.example"
        val newOrigin = "https://new.example"
        val enterpriseAccess = access()
        val personalSelection = RealmSelection(RealmAccess.Personal, 7)
        val initial = overview(enterpriseAccess).copy(
            selection = personalSelection,
            platformOrigin = oldOrigin,
        )
        val request = EnterpriseAddressChangeRequest(enterpriseAccess, personalSelection)
        val fixture = Fixture(initial)
        coEvery { fixture.service.changeAddress(request, newOrigin) } just Runs
        fixture.show()

        click(R.string.edit)
        compose.onNodeWithText(text(R.string.enterprise_address_edit_title)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement(newOrigin)
        compose.onNode(hasText(text(R.string.confirm)) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { !fixture.vm.busy.value }

        coVerify(exactly = 1) { fixture.service.changeAddress(request, newOrigin) }
        compose.onNodeWithText(text(R.string.enterprise_address_changed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.enterprise_configuration_details_open)).assertIsDisplayed()
    }

    @Test
    fun configurationDetailsStayInTheRightPaneOfAVerticalSeparatingHinge() {
        val fixture = Fixture(overview())
        fixture.show(withVerticalHinge = true)

        click(R.string.enterprise_configuration_details_open)
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val contentBounds = compose.onNodeWithTag("enterprise-configuration-details-content")
            .fetchSemanticsNode().boundsInRoot

        assertTrue("content must start to the right of the centered hinge", contentBounds.left > rootBounds.center.x)
        assertTrue("content must remain inside the dialog", contentBounds.right <= rootBounds.right)
    }

    @Test
    fun configurationDetailsShowAvailableUnsetAndUnavailableDefaults() {
        val initial = overview()
        val defaults = EnterpriseConfigurationDefaultKind.entries.mapIndexed { index, kind ->
            when (index) {
                0 -> EnterpriseConfigurationDefaultUiModel(
                    kind,
                    "Available default",
                    EnterpriseConfigurationReferenceState.AVAILABLE,
                )
                1 -> EnterpriseConfigurationDefaultUiModel(
                    kind,
                    null,
                    EnterpriseConfigurationReferenceState.UNAVAILABLE,
                )
                else -> EnterpriseConfigurationDefaultUiModel(
                    kind,
                    null,
                    EnterpriseConfigurationReferenceState.UNSET,
                )
            }
        }
        val fixture = Fixture(initial.copy(
            configurationDetails = requireNotNull(initial.configurationDetails).copy(defaults = defaults),
        ))
        fixture.show()

        click(R.string.enterprise_configuration_details_open)
        compose.onNode(hasText(text(R.string.enterprise_configuration_defaults_title)) and hasClickAction()).performClick()
        compose.onNodeWithText("Available default").assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.enterprise_configuration_unset))[0].assertIsDisplayed()
        compose.onNodeWithText(text(R.string.enterprise_configuration_reference_unavailable)).assertIsDisplayed()
    }

    @Test
    fun configurationDetailsUseOverviewCategoryItemDisclosureAndLayeredBack() {
        val fixture = Fixture(overview())
        fixture.show()

        click(R.string.enterprise_configuration_details_open)
        click(R.string.enterprise_configuration_resource_image_generators)
        compose.onNodeWithText("Managed image").assertIsDisplayed().performClick()
        compose.onNodeWithText("img_example").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.enterprise_configuration_disabled)).assertIsDisplayed()

        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithText("Managed image").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithText(text(R.string.enterprise_configuration_defaults_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithText(text(R.string.enterprise_configuration_details_open)).assertIsDisplayed()
    }

    @Test
    fun cachedConfigurationRemainsAvailableWhenStartupSyncFails() {
        val diagnostic = "InterruptedIOException: timeout\nCaused by: SocketException: Socket closed"
        val fixture = Fixture(overview().copy(
            enrollmentRecoveryFailure = diagnostic,
            lastSyncMillis = 1_000,
        ))
        fixture.show()

        compose.onNodeWithText(text(R.string.enterprise_ready)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.enterprise_sync_failed_cached)).assertIsDisplayed()
        compose.onNodeWithText(diagnostic).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.enterprise_failure)).assertDoesNotExist()
    }

    @Test
    fun platformPasteShowsOriginBeforeConnectingAndCancelDoesNotEnroll() {
        val fixture = Fixture(overview(access = null))
        val origin = "http://192.168.1.20:8080"
        val confirmation = net.weero.measix.pilot.service.EnterpriseJoinConfirmation(kotlin.uuid.Uuid.random(), origin)
        val raw = """{"formatVersion":1,"kind":"PLATFORM_ENROLLMENT","platformUrl":"$origin","code":"test-code","expiresAt":"2030-01-01T00:00:00Z"}"""
        coEvery { fixture.service.join(raw) } returns confirmation
        coEvery { fixture.service.dismissJoin(confirmation) } just Runs
        coEvery { fixture.service.confirmJoin(confirmation) } just Runs
        fixture.show()
        compose.onNodeWithText(text(R.string.enterprise_join_scan_gallery)).assertIsDisplayed()
        click(R.string.enterprise_join_paste)
        compose.onNode(hasSetTextAction()).performTextInput(raw)
        compose.onNode(hasText(text(R.string.enterprise_join_submit)) and hasClickAction()).performClick()
        compose.onNodeWithText(text(R.string.enterprise_platform_confirm, origin)).assertIsDisplayed()
        coVerify(exactly = 0) { fixture.service.confirmJoin(any()) }
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(compose.activity.cacheDir, "enterprise-platform-confirm.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        compose.onNode(hasText(text(R.string.cancel)) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { fixture.vm.joinConfirmation.value == null }
        coVerify(exactly = 1) { fixture.service.dismissJoin(confirmation) }
        coVerify(exactly = 0) { fixture.service.confirmJoin(any()) }
        compose.runOnUiThread { fixture.vm.join(raw) }
        compose.waitUntil(5_000) { fixture.vm.joinConfirmation.value != null }
        compose.onNode(hasText(text(R.string.confirm)) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { !fixture.vm.busy.value }
        coVerify(exactly = 1) { fixture.service.confirmJoin(confirmation) }
    }


    @Test
    fun storageFailureRepairEntryOpensChoiceThenConfirmationAndNeverAutoExecutes() {
        val fixture = Fixture(overview(access = null).copy(
            failure = "invalid_enterprise_storage",
            resetPath = net.weero.measix.pilot.service.EnterpriseResetPath.STORAGE_FAILURE,
        ))
        coEvery { fixture.service.localDataReset(any()) } returns Unit
        fixture.show()
        click(R.string.enterprise_reset_repair)
        compose.onNodeWithText(text(R.string.enterprise_reset_option_notice)).assertIsDisplayed()
        coVerify(exactly = 0) { fixture.service.localDataReset(any()) }
        click(R.string.enterprise_reset_keep_history)
        compose.onNodeWithText(text(R.string.enterprise_reset_repair_confirm)).assertIsDisplayed()
        coVerify(exactly = 0) { fixture.service.localDataReset(any()) }
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.waitUntil(5_000) { fixture.vm.resetConfirmation.value == null }
        coVerify(exactly = 0) { fixture.service.localDataReset(any()) }
    }

    @Test
    fun connectedResetOffersBothModesAndClearAllConfirmationStaysUserDriven() {
        val fixture = Fixture(overview().copy(
            resetPath = net.weero.measix.pilot.service.EnterpriseResetPath.CONNECTED,
        ))
        coEvery { fixture.service.localDataReset(any()) } coAnswers { fixture.state.value = overview(access = null) }
        fixture.show()
        click(R.string.enterprise_reset_title)
        compose.onNodeWithText(text(R.string.enterprise_reset_clear_all)).assertIsDisplayed()
        click(R.string.enterprise_reset_clear_all)
        compose.onNodeWithText(text(R.string.enterprise_reset_clear_all_confirm)).assertIsDisplayed()
        coVerify(exactly = 0) { fixture.service.localDataReset(any()) }
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        compose.waitUntil(5_000) { !fixture.vm.busy.value }
        coVerify(exactly = 1) { fixture.service.localDataReset(any()) }
    }

    @Test
    fun ordinaryBackReturnsToTheSettingsPageThatOpenedSpaces() {
        val fixture = Fixture(overview(), mutableListOf(Screen.Setting, Screen.Enterprise))
        fixture.show()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.runOnIdle { assertEquals(listOf(Screen.Setting), fixture.backStack) }
    }

    @Test
    fun syncFeedbackDoesNotSurviveTheEnterpriseSelectionThatProducedIt() {
        val original = overview()
        val fixture = Fixture(original)
        coEvery { fixture.service.synchronize(requireNotNull(original.access)) } just Runs
        fixture.show()
        click(R.string.enterprise_sync)
        compose.waitUntil(5_000) { fixture.vm.notice.value == R.string.enterprise_sync_completed }
        fixture.state.value = overview(access = null, revision = 2L)
        compose.waitUntil(5_000) { fixture.vm.overview.value?.access == null && fixture.vm.notice.value == null }
        compose.onNodeWithText(text(R.string.enterprise_sync_completed)).assertDoesNotExist()
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
            net.weero.measix.pilot.service.EnterpriseExitResult()
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
        // Recording the WebView getter creates a framework View proxy even for a throwing answer.
        // Keep this strict host opaque and verify its complete call set after cleanup instead.
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
            verify(exactly = 1) { host.document }
            confirmVerified(host)
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
        ConfigurationScope.Enterprise(EnterpriseAuthority("deployment"), "user"), session,
    )

    private fun overview(
        access: RealmAccess.Enterprise? = access(),
        name: String = "Example enterprise",
        revision: Long = 1L,
    ): EnterpriseOverview {
        val selection = RealmSelection(access ?: RealmAccess.Personal, revision)
        return EnterpriseOverview(
        selection = selection,
        phase = if (access == null) EnterpriseSessionPhase.SIGNED_OUT else EnterpriseSessionPhase.READY,
        enterpriseName = name.takeIf { access != null },
        userName = "Example user".takeIf { access != null },
        access = access,
        generation = 1L.takeIf { access != null },
        lastSyncMillis = null,
        failure = null,
        exitFailure = null,
        switching = false,
        platformOrigin = "https://core.example".takeIf { access != null },
        configurationDetails = access?.let { configurationDetails(name) },
    )
    }

    private fun configurationDetails(name: String) = EnterpriseConfigurationDetailsUiModel(
        enterpriseName = name,
        phase = EnterpriseSessionPhase.READY,
        generation = 1,
        lastSyncMillis = 1_000,
        platformOrigin = "https://core.example",
        defaults = EnterpriseConfigurationDefaultKind.entries.map { kind ->
            EnterpriseConfigurationDefaultUiModel(
                kind = kind,
                displayName = null,
                state = EnterpriseConfigurationReferenceState.UNSET,
            )
        },
        policies = EnterpriseConfigurationPolicyKind.entries.map { kind ->
            EnterpriseConfigurationPolicyUiModel(kind, allowed = false)
        },
        resources = EnterpriseConfigurationResourceKind.entries.map { kind ->
            EnterpriseConfigurationResourceGroupUiModel(
                kind,
                if (kind == EnterpriseConfigurationResourceKind.IMAGE_GENERATOR) listOf(
                    EnterpriseConfigurationResourceUiModel(
                        key = "1:IMAGE_GENERATOR:0",
                        displayName = "Managed image",
                        enabled = false,
                    ),
                ) else emptyList(),
            )
        },
    )

    private inner class Fixture(
        initial: EnterpriseOverview,
        val backStack: MutableList<NavKey> = mutableListOf(Screen.Enterprise),
    ) {
        val state = MutableStateFlow(initial)
        val service = mockk<EnterpriseApplicationService>()
        lateinit var vm: EnterpriseVM

        fun show(withVerticalHinge: Boolean = false) {
            every { service.observe() } returns state
            every { service.runtimeUsageChanges() } returns emptyFlow()
            compose.runOnUiThread {
                vm = EnterpriseVM(service)
                viewModels.put("enterprise", vm)
            }
            val navigator = Navigator(backStack)
            compose.setContent {
                val toaster = rememberToasterState()
                val measuredAdaptive = rememberAdaptiveLayoutInfo()
                val adaptive = if (withVerticalHinge) {
                    val width = measuredAdaptive.windowSize.width.value
                    val height = measuredAdaptive.windowSize.height.value
                    measuredAdaptive.copy(
                        separatingVerticalHingeBounds = listOf(
                            AdaptiveHingeBounds(width * 0.49f, 0f, width * 0.51f, height),
                        ),
                    )
                } else {
                    measuredAdaptive
                }
                MaterialTheme {
                    CompositionLocalProvider(
                        LocalNavController provides navigator,
                        LocalToaster provides toaster,
                        LocalAdaptiveLayoutInfo provides adaptive,
                    ) {
                        Toaster(state = toaster, alignment = Alignment.TopCenter)
                        EnterprisePage(vm = vm)
                    }
                }
            }
            compose.waitUntil(5_000) { vm.overview.value == state.value }
        }
    }
}
