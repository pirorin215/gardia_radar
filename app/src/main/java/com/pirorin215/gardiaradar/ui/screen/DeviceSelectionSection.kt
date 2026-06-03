package com.pirorin215.gardiaradar.ui.screen

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun DeviceSelectionSection(
    currentDeviceName: String,
    currentDeviceAddress: String,
    onSelectDevice: (address: String, name: String) -> Unit,
    onClearDevice: () -> Unit
) {
    val context = LocalContext.current
    var isScanning by remember { mutableStateOf(false) }
    var discoveredDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var scanError by remember { mutableStateOf(false) }

    val bluetoothManager = remember {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    // スキャンコールバックをrememberで保持（同一インスタンスでstopScan可能にする）
    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                val device = result.device
                val name = device.name ?: return
                if (name.contains("Gardia", ignoreCase = true) || name.contains("R300L", ignoreCase = true)) {
                    discoveredDevices = (discoveredDevices + device)
                        .distinctBy { it.address }
                        .sortedByDescending { it.name }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                Log.e("DeviceSelection", "Scan failed: $errorCode")
                isScanning = false
                scanError = true
            }
        }
    }

    // コンポーザブルが破棄されたらスキャンを停止
    DisposableEffect(Unit) {
        onDispose {
            try {
                bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: Exception) {}
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("接続先デバイス", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        if (currentDeviceAddress.isNotEmpty()) {
            // デバイス選択済み
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        currentDeviceName.ifEmpty { "不明なデバイス" },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        currentDeviceAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onClearDevice) {
                    Text("解除")
                }
            }
        } else {
            // デバイス未選択
            if (isScanning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("スキャン中...", style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = {
                        bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
                        isScanning = false
                    }) {
                        Text("停止")
                    }
                }

                if (scanError) {
                    Text(
                        "スキャンに失敗しました。Bluetoothが有効か確認してください。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (discoveredDevices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                    ) {
                        items(discoveredDevices) { device ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clickable {
                                        bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
                                        isScanning = false
                                        onSelectDevice(device.address, device.name ?: "Unknown")
                                    }
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        device.name ?: "Unknown",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        device.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Text(
                    "デバイスが選択されていません",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        discoveredDevices = emptyList()
                        scanError = false
                        val scanner = bluetoothManager.adapter?.bluetoothLeScanner
                        if (scanner != null) {
                            val settings = ScanSettings.Builder()
                                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                                .build()
                            scanner.startScan(null, settings, scanCallback)
                            isScanning = true
                        }
                    },
                    enabled = bluetoothManager.adapter?.isEnabled == true
                ) {
                    Text("デバイスを検索")
                }

                if (bluetoothManager.adapter?.isEnabled != true) {
                    Text(
                        "Bluetoothが無効です",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
