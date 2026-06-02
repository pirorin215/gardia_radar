package com.pirorin215.gardiaradar.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.gardiaradar.data.AppSettingsRepository
import com.pirorin215.gardiaradar.data.NotificationMode
import com.pirorin215.gardiaradar.data.Settings
import com.pirorin215.gardiaradar.data.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppSettingsViewModel(
    private val repository: AppSettingsRepository
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = repository.getFlow(Settings.THEME_MODE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val notificationMode: StateFlow<NotificationMode> = repository.getFlow(Settings.NOTIFICATION_MODE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NotificationMode.FIRST_ONLY)

    fun saveThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setValue(Settings.THEME_MODE, mode) }
    }

    fun saveNotificationMode(mode: NotificationMode) {
        viewModelScope.launch { repository.setValue(Settings.NOTIFICATION_MODE, mode) }
    }
}
