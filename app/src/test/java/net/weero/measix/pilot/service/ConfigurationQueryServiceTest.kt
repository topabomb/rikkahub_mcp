package net.weero.measix.pilot.service

import io.mockk.*
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.enterprise.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigurationQueryServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `personal selection cannot receive enterprise definition after session expires during configuration wait`() = runTest {
        var now = 1000L
        val fixture = fixture(exampleEnterprisePackage()) { now }
        val selection = fixture.sessions.switchRealm(RealmSwitchRequest(fixture.selection, RealmAccess.Personal)) {}
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.beforeConfiguration = { entered.complete(Unit); release.await() }
        val target = fixture.target(fixture.packet.configuration.starters.first().id).copy(selection = selection)
        val read = async { runCatching { fixture.queries.readEnterpriseStarterDetails(target) } }
        entered.await()
        now = 3000L
        release.complete(Unit)
        val error = read.await().exceptionOrNull()
        assertTrue(error is EnterpriseConfigurationException)
        assertEquals("enterprise_data_access_unavailable", (error as EnterpriseConfigurationException).reason)
    }

    @Test fun `configuration details read disabled Starter while task selection continues to reject it`() = runTest {
        val original = exampleEnterprisePackage()
        val starter = original.configuration.starters.first().copy(enabled = false)
        val fixture = fixture(original.copy(configuration = original.configuration.copy(starters = listOf(starter))))
        val detail = fixture.queries.readEnterpriseStarterDetails(fixture.target(starter.id))
        assertEquals(starter.details(isDraft = false), detail)
        val error = runCatching { fixture.queries.readEnterpriseStarterDetails(EnterpriseStarterTarget(
            fixture.selection, fixture.packet.configuration.generation, fixture.packet.identity.reference(starter.id))) }.exceptionOrNull()
        assertEquals(StarterOpeningIssue.UNAVAILABLE, (error as StarterOpeningException).issue)
    }

    @Test fun `personal configuration details read disabled Starter and assistant without enabling task selection`() = runTest {
        val original = exampleEnterprisePackage()
        val starter = original.configuration.starters.first { it.enabled }.copy(enabled = false)
        val packet = original.copy(configuration = original.configuration.copy(starters = listOf(starter),
            defaults = original.configuration.defaults.copy(assistantId = null),
            assistants = original.configuration.assistants.map { if (it.id == starter.assistantId) it.copy(enabled = false) else it }))
        val fixture = fixture(packet)
        val selection = fixture.sessions.switchRealm(RealmSwitchRequest(fixture.selection, RealmAccess.Personal)) {}
        assertEquals(starter.details(isDraft = false),
            fixture.queries.readEnterpriseStarterDetails(fixture.target(starter.id).copy(selection = selection)))
        val resolved = ConfigurationResolver.resolve(UserSettingsDocument.empty(), packet.identity.scope, fixture.sessions.state.value)
        assertTrue(resolved.availableStarters().isEmpty())
    }

    private suspend fun fixture(packet: EnterprisePackage, now: () -> Long = { 1000L }): Fixture {
        val sessions = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder()), now)
        sessions.enrollFixture(packet, Instant.ofEpochMilli(3000L))
        return Fixture(packet, sessions, requireNotNull(sessions.readPresentation().selection))
    }

    private class Fixture(val packet: EnterprisePackage, val sessions: EnterpriseSessionController,
        val selection: RealmSelection) {
        var beforeConfiguration: suspend () -> Unit = {}
        private val settings = mockk<SettingsStore>()
        val queries = ConfigurationQueryService(settings, sessions, ApplicationRecoveryGate().apply { ready() })
        init {
            coEvery { settings.withResolvedConfiguration<Any?>(any(), any(), any()) } coAnswers {
                beforeConfiguration()
                thirdArg<suspend (ResolvedConfiguration) -> Any?>()(ConfigurationResolver.resolve(
                    UserSettingsDocument.empty(), firstArg(), secondArg()))
            }
        }
        fun target(id: String) = EnterpriseResourceDetailTarget(selection, selection.access as RealmAccess.Enterprise,
            packet.configuration.generation, packet.identity.reference(id), EnterpriseConfigurationResourceKind.STARTER)
    }
}
