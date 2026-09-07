package net.weero.measix.pilot.data.enterprise

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
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
        first.applyPackage(EnterprisePackageCodec.encode(privatePacket))
        first.switchToPersonal()
        val reopened = EnterpriseSessionController(EnterpriseAppliedStore(root))
        assertEquals(first.state.value, reopened.recover())
        assertEquals(ConfigurationScope.Personal, (reopened.state.value as EnterpriseState.Available).manifest.selectedScope)
        reopened.switchToEnterprise()
        val lease = reopened.captureBindings(example.identity.scope)
        assertEquals("device-test-secret", lease.binding("mdl_chat").credential)
        val token = requireNotNull(reopened.beginExit())
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
        controller.applyPackage(EnterprisePackageCodec.encode(example))
        val original = controller.state.value
        breakRename = true
        try {
            controller.applyPackage(EnterprisePackageCodec.encode(example.copy(configuration = example.configuration.copy(generation = 2))))
            fail("Broken rename must reject publication")
        } catch (_: EnterpriseStorageException) { }
        assertEquals(original, controller.state.value)
        assertEquals(original, EnterpriseSessionController(EnterpriseAppliedStore(root)).recover())
    }

    @Test
    fun damagedConfigurationCanBeRevokedWithoutReadingItsBindings() = runBlocking {
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(root))
        controller.applyPackage(EnterprisePackageCodec.encode(example))
        val version = (controller.state.value as EnterpriseState.Available).manifest.applied!!
        File(root, "revisions/${version.revision}/bindings.json").writeText("corrupt")
        val reopened = EnterpriseSessionController(EnterpriseAppliedStore(root))
        assertTrue(reopened.recover() is EnterpriseState.Failed)
        reopened.finishExit(requireNotNull(reopened.beginExit()))
        assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (reopened.state.value as EnterpriseState.Available).manifest.phase)
        assertTrue(File(root, "revisions").listFiles()!!.isEmpty())
    }
}
