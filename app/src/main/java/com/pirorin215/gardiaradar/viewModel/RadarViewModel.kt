package com.pirorin215.gardiaradar.viewModel

import androidx.lifecycle.ViewModel
import com.pirorin215.gardiaradar.data.RadarRepository

class RadarViewModel(
    private val repository: RadarRepository
) : ViewModel() {
    val connectionState = repository.connectionState
    val targets = repository.targets
    val rawPacket = repository.rawPacket
    val radarBatteryLevel = repository.radarBatteryLevel

    fun startScan() {
        repository.startScan()
    }

    fun forceReconnect() {
        repository.forceReconnect()
    }

    fun disconnect() {
        repository.disconnect()
    }
}
