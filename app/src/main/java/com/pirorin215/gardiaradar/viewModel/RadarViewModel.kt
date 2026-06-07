package com.pirorin215.gardiaradar.viewModel

import androidx.lifecycle.ViewModel
import com.pirorin215.gardiaradar.data.BatterySessionRepository
import com.pirorin215.gardiaradar.data.ConnectionState
import com.pirorin215.gardiaradar.data.RadarRepository

class RadarViewModel(
    private val repository: RadarRepository,
    private val connectionManager: RadarConnectionManager,
    private val batterySessionRepository: BatterySessionRepository
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
}
