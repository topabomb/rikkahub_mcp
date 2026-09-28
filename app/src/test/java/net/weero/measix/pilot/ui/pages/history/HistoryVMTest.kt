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
import net.weero.measix.pilot.service.AssistantCatalogReadState
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryVMTest {
    @Test fun `history failure exposes cause and retry restores rows without recreating the page`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val assistant = ConfigurationReference.random()
            val selection = RealmSelection(RealmAccess.Personal, 1)
            val configuration = mockk<ConfigurationQueryService>()
            every { configuration.observeAssistantCatalog() } returns flowOf(AssistantCatalogReadState.Available(
                AssistantCatalogUiModel(selection, ConfigurationSelection(assistant, null), emptyMap(), emptyList())))
            val conversations = mockk<ConversationQueryService>()
            val row = mockk<ConversationSummary>()
            var failed = true
            every { conversations.conversationsOfAssistant(assistant) } answers {
                flowOf(if (failed) Result.failure(IllegalStateException("history unreadable", java.io.IOException("disk cause")))
                    else Result.success(listOf(row)))
            }
            val vm = HistoryVM(conversations, configuration, mockk())
            store.put("history", vm)
            runCurrent()
            org.junit.Assert.assertTrue(vm.conversations.value.isEmpty())
            org.junit.Assert.assertTrue(requireNotNull(vm.readFailure.value).contains("IllegalStateException: history unreadable"))
            org.junit.Assert.assertTrue(requireNotNull(vm.readFailure.value).contains("IOException: disk cause"))
            failed = false
            vm.retry()
            runCurrent()
            assertEquals(listOf(row), vm.conversations.value)
            org.junit.Assert.assertNull(vm.readFailure.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `unavailable assistant still reads authorized history and revoked realm clears it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val scope = ConfigurationScope.Enterprise(EnterpriseAuthority("dep_history"), "user")
            val selection = RealmSelection(RealmAccess.Enterprise(scope, "session"), 1)
            val assistant = ConfigurationReference.random()
            val catalog = MutableStateFlow<AssistantCatalogReadState>(AssistantCatalogReadState.Available(AssistantCatalogUiModel(
                selection, ConfigurationSelection(assistant, ConfigurationUnavailableReason.USER_CATEGORY_NOT_ALLOWED),
                emptyMap(), emptyList(),
            )))
            val configuration = mockk<ConfigurationQueryService>()
            every { configuration.observeAssistantCatalog() } returns catalog
            val conversations = mockk<ConversationQueryService>()
            val row = mockk<ConversationSummary>()
            every { conversations.conversationsOfAssistant(assistant) } returns flowOf(Result.success(listOf(row)))
            val vm = HistoryVM(conversations, configuration, mockk())
            store.put("history", vm)
            runCurrent()
            assertEquals(listOf(row), vm.conversations.value)
            catalog.value = AssistantCatalogReadState.Unavailable("revoked")
            runCurrent()
            assertEquals(emptyList<ConversationSummary>(), vm.conversations.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
