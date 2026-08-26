package net.weero.measix.pilot.ui.pages.extensions.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.files.SkillFile
import net.weero.measix.pilot.data.files.SkillFileDeleteResult
import net.weero.measix.pilot.data.files.SkillFileNode
import net.weero.measix.pilot.data.files.SkillFileSaveResult
import net.weero.measix.pilot.data.files.SkillContentReadResult
import net.weero.measix.pilot.data.files.SkillManager

class SkillDetailVM(
    private val skillManager: SkillManager,
) : ViewModel() {

    private val _tree = MutableStateFlow<List<SkillFileNode>>(emptyList())
    val tree = _tree.asStateFlow()

    private var skillName = ""

    fun init(name: String) {
        if (skillName == name) return
        skillName = name
        loadFiles()
    }

    fun loadFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            _tree.value = skillManager.listSkillFiles(skillName)
        }
    }

    fun readFile(skillFile: SkillFile, onResult: (SkillFileLoadResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = when (val read = skillManager.readSkillContent(skillName, skillFile.relativePath)) {
                is SkillContentReadResult.Success -> SkillFileLoadResult.Success(read.content)
                else -> SkillFileLoadResult.Failure
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    fun saveFile(relativePath: String, content: String, onResult: (SkillFileSaveResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = skillManager.saveSkillFile(skillName, relativePath, content)
            if (result == SkillFileSaveResult.SUCCESS) loadFiles()
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    fun deleteFile(skillFile: SkillFile, onResult: (SkillFileDeleteResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = skillManager.deleteSkillFile(skillName, skillFile.relativePath)
            if (result == SkillFileDeleteResult.SUCCESS) loadFiles()
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }
}

sealed interface SkillFileLoadResult {
    data class Success(val content: String) : SkillFileLoadResult
    data object Failure : SkillFileLoadResult
}
