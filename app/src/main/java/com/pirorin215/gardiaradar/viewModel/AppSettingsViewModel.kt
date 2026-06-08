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

    val phoneNotificationMode: StateFlow<NotificationMode> = repository.getFlow(Settings.PHONE_NOTIFICATION_MODE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NotificationMode.FIRST_ONLY)

    val wearNotificationMode: StateFlow<NotificationMode> = repository.getFlow(Settings.WEAR_NOTIFICATION_MODE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NotificationMode.FIRST_ONLY)

    val clearSuppressionSeconds: StateFlow<Int> = repository.getFlow(Settings.CLEAR_SUPPRESSION_SECONDS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 10)

    val radarLowBatteryThreshold: StateFlow<Int> = repository.getFlow(Settings.RADAR_LOW_BATTERY_THRESHOLD)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 20)

    val targetDeviceAddress: StateFlow<String> = repository.getFlow(Settings.TARGET_DEVICE_ADDRESS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val targetDeviceName: StateFlow<String> = repository.getFlow(Settings.TARGET_DEVICE_NAME)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val wearPowerSavingMode: StateFlow<Boolean> = repository.getFlow(Settings.WEAR_POWER_SAVING_MODE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val rssiDisconnectEnabled: StateFlow<Boolean> = repository.getFlow(Settings.RSSI_DISCONNECT_ENABLED)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val rssiDisconnectThreshold: StateFlow<Int> = repository.getFlow(Settings.RSSI_DISCONNECT_THRESHOLD)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -90)

    val rssiDisconnectCount: StateFlow<Int> = repository.getFlow(Settings.RSSI_DISCONNECT_COUNT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)

    val rssiConnectThreshold: StateFlow<Int> = repository.getFlow(Settings.RSSI_CONNECT_THRESHOLD)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -80)

    val phoneAlertSoundEnabled: StateFlow<Boolean> = repository.getFlow(Settings.PHONE_ALERT_SOUND_ENABLED)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val phoneAlertVibrationEnabled: StateFlow<Boolean> = repository.getFlow(Settings.PHONE_ALERT_VIBRATION_ENABLED)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val wearAlertSoundEnabled: StateFlow<Boolean> = repository.getFlow(Settings.WEAR_ALERT_SOUND_ENABLED)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val wearAlertVibrationEnabled: StateFlow<Boolean> = repository.getFlow(Settings.WEAR_ALERT_VIBRATION_ENABLED)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val phoneAlertSoundUri: StateFlow<String> = repository.getFlow(Settings.PHONE_ALERT_SOUND_URI)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val phoneAlertSoundName: StateFlow<String> = repository.getFlow(Settings.PHONE_ALERT_SOUND_NAME)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "デフォルト")

    fun saveThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setValue(Settings.THEME_MODE, mode) }
    }

    fun savePhoneNotificationMode(mode: NotificationMode) {
        viewModelScope.launch { repository.setValue(Settings.PHONE_NOTIFICATION_MODE, mode) }
    }

    fun saveWearNotificationMode(mode: NotificationMode) {
        viewModelScope.launch { repository.setValue(Settings.WEAR_NOTIFICATION_MODE, mode) }
    }

    fun saveClearSuppressionSeconds(seconds: Int) {
        viewModelScope.launch { repository.setValue(Settings.CLEAR_SUPPRESSION_SECONDS, seconds) }
    }

    fun saveRadarLowBatteryThreshold(value: Int) {
        viewModelScope.launch { repository.setValue(Settings.RADAR_LOW_BATTERY_THRESHOLD, value) }
    }

    fun saveTargetDevice(address: String, name: String) {
        viewModelScope.launch {
            repository.setValue(Settings.TARGET_DEVICE_ADDRESS, address)
            repository.setValue(Settings.TARGET_DEVICE_NAME, name)
        }
    }

    fun clearTargetDevice() {
        viewModelScope.launch {
            repository.setValue(Settings.TARGET_DEVICE_ADDRESS, "")
            repository.setValue(Settings.TARGET_DEVICE_NAME, "")
        }
    }

    fun saveWearPowerSavingMode(enabled: Boolean) {
        viewModelScope.launch { repository.setValue(Settings.WEAR_POWER_SAVING_MODE, enabled) }
    }

    fun saveRssiDisconnectEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setValue(Settings.RSSI_DISCONNECT_ENABLED, enabled) }
    }

    fun saveRssiDisconnectThreshold(value: Int) {
        viewModelScope.launch { repository.setValue(Settings.RSSI_DISCONNECT_THRESHOLD, value) }
    }

    fun saveRssiDisconnectCount(value: Int) {
        viewModelScope.launch { repository.setValue(Settings.RSSI_DISCONNECT_COUNT, value) }
    }

    fun saveRssiConnectThreshold(value: Int) {
        viewModelScope.launch { repository.setValue(Settings.RSSI_CONNECT_THRESHOLD, value) }
    }

    fun savePhoneAlertSoundEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setValue(Settings.PHONE_ALERT_SOUND_ENABLED, enabled) }
    }

    fun savePhoneAlertVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setValue(Settings.PHONE_ALERT_VIBRATION_ENABLED, enabled) }
    }

    fun saveWearAlertSoundEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setValue(Settings.WEAR_ALERT_SOUND_ENABLED, enabled) }
    }

    fun saveWearAlertVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setValue(Settings.WEAR_ALERT_VIBRATION_ENABLED, enabled) }
    }

    fun savePhoneAlertSound(uri: String, name: String) {
        viewModelScope.launch {
            repository.setValue(Settings.PHONE_ALERT_SOUND_URI, uri)
            repository.setValue(Settings.PHONE_ALERT_SOUND_NAME, name)
        }
    }
}
