package com.pirorin215.gardiaradar.viewModel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.pirorin215.gardiaradar.data.AppSettingsRepository
import com.pirorin215.gardiaradar.data.ConnectionState
import com.pirorin215.gardiaradar.data.RadarRepository
import com.pirorin215.gardiaradar.data.Settings
import com.pirorin215.gardiaradar.service.RadarScanServiceManager
import com.pirorin215.gardiaradar.service.WearableDataHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 接続ライフサイクル管理クラス。
 * FastRecMobのBleConnectionManagerパターンに準拠。
 *
 * 責務:
 * - 再接続・forceReconnect・スキャン起動
 * - bondedDevices判定による直接接続
 * - Bluetooth ON/OFF監視
 * - ターゲットデバイス設定変更の監視
 * - Wear OSへの接続状態通知
 *
 * 責務外（RadarRepositoryが担当）:
 * - GATT通信・BluetoothGattCallback
 * - データのデコード・StateFlow
 */
@SuppressLint("MissingPermission")
class RadarConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: RadarRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val wearableDataHost: WearableDataHost
) {
    companion object {
        private const val TAG = "RadarConnectionManager"
        private const val FORCE_RECONNECT_DELAY_MS = 2000L
        private const val ERROR_RECONNECT_DELAY_MS = 3000L
        private const val DISCONNECTED_RECONNECT_DELAY_MS = 1000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var reconnectJob: Job? = null

    // Bluetooth ON/OFF監視
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d(TAG, "Bluetooth OFF - disconnecting")
                        repository.disconnect()
                    }
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(TAG, "Bluetooth ON - attempting reconnection")
                        reconnectJob?.cancel()
                        reconnectJob = scope.launch {
                            delay(1000L)
                            restartScan(forceScan = true)
                        }
                    }
                }
            }
        }
    }

    init {
        // Bluetooth状態レシーバー登録
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(bluetoothStateReceiver, filter)

        // RadarRepositoryの接続状態を監視して自動再接続
        scope.launch {
            repository.connectionState.collectLatest { state ->
                Log.d(TAG, "Repository state changed to: $state")
                when (state) {
                    is ConnectionState.Disconnected -> {
                        wearableDataHost.putConnectionStateData(false)
                        forceReconnect()
                    }
                    is ConnectionState.Error -> {
                        wearableDataHost.putConnectionStateData(false)
                        // エラー時はリソースを解放してから再スキャン
                        repository.disconnect()
                        repository.close()
                        reconnectJob?.cancel()
                        reconnectJob = scope.launch {
                            delay(ERROR_RECONNECT_DELAY_MS)
                            Log.d(TAG, "Reconnecting after error: ${state.message}")
                            restartScan(forceScan = true)
                        }
                    }
                    is ConnectionState.Connected -> {
                        wearableDataHost.putConnectionStateData(true)
                        Log.d(TAG, "Connection state sent to Wear OS: Connected")
                    }
                    else -> {
                        // Scanning, Connecting は何もしない
                    }
                }
            }
        }

        // スキャン結果のデバイス受信
        scope.launch {
            RadarScanServiceManager.deviceFoundFlow.collectLatest { device ->
                Log.d(TAG, "Device found: ${device.name}")
                if (repository.connectionState.value is ConnectionState.Disconnected ||
                    repository.connectionState.value is ConnectionState.Scanning) {
                    repository.connect(device)
                }
            }
        }

        // ターゲットデバイス設定の変更を監視
        scope.launch {
            appSettingsRepository.getFlow(Settings.TARGET_DEVICE_ADDRESS).collectLatest { address ->
                if (address.isNotEmpty()) {
                    val state = repository.connectionState.value
                    if (state is ConnectionState.Disconnected || state is ConnectionState.Scanning) {
                        Log.d(TAG, "Target device address changed to '$address'. Restarting connection.")
                        restartScan(forceScan = true)
                    }
                }
            }
        }
    }

    fun startScan() {
        val state = repository.connectionState.value
        if (state !is ConnectionState.Disconnected && state !is ConnectionState.Scanning) {
            Log.d(TAG, "startScan skipped: state=$state")
            return
        }
        Log.d(TAG, "startScan requested.")
        repository.setConnectionState(ConnectionState.Scanning)

        scope.launch {
            val savedAddress = appSettingsRepository.getFlow(Settings.TARGET_DEVICE_ADDRESS).first()

            // ボンデッドデバイスから直接接続を試行（スキャン不要）
            if (savedAddress.isNotEmpty()) {
                val bondedTarget = bluetoothAdapter?.bondedDevices?.find { it.address == savedAddress }
                if (bondedTarget != null) {
                    Log.d(TAG, "Found bonded device '${bondedTarget.name}' (${bondedTarget.address}). Connecting directly.")
                    repository.setConnectedDeviceName(bondedTarget.name)
                    repository.connect(bondedTarget)
                    return@launch
                } else {
                    Log.d(TAG, "Target device not in bonded devices. Falling back to scan.")
                }
            }

            // ボンデッドに無い場合はスキャン
            Log.d(TAG, "Signaling RadarScanService to start scan.")
            RadarScanServiceManager.emitRestartScan()
        }
    }

    fun forceReconnect() {
        val state = repository.connectionState.value
        if (state is ConnectionState.Connected || state is ConnectionState.Connecting) {
            Log.d(TAG, "forceReconnect: disconnecting current connection.")
            repository.disconnect()
            repository.close()
        }

        // 既存の再接続処理をキャンセルして重複防止
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(FORCE_RECONNECT_DELAY_MS)
            repository.resetState()
            restartScan(forceScan = true)
        }
    }

    fun disconnect() {
        repository.disconnect()
    }

    private fun restartScan(forceScan: Boolean = false) {
        val state = repository.connectionState.value
        if (!forceScan && state !is ConnectionState.Disconnected) {
            Log.d(TAG, "restartScan skipped: state=$state")
            return
        }
        repository.resetState()
        startScan()
    }

    fun close() {
        repository.close()
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "BluetoothStateReceiver was not registered")
        }
    }
}
