package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.RequestCredentials
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.images.ImageGenerationClientProtocol
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.EnterpriseExecution
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.PlatformAccessToken
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.enrollFixture
import net.weero.measix.pilot.data.enterprise.enterpriseTestStore
import net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage
import net.weero.measix.pilot.data.enterprise.platformCandidate
import net.weero.measix.pilot.data.enterprise.reference
import net.weero.measix.pilot.service.runtime.ModelExecutionLease
import net.weero.measix.pilot.service.runtime.ModelRequestTarget
import net.weero.measix.pilot.test.installExecutionConfigurationFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelExecutionServiceImageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `enterprise image capture freezes profile and exact routed request`() = runTest {
        val appScope = AppScope(StandardTestDispatcher(testScheduler))
        val sessions = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder()))
        val packet = exampleEnterprisePackage()
        val available = sessions.enrollFixture(packet)
        val selection = requireNotNull(sessions.readPresentation().selection)
        val access = selection.access as RealmAccess.Enterprise
        val image = packet.configuration.imageGenerators.single()
        val execution = platformCandidate(packet).execution as EnterpriseExecution.Platform
        val settings = mockk<SettingsStore>()
        every { settings.userSettings } returns MutableStateFlow(Settings())
        installExecutionConfigurationFixture(settings)
        val synchronization = mockk<EnterpriseSynchronizationService>()
        coEvery { synchronization.prepareExecution(access) } returns requireNotNull(available.manifest.applied)
        val platform = mockk<PlatformEnterpriseService>()
        coEvery { platform.accessToken(access.sessionId, any()) } returns PlatformAccessToken("relay-secret", Long.MAX_VALUE)
        every { platform.runtimeCompleted(access) } returns Unit
        val service = ModelExecutionService(
            settings = settings,
            sessions = sessions,
            recoveryGate = ApplicationRecoveryGate().apply { ready() },
            providers = mockk<ProviderManager>(),
            appScope = appScope,
            synchronization = synchronization,
            platform = platform,
        )
        var owner: ModelExecutionLease? = null

        try {
            val snapshot = service.capturePageImage(
                selection = selection,
                worker = requireNotNull(currentCoroutineContext()[Job]),
                modelId = packet.identity.reference(image.id),
                stopRequest = {},
                bindOwner = { owner = it },
            )
            var target: ModelRequestTarget.ManagedImage? = null
            snapshot.requests.execute { captured -> target = captured as ModelRequestTarget.ManagedImage }
            val routed = requireNotNull(target)
            val credentials = routed.credentials

            assertEquals(image.modelId, snapshot.model.modelId)
            assertEquals(ImageGenerationClientProtocol.OPENAI_IMAGES_GENERATIONS, routed.protocol)
            assertEquals(
                execution.connection.runtime(image.id, execution.runtimePaths.getValue(image.id)),
                credentials.endpoint,
            )
            assertEquals(packet.configuration.generation.toString(),
                routed.headers.single { it.name == "X-Measix-Managed-Generation" }.value)
            assertTrue(routed.headers.single { it.name == "X-Measix-Interaction-Id" }.value.startsWith("int_"))
            coVerify(exactly = 1) { platform.accessToken(access.sessionId, execution.connection) }
            assertNotNull(snapshot.enterpriseVersion)
            assertTrue(requireNotNull(snapshot.imageGeneration).canGenerate)
            assertFalse(requireNotNull(snapshot.imageGeneration).canEdit)
            assertEquals(image.maxImagesPerRequest, requireNotNull(snapshot.imageGeneration).maxImagesPerRequest)
            assertEquals(image.allowedSizes.toSet(), requireNotNull(snapshot.imageGeneration).allowedSizes)
            assertFalse(requireNotNull(snapshot.imageGeneration).supportsPartialImages)
            assertEquals(ImageGenerationClientProtocol.OPENAI_IMAGES_GENERATIONS,
                requireNotNull(snapshot.imageGeneration).protocol)
            assertEquals(ModelType.IMAGE, snapshot.model.type)
        } finally {
            owner?.release()
            appScope.cancel()
        }
    }
}
