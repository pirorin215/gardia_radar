package com.pirorin215.gardiaradar.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.gardiaradar.data.AppSettingsRepository
import com.pirorin215.gardiaradar.data.BatterySessionRepository
import com.pirorin215.gardiaradar.data.RadarTarget
import com.pirorin215.gardiaradar.data.Settings
import com.pirorin215.gardiaradar.service.RadarNotificationManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.pirorin215.gardiaradar.data.ConnectionState
import com.pirorin215.gardiaradar.data.RadarRepository

class RadarViewModel(
    private val repository: RadarRepository,
    private val connectionManager: RadarConnectionManager,
    private val batterySessionRepository: BatterySessionRepository,
    private val notificationManager: RadarNotificationManager,
    private val appSettingsRepository: AppSettingsRepository
) : ViewModel() {
    val connectionState = repository.connectionState
    val connectedDeviceName = repository.connectedDeviceName
    val targets = repository.targets
    val rawPacket = repository.rawPacket
    val radarBatteryLevel = repository.radarBatteryLevel
    val wearBatteryLevel = repository.wearBatteryLevel
    val suppressionRemainingSeconds = repository.suppressionRemainingSeconds
    val connectionElapsedSeconds = repository.connectionElapsedSeconds
    val rssi = repository.rssi
    val batterySessions = batterySessionRepository.sessions

    fun startScan() {
        connectionManager.startScan()
    }

    fun forceReconnect() {
        connectionManager.forceReconnect()
    }

    fun disconnect() {
        connectionManager.disconnect()
    }

    fun deleteSession(sessionId: String) {
        batterySessionRepository.deleteSession(sessionId)
    }

    fun deleteSessions(sessionIds: List<String>) {
        batterySessionRepository.deleteSessions(sessionIds)
    }

    fun triggerDebugAlert() {
        viewModelScope.launch {
            val fakeTargets = listOf(
                RadarTarget(id = 1, distance = 80, speed = 30, threat = 1)
            )
            val phoneMode = appSettingsRepository.getFlow(Settings.PHONE_NOTIFICATION_MODE).first()
            val wearMode = appSettingsRepository.getFlow(Settings.WEAR_NOTIFICATION_MODE).first()
            notificationManager.handleRadarUpdate(fakeTargets, phoneMode, wearMode)
        }
    }

    /**
     * 直近のレーダー生パケットをファイルに保存（フィールドテスト用）。
     * @param note 保存時の状況メモ（任意）。ファイルヘッダに記録される。
     * @return 保存先ファイルの絶対パス、失敗時は null
     */
    fun saveRadarData(note: String): String? = repository.saveRecentPacketsToFile(note)
}
