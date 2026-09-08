package net.weero.measix.pilot.ui.pages.setting

import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.ConfigurationApplicationService
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ModelCatalogReadState
import net.weero.measix.pilot.service.ModelCatalogUiModel
import org.junit.Assert.*
import org.junit.Test

class ModelSettingsVMTest {
    @Test
    fun `late error from a previous selection cannot replace the current page error`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val first = RealmSelection(RealmAccess.Personal, 0)
        val next = RealmSelection(RealmAccess.Personal, 2)
        val catalogs = MutableStateFlow<ModelCatalogReadState>(ModelCatalogReadState.Available(ModelCatalogUiModel(emptyList(), first)))
        val queries = mockk<ConfigurationQueryService>()
        val commands = mockk<ConfigurationApplicationService>()
        every { queries.observeModelCatalog() } returns catalogs
        val release = CompletableDeferred<Unit>()
        coEvery { commands.setSuggestionEnabled(first, false) } coAnswers { release.await(); throw IOException("old_error") }
        coEvery { commands.setSuggestionEnabled(next, false) } throws IOException("current_error")
        val vm = ModelSettingsVM(queries, commands)
        try {
            backgroundScope.launch { vm.catalog.collect {} }
            runCurrent()
            vm.enableSuggestion(first, false)
            runCurrent()
            catalogs.value = ModelCatalogReadState.Available(ModelCatalogUiModel(emptyList(), next))
            runCurrent()
            vm.enableSuggestion(next, false)
            runCurrent()
            assertEquals(ModelSettingsError(next, "current_error"), vm.error.value)
            release.complete(Unit)
            runCurrent()
            assertEquals(ModelSettingsError(next, "current_error"), vm.error.value)
        } finally {
            backgroundScope.coroutineContext[Job]!!.cancelAndJoin()
            vm.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }
}
