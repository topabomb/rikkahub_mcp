package net.weero.measix.pilot.data.enterprise

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import me.rerere.common.configuration.EnterpriseAuthority
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class EnterpriseFeedAndroidTest {
    @Test
    fun identicalUpdateIdsRemainIsolatedAcrossSourcesUsersExitAndActualReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.noBackupFilesDir, "enterprise-feed-test-${Uuid.random()}").apply { check(mkdirs()) }
        val now = Instant.parse("2026-09-07T12:00:00Z").toEpochMilli()
        try {
            val packet = context.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET).use(EnterprisePackageCodec::decode)
            val a = EnterpriseSessionController(EnterpriseAppliedStore(root)) { now }
            val initial = a.enrollLocal(packet.identity, { packet.identity }, { packet })
            val accessA = a.captureRealmAccess(packet.identity.scope) as RealmAccess.Enterprise
            val id = a.listFeed(accessA, EnterpriseFeedQuery()).body.items.single().enterpriseUpdateId
            a.changeFeed(accessA, initial.manifest.feeds.single().revision, EnterpriseFeedCommand.Withdraw(id))
            assertEquals(initial.manifest.applied, (a.state.value as EnterpriseState.Available).manifest.applied)
            a.finishExit(requireNotNull(a.beginExit()))

            val identities = listOf(packet.identity.copy(userId = "other-user"), packet.identity.copy(
                authority = EnterpriseAuthority("local:another", packet.identity.authority.deploymentId)))
            for (identity in identities) {
                val sessions = EnterpriseSessionController(EnterpriseAppliedStore(root)) { now }
                sessions.recover()
                sessions.enrollLocal(identity, { identity }, { packet.copy(identity = identity) })
                val access = sessions.captureRealmAccess(identity.scope) as RealmAccess.Enterprise
                assertEquals(id, sessions.listFeed(access, EnterpriseFeedQuery()).body.items.single().enterpriseUpdateId)
                try { sessions.listFeed(accessA, EnterpriseFeedQuery()); fail("Old principal accessed another Feed") }
                catch (_: EnterpriseConfigurationException) { }
                sessions.finishExit(requireNotNull(sessions.beginExit()))
            }
            val reopened = EnterpriseSessionController(EnterpriseAppliedStore(root)) { now }
            reopened.recover()
            val ready = reopened.enrollLocal(packet.identity, { packet.identity }, { packet })
            assertEquals(3, ready.manifest.feeds.size)
            val newAccess = reopened.captureRealmAccess(packet.identity.scope) as RealmAccess.Enterprise
            assertTrue(reopened.listFeed(newAccess, EnterpriseFeedQuery()).body.items.isEmpty())
            try {
                reopened.changeFeed(accessA, ready.manifest.feeds.first { it.scope == accessA.scope }.revision,
                    EnterpriseFeedCommand.Publish(id))
                fail("Old Session cannot mutate Feed after reentry")
            } catch (error: EnterpriseConfigurationException) { assertEquals("enterprise_data_access_unavailable", error.reason) }
        } finally { root.deleteRecursively() }
    }
}
