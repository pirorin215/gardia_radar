package com.pirorin215.gardiaradar.viewModel

import androidx.lifecycle.ViewModel
import com.pirorin215.gardiaradar.data.ConnectionState
import com.pirorin215.gardiaradar.data.RadarRepository

class RadarViewModel(
    private val repository: RadarRepository,
    private val connectionManager: RadarConnectionManager
) : ViewModel() {
    val connectionState = repository.connectionState
    val connectedDeviceName = repository.connectedDeviceName
    val targets = repository.targets
    val rawPacket = repository.rawPacket
    val radarBatteryLevel = repository.radarBatteryLevel
    val suppressionRemainingSeconds = repository.suppressionRemainingSeconds

    fun startScan() {
        connectionManager.startScan()
    }

    fun forceReconnect() {
        connectionManager.forceReconnect()
    }

    fun disconnect() {
        connectionManager.disconnect()
    }
}
