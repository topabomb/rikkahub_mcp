package net.weero.measix.pilot.ui.pages.enterprise

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performSemanticsAction
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
import net.weero.measix.pilot.service.portal.PortalDestination
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
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlinx.coroutines.flow.map
import me.rerere.common.configuration.ConfigurationReference
import kotlin.uuid.Uuid
import net.weero.measix.pilot.service.remoteworkspace.RemoteWorkspaceQueryState
import net.weero.measix.pilot.service.remoteworkspace.RemoteWorkspaceSummary
import net.weero.measix.pilot.service.remoteworkspace.RemoteWorkspaceStatus
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Exercises the native consumer and Activity lifecycle; protocol and real WebView tests have separate owners. */
@RunWith(AndroidJUnit4::class)
class EnterprisePageAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val viewModels = ViewModelStore()
    private val isolatedContexts = mutableListOf<org.koin.core.KoinApplication>()

    @After
    fun clearViewModels() {
        compose.runOnUiThread { viewModels.clear() }
        isolatedContexts.forEach { it.close() }
    }

    @Test
    fun personalTitleActionExplicitlyCreatesADraftInItsRenderedSelection() {
        val selection = RealmSelection(RealmAccess.Personal, 2)
        val fixture = Fixture(overview().copy(selection = selection))
        val request = ConversationOpenRequest.NewDraft(Uuid.random(), RealmAccess.Personal, ConfigurationReference.random())
        coEvery { fixture.conversations.newDraftRequest(selection, null) } returns request
        fixture.show()
        compose.onNodeWithTag("space-start-conversation").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { fixture.backStack.lastOrNull() == Screen.Chat(request) }
        coVerify(exactly = 1) { fixture.conversations.newDraftRequest(selection, null) }
        coVerify(exactly = 0) { fixture.conversations.initialRequest(any()) }
    }

    @Test
    fun unknownWorkspaceStaysHiddenAndItsFirstFailureIsLocalToTheConnection() {
        val fixture = Fixture(overview())
        val selection = requireNotNull(fixture.state.value.selection)
        fixture.workspaceQuery.value = RemoteWorkspaceQueryState(selection, true)
        fixture.show()
        compose.onNodeWithText(text(R.string.remote_workspace_title)).assertDoesNotExist()
        fixture.workspaceQuery.value = RemoteWorkspaceQueryState(selection, false, "IOException: status unavailable")
        compose.onNodeWithText(text(R.string.remote_workspace_query_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_workspace_title)).assertDoesNotExist()
        capturePage("enterprise-workspace-first-failure.png")
        fixture.workspaceQuery.value = RemoteWorkspaceQueryState(selection, false)
        fixture.workspaceSummary.value = RemoteWorkspaceSummary(selection, RemoteWorkspaceStatus.AVAILABLE)
        compose.onNodeWithText(text(R.string.remote_workspace_title)).assertIsDisplayed()
        fixture.workspaceSummary.value = RemoteWorkspaceSummary(selection, RemoteWorkspaceStatus.CHECKING)
        compose.onNodeWithText(text(R.string.remote_workspace_title)).assertIsDisplayed()
        capturePage("enterprise-workspace-refreshing.png")
        fixture.workspaceSummary.value = RemoteWorkspaceSummary(selection, RemoteWorkspaceStatus.FAILED, diagnostic = "IOException: retry failed")
        compose.onNodeWithText(text(R.string.remote_workspace_title)).assertIsDisplayed()
        capturePage("enterprise-workspace-retained-failure.png")
        fixture.workspaceSummary.value = null
        compose.onNodeWithText(text(R.string.remote_workspace_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.remote_workspace_query_failed)).assertDoesNotExist()
    }

    @Test
    fun personalUsageSummaryOffersExplicitSpaceSwitchInsteadOfAnUnauthorizedPortal() {
        val fixture = Fixture(overview().copy(selection = RealmSelection(RealmAccess.Personal, 2)))
        fixture.show()
        compose.onNodeWithTag("enterprise-page-menu").performClick()
        compose.onNodeWithText(text(R.string.enterprise_budget_title)).performClick()
        compose.onNodeWithText(text(R.string.enterprise_budget_details)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.enterprise_switch_enterprise)).performScrollTo().assertIsDisplayed()
        assertNull(fixture.vm.portal.value)
    }

    @Test
    fun recentUpdatesStayInPersonalSpaceAndDisappearOnLogout() {
        val titles = listOf("本周设备巡检安排", "测量报告模板已更新", "企业服务维护通知", "新员工测量培训指引", "数据归档与查询功能说明")
        val bodies = listOf(
            "**巡检通知**：请各班组在本周五前完成测量设备巡检，重点检查探头状态、校准记录和设备运行环境。\n" +
                "巡检结果请记录在企业台账中，发现异常及时联系设备管理员。\n" +
                "详细操作步骤和注意事项已更新，请在操作前仔细阅读。\n完整正文最后一行。",
            "新版报告增加了**公差判定**与测量趋势摘要，可在配置详情中确认当前使用的模板。历史报告仍按生成时的模板保存。",
            "今晚 23:00–23:30 进行例行维护。期间配置同步和新任务提交可能暂时不可用，已完成的测量记录不受影响。",
            "培训内容包括设备校准、测量流程和异常记录。请先阅读操作指引，再由班组负责人带领完成首次测量。",
            "归档记录支持按设备、零件编号与日期查询。打开企业工作台即可检索历史测量结果，并查看对应报告。",
        )
        val fixture = Fixture(overview(name = "示例制造企业 · Measix Orchelm 企业配置中心").copy(
            platformOrigin = "https://measix-orchelm.weero.net",
        ), recentUpdates = EnterpriseUpdatesUiModel("Asia/Shanghai",
            titles.mapIndexed { index, title -> EnterpriseUpdateSummaryUiModel("update-$index", title, "2026-09-22T08:00:00Z",
                bodies[index], true, when (index) {
                    0, 3 -> EnterpriseUpdateCategory.ANNOUNCEMENT
                    2 -> EnterpriseUpdateCategory.MAINTENANCE
                    else -> EnterpriseUpdateCategory.NOTICE
                }, when (index) {
                    1 -> EnterpriseUpdateSeverity.WARNING
                    2 -> EnterpriseUpdateSeverity.CRITICAL
                    else -> EnterpriseUpdateSeverity.INFO
                }) }))
        fixture.show()
        compose.onNodeWithText(text(R.string.enterprise_recent_updates_count, 5)).assertIsDisplayed()
        compose.onAllNodesWithContentDescription(text(R.string.enterprise_update_announcement))[0].assertIsDisplayed()
        compose.onAllNodesWithContentDescription(text(R.string.enterprise_update_maintenance))[0].assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.enterprise_update_important))[0].assertIsDisplayed()
        val preview = compose.onAllNodesWithText("巡检通知", substring = true, useUnmergedTree = true)[0]
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        assertTrue(preview.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult]
            .action!!.invoke(layouts))
        assertEquals(3, layouts.single().lineCount)
        assertTrue(layouts.single().hasVisualOverflow)
        assertFalse(layouts.single().layoutInput.text.text.contains("**"))
        capturePage("enterprise-content-home.png")
        compose.onNodeWithText(titles.first()).performClick()
        compose.waitForIdle()
        layouts.clear()
        preview.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertFalse(layouts.single().hasVisualOverflow)
        compose.onNodeWithText(titles.first()).performClick()
        compose.onNodeWithText(titles.last()).performScrollTo().assertIsDisplayed()
        capturePage("enterprise-recent-updates.png")
        fixture.state.value = fixture.state.value.copy(selection = RealmSelection(RealmAccess.Personal, 2))
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.enterprise_personal)).performScrollTo()
        compose.onNode(hasScrollAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {
            it(0f, -10_000f)
        }
        compose.onNodeWithText(text(R.string.enterprise_recent_updates_count, 5)).assertIsDisplayed()
        capturePage("enterprise-personal-updates.png")
        coVerify { fixture.service.recentUpdates(match { it.access == RealmAccess.Personal }, any()) }
        fixture.state.value = overview(access = null, revision = 3)
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.enterprise_recent_updates_count, 5)).assertDoesNotExist()
        compose.onNodeWithText(titles.first()).assertDoesNotExist()
    }

    @Test
    fun enrollmentPrioritizesScanAndPasteWithPracticalIntroduction() {
        val fixture = Fixture(overview(access = null))
        fixture.show()
        compose.onNodeWithText(text(R.string.enterprise_join_scan)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.enterprise_join_paste)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.enterprise_join_scan_gallery)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.enterprise_orchelm_collaboration_detail)).assertIsDisplayed()
        capturePage("enterprise-join-introduction.png")
        compose.onNodeWithText(text(R.string.enterprise_join_paste)).performClick()
        compose.onNodeWithText(text(R.string.enterprise_join_submit)).assertIsDisplayed()
        coVerify(exactly = 0) { fixture.service.recentUpdates(any(), any()) }
    }

    @Test
    fun homeShowsOnlyExceededQuotaAndKeepsAddressCopyVisible() {
        val fixture = Fixture(overview(name = "麦睿菱-Measix Orchelm 生产测量企业空间").copy(
            platformOrigin = "https://measix-orchelm.weero.net",
        ))
        coEvery { fixture.service.budgets(any(), any()) } returns EnterpriseBudgetSummaryUiModel(
            "Asia/Shanghai",
            listOf(
                EnterpriseBudgetCapabilityUiModel(EnterpriseBudgetCapabilityKind.MODEL,
                    EnterpriseBudgetAvailability.EXHAUSTED, null, 0, emptyList()),
                EnterpriseBudgetCapabilityUiModel(EnterpriseBudgetCapabilityKind.TTS,
                    EnterpriseBudgetAvailability.AVAILABLE, null, 0, emptyList()),
            ), 0, "2026-09-22T08:00:00Z",
        )
        fixture.show()
        compose.onNodeWithText("https://measix-orchelm.weero.net").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.enterprise_budget_alerts)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.enterprise_budget_capability_model)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.enterprise_budget_capability_tts)).assertDoesNotExist()
        capturePage("enterprise-quota-alert.png")
        compose.onNodeWithContentDescription(text(R.string.copy)).performClick()
        compose.onNodeWithText(text(R.string.copied)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.enterprise_budget_title)).performClick()
        compose.onNodeWithText(text(R.string.enterprise_budget_capability_tts)).assertIsDisplayed()
    }

    @Test
    fun budgetSummaryUsesCompactRowsAndTheDetailsEntryOpensUsage() {
        val fixture = Fixture(overview(name = "示例制造企业").copy(
            userName = "张工",
            resetPath = EnterpriseResetPath.CONNECTED,
        ))
        coEvery { fixture.service.budgets(any(), any()) } returns EnterpriseBudgetSummaryUiModel(
            timezone = "Asia/Shanghai",
            items = listOf(
                EnterpriseBudgetCapabilityUiModel(
                    capability = EnterpriseBudgetCapabilityKind.MODEL,
                    availability = EnterpriseBudgetAvailability.NEAR_LIMIT,
                    primaryLimit = EnterpriseBudgetLimitUiModel(
                        period = EnterpriseBudgetPeriodKind.DAY,
                        meter = EnterpriseBudgetMeterKind.REQUESTS,
                        limit = "1000",
                        used = "830",
                        reserved = "50",
                        remaining = "120",
                        occupiedFraction = 0.88f,
                        resetAt = "2026-09-23T00:00:00Z",
                    ),
                    additionalLimitCount = 1,
                    usageSummary = emptyList(),
                ),
                EnterpriseBudgetCapabilityUiModel(
                    capability = EnterpriseBudgetCapabilityKind.TTS,
                    availability = EnterpriseBudgetAvailability.UNLIMITED,
                    primaryLimit = null,
                    additionalLimitCount = 0,
                    usageSummary = listOf(
                        EnterpriseBudgetUsageUiModel(
                            EnterpriseBudgetMeterKind.CHARACTERS,
                            "1983",
                            EnterpriseBudgetCompleteness.EXACT,
                        ),
                    ),
                ),
            ),
            totalInFlightRequests = 3,
            asOf = "2026-09-22T06:00:00Z",
        )
        coEvery { fixture.service.openPortal(any(), any(), any()) } coAnswers { awaitCancellation() }

        fixture.show()

        capturePage("enterprise-spaces.png")
        compose.onNodeWithText(text(R.string.enterprise_budget_capability_model)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.enterprise_reset_title)).assertDoesNotExist()
        compose.onNodeWithTag("enterprise-page-menu").performClick()
        capturePage("enterprise-space-menu.png")
        compose.onNodeWithText(text(R.string.enterprise_budget_title)).performClick()
        compose.onNodeWithText(text(R.string.enterprise_budget_capability_model)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.enterprise_budget_near_limit)).assertIsDisplayed()
        capturePage("enterprise-usage.png")
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithTag("enterprise-page-menu").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.enterprise_budget_capability_model)).assertDoesNotExist()
        compose.onNodeWithTag("enterprise-page-menu").performClick()
        compose.onNodeWithText(text(R.string.enterprise_budget_title)).performClick()
        compose.onNodeWithText(text(R.string.enterprise_budget_capability_tts)).performScrollTo().assertIsDisplayed()
        val details = compose.onNodeWithText(text(R.string.enterprise_budget_details)).performScrollTo().assertIsDisplayed()
        details.performClick()
        compose.waitUntil(5_000) { fixture.vm.portal.value?.destination == PortalDestination.USAGE }
    }

    @Test
    fun disabledStarterDefinitionRemainsReadableWithoutOfferingADraftAction() {
        val initial = overview().copy(selection = RealmSelection(RealmAccess.Personal, 2))
        val target = EnterpriseResourceDetailTarget(requireNotNull(initial.selection), requireNotNull(initial.access), 1,
            ConfigurationReference.Enterprise(initial.access!!.scope.authority, "starter_disabled"), EnterpriseConfigurationResourceKind.STARTER)
        val resource = EnterpriseConfigurationResourceUiModel("disabled-starter", "Disabled inspection topic", false, target = target)
        val fixture = Fixture(initial.copy(configurationDetails = initial.configurationDetails!!.copy(
            resources = listOf(EnterpriseConfigurationResourceGroupUiModel(EnterpriseConfigurationResourceKind.STARTER, listOf(resource))))))
        val detail = StarterOpeningDetailUiModel(resource.displayName, "Review the inspection report.", "Published opening system.",
            listOf(StarterContextUiModel("Published opening background.")), false, true)
        coEvery { fixture.configurationQueries.readEnterpriseStarterDetails(target) } returns detail
        fixture.show()
        click(R.string.enterprise_configuration_details_open)
        click(R.string.enterprise_configuration_resource_starters)
        compose.onNodeWithText(resource.displayName).performClick()
        compose.onNodeWithText(text(R.string.enterprise_configuration_disabled)).assertIsDisplayed()
        click(R.string.enterprise_resource_definition)
        compose.onNodeWithText(detail.prompt).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(resource.displayName).assertCountEquals(1)
        compose.onNodeWithText(text(R.string.enterprise_starters_description)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.enterprise_starter_open)).assertDoesNotExist()
        click(R.string.opening_context)
        compose.onNodeWithText("Published opening system.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Published opening background.").performScrollTo().assertIsDisplayed()
        capturePage("enterprise-disabled-starter-definition.png")
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithText(resource.displayName).assertIsDisplayed()
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
        compose.onNodeWithText(text(R.string.enterprise_configuration_generation)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.enterprise_publication_details)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.enterprise_address)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(origin).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.enterprise_publication_copy)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.copied)).assertIsDisplayed()
    }

    @Test
    fun pageMenuEditsTheAddressWithoutStartingEnrollment() {
        val oldOrigin = "https://old.example"
        val newOrigin = "https://new.example"
        val initial = overview().copy(platformOrigin = oldOrigin)
        val fixture = Fixture(initial)
        val request = EnterpriseAddressChangeRequest(initial.access!!, initial.selection!!)
        coEvery { fixture.service.changeAddress(request, newOrigin) } just Runs
        fixture.show()

        compose.onNodeWithTag("enterprise-page-menu").performClick()
        capturePage("enterprise-management-menu.png")
        compose.onNodeWithText(text(R.string.enterprise_address_edit_title)).performClick()
        compose.onNodeWithText(text(R.string.enterprise_address_edit_title)).assertIsDisplayed()
        capturePage("enterprise-address-editor.png")
        compose.onNode(hasSetTextAction()).performTextReplacement(newOrigin)
        compose.onNode(hasText(text(R.string.confirm)) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { !fixture.vm.busy.value }

        coVerify(exactly = 1) { fixture.service.changeAddress(request, newOrigin) }
        coVerify(exactly = 0) { fixture.service.confirmJoin(any()) }
        compose.onNodeWithText(text(R.string.enterprise_address_changed)).assertIsDisplayed()
    }

    @Test
    fun pageMenuEditsTheAddressWhilePersonalSpaceIsSelected() {
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
        capturePage("enterprise-personal-space.png")

        compose.onNodeWithTag("enterprise-page-menu").performClick()
        capturePage("enterprise-personal-management-menu.png")
        compose.onNodeWithText(text(R.string.enterprise_address_edit_title)).performClick()
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
        listOf(
            R.string.enterprise_configuration_default_assistant,
            R.string.enterprise_configuration_default_chat_model,
            R.string.enterprise_configuration_default_fast_model,
            R.string.enterprise_configuration_default_title_model,
            R.string.enterprise_configuration_default_image_generation,
            R.string.enterprise_configuration_default_attachment_inspection_model,
            R.string.enterprise_configuration_default_suggestion_model,
            R.string.enterprise_configuration_default_compress_model,
            R.string.enterprise_configuration_default_tts,
            R.string.enterprise_configuration_default_asr,
        ).forEach { resource ->
            compose.onNodeWithText(text(resource)).performScrollTo().assertIsDisplayed()
        }
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
    fun syncFailureRetainsDataAndDisclosesTheOriginalDiagnosticOnDemand() {
        val diagnostic = "InterruptedIOException: timeout\nCaused by: SocketException: Socket closed"
        val fixture = Fixture(overview().copy(
            synchronization = EnterpriseSynchronizationStatus(access(), false,
                EnterpriseSynchronizationFailure(EnterpriseSynchronizationIssue.NETWORK, diagnostic)),
            lastSyncMillis = 1_000,
        ))
        fixture.show()

        compose.onNodeWithText(text(R.string.enterprise_configuration_attention)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.enterprise_sync_network_failed)).assertIsDisplayed()
        compose.onNodeWithText(diagnostic).assertDoesNotExist()
        click(R.string.chat_conversation_diagnostics)
        compose.onNodeWithText(diagnostic).assertIsDisplayed()
        capturePage("enterprise-sync-network-diagnostic.png")
        compose.onNodeWithContentDescription(text(R.string.update_card_close)).performClick()
        compose.onNodeWithText(text(R.string.enterprise_failure)).assertDoesNotExist()
    }

    @Test
    fun historyRemainsReachableWithoutAnAppliedConfiguration() {
        val fixture = Fixture(overview().copy(
            phase = EnterpriseSessionPhase.CONFIGURATION_PENDING,
            generation = null,
            configurationDetails = null,
            synchronization = EnterpriseSynchronizationStatus(access(), false,
                EnterpriseSynchronizationFailure(EnterpriseSynchronizationIssue.INVALID_CONFIGURATION,
                    "enterprise_configuration_unreadable")),
        ))
        fixture.show()
        compose.onNodeWithTag("enterprise-page-menu").performClick()
        compose.onNodeWithText(text(R.string.enterprise_all_chat_history)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(Screen.Enterprise, Screen.History), fixture.backStack) }
        coVerify(exactly = 0) { fixture.service.synchronize(any()) }
    }

    @Test
    fun unsupportedConfigurationAllowsEnteringTheEnterpriseSpaceAndShowsVersionDetails() {
        val diagnostic = "enterprise_configuration_version_unsupported: server [6], client [4, 5]"
        val personal = RealmSelection(RealmAccess.Personal, 2)
        val enterprise = RealmSelection(access(), 3)
        val fixture = Fixture(overview().copy(
            selection = personal,
            phase = EnterpriseSessionPhase.CONFIGURATION_PENDING,
            generation = null,
            configurationDetails = null,
            synchronization = EnterpriseSynchronizationStatus(access(), false,
                EnterpriseSynchronizationFailure(EnterpriseSynchronizationIssue.UPDATE_APP, diagnostic)),
        ))
        coEvery { fixture.service.switchRealm(RealmSwitchRequest(personal, access())) } returns enterprise
        fixture.show()
        compose.onNodeWithText(text(R.string.enterprise_snapshot_update_app)).assertIsDisplayed()
        compose.onNodeWithText(diagnostic).assertDoesNotExist()
        click(R.string.chat_conversation_diagnostics)
        compose.onNodeWithText(diagnostic).assertIsDisplayed()
        capturePage("enterprise-sync-version-diagnostic.png")
        compose.onNodeWithContentDescription(text(R.string.update_card_close)).performClick()
        click(R.string.enterprise_switch_enterprise)
        coVerify(exactly = 1) { fixture.service.switchRealm(RealmSwitchRequest(personal, access())) }
    }

    @Test
    fun platformPasteShowsOriginBeforeConnectingAndCancelDoesNotEnroll() {
        val fixture = Fixture(overview(access = null))
        val origin = "http://192.168.1.20:8080"
        val confirmation = net.weero.measix.pilot.service.EnterpriseJoinConfirmation(kotlin.uuid.Uuid.random(), origin)
        val raw = """{"formatVersion":1,"kind":"PLATFORM_ENROLLMENT","platformUrl":"$origin","code":"test-code","expiresAt":"2030-01-01T00:00:00Z"}"""
        coEvery { fixture.service.join(raw) } returns confirmation
        coEvery { fixture.service.dismissJoin(confirmation) } just Runs
        coEvery { fixture.service.confirmJoin(confirmation) } returns net.weero.measix.pilot.service.EnterpriseSynchronizationCommandResult.COMPLETED
        fixture.show()
        compose.onNodeWithText(text(R.string.enterprise_join_scan_gallery)).assertIsDisplayed()
        click(R.string.enterprise_join_paste)
        compose.onNode(hasSetTextAction()).performTextInput(raw)
        compose.onNode(hasText(text(R.string.enterprise_join_submit)) and hasClickAction()).performClick()
        compose.onNodeWithText(text(R.string.enterprise_platform_confirm, origin)).assertIsDisplayed()
        coVerify(exactly = 0) { fixture.service.confirmJoin(any()) }
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Allow the display compositor to present the committed Compose frame before capture.
        android.os.SystemClock.sleep(250)
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
        compose.onNodeWithTag("enterprise-page-menu").performClick()
        compose.onNodeWithText(text(R.string.enterprise_reset_repair)).performClick()
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
        compose.onNodeWithTag("enterprise-page-menu").performClick()
        compose.onNodeWithText(text(R.string.enterprise_reset_title)).performClick()
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
    fun pendingResetCanRetryWithoutDisplayingCancellationAsFailure() {
        val pending = net.weero.measix.pilot.data.enterprise.EnterpriseDataResetProgress(
            operationId = Uuid.random(),
            mode = net.weero.measix.pilot.data.enterprise.EnterpriseDataResetMode.KEEP_HISTORY,
            stage = net.weero.measix.pilot.data.enterprise.EnterpriseDataResetStage.DOMAINS_CLOSED,
            failure = null,
            running = false,
        )
        val fixture = Fixture(overview().copy(reset = pending))
        coEvery { fixture.service.retryLocalDataReset() } coAnswers {
            fixture.state.value = fixture.state.value.copy(reset = pending.copy(running = true))
        }
        fixture.show()
        compose.onNodeWithText(text(R.string.enterprise_reset_failed)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.application_recovery_retry)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.enterprise_reset_progress)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.application_recovery_retry)).assertDoesNotExist()
        coVerify(exactly = 1) { fixture.service.retryLocalDataReset() }
    }

    @Test
    fun replacementSessionAfterStateRestorationCannotReturnToThePreviousChat() {
        val original = overview()
        val request = ConversationOpenRequest.NewDraft(
            kotlin.uuid.Uuid.random(), requireNotNull(original.access),
            me.rerere.common.configuration.ConfigurationReference.random(),
        )
        val fixture = Fixture(original, mutableListOf(Screen.Chat(request), Screen.Enterprise))
        val restoration = StateRestorationTester(compose)
        fixture.show(restoration = restoration)
        val replacement = overview(access = access("replacement-session"), revision = 2L)
        fixture.state.value = replacement
        compose.waitUntil(5_000) { fixture.vm.overview.value == replacement }
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.runOnIdle { assertEquals(listOf(Screen.Startup()), fixture.backStack) }
    }

    @Test
    fun firstEnrollmentAfterStateRestorationCannotReturnToThePersonalChat() {
        val request = ConversationOpenRequest.NewDraft(
            kotlin.uuid.Uuid.random(), RealmAccess.Personal,
            me.rerere.common.configuration.ConfigurationReference.random(),
        )
        val fixture = Fixture(overview(access = null), mutableListOf(Screen.Chat(request), Screen.Enterprise))
        val joined = overview(revision = 2L)
        val confirmation = EnterpriseJoinConfirmation(kotlin.uuid.Uuid.random(), "https://core.example")
        coEvery { fixture.service.join("test enrollment") } returns confirmation
        coEvery { fixture.service.confirmJoin(confirmation) } coAnswers {
            fixture.state.value = joined
            EnterpriseSynchronizationCommandResult.COMPLETED
        }
        val restoration = StateRestorationTester(compose)
        fixture.show(restoration = restoration)
        click(R.string.enterprise_join_paste)
        compose.onNode(hasSetTextAction()).performTextInput("test enrollment")
        compose.onNodeWithText(text(R.string.enterprise_join_submit)).performClick()
        compose.waitUntil(5_000) { fixture.vm.joinConfirmation.value == confirmation }
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        compose.waitUntil(5_000) { fixture.vm.overview.value == joined && !fixture.vm.busy.value }
        compose.runOnIdle { assertEquals(listOf(Screen.Chat(request), Screen.Enterprise), fixture.backStack) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.runOnIdle { assertEquals(listOf(Screen.Startup()), fixture.backStack) }
        coVerify(exactly = 1) { fixture.service.confirmJoin(confirmation) }
    }

    @Test
    fun selectionRoundTripAfterStateRestorationInterceptsSystemBack() {
        val original = overview()
        val fixture = Fixture(original, mutableListOf(Screen.Setting, Screen.Enterprise))
        val restoration = StateRestorationTester(compose)
        fixture.show(restoration = restoration)
        fixture.state.value = overview(access = null, revision = 2L)
        compose.waitUntil(5_000) { fixture.vm.overview.value == fixture.state.value }
        fixture.state.value = original.copy(selection = RealmSelection(requireNotNull(original.access), 3L))
        compose.waitUntil(5_000) { fixture.vm.overview.value == fixture.state.value }
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(listOf(Screen.Startup()), fixture.backStack) }
    }

    @Test
    fun unchangedSelectionAfterStateRestorationReturnsToTheOpeningPage() {
        val fixture = Fixture(overview(), mutableListOf(Screen.Setting, Screen.Enterprise))
        val restoration = StateRestorationTester(compose)
        fixture.show(restoration = restoration)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.runOnIdle { assertEquals(listOf(Screen.Setting), fixture.backStack) }
    }

    @Test
    fun syncFeedbackDoesNotSurviveTheEnterpriseSelectionThatProducedIt() {
        val original = overview()
        val fixture = Fixture(original)
        coEvery { fixture.service.synchronize(requireNotNull(original.access)) } returns net.weero.measix.pilot.service.EnterpriseSynchronizationCommandResult.COMPLETED
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
            compose.onNodeWithTag("enterprise-page-menu").performClick()
            compose.onNodeWithText(text(R.string.enterprise_exit)).performClick()
            compose.waitUntil(5_000) { fixture.vm.exitRequest.value != null }
            val confirmation = text(R.string.enterprise_exit_confirm, "Original enterprise")
            compose.onNodeWithText(confirmation).assertIsDisplayed()
            capturePage("enterprise-exit-confirmation.png")
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

    private fun capturePage(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Window transitions are rendered outside Compose's idle tracking.
        android.os.SystemClock.sleep(1_000)
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: compose.activity.cacheDir
        java.io.File(directory, name).outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
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
        canEnterEnterprise = access != null,
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
        val recentUpdates: EnterpriseUpdatesUiModel = EnterpriseUpdatesUiModel("Asia/Shanghai", emptyList()),
    ) {
        val state = MutableStateFlow(initial)
        val service = mockk<EnterpriseApplicationService>()
        val workspaceSummary = MutableStateFlow<RemoteWorkspaceSummary?>(null)
        val workspaceQuery = MutableStateFlow<RemoteWorkspaceQueryState?>(null)
        val conversations = mockk<ConversationApplicationService>()
        private val conversationQuery = mockk<ConversationQueryService>()
        val configurationQueries = mockk<ConfigurationQueryService>()
        private val isolated = koinApplication { modules(module {
            single { conversations }
            single { conversationQuery }
            single { configurationQueries }
        }) }.also { isolatedContexts += it }
        lateinit var vm: EnterpriseVM

        fun show(withVerticalHinge: Boolean = false, restoration: StateRestorationTester? = null) {
            every { conversationQuery.observeCurrentSelection() } returns state.map { it.selection }
            every { service.observe() } returns state
            every { service.runtimeUsageChanges() } returns emptyFlow()
            coEvery { service.recentUpdates(any(), any()) } returns recentUpdates
            compose.runOnUiThread {
                vm = EnterpriseVM(service, mockk { every { summary } returns workspaceSummary; every { queryState } returns workspaceQuery; coEvery { refresh(any()) } returns Unit })
                viewModels.put("enterprise", vm)
            }
            val navigator = Navigator(backStack)
            val content: @Composable () -> Unit = {
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
                KoinIsolatedContext(isolated) { MaterialTheme {
                    CompositionLocalProvider(
                        LocalNavController provides navigator,
                        LocalToaster provides toaster,
                        LocalAdaptiveLayoutInfo provides adaptive,
                        net.weero.measix.pilot.ui.context.LocalSettings provides net.weero.measix.pilot.data.datastore.Settings(),
                    ) {
                        Toaster(state = toaster, alignment = Alignment.TopCenter)
                        EnterprisePage(vm = vm)
                    }
                } }
            }
            if (restoration == null) compose.setContent(content) else restoration.setContent(content)
            compose.waitUntil(5_000) { vm.overview.value == state.value }
        }
    }
}
