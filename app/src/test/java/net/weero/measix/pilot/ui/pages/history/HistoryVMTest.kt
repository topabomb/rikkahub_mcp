package net.weero.measix.pilot.ui.pages.history

import androidx.lifecycle.ViewModelStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ConfigurationSelection
import net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.AssistantCatalogUiModel
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryVMTest {
    @Test fun `unavailable assistant still reads authorized history and revoked realm clears it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val scope = ConfigurationScope.Enterprise(EnterpriseAuthority("local:history", "dep_history"), "user")
            val selection = RealmSelection(RealmAccess.Enterprise(scope, "session"), 1)
            val assistant = ConfigurationReference.random()
            val catalog = MutableStateFlow<AssistantCatalogUiModel?>(AssistantCatalogUiModel(
                selection, ConfigurationSelection(assistant, ConfigurationUnavailableReason.USER_CATEGORY_NOT_ALLOWED),
                emptyMap(), emptyList(),
            ))
            val configuration = mockk<ConfigurationQueryService>()
            every { configuration.observeAssistantCatalog() } returns catalog
            val conversations = mockk<ConversationQueryService>()
            val row = mockk<ConversationSummary>()
            every { conversations.conversationsOfAssistant(assistant) } returns flowOf(listOf(row))
            val vm = HistoryVM(conversations, configuration, mockk())
            store.put("history", vm)
            runCurrent()
            assertEquals(listOf(row), vm.conversations.value)
            catalog.value = null
            runCurrent()
            assertEquals(emptyList<ConversationSummary>(), vm.conversations.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
