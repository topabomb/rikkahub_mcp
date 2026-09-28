package net.weero.measix.pilot.ui.pages.extensions

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.QuickMessage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class QuickMessagesVMTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `deleting quick message clears only its assistant references in the same command`() = runTest(dispatcher) {
        val removed = QuickMessage(title = "remove", content = "body")
        val kept = QuickMessage(title = "keep", content = "body")
        var state = Settings(quickMessages = listOf(removed, kept), assistants = listOf(
            Assistant(quickMessageIds = setOf(removed.id, kept.id)),
        ))
        val store = mockk<SettingsStore>()
        every { store.userSettings } returns MutableStateFlow(state)
        var writes = 0
        coEvery { store.updateLocal(any()) } coAnswers {
            writes++
            state = firstArg<(Settings) -> Settings>()(state)
            state
        }
        QuickMessagesVM(store).deleteQuickMessage(removed.id)
        assertEquals(1, writes)
        assertEquals(listOf(kept.id), state.quickMessages.map { it.id })
        assertEquals(setOf(kept.id), state.assistants.single().quickMessageIds)
    }

    @Test fun `save completion waits for settings and failure remains visible to caller`() = runTest(dispatcher) {
        val store = mockk<SettingsStore>()
        every { store.userSettings } returns MutableStateFlow(Settings.dummy())
        val failure = java.io.IOException("disk full")
        coEvery { store.updateLocal(any()) } throws failure
        val error = runCatching { QuickMessagesVM(store).addQuickMessage("draft", "preserved") }.exceptionOrNull()
        assertSame(failure, error)
    }
}
