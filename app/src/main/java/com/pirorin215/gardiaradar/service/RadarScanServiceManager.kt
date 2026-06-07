package com.pirorin215.gardiaradar.service

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object RadarScanServiceManager {
    private val _deviceFoundFlow = MutableSharedFlow<ScanResult>(extraBufferCapacity = 1)
    val deviceFoundFlow: SharedFlow<ScanResult> = _deviceFoundFlow.asSharedFlow()

    private val _restartScanFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val restartScanFlow: SharedFlow<Unit> = _restartScanFlow.asSharedFlow()

    suspend fun emitDeviceFound(scanResult: ScanResult) {
        _deviceFoundFlow.emit(scanResult)
    }

    suspend fun emitRestartScan() {
        _restartScanFlow.emit(Unit)
    }
}
