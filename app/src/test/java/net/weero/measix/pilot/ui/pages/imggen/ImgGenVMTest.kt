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
            val model = Model(modelId = "image", type = me.rerere.ai.provider.ModelType.IMAGE)
            val provider = ProviderSetting.OpenAI(models = listOf(model))
            val catalog = net.weero.measix.pilot.service.userDefinitionModelCatalog(listOf(provider)).copy(
                selection = selected,
                roleSelections = mapOf(net.weero.measix.pilot.data.configuration.ResourceSelectionSlot.IMAGE_MODEL to
                    net.weero.measix.pilot.data.configuration.ConfigurationSelection(model.id, null)),
            )
            val configuration = mockk<ConfigurationQueryService>()
            every { configuration.observeModelCatalog() } returns flowOf(
                ModelCatalogReadState.Available(catalog),
            )
            coEvery { configuration.requireSelection(selected) } returns Unit
            val files = mockk<FileManagementQueryService>()
            every { files.observeSelection() } returns MutableStateFlow(selected)
            every { files.observeGeneratedPaging() } returns flowOf(PagingData.empty())
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
            val vm = ImgGenVM(ApplicationProvider.getApplicationContext<Application>(), settings,
                coordinator, files, mockk(), configuration, mockk())
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
