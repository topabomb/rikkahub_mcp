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
    fun `recent updates reject a revoked selection before returning enterprise content`() = runTest {
        val sessions = mockk<EnterpriseSessionController>()
        val platform = mockk<PlatformEnterpriseService>()
        val access = RealmAccess.Enterprise(
            net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage().identity.scope, "session")
        val selection = RealmSelection(access, 1)
        var current = selection
        val presentation = mockk<EnterprisePresentation> {
            coEvery { this@mockk.selection } answers { current }
        }
        coEvery { sessions.readPresentation() } returns presentation
        coEvery { platform.recoverPlatformAccess() } returns null
        val response = CompletableDeferred<net.weero.measix.pilot.data.enterprise.PlatformEnterpriseUpdateFeed>()
        coEvery { platform.recentUpdates(access) } coAnswers { response.await() }
        val service = EnterpriseApplicationService(
            sessions = sessions,
            synchronization = mockk(relaxed = true),
            exit = mockk(relaxed = true),
            dataReset = mockk(relaxed = true),
            portals = PortalDocumentRegistry(),
            recovery = ApplicationRecoveryGate().apply { ready() },
            scope = backgroundScope,
            media = mockk(relaxed = true),
            terminals = mockk(relaxed = true),
            speech = mockk(relaxed = true),
            platform = platform,
        )
        val read = async {
            try { service.recentUpdates(selection, access); false }
            catch (error: EnterpriseConfigurationException) { error.message == "enterprise_selection_revoked" }
        }
        runCurrent()
        coVerify(exactly = 1) { platform.recentUpdates(access) }
        current = RealmSelection(RealmAccess.Personal, 2)
        val content = "**Notice**\n\nFull enterprise update"
        response.complete(net.weero.measix.pilot.data.enterprise.PlatformEnterpriseUpdateFeed("UTC", List(6) { index ->
            net.weero.measix.pilot.data.enterprise.PlatformEnterpriseUpdateItem(
                "eup_12345678-1234-4234-8234-12345678901$index", "Notice $index", content,
                net.weero.measix.pilot.data.enterprise.PlatformEnterpriseUpdateContentFormat.MARKDOWN,
                net.weero.measix.pilot.data.enterprise.PlatformEnterpriseUpdateCategory.entries[index % 3],
                net.weero.measix.pilot.data.enterprise.PlatformEnterpriseUpdateSeverity.entries[index % 3],
                "2026-09-22T00:00:00Z",
            )
        }, true))
        assertTrue(read.await())
        try { service.recentUpdates(selection, access); org.junit.Assert.fail("stale selection accepted") }
        catch (error: EnterpriseConfigurationException) { assertTrue(error.message == "enterprise_selection_revoked") }
        coVerify(exactly = 1) { platform.recentUpdates(access) }
        val feed = service.recentUpdates(current, access)
        org.junit.Assert.assertEquals(5, feed.items.size)
        org.junit.Assert.assertEquals(content, feed.items.first().content)
        assertTrue(feed.items.first().markdown)
        org.junit.Assert.assertEquals(EnterpriseUpdateCategory.entries, feed.items.take(3).map { it.category })
        org.junit.Assert.assertEquals(EnterpriseUpdateSeverity.entries, feed.items.take(3).map { it.severity })
    }

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
