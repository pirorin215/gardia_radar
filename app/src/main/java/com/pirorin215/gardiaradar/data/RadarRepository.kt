package com.pirorin215.gardiaradar.data

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*

@SuppressLint("MissingPermission")
class RadarRepository(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "RadarRepository"
    private val TARGET_CHAR_UUID = UUID.fromString("f3641401-00b0-4240-ba50-05ca45bf8abc")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _targets = MutableStateFlow<List<RadarTarget>>(emptyList())
    val targets = _targets.asStateFlow()

    private val _rawPacket = MutableStateFlow("")
    val rawPacket = _rawPacket.asStateFlow()

    fun startScan() {
        if (_connectionState.value != ConnectionState.Disconnected) return
        
        _connectionState.value = ConnectionState.Scanning
        val scanner = adapter.bluetoothLeScanner ?: return
        scanner.startScan(scanCallback)
        
        // Timeout after 10 seconds and retry
        scope.launch {
            delay(10000)
            if (_connectionState.value == ConnectionState.Scanning) {
                Log.d(TAG, "Scan timed out. Retrying in 5s...")
                stopScan()
                _connectionState.value = ConnectionState.Disconnected
                delay(5000)
                startScan()
            }
        }
    }

    private fun stopScan() {
        adapter.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name ?: ""
            if (deviceName.contains("Gardia") || deviceName.contains("R300L")) {
                stopScan()
                connectToDevice(result.device)
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
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
                Log.d(TAG, "Disconnected from GATT server. Triggering auto-reconnect...")
                _connectionState.value = ConnectionState.Disconnected
                _targets.value = emptyList()
                _rawPacket.value = ""
                
                // Auto-reconnect logic
                scope.launch {
                    delay(5000) // Wait 5 seconds before retrying
                    Log.d(TAG, "Auto-reconnecting...")
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
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.Disconnected
        _targets.value = emptyList()
        _rawPacket.value = ""
    }
}
