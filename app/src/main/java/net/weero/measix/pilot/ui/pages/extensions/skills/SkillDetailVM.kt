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

    private val _failure = MutableStateFlow<Exception?>(null)
    val failure = _failure.asStateFlow()
    fun dismissFailure() { _failure.value = null }

    fun loadFiles() {
        val name = skillName
        viewModelScope.launch(Dispatchers.IO) {
            try { reload(name) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.e("SkillDetailVM", "Unable to list Skill files", error)
                _failure.value = error
            }
        }
    }

    private fun reload(name: String) {
        val files = skillManager.listSkillFiles(name)
        if (name == skillName) _tree.value = files
    }

    suspend fun readFile(name: String, skillFile: SkillFile): SkillContentReadResult = withContext(Dispatchers.IO) {
        skillManager.readSkillContent(name, skillFile.relativePath)
    }

    suspend fun saveFile(name: String, relativePath: String, content: String): SkillFileSaveResult = withContext(Dispatchers.IO) {
        val result = skillManager.saveSkillFile(name, relativePath, content)
        if (result == SkillFileSaveResult.SUCCESS) reload(name)
        result
    }

    suspend fun deleteFile(name: String, skillFile: SkillFile): SkillFileDeleteResult = withContext(Dispatchers.IO) {
        val result = skillManager.deleteSkillFile(name, skillFile.relativePath)
        if (result == SkillFileDeleteResult.SUCCESS) reload(name)
        result
    }
}
