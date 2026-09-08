package net.weero.measix.pilot.data.enterprise

import java.io.IOException
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnterpriseFeedPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `withdrawal survives repeated seed logout and reopen without configuration changes`() = runTest {
        val root = temporary.newFolder()
        val packet = exampleEnterprisePackage()
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(root))
        val applied = sessions.enrollFixture(packet)
        val access = sessions.captureRealmAccess(packet.identity.scope) as RealmAccess.Enterprise
        val original = applied.manifest.feeds.single()
        val id = sessions.listFeed(RealmSelection(access, sessions.selectionRevision.value), EnterpriseFeedQuery()).body.items.single().enterpriseUpdateId
        sessions.changeFeed(access, original.revision, EnterpriseFeedCommand.Withdraw(id))
        val changed = sessions.available()
        assertEquals(applied.configuration, changed.configuration)
        assertEquals(applied.manifest.applied, changed.manifest.applied)
        assertEquals(applied.manifest.session, changed.manifest.session)
        assertEquals(original.publicRevision + 1, changed.manifest.feeds.single().publicRevision)
        expectReason("enterprise_feed_changed") { sessions.changeFeed(access, original.revision, EnterpriseFeedCommand.Withdraw(id)) }
        // A changed seed at the same generation is neither a configuration conflict nor a replacement Feed.
        sessions.synchronize(access, packet.copy(feedSeed = packet.feedSeed!!.copy(items = emptyList())))
        assertEquals(access, sessions.captureRealmAccess(packet.identity.scope))
        assertEquals(changed.manifest.feeds, sessions.available().manifest.feeds)
        sessions.finishExit(sessions.beginExit(requireNotNull(sessions.captureExitRequest())))
        val reopened = EnterpriseSessionController(EnterpriseAppliedStore(root))
        reopened.recover()
        reopened.enrollFixture(packet)
        expectReason("enterprise_data_access_unavailable") { reopened.listFeed(RealmSelection(access, reopened.selectionRevision.value), EnterpriseFeedQuery()) }
        val nextAccess = reopened.captureRealmAccess(packet.identity.scope) as RealmAccess.Enterprise
        assertTrue(reopened.listFeed(RealmSelection(nextAccess, reopened.selectionRevision.value), EnterpriseFeedQuery()).body.items.isEmpty())
        assertEquals(changed.manifest.feeds, reopened.available().manifest.feeds)
        reopened.selectPersonalFixture()
        expectReason("enterprise_data_access_unavailable") { reopened.listFeed(RealmSelection(nextAccess, reopened.selectionRevision.value), EnterpriseFeedQuery()) }
    }

    @Test
    fun `failed Feed commit preserves prior public content across reopen`() = runTest {
        val root = temporary.newFolder()
        var failCommit = false
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(root) {
            if (failCommit && it == EnterpriseStorageCheckpoint.BEFORE_MANIFEST_COMMIT) throw IOException("injected")
        })
        val packet = exampleEnterprisePackage()
        val applied = sessions.enrollFixture(packet)
        val access = sessions.captureRealmAccess(packet.identity.scope) as RealmAccess.Enterprise
        val before = sessions.listFeed(RealmSelection(access, sessions.selectionRevision.value), EnterpriseFeedQuery())
        failCommit = true
        try {
            sessions.changeFeed(access, applied.manifest.feeds.single().revision,
                EnterpriseFeedCommand.Withdraw(before.body.items.single().enterpriseUpdateId))
            fail("failed manifest must not publish Feed")
        } catch (_: IOException) { }
        assertEquals(applied, sessions.available())
        val reopened = EnterpriseSessionController(EnterpriseAppliedStore(root))
        assertEquals(applied, reopened.recover())
        assertEquals(before, reopened.listFeed(RealmSelection(access, reopened.selectionRevision.value), EnterpriseFeedQuery()))
    }

    @Test
    fun `new Feed reference must be readable before manifest publication but retained corrupt Feed does not block exit`() = runTest {
        val root = temporary.newFolder()
        val store = EnterpriseAppliedStore(root)
        val sessions = EnterpriseSessionController(store)
        val packet = exampleEnterprisePackage()
        val applied = sessions.enrollFixture(packet)
        val original = applied.manifest.feeds.single()
        val next = store.prepareFeed(packet.identity.scope, EnterpriseFeed.change(store.readFeed(original),
            EnterpriseFeedCommand.Withdraw(packet.feedSeed!!.items.single().enterpriseUpdateId), Instant.parse("2026-09-07T12:00:00Z")))
        File(root, "feed-revisions/${next.revision}/feed.json").writeText("corrupted")
        assertThrows(EnterpriseStorageException::class.java) { store.commit(applied.manifest.copy(feeds = listOf(next))) }
        assertEquals(applied.manifest, store.readManifest())
        File(root, "feed-revisions/${original.revision}/feed.json").writeText("corrupted")
        sessions.finishExit(sessions.beginExit(requireNotNull(sessions.captureExitRequest())))
        assertEquals(EnterpriseSessionPhase.SIGNED_OUT, store.readManifest().phase)
        assertEquals(listOf(original), store.readManifest().feeds)
    }

    @Test
    fun `Feed staging cancellation rolls back while owned manifest cancellation finishes publication`() = runTest {
        for (point in listOf(EnterpriseStorageCheckpoint.FEED_STAGED, EnterpriseStorageCheckpoint.MANIFEST_WRITTEN)) {
            val root = temporary.newFolder()
            var pause = false
            val entered = CompletableDeferred<Unit>()
            val resume = CountDownLatch(1)
            val store = EnterpriseAppliedStore(root) {
                if (pause && it == point) {
                    entered.complete(Unit)
                    check(resume.await(10, TimeUnit.SECONDS))
                }
            }
            val sessions = EnterpriseSessionController(store)
            val packet = exampleEnterprisePackage()
            val before = sessions.enrollFixture(packet)
            val access = sessions.captureRealmAccess(packet.identity.scope) as RealmAccess.Enterprise
            pause = true
            val operation = launch {
                sessions.changeFeed(access, before.manifest.feeds.single().revision,
                    EnterpriseFeedCommand.Withdraw(packet.feedSeed!!.items.single().enterpriseUpdateId))
            }
            entered.await()
            operation.cancel()
            resume.countDown()
            operation.join()
            assertTrue(operation.isCancelled)
            assertEquals(sessions.available().manifest, store.readManifest())
            assertEquals(before.manifest.applied, sessions.available().manifest.applied)
            if (point == EnterpriseStorageCheckpoint.FEED_STAGED) assertEquals(before, sessions.available())
            else assertTrue(sessions.listFeed(RealmSelection(access, sessions.selectionRevision.value), EnterpriseFeedQuery()).body.items.isEmpty())
            assertEquals(sessions.available(), EnterpriseSessionController(EnterpriseAppliedStore(root)).recover())
            assertEquals(1, File(root, "feed-revisions").listFiles()!!.size)
        }
    }

    private fun EnterpriseSessionController.available() = state.value as EnterpriseState.Available
    private suspend fun expectReason(reason: String, operation: suspend () -> Any?) {
        try { operation(); fail("Expected $reason") }
        catch (error: EnterpriseConfigurationException) { assertEquals(reason, error.reason) }
    }
}
