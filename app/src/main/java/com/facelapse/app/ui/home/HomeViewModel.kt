package com.facelapse.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facelapse.app.data.repository.ProjectRepository
import com.facelapse.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: ProjectRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    val projects = repository.getAllProjects()

    fun createProject(name: String) {
        viewModelScope.launch {
            val (defaultFps, defaultExportGif) = settingsRepository.projectDefaults.first()
            repository.createProject(name, defaultFps, defaultExportGif)
        }
    }

    fun deleteProject(project: com.facelapse.app.data.local.entity.ProjectEntity) {
        viewModelScope.launch {
            repository.deleteProject(project)
        }
    }
}
