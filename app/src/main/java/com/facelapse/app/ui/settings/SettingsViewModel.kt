package com.facelapse.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facelapse.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository
) : ViewModel() {
    val isDateOverlayEnabled = repository.isDateOverlayEnabled
    val showDayOfWeek = repository.showDayOfWeek

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
}
