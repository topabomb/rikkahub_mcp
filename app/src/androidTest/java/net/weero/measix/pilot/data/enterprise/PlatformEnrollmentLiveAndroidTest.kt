package net.weero.measix.pilot.data.enterprise

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.images.ImageGenerationClientProtocol
import net.weero.measix.pilot.data.imggen.ImageGenerationCoordinator
import net.weero.measix.pilot.data.imggen.ImageGenerationOutcome
import net.weero.measix.pilot.data.imggen.ImageGenerationRequest
import net.weero.measix.pilot.data.imggen.ImageGenerationSource
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.EnterpriseApplicationService
import net.weero.measix.pilot.service.ModelExecutionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Explicit live setup: consumes one caller-created enrollment and completes one managed image request. */
@RunWith(AndroidJUnit4::class)
class PlatformEnrollmentLiveAndroidTest {
    @Test fun enrollsSelectsAndPersistsManagedImage() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("platformEnrollmentLive") == "true")
        val encoded = requireNotNull(arguments.getString("platformEnrollmentMaterialBase64"))
        val material = String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        withTimeout(240_000) {
            val koin = GlobalContext.get()
            koin.get<ApplicationRecoveryGate>().awaitReady()
            val service = koin.get<EnterpriseApplicationService>()
            val confirmation = requireNotNull(service.join(material))
            service.confirmJoin(confirmation)

            val access = koin.get<EnterpriseSessionController>().captureSelectedRealmAccess() as RealmAccess.Enterprise
            val configuration = requireNotNull(
                koin.get<ModelExecutionService>().read(access).configuration.enterpriseConfiguration,
            )
            val image = configuration.imageGenerators.single()
            assertEquals(ImageGenerationClientProtocol.DASHSCOPE_MULTIMODAL_GENERATION, image.protocol)
            assertEquals("wan2.7-image", image.modelId)
            assertEquals(image.id, configuration.defaults.imageGenerationModelId)
            assertNotNull(configuration.defaults.imageGenerationModelId)

            val selection = koin.get<EnterpriseSessionController>()
                .observeSelectedRealmSelection().first { it?.access == access }!!
            val resolved = koin.get<ModelExecutionService>().read(access).configuration
            val modelId = requireNotNull(resolved.selections.imageGenerationModelId)
            val size = requireNotNull(resolved.models.getValue(modelId).imageGeneration?.allowedSizes).single()
            val outcome = koin.get<ImageGenerationCoordinator>().enqueue(ImageGenerationRequest(
                source = ImageGenerationSource.Page(selection, modelId),
                prompt = "A simple blue circle centered on a plain white background",
                size = size,
            )) as ImageGenerationOutcome.Success
            val media = outcome.media.single()
            assertTrue(media.mediaId > 0)
            assertTrue(media.canonicalFile.isFile)
            assertTrue(media.canonicalFile.length() > 0)
        }
    }
}
