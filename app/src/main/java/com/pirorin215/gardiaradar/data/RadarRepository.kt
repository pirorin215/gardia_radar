package com.pirorin215.gardiaradar.data

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.pirorin215.gardiaradar.service.RadarNotificationManager
import com.pirorin215.gardiaradar.service.WearableDataHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.*

/**
 * 純粋なGATT通信クラス。
 * 接続ライフサイクル（再接続・スキャン起動）は RadarConnectionManager が担当。
 *
 * 責務:
 * - BluetoothGattCallback の処理
 * - connectGatt / discoverServices / MTU要求
 * - レーダーデータのデコード
 * - 電池残量の管理
 * - 通知の有効化/無効化
 */
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
    private val BATTERY_LEVEL_CHAR_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var gatt: BluetoothGatt? = null
    private var phoneNotificationMode: NotificationMode = NotificationMode.FIRST_ONLY
    private var wearNotificationMode: NotificationMode = NotificationMode.FIRST_ONLY

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName = _connectedDeviceName.asStateFlow()

    private val _targets = MutableStateFlow<List<RadarTarget>>(emptyList())
    val targets = _targets.asStateFlow()

    private val _rawPacket = MutableStateFlow("")
    val rawPacket = _rawPacket.asStateFlow()

    private val _radarBatteryLevel = MutableStateFlow(-1)
    val radarBatteryLevel = _radarBatteryLevel.asStateFlow()

    val suppressionRemainingSeconds = notificationManager.suppressionRemainingSeconds

    private var batteryChar: BluetoothGattCharacteristic? = null
    private var batteryRetryScheduled = false

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
    }

    // --- RadarConnectionManager から呼ばれる公開メソッド ---

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    fun setConnectedDeviceName(name: String?) {
        _connectedDeviceName.value = name
    }

    fun resetState() {
        _connectionState.value = ConnectionState.Disconnected
        _connectedDeviceName.value = null
        _targets.value = emptyList()
        _rawPacket.value = ""
        _radarBatteryLevel.value = -1
        batteryChar = null
        batteryRetryScheduled = false
    }

    fun connect(device: android.bluetooth.BluetoothDevice) {
        Log.d(TAG, "Connecting to device: ${device.address}")
        _connectedDeviceName.value = device.name
        _connectionState.value = ConnectionState.Connecting
        gatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.Disconnected
        _connectedDeviceName.value = null
        _targets.value = emptyList()
        _rawPacket.value = ""
        _radarBatteryLevel.value = -1
        batteryChar = null
        batteryRetryScheduled = false
        notificationManager.handleBatteryUpdate(-1)
        wearableDataHost.putTargetsData(emptyList())
    }

    fun close() {
        gatt?.close()
        gatt = null
    }

    // --- GATT Callback（純粋なGATT通信処理のみ）---

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Connected to GATT server. Requesting MTU...")
                    gatt.requestMtu(512)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "Successfully disconnected from GATT server.")
                    gatt.close()
                    this@RadarRepository.gatt = null
                    _connectionState.value = ConnectionState.Disconnected
                }
            } else {
                Log.e(TAG, "GATT Error: status=$status for ${gatt.device.address}")
                gatt.close()
                this@RadarRepository.gatt = null
                _connectionState.value = ConnectionState.Error("GATT Error $status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged: mtu=$mtu, status=$status.")
            // FastRecMobパターン: discoverServices前に確実に待機
            scope.launch {
                delay(600)
                Log.d(TAG, "Discovering services...")
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // デバイスの全サービス・キャラクタリスティックを列挙
                Log.d(TAG, "=== Device Service Enumeration ===")
                for (service in gatt.services) {
                    Log.d(TAG, "Service: ${service.uuid} (${service.characteristics.size} chars)")
                    for (char in service.characteristics) {
                        val props = mutableListOf<String>()
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) props.add("READ")
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) props.add("NOTIFY")
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) props.add("WRITE")
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) props.add("INDICATE")
                        Log.d(TAG, "  Char: ${char.uuid} properties=[${props.joinToString(",")}]")
                    }
                }
                Log.d(TAG, "=== End Service Enumeration ===")

                var found = false
                for (service in gatt.services) {
                    val characteristic = service.getCharacteristic(TARGET_CHAR_UUID)
                    if (characteristic != null) {
                        Log.d(TAG, "Found Radar Characteristic: ${characteristic.uuid} in service: ${service.uuid}")
                        enableNotifications(gatt, characteristic)
                        _connectionState.value = ConnectionState.Connected
                        found = true
                    }

                    val battery = service.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)
                    if (battery != null) {
                        val batProps = mutableListOf<String>()
                        if (battery.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) batProps.add("READ")
                        if (battery.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) batProps.add("NOTIFY")
                        Log.d(TAG, "Found Battery Level Characteristic in service: ${service.uuid}, properties=[${batProps.joinToString(",")}]")
                        batteryChar = battery
                    }
                }
                if (!found) {
                    Log.e(TAG, "Radar Characteristic NOT found in any service!")
                } else {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite: uuid=${descriptor.uuid}, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "SUCCESS: Notifications enabled on Gardia!")
                batteryChar?.let { battery ->
                    if (battery.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                        Log.d(TAG, "Reading battery level...")
                        gatt.readCharacteristic(battery)
                    } else {
                        Log.d(TAG, "Enabling battery level notifications...")
                        enableNotifications(gatt, battery)
                    }
                    batteryChar = null
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == BATTERY_LEVEL_CHAR_UUID) {
                val value = characteristic.value
                val hex = value?.joinToString(" ") { "%02x".format(it) } ?: "null"
                Log.d(TAG, "onCharacteristicRead (legacy): status=$status, bytes=[$hex] (${value?.size ?: 0} bytes)")
                if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
                    updateBatteryLevel(value, "read")
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (characteristic.uuid == BATTERY_LEVEL_CHAR_UUID) {
                val hex = value.joinToString(" ") { "%02x".format(it) }
                Log.d(TAG, "onCharacteristicRead: status=$status, bytes=[$hex] (${value.size} bytes)")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    updateBatteryLevel(value, "read")
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value
            if (value != null) {
                when (characteristic.uuid) {
                    TARGET_CHAR_UUID -> decodeRadarData(value)
                    BATTERY_LEVEL_CHAR_UUID -> updateBatteryLevel(value, "notification")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            when (characteristic.uuid) {
                TARGET_CHAR_UUID -> decodeRadarData(value)
                BATTERY_LEVEL_CHAR_UUID -> updateBatteryLevel(value, "notification")
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

    private fun updateBatteryLevel(value: ByteArray, source: String) {
        if (value.isNotEmpty()) {
            val level = value[0].toInt() and 0xFF
            val hex = value.joinToString(" ") { "%02x".format(it) }
            val allBytes = value.joinToString(", ") { "${it.toInt() and 0xFF} (0x${"%02x".format(it)})" }
            Log.d(TAG, "Radar battery level: $level% (source=$source, raw=[$hex], ${value.size} bytes, all=[$allBytes])")

            // 初回readで100%が返った場合、キャッシュ値の可能性が高いのでリトライ
            if (level == 100 && source == "read" && !batteryRetryScheduled) {
                Log.w(TAG, "Battery 100% from initial read - possibly stale. Scheduling retry in 2s...")
                batteryRetryScheduled = true
                scope.launch {
                    delay(2000)
                    retryBatteryRead()
                }
                return
            }

            batteryRetryScheduled = false
            _radarBatteryLevel.value = level
            wearableDataHost.putRadarBatteryLevel(level)
            notificationManager.handleBatteryUpdate(level)
        }
    }

    private fun retryBatteryRead() {
        gatt?.let { g ->
            for (service in g.services) {
                val battery = service.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)
                if (battery != null && battery.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    Log.d(TAG, "Retrying battery level read...")
                    g.readCharacteristic(battery)
                    return
                }
            }
        }
        Log.w(TAG, "Battery retry: could not find readable battery characteristic")
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
        wearableDataHost.putTargetsData(newTargets)
        notificationManager.handleRadarUpdate(newTargets, phoneNotificationMode, wearNotificationMode)
    }
}
