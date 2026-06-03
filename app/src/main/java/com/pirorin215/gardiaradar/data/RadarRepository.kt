package com.pirorin215.gardiaradar.data

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import com.pirorin215.gardiaradar.service.RadarNotificationManager
import com.pirorin215.gardiaradar.service.RadarScanServiceManager
import com.pirorin215.gardiaradar.service.WearableDataHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.*

@SuppressLint("MissingPermission")
class RadarRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val appSettingsRepository: AppSettingsRepository,
    private val notificationManager: RadarNotificationManager,
    private val wearableDataHost: WearableDataHost
) {
    private val TAG = "RadarRepository"
    private val TARGET_CHAR_UUID = UUID.fromString("f3641401-00b0-4240-ba50-05ca45bf8abc")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null
    private var phoneNotificationMode: com.pirorin215.gardiaradar.data.NotificationMode = com.pirorin215.gardiaradar.data.NotificationMode.FIRST_ONLY
    private var wearNotificationMode: com.pirorin215.gardiaradar.data.NotificationMode = com.pirorin215.gardiaradar.data.NotificationMode.FIRST_ONLY

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _targets = MutableStateFlow<List<RadarTarget>>(emptyList())
    val targets = _targets.asStateFlow()

    private val _rawPacket = MutableStateFlow("")
    val rawPacket = _rawPacket.asStateFlow()

    init {
        scope.launch {
            appSettingsRepository.getFlow(Settings.PHONE_NOTIFICATION_MODE).collectLatest { mode ->
                phoneNotificationMode = mode
            }
        }

        scope.launch {
            appSettingsRepository.getFlow(Settings.WEAR_NOTIFICATION_MODE).collectLatest { mode ->
                wearNotificationMode = mode
            }
        }

        scope.launch {
            RadarScanServiceManager.deviceFoundFlow.collectLatest { device ->
                Log.d(TAG, "Device found from service: ${device.name}")
                if (_connectionState.value == ConnectionState.Scanning || _connectionState.value == ConnectionState.Disconnected) {
                    connectToDevice(device)
                }
            }
        }

        // 接続状態の変化をWear OSに通知
        scope.launch {
            connectionState.collectLatest { state ->
                when (state) {
                    ConnectionState.Connected -> {
                        wearableDataHost.putConnectionStateData(true)
                        Log.d(TAG, "Connection state change sent to Wear OS: Connected")
                    }
                    ConnectionState.Disconnected -> {
                        wearableDataHost.putConnectionStateData(false)
                        Log.d(TAG, "Connection state change sent to Wear OS: Disconnected")
                    }
                    else -> {
                        // ScanningやConnectingは通知しない
                    }
                }
            }
        }
    }

    fun startScan() {
        if (_connectionState.value != ConnectionState.Disconnected) return
        
        Log.d(TAG, "startScan requested. Signaling RadarScanService.")
        _connectionState.value = ConnectionState.Scanning
        scope.launch {
            RadarScanServiceManager.emitRestartScan()
        }
    }

    private fun stopScan() {
        // No direct stopScan needed here, service handles it or we just connect
    }

    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to device: ${device.address}")
        _connectionState.value = ConnectionState.Connecting
        gatt = device.connectGatt(context, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server. Requesting MTU...")
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server (status=$status). Cleaning up...")
                // GATTリソースを確実に解放（FastRecMobの安定パターン）
                gatt.close()
                this@RadarRepository.gatt = null

                _connectionState.value = ConnectionState.Disconnected
                _targets.value = emptyList()
                _rawPacket.value = ""

                // Wear OSに空の車列データを送信して古いデータをクリア
                wearableDataHost.putTargetsData(emptyList())

                // Auto-reconnect logic
                scope.launch {
                    delay(1000)
                    Log.d(TAG, "Auto-reconnecting via service...")
                    startScan()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged: mtu=$mtu, status=$status. Discovering services...")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                var found = false
                for (service in gatt.services) {
                    val characteristic = service.getCharacteristic(TARGET_CHAR_UUID)
                    if (characteristic != null) {
                        Log.d(TAG, "Found Radar Characteristic: ${characteristic.uuid}")
                        enableNotifications(gatt, characteristic)
                        _connectionState.value = ConnectionState.Connected
                        found = true
                        break
                    }
                }
                if (!found) {
                    Log.e(TAG, "Radar Characteristic NOT found in any service!")
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite: uuid=${descriptor.uuid}, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "SUCCESS: Notifications enabled on Gardia!")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value
            if (characteristic.uuid == TARGET_CHAR_UUID && value != null) {
                decodeRadarData(value)
            }
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == TARGET_CHAR_UUID) {
                decodeRadarData(value)
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun decodeRadarData(data: ByteArray) {
        if (data.isEmpty()) return
        val hexString = data.joinToString("") { "%02x".format(it) }
        _rawPacket.value = hexString

        val newTargets = mutableListOf<RadarTarget>()
        val dataInt = data.map { it.toInt() and 0xFF }

        // Byte 0: Header 0x30
        if (dataInt[0] == 0x30) {
            if (dataInt[3] > 0) {
                newTargets.add(RadarTarget(1, dataInt[3], dataInt[2], dataInt[1]))
            }
            if (dataInt[6] > 1) {
                newTargets.add(RadarTarget(2, dataInt[6], dataInt[5], dataInt[4]))
            }
        }

        // Byte 8: Header 0x31
        if (dataInt.size >= 15 && dataInt[8] == 0x31) {
            if (dataInt[11] > 0) {
                newTargets.add(RadarTarget(3, dataInt[11], dataInt[10], dataInt[9]))
            }
            if (dataInt[14] > 0) {
                newTargets.add(RadarTarget(4, dataInt[14], dataInt[13], dataInt[12]))
            }
        }

        _targets.value = newTargets

        // Wear OSに車列データを送信
        wearableDataHost.putTargetsData(newTargets)

        notificationManager.handleRadarUpdate(newTargets, phoneNotificationMode, wearNotificationMode)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.Disconnected
        _targets.value = emptyList()
        _rawPacket.value = ""

        // Wear OSに空の車列データを送信して古いデータをクリア
        wearableDataHost.putTargetsData(emptyList())
    }
}
