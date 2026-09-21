package net.weero.measix.pilot.service

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.enterprise.EnterpriseAddressChangeRequest
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.enterprise.EnterprisePresentation
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.PlatformSessionContext
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.portal.PortalDocumentRegistry
import net.weero.measix.pilot.service.portal.PortalMediaStore
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalRuntime
import org.junit.Assert.assertTrue
import org.junit.Test

class EnterpriseApplicationServiceTest {
    @Test
    fun `address change waits for an in flight Portal grant before committing the new route`() = runTest {
        val sessions = mockk<EnterpriseSessionController>()
        val synchronization = mockk<EnterpriseSynchronizationService>(relaxed = true)
        val exit = mockk<EnterpriseExitService>(relaxed = true)
        val dataReset = mockk<EnterpriseDataResetService>(relaxed = true)
        val platform = mockk<PlatformEnterpriseService>()
        val recovery = ApplicationRecoveryGate().apply { ready() }
        val access = RealmAccess.Enterprise(
            net.weero.measix.pilot.data.configuration.ConfigurationScope.Enterprise(
                me.rerere.common.configuration.EnterpriseAuthority(
                    "dep_12345678-1234-4234-8234-123456789abc",
                ),
                "usr_12345678-1234-4234-8234-123456789abc",
            ),
            "session",
        )
        val selection = RealmSelection(access, 1)
        val request = EnterpriseAddressChangeRequest(access, selection)
        val presentation = mockk<EnterprisePresentation> {
            coEvery { this@mockk.selection } returns selection
        }
        val context = mockk<PlatformSessionContext>()
        coEvery { context.platform.connection.origin } returns "https://old.example"
        coEvery { sessions.readPresentation() } returns presentation
        coEvery { sessions.platformContext(access.sessionId) } returns context
        coEvery { platform.recoverPlatformAccess() } returns null
        val grantStarted = CompletableDeferred<Unit>()
        val releaseGrant = CompletableDeferred<Unit>()
        coEvery { platform.createPortalGrant(access) } coAnswers {
            grantStarted.complete(Unit)
            releaseGrant.await()
            throw EnterpriseConfigurationException("portal_test_stop")
        }
        coEvery { platform.changeAddress(request, "https://new.example") } returns access
        val service = EnterpriseApplicationService(
            sessions = sessions,
            synchronization = synchronization,
            exit = exit,
            dataReset = dataReset,
            portals = PortalDocumentRegistry(),
            recovery = recovery,
            scope = backgroundScope,
            media = mockk<PortalMediaStore>(relaxed = true),
            terminals = mockk<WorkspaceTerminalRuntime>(relaxed = true),
            speech = mockk<SpeechApplicationService>(relaxed = true),
            platform = platform,
        )
        runCurrent()

        val portal = launch {
            runCatching { service.openPortal(mockk<Context>(), selection) {} }
        }
        grantStarted.await()
        val change = async { service.changeAddress(request, "https://new.example") }
        runCurrent()
        coVerify(exactly = 0) { platform.changeAddress(request, "https://new.example") }

        releaseGrant.complete(Unit)
        portal.join()
        change.await()
        coVerify(exactly = 1) { platform.changeAddress(request, "https://new.example") }
        assertTrue(change.isCompleted)
    }
}
