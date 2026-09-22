package net.weero.measix.pilot.ui.pages.enterprise

import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.*
import org.junit.Assert.*
import org.junit.Test

class EnterpriseVMTest {
    @Test fun `cached budget refresh is neutral until a completed request actually fails`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val packet = exampleEnterprisePackage()
            val access = RealmAccess.Enterprise(packet.identity.scope, "session")
            val selection = RealmSelection(access, 1)
            val overview = MutableStateFlow(EnterpriseOverview(selection, EnterpriseSessionPhase.READY,
                "Example", "Member", access, 1, 1000, null, null, false,
                platformOrigin = "https://old.example"))
            val changes = MutableSharedFlow<RealmAccess.Enterprise>(extraBufferCapacity = 1)
            val cached = budgetView(access.scope.userId, "2026-09-20T12:00:00Z")
            val retry = CompletableDeferred<EnterpriseBudgetSummaryUiModel>()
            var calls = 0
            val service = mockk<EnterpriseApplicationService>()
            every { service.observe() } returns overview
            every { service.runtimeUsageChanges() } returns changes
            coEvery { service.budgets(selection, access) } coAnswers {
                calls++
                if (calls == 1) cached else retry.await()
            }
            val vm = EnterpriseVM(service)
            store.put("enterprise", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.budgets.collect {} }
            runCurrent()

            vm.refreshBudgets()
            runCurrent()
            assertEquals(cached, vm.budgets.value?.value)
            assertFalse(vm.budgets.value?.stale == true)

            vm.refreshBudgets()
            runCurrent()
            assertTrue(vm.budgets.value?.loading == true)
            assertEquals(cached, vm.budgets.value?.value)
            assertFalse(vm.budgets.value?.stale == true)
            assertNull(vm.budgets.value?.failure)

            retry.completeExceptionally(IllegalStateException("upstream unavailable"))
            runCurrent()
            assertFalse(vm.budgets.value?.loading == true)
            assertTrue(vm.budgets.value?.stale == true)
            assertEquals(cached, vm.budgets.value?.value)
            assertTrue(vm.budgets.value?.failure?.contains("upstream unavailable") == true)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun `runtime completion during budget load queues a fresh projection`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val packet = exampleEnterprisePackage()
            val access = RealmAccess.Enterprise(packet.identity.scope, "session")
            val selection = RealmSelection(access, 1)
            val overview = MutableStateFlow(EnterpriseOverview(selection, EnterpriseSessionPhase.READY,
                "Example", "Member", access, 1, 1000, null, null, false,
                platformOrigin = "https://old.example"))
            val changes = MutableSharedFlow<RealmAccess.Enterprise>(extraBufferCapacity = 1)
            val first = CompletableDeferred<EnterpriseBudgetSummaryUiModel>()
            val firstView = budgetView(access.scope.userId, "2026-09-20T12:00:00Z")
            val secondView = budgetView(access.scope.userId, "2026-09-20T12:00:01Z")
            var calls = 0
            val service = mockk<EnterpriseApplicationService>()
            every { service.observe() } returns overview
            every { service.runtimeUsageChanges() } returns changes
            coEvery { service.budgets(selection, access) } coAnswers {
                calls++
                if (calls == 1) first.await() else secondView
            }
            val vm = EnterpriseVM(service)
            store.put("enterprise", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.budgets.collect {} }
            runCurrent()

            vm.refreshBudgets()
            runCurrent()
            assertTrue(vm.budgets.value?.loading == true)
            changes.emit(access)
            runCurrent()
            first.complete(firstView)
            runCurrent()

            assertEquals(2, calls)
            assertEquals(secondView.asOf, vm.budgets.value?.value?.asOf)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun `address change cancels the old budget target and cannot publish its failure`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val packet = exampleEnterprisePackage()
            val access = RealmAccess.Enterprise(packet.identity.scope, "session")
            val selection = RealmSelection(access, 1)
            val overview = MutableStateFlow(EnterpriseOverview(selection, EnterpriseSessionPhase.READY,
                "Example", "Member", access, 1, 1000, null, null, false,
                platformOrigin = "https://old.example"))
            val changes = MutableSharedFlow<RealmAccess.Enterprise>(extraBufferCapacity = 1)
            val old = CompletableDeferred<EnterpriseBudgetSummaryUiModel>()
            val current = budgetView(access.scope.userId, "2026-09-20T12:00:01Z")
            var calls = 0
            val service = mockk<EnterpriseApplicationService>()
            every { service.observe() } returns overview
            every { service.runtimeUsageChanges() } returns changes
            coEvery { service.budgets(selection, access) } coAnswers {
                calls++
                if (calls == 1) old.await() else current
            }
            val vm = EnterpriseVM(service)
            store.put("enterprise", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.budgets.collect {} }
            runCurrent()

            vm.refreshBudgets()
            runCurrent()
            overview.value = overview.value.copy(platformOrigin = "https://new.example")
            runCurrent()
            vm.refreshBudgets()
            runCurrent()

            assertEquals(2, calls)
            assertEquals(current, vm.budgets.value?.value)
            old.completeExceptionally(java.io.InterruptedIOException("old origin timeout"))
            runCurrent()
            assertEquals(current, vm.budgets.value?.value)
            assertNull(vm.budgets.value?.failure)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun `opening a portal clears stale success and preserves an unexpected failure diagnostic`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val packet = exampleEnterprisePackage()
            val access = RealmAccess.Enterprise(packet.identity.scope, "session")
            val selection = RealmSelection(access, 1)
            val overview = MutableStateFlow(EnterpriseOverview(selection, EnterpriseSessionPhase.READY,
                "Example", "Member", access, 1, 1000, null, null, false,
                platformOrigin = "https://core.example"))
            val service = mockk<EnterpriseApplicationService>()
            every { service.observe() } returns overview
            coEvery { service.synchronize(access) } returns Unit
            val vm = EnterpriseVM(service)
            store.put("enterprise", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.notice.collect {} }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.error.collect {} }
            runCurrent()

            vm.synchronize()
            runCurrent()
            assertEquals(net.weero.measix.pilot.R.string.enterprise_sync_completed, vm.notice.value)
            vm.showPortal()
            runCurrent()
            assertNull(vm.notice.value)

            val portal = requireNotNull(vm.portal.value)
            vm.portalFailed(portal, IllegalArgumentException("specific_portal_failure"))
            runCurrent()
            assertEquals(listOf("specific_portal_failure"), vm.error.value?.arguments)
            assertTrue(vm.error.value?.detail?.contains("IllegalArgumentException") == true)
            assertTrue(vm.error.value?.detail?.contains("specific_portal_failure") == true)

            vm.showPortal()
            val mismatch = requireNotNull(vm.portal.value)
            vm.portalFailed(mismatch, EnterpriseConfigurationException("platform_portal_exchange_url_mismatch"))
            runCurrent()
            assertEquals(net.weero.measix.pilot.R.string.enterprise_portal_origin_mismatch,
                vm.error.value?.resource)
            assertTrue(vm.error.value?.arguments.isNullOrEmpty())
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun `expired enrollment has a dedicated actionable message`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val overview = MutableStateFlow(EnterpriseOverview(
                RealmSelection(RealmAccess.Personal, 1), EnterpriseSessionPhase.SIGNED_OUT,
                null, null, null, null, null, null, null, false,
            ))
            val local = EnterpriseJoinConfirmation(kotlin.uuid.Uuid.random(), "https://platform.example")
            val remote = EnterpriseJoinConfirmation(kotlin.uuid.Uuid.random(), "https://platform.example")
            val service = mockk<EnterpriseApplicationService>()
            every { service.observe() } returns overview
            coEvery { service.join("local-expired") } returns local
            coEvery { service.confirmJoin(local) } throws EnterpriseConfigurationException("enrollment_expired")
            coEvery { service.join("remote-expired") } returns remote
            coEvery { service.confirmJoin(remote) } throws PlatformHttpException(401,
                PlatformProblem("about:blank", "Enrollment code expired", 401, "enrollment_expired"),
                "Enrollment code expired")
            val vm = EnterpriseVM(service)
            store.put("enterprise", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.error.collect {} }

            vm.join("local-expired")
            runCurrent()
            vm.confirmJoin()
            runCurrent()
            assertEquals(net.weero.measix.pilot.R.string.enterprise_enrollment_expired, vm.error.value?.resource)

            vm.join("remote-expired")
            runCurrent()
            vm.confirmJoin()
            runCurrent()
            assertEquals(net.weero.measix.pilot.R.string.enterprise_enrollment_expired, vm.error.value?.resource)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun `platform enrollment conflict is actionable and does not expose a runtime class name`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val overview = MutableStateFlow(EnterpriseOverview(
                RealmSelection(RealmAccess.Personal, 1), EnterpriseSessionPhase.SIGNED_OUT,
                null, null, null, null, null, null, null, false,
            ))
            val confirmation = EnterpriseJoinConfirmation(kotlin.uuid.Uuid.random(), "https://platform.example")
            val service = mockk<EnterpriseApplicationService>()
            every { service.observe() } returns overview
            coEvery { service.join("payload") } returns confirmation
            coEvery { service.confirmJoin(confirmation) } throws PlatformHttpException(409,
                PlatformProblem("about:blank", "Installation is already bound to another user", 409,
                    "installation_user_conflict"),
                "Installation is already bound to another user")
            val vm = EnterpriseVM(service)
            store.put("enterprise", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.error.collect {} }

            vm.join("payload")
            runCurrent()
            vm.confirmJoin()
            runCurrent()

            assertEquals(net.weero.measix.pilot.R.string.enterprise_installation_user_conflict, vm.error.value?.resource)
            assertEquals("HTTP 409: installation_user_conflict: Installation is already bound to another user",
                vm.error.value?.detail)

            val used = EnterpriseJoinConfirmation(kotlin.uuid.Uuid.random(), "https://platform.example")
            coEvery { service.join("used") } returns used
            coEvery { service.confirmJoin(used) } throws PlatformHttpException(409,
                PlatformProblem("about:blank", "Enrollment code already used", 409, "enrollment_already_used"),
                "Enrollment code already used")
            vm.join("used")
            runCurrent()
            vm.confirmJoin()
            runCurrent()
            assertEquals(net.weero.measix.pilot.R.string.enterprise_enrollment_already_used, vm.error.value?.resource)
            assertEquals("HTTP 409: enrollment_already_used: Enrollment code already used", vm.error.value?.detail)

            val deleted = EnterpriseJoinConfirmation(kotlin.uuid.Uuid.random(), "https://platform.example")
            coEvery { service.join("deleted") } returns deleted
            coEvery { service.confirmJoin(deleted) } throws PlatformHttpException(401,
                PlatformProblem("about:blank", "Unauthorized", 401,
                    EnterpriseRuntimeProblemCodes.IDENTITY_DELETED, "Enterprise identity was deleted"),
                "Enterprise identity was deleted")
            vm.join("deleted")
            runCurrent()
            vm.confirmJoin()
            runCurrent()
            assertEquals(net.weero.measix.pilot.R.string.enterprise_identity_deleted, vm.error.value?.resource)
            assertEquals("HTTP 401: enterprise_identity_deleted: Enterprise identity was deleted", vm.error.value?.detail)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun `portal failure retains actionable diagnostics and cannot overwrite a replacement request`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val packet = exampleEnterprisePackage()
            val access = RealmAccess.Enterprise(packet.identity.scope, "session")
            val selection = RealmSelection(access, 1)
            val overview = MutableStateFlow(EnterpriseOverview(selection, EnterpriseSessionPhase.READY,
                "Example", "Member", access, 1, 1000, null, null, false))
            val service = mockk<EnterpriseApplicationService>()
            every { service.observe() } returns overview
            val vm = EnterpriseVM(service)
            store.put("enterprise", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.error.collect {} }
            runCurrent()
            vm.showPortal()
            val original = requireNotNull(vm.portal.value)
            val unavailable = net.weero.measix.pilot.service.portal.PortalHostUnavailable("vendor.webview 130", listOf("REQUIRED_FEATURE"))
            vm.portalFailed(original, unavailable)
            runCurrent()
            assertNull(vm.portal.value)
            assertEquals(net.weero.measix.pilot.R.string.enterprise_portal_unavailable, vm.error.value?.resource)
            assertEquals(listOf("vendor.webview 130", "REQUIRED_FEATURE"), vm.error.value?.arguments)
            vm.showPortal()
            val replacement = requireNotNull(vm.portal.value)
            vm.portalFailed(original, unavailable)
            runCurrent()
            assertEquals(replacement, vm.portal.value)
            assertNull(vm.error.value)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun `abnormal portal closure is visible while user closure is silent`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val packet = exampleEnterprisePackage()
            val access = RealmAccess.Enterprise(packet.identity.scope, "session")
            val selection = RealmSelection(access, 1)
            val overview = MutableStateFlow(EnterpriseOverview(selection, EnterpriseSessionPhase.READY,
                "Example", "Member", access, 1, 1000, null, null, false))
            val service = mockk<EnterpriseApplicationService>()
            every { service.observe() } returns overview
            val vm = EnterpriseVM(service)
            store.put("enterprise", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.error.collect {} }
            runCurrent()

            vm.showPortal()
            val failed = requireNotNull(vm.portal.value)
            vm.portalClosed(failed, net.weero.measix.pilot.service.portal.PortalClosure(
                "document", net.weero.measix.pilot.service.portal.PortalCloseReason.HOST_FAILURE))
            runCurrent()
            assertNull(vm.portal.value)
            assertEquals(net.weero.measix.pilot.R.string.enterprise_portal_open_failed, vm.error.value?.resource)
            assertEquals(listOf("host_failure"), vm.error.value?.arguments)

            vm.showPortal()
            val dismissed = requireNotNull(vm.portal.value)
            vm.portalClosed(dismissed, net.weero.measix.pilot.service.portal.PortalClosure(
                "document", net.weero.measix.pilot.service.portal.PortalCloseReason.USER_REQUEST))
            runCurrent()
            assertNull(vm.portal.value)
            assertNull(vm.error.value)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun `space changes hide prior errors and reject delayed sync failures`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val packet = exampleEnterprisePackage()
            val access = RealmAccess.Enterprise(packet.identity.scope, "session")
            val selection = RealmSelection(access, 1)
            val overview = MutableStateFlow(EnterpriseOverview(selection, EnterpriseSessionPhase.READY,
                "Example", "Member", access, 1, 1000, null, null, false))
            val service = mockk<EnterpriseApplicationService>()
            every { service.observe() } returns overview
            val syncing = CompletableDeferred<Unit>()
            coEvery { service.synchronize(access) } coAnswers { syncing.await() }
            val vm = EnterpriseVM(service)
            store.put("enterprise", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.error.collect {} }
            runCurrent()
            vm.scanFailed()
            runCurrent()
            assertNotNull(vm.error.value)
            overview.value = overview.value.copy(selection = RealmSelection(RealmAccess.Personal, 2))
            runCurrent()
            assertNull(vm.error.value)
            overview.value = overview.value.copy(selection = RealmSelection(access, 3))
            runCurrent()
            vm.synchronize()
            runCurrent()
            overview.value = overview.value.copy(selection = RealmSelection(RealmAccess.Personal, 4))
            runCurrent()
            syncing.completeExceptionally(IllegalStateException("late failure"))
            runCurrent()
            assertNull(vm.error.value)
            assertFalse(vm.busy.value)
            overview.value = overview.value.copy(selection = RealmSelection(access, 5))
            runCurrent()
            assertNull(vm.error.value)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun `budget projection requires every capability exactly once`() {
        val view = platformBudgetView("usr_12345678-1234-4234-8234-123456789abc", "2026-09-20T12:00:00Z")
        assertThrows(IllegalArgumentException::class.java) {
            view.copy(items = view.items.dropLast(1) + view.items.first())
        }
    }

    @Test fun `configuration details preserve unset and unavailable references without sensitive fields`() {
        val packet = exampleEnterprisePackage()
        val image = EnterpriseImageGenerationResource(
            id = "img_12345678-1234-4234-8234-123456789abc",
            name = "Managed image",
            modelId = "private-upstream-model",
            enabled = false,
            protocol = me.rerere.ai.provider.images.ImageGenerationClientProtocol.OPENAI_IMAGES_GENERATIONS,
            maxImagesPerRequest = 3,
            allowedSizes = listOf("1024x1024"),
        )
        val configuration = packet.configuration.copy(
            imageGenerators = listOf(image),
            defaults = packet.configuration.defaults.copy(
                chatModelId = "mdl_missing",
                imageGenerationModelId = image.id,
            ),
            assistants = packet.configuration.assistants.mapIndexed { index, assistant ->
                if (index == 0) assistant.copy(systemPrompt = "private-system-prompt") else assistant
            },
            memorySeeds = packet.configuration.memorySeeds + EnterpriseMemorySeed("seed_private", "private-memory-seed"),
        )

        val projected = projectEnterpriseConfigurationDetails(
            identity = packet.identity,
            phase = EnterpriseSessionPhase.OFFLINE,
            generation = 12,
            lastSyncMillis = 1_000,
            platformOrigin = "https://core.example",
            configuration = configuration,
        )

        assertEquals(10, projected.defaults.size)
        assertEquals(
            EnterpriseConfigurationReferenceState.UNAVAILABLE,
            projected.defaults.single { it.kind == EnterpriseConfigurationDefaultKind.CHAT_MODEL }.state,
        )
        val imageDefault = projected.defaults.single { it.kind == EnterpriseConfigurationDefaultKind.IMAGE_GENERATION }
        assertEquals("Managed image", imageDefault.displayName)
        assertEquals(EnterpriseConfigurationReferenceState.UNAVAILABLE, imageDefault.state)
        assertEquals(10, projected.resources.size)
        assertEquals(
            configuration.memorySeeds.size,
            projected.resources.single { it.kind == EnterpriseConfigurationResourceKind.MEMORY_SEED }.itemCount,
        )
        assertTrue(projected.resources.single { it.kind == EnterpriseConfigurationResourceKind.IMAGE_GENERATOR }
            .items.single().facts.any { it.kind == EnterpriseConfigurationResourceFactKind.MAX_IMAGES && it.value == "3" })
        assertFalse(projected.toString().contains(image.id))
        assertFalse(projected.toString().contains("mdl_missing"))
        assertFalse(projected.toString().contains("OPENAI_IMAGES_GENERATIONS"))
        assertFalse(projected.toString().contains("private-upstream-model"))
        assertFalse(projected.toString().contains("private-system-prompt"))
        assertFalse(projected.toString().contains("private-memory-seed"))
    }
}

private fun budgetView(userId: String, asOf: String) = projectEnterpriseBudget(platformBudgetView(userId, asOf))

private fun platformBudgetView(userId: String, asOf: String) = PlatformUserBudgetView(
    userId = userId,
    timezone = "Asia/Shanghai",
    items = listOf(
        PlatformBudgetCapability.MODEL,
        PlatformBudgetCapability.TTS,
        PlatformBudgetCapability.ASR,
        PlatformBudgetCapability.MCP,
        PlatformBudgetCapability.IMAGE_GENERATION,
    ).map { capability ->
        PlatformBudgetCapabilityView(
            capability = capability,
            mode = PlatformBudgetMode.UNLIMITED,
            source = PlatformBudgetSource.DEFAULT,
            revision = 0,
            effectiveFrom = asOf,
            asOf = asOf,
            inFlightRequests = 0,
            limits = emptyList(),
            usageMeters = if (capability == PlatformBudgetCapability.TTS) listOf(
                PlatformMeterQuantity(
                    meter = PlatformUsageMeter.CHARACTERS,
                    quantity = "1200",
                    completeness = PlatformUsageCompleteness.EXACT,
                ),
            ) else emptyList(),
            status = PlatformBudgetStatus.AVAILABLE,
        )
    },
    asOf = asOf,
)
