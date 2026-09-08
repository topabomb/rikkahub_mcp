package net.weero.measix.pilot.data.enterprise

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class EnterpriseAppliedStateAndroidTest {
    private lateinit var root: File
    private lateinit var example: EnterprisePackage

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.noBackupFilesDir, "enterprise-state-test-${Uuid.random()}").apply { check(mkdirs()) }
        example = context.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET).use(EnterprisePackageCodec::decode)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun committedSelectionAndBindingsSurviveReopenAndExitRemovesCredentials() = runBlocking {
        val privatePacket = example.copy(runtimeBindings = example.runtimeBindings.map {
            if (it.resourceId == "mdl_chat") it.copy(
                protocol = EnterpriseRuntimeProtocol.OPENAI_CHAT, endpoint = "https://example.invalid/v1", credential = "device-test-secret",
            ) else it
        })
        val first = EnterpriseSessionController(EnterpriseAppliedStore(root))
        first.enrollLocal(privatePacket.identity, { privatePacket.identity }, { privatePacket })
        first.switchToPersonal()
        val reopened = EnterpriseSessionController(EnterpriseAppliedStore(root))
        assertEquals(first.state.value, reopened.recover())
        assertEquals(ConfigurationScope.Personal, (reopened.state.value as EnterpriseState.Available).manifest.selectedScope)
        reopened.switchToEnterprise()
        val lease = reopened.captureBindings(example.identity.scope)
        assertEquals("device-test-secret", lease.binding("mdl_chat").credential)
        val token = reopened.beginExit(requireNotNull(reopened.captureExitRequest()))
        lease.release()
        reopened.finishExit(token)
        val afterExit = EnterpriseSessionController(EnterpriseAppliedStore(root)).recover() as EnterpriseState.Available
        assertEquals(EnterpriseSessionPhase.SIGNED_OUT, afterExit.manifest.phase)
        assertTrue(File(root, "revisions").listFiles()!!.isEmpty())
        assertFalse(File(root, "manifest.json").readText().contains("device-test-secret"))
    }

    @Test
    fun actualAtomicFileRenameFailureDoesNotPublishNewConfiguration() = runBlocking {
        var breakRename = false
        val store = EnterpriseAppliedStore(root) {
            if (breakRename && it == EnterpriseStorageCheckpoint.MANIFEST_WRITTEN) {
                check(File(root, "manifest.json.new").renameTo(File(root, "unpublished-manifest")))
            }
        }
        val controller = EnterpriseSessionController(store)
        val first = controller.enrollLocal(example.identity, { example.identity }, { example })
        val original = controller.state.value
        breakRename = true
        try {
            controller.synchronize(RealmAccess.Enterprise(example.identity.scope, first.manifest.session!!.id),
                example.copy(configuration = example.configuration.copy(generation = 2)))
            fail("Broken rename must reject publication")
        } catch (_: EnterpriseStorageException) { }
        assertEquals(original, controller.state.value)
        assertEquals(original, EnterpriseSessionController(EnterpriseAppliedStore(root)).recover())
    }

    @Test
    fun manifestMigrationPreservesAppliedFactsAndRevokedExitSurvivesReopen() = runBlocking {
        val store = EnterpriseAppliedStore(root)
        val controller = EnterpriseSessionController(store) { 1000L }
        val original = controller.enrollLocal(example.identity, { example.identity }, { example })
        val bindings = store.bindings(original.manifest)
        val json = EnterprisePackageCodec.json
        val legacy = JsonObject(json.encodeToJsonElement(original.manifest).jsonObject.toMutableMap().apply {
            this["schemaVersion"] = JsonPrimitive(2)
            remove("exitReason")
        }).toString().toByteArray()
        val manifestFile = File(root, "manifest.json")
        manifestFile.writeBytes(legacy)
        try {
            EnterpriseAppliedStore(root) {
                if (it == EnterpriseStorageCheckpoint.MANIFEST_WRITTEN) throw IOException("interrupted migration")
            }.readManifest()
            fail("Expected interrupted migration")
        } catch (_: IOException) { }
        assertArrayEquals(legacy, manifestFile.readBytes())
        val migratedStore = EnterpriseAppliedStore(root)
        val reopened = EnterpriseSessionController(migratedStore) { 1000L }
        val restored = reopened.recover() as EnterpriseState.Available
        assertEquals(original, restored)
        assertEquals(ENTERPRISE_MANIFEST_SCHEMA_VERSION, restored.manifest.schemaVersion)
        assertEquals(bindings, migratedStore.bindings(restored.manifest))
        val access = reopened.captureSelectedRealmAccess() as RealmAccess.Enterprise
        val token = reopened.beginInvalidation(access, EnterpriseExitReason.AUTHORIZATION_REVOKED)
        File(root, "revisions/${requireNotNull(restored.manifest.applied).revision}/bindings.json").writeText("corrupt")
        val interrupted = EnterpriseSessionController(EnterpriseAppliedStore(root)) { 1000L }
        val closing = interrupted.recover() as EnterpriseState.Available
        assertEquals(EnterpriseSessionPhase.CLOSING, closing.manifest.phase)
        assertEquals(token, interrupted.pendingExit())
        assertEquals(original.manifest.feeds, closing.manifest.feeds)
        interrupted.finishExit(token)
        interrupted.pruneUnusedRevisions()
        assertEquals(EnterpriseSessionPhase.REAUTH_REQUIRED, (interrupted.state.value as EnterpriseState.Available).manifest.phase)
        assertTrue(File(root, "revisions").listFiles()!!.isEmpty())
    }

    @Test
    fun damagedConfigurationCanBeRevokedWithoutReadingItsBindings() = runBlocking {
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(root))
        controller.enrollLocal(example.identity, { example.identity }, { example })
        val version = (controller.state.value as EnterpriseState.Available).manifest.applied!!
        File(root, "revisions/${version.revision}/bindings.json").writeText("corrupt")
        val reopened = EnterpriseSessionController(EnterpriseAppliedStore(root))
        assertTrue(reopened.recover() is EnterpriseState.Failed)
        reopened.finishExit(reopened.beginExit(requireNotNull(reopened.captureExitRequest())))
        reopened.pruneUnusedRevisions()
        assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (reopened.state.value as EnterpriseState.Available).manifest.phase)
        assertTrue(File(root, "revisions").listFiles()!!.isEmpty())
    }
}
