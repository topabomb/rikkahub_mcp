package net.weero.measix.pilot.ui.pages.imggen

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.paging.PagingData
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.files.toEffectiveSnapshot
import net.weero.measix.pilot.data.imggen.ImageGenerationCoordinator
import net.weero.measix.pilot.data.imggen.ImageGenerationModelDescriptor
import net.weero.measix.pilot.data.imggen.ImageGenerationOutcome
import net.weero.measix.pilot.data.imggen.ImageGenerationRequest
import net.weero.measix.pilot.data.imggen.ImageGenerationSelection
import net.weero.measix.pilot.data.imggen.ImageGenerationSelectionResolver
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.FileManagementQueryService
import net.weero.measix.pilot.service.ModelCatalogReadState
import net.weero.measix.pilot.service.ModelCatalogUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImgGenVMTest {
    @Test
    fun `rapid replacement cannot bypass a cancelled predecessor that still owns cleanup`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val owner = ViewModelStore()
        try {
            val settings = mockk<SettingsStore>()
            every { settings.effectiveSettings } returns MutableStateFlow(Settings().toEffectiveSnapshot())
            val selected = RealmSelection(RealmAccess.Personal, 0)
            val configuration = mockk<ConfigurationQueryService>()
            every { configuration.observeModelCatalog() } returns flowOf(
                ModelCatalogReadState.Available(ModelCatalogUiModel(emptyList(), selected)),
            )
            coEvery { configuration.requireSelection(selected) } returns Unit
            val files = mockk<FileManagementQueryService>()
            every { files.observeSelection() } returns MutableStateFlow(selected)
            every { files.observeGeneratedPaging() } returns flowOf(PagingData.empty())
            val model = Model(modelId = "image")
            val provider = ProviderSetting.OpenAI(models = listOf(model))
            val resolver = mockk<ImageGenerationSelectionResolver>()
            every { resolver.resolve(any()) } returns ImageGenerationSelection.Available(
                model, provider, provider, mockk(), ImageGenerationModelDescriptor.from(model, provider),
            )
            val coordinator = mockk<ImageGenerationCoordinator>()
            val started = mutableListOf<String>()
            coEvery { coordinator.enqueue(any()) } coAnswers {
                val request = firstArg<ImageGenerationRequest>()
                started += request.prompt
                if (request.prompt == "A") {
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                    }
                } else ImageGenerationOutcome.Success(emptyList())
            }
            val vm = ImgGenVM(ApplicationProvider.getApplicationContext<Application>(), settings, mockk(), resolver,
                coordinator, files, mockk(), configuration)
            owner.put("image", vm)
            runCurrent()
            vm.updatePrompt("A")
            vm.generateImage()
            runCurrent()
            assertEquals(listOf("A"), started)
            vm.updatePrompt("B")
            vm.generateImage()
            // C replaces B before B's coroutine body starts. Both must retain A's cleanup barrier.
            vm.updatePrompt("C")
            vm.generateImage()
            runCurrent()
            assertEquals(listOf("A"), started)
            cleaning.await()
            vm.updatePrompt("D")
            vm.generateImage()
            runCurrent()
            assertEquals(listOf("A"), started)
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf("A", "D"), started)
            assertFalse(vm.isGenerating.value)
        } finally {
            release.complete(Unit)
            owner.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
