package com.facelapse.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facelapse.app.data.repository.ProjectRepository
import com.facelapse.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val projectRepository: ProjectRepository
) : ViewModel() {
    val isDateOverlayEnabled = repository.isDateOverlayEnabled
    val showDayOfWeek = repository.showDayOfWeek
    val defaultFps = repository.defaultFps
    val defaultExportGif = repository.defaultExportGif

    fun setDateOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setDateOverlayEnabled(enabled)
        }
    }

    fun setShowDayOfWeek(show: Boolean) {
        viewModelScope.launch {
            repository.setShowDayOfWeek(show)
        }
    }

    fun setDefaultFps(fps: Float) {
        viewModelScope.launch {
            repository.setDefaultFps(fps)
        }
    }

    fun setDefaultExportGif(exportGif: Boolean) {
        viewModelScope.launch {
            repository.setDefaultExportGif(exportGif)
        }
    }

    fun applyDefaultsToAllProjects() {
        viewModelScope.launch {
            val fps = repository.defaultFps.first()
            val exportGif = repository.defaultExportGif.first()
            val overlay = repository.isDateOverlayEnabled.first()
            projectRepository.updateAllProjectsSettings(fps, exportGif, overlay)
        }
    }
}
