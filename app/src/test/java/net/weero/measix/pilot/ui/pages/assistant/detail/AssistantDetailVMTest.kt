package net.weero.measix.pilot.ui.pages.assistant.detail

import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.toEffectiveSettingsSnapshot
import net.weero.measix.pilot.data.files.SkillManager
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.ArtifactUseCase
import net.weero.measix.pilot.service.MemoryService
import net.weero.measix.pilot.service.MemoryView
import net.weero.measix.pilot.service.workspace.WorkspaceQueryService
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantDetailVMTest {
    @Test
    fun `old prompt callback preserves a newly committed background and unrelated definition changes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val rendered = Assistant(name = "Before", systemPrompt = "Before", background = "old.png", useGradientBackground = true)
            val backgroundChanged = rendered.copy(background = "new.png", useGradientBackground = false, name = "Renamed")
            var stored = Settings(assistants = listOf(backgroundChanged))
            val projection = MutableStateFlow(stored.toEffectiveSettingsSnapshot())
            val settings = mockk<SettingsStore>()
            every { settings.effectiveSettings } returns projection
            val artifacts = mockk<ArtifactUseCase>()
            coEvery { artifacts.updateSettingsReferences(any()) } coAnswers {
                stored = firstArg<(Settings) -> Settings>()(stored)
                projection.value = stored.toEffectiveSettingsSnapshot()
                stored
            }
            val memories = mockk<MemoryService>()
            every { memories.observeCurrent(any()) } returns flowOf(MemoryView.Loading)
            val skills = mockk<SkillManager>()
            coEvery { skills.listSkills() } returns emptyList()
            val workspaces = mockk<WorkspaceQueryService>()
            every { workspaces.observeWorkspaces() } returns flowOf(emptyList())
            val vm = AssistantDetailVM(rendered.id.toString(), settings, memories, artifacts, skills, workspaces)
            owner.put("assistant", vm)
            runCurrent()
            assertEquals("new.png", vm.assistant.value.background)
            vm.update(rendered, rendered.copy(systemPrompt = "Edited"))
            runCurrent()
            assertEquals(backgroundChanged.copy(systemPrompt = "Edited"), stored.assistants.single())
            coVerify(exactly = 0) { artifacts.maintainStorage(any()) }
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }
}
