package com.pirorin215.gardiaradar.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pirorin215.gardiaradar.MainActivity
import com.pirorin215.gardiaradar.R
import com.pirorin215.gardiaradar.data.AppSettingsRepository
import com.pirorin215.gardiaradar.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class RadarScanService : Service() {

    companion object {
        const val NOTIFICATION_ID = 99
    }

    private val TAG = "RadarScanService"
    private val CHANNEL_ID = "RadarScanServiceChannel"

    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null

    private val appSettingsRepository: AppSettingsRepository by inject()
    private var targetDeviceAddress = ""

    private var scanRetryCount = 0
    private var scanRetryJob: Job? = null
    private val maxScanRetryDelay = 30_000L // 最大30秒
    private var lastScanStartTime = 0L
    private val MIN_SCAN_INTERVAL_MS = 6000L // Android制限(30秒間5回)に対する安全マージン

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d(TAG, "Bluetooth turned OFF - stopping scan")
                        stopBleScan()
                    }
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(TAG, "Bluetooth turned ON - starting scan")
                        startBleScan()
                    }
                }
            }
        }
    }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
        .build()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "RadarScanService onCreate")
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        createNotificationChannel()

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothStateReceiver, filter)

        // Subscribe to target device address changes
        CoroutineScope(Dispatchers.IO).launch {
            appSettingsRepository.getFlow(Settings.TARGET_DEVICE_ADDRESS).collect { address ->
                if (targetDeviceAddress != address) {
                    targetDeviceAddress = address
                    Log.d(TAG, "Target device address updated to: $address")
                    // アドレスが新しく設定されたらスキャンを再開
                    if (address.isNotEmpty()) {
                        startBleScan()
                    }
                }
            }
        }

        // Subscribe to restart scan events
        CoroutineScope(Dispatchers.IO).launch {
            RadarScanServiceManager.restartScanFlow.collect {
                Log.d(TAG, "Received restart scan signal. Restarting BLE scan.")
                startBleScan()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "RadarScanService onStartCommand")

        // 権限不足で startForeground / BLE スキャンを呼ぶと SecurityException でクラッシュするため、
        // 起動前に必ずチェックして弾く。従来は「両方無い」時しか弾かないバグ（&&）があり、
        // 片方だけ無いとスキャン開始で落ちていた。共有モジュールで SDK 差異も吸収。
        if (!com.pirorin215.permissioncore.PermissionChecker.hasBleScanPermissions(this)) {
            Log.e(TAG, "Bluetooth scan permissions not granted. Cannot start foreground service.")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification().build())

        // Load the latest target device address before starting scan
        CoroutineScope(Dispatchers.IO).launch {
            targetDeviceAddress = appSettingsRepository.getFlow(Settings.TARGET_DEVICE_ADDRESS).first()
            startBleScan()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "RadarScanService onDestroy")
        scanRetryJob?.cancel()
        stopBleScan()
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "BluetoothStateReceiver was not registered")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth not available or not enabled.")
            return
        }

        // スキャン開始間隔の制限（Androidの "scanning too frequently" を回避）
        val now = System.currentTimeMillis()
        val elapsed = now - lastScanStartTime
        if (elapsed < MIN_SCAN_INTERVAL_MS) {
            val waitMs = MIN_SCAN_INTERVAL_MS - elapsed
            Log.w(TAG, "Scan throttled - waiting ${waitMs}ms (min interval=${MIN_SCAN_INTERVAL_MS}ms)")
            scanRetryJob?.cancel()
            scanRetryJob = CoroutineScope(Dispatchers.IO).launch {
                delay(waitMs)
                doStartBleScan()
            }
            return
        }

        doStartBleScan()
    }

    @SuppressLint("MissingPermission")
    private fun doStartBleScan() {
        lastScanStartTime = System.currentTimeMillis()
        scanRetryJob?.cancel()
        stopBleScan()

        val filters = if (targetDeviceAddress.isNotEmpty()) {
            Log.d(TAG, "Starting BLE scan with hardware filter for address: $targetDeviceAddress")
            listOf(
                ScanFilter.Builder()
                    .setDeviceAddress(targetDeviceAddress)
                    .build()
            )
        } else {
            Log.d(TAG, "Starting BLE scan without hardware filter (no target device address saved)")
            null
        }

        bluetoothAdapter?.bluetoothLeScanner?.startScan(filters, scanSettings, bleScanCallback)
        scanRetryCount = 0
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        Log.d(TAG, "Stopping BLE scan in service.")
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback)
    }

    private val bleScanCallback = @SuppressLint("MissingPermission") object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val deviceName = result.device.name ?: ""

            // ハードウェアScanFilterが設定されている場合は直接採用
            // 未設定の場合はソフトウェアフィルタで判定
            val isTarget = if (targetDeviceAddress.isNotEmpty()) {
                result.device.address == targetDeviceAddress
            } else {
                deviceName.contains("Gardia", ignoreCase = true) || deviceName.contains("R300L", ignoreCase = true)
            }

            if (isTarget) {
                Log.d(TAG, "Target device '$deviceName' (${result.device.address}) found! RSSI: ${result.rssi}dBm. Signaling connect.")
                stopBleScan()
                CoroutineScope(Dispatchers.IO).launch {
                    RadarScanServiceManager.emitDeviceFound(result)
                }
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)
            Log.d(TAG, "onBatchScanResults: ${results.size} devices found.")
            results.forEach { result ->
                val deviceName = result.device.name ?: ""
                val isTarget = if (targetDeviceAddress.isNotEmpty()) {
                    result.device.address == targetDeviceAddress
                } else {
                    deviceName.contains("Gardia", ignoreCase = true) || deviceName.contains("R300L", ignoreCase = true)
                }
                if (isTarget) {
                    Log.d(TAG, "Target device '$deviceName' (${result.device.address}) found in batch! RSSI: ${result.rssi}dBm")
                    stopBleScan()
                    CoroutineScope(Dispatchers.IO).launch {
                        RadarScanServiceManager.emitDeviceFound(result)
                    }
                    return
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "Scan failed with error code: $errorCode")

            // リトライ（指数バックオフ: 5s, 10s, 20s, 30s, 30s...）
            val delayMs = minOf(5000L * (1L shl minOf(scanRetryCount, 2)), maxScanRetryDelay)
            scanRetryCount++
            Log.w(TAG, "Retrying scan in ${delayMs}ms (attempt $scanRetryCount)")
            scanRetryJob?.cancel()
            scanRetryJob = CoroutineScope(Dispatchers.IO).launch {
                delay(delayMs)
                startBleScan()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Radar Scan Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(): NotificationCompat.Builder {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gardia Radar")
            .setContentText("Radarデバイスをスキャン中...")
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
    }
}
