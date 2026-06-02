package com.pirorin215.gardiaradar.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RadarScanService : Service() {

    companion object {
        const val NOTIFICATION_ID = 99
    }

    private val TAG = "RadarScanService"
    private val CHANNEL_ID = "RadarScanServiceChannel"

    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasBluetoothConnect = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasBluetoothScan = checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!hasBluetoothConnect && !hasBluetoothScan) {
                Log.e(TAG, "Bluetooth permissions not granted. Cannot start foreground service.")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification().build())
        startBleScan()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "RadarScanService onDestroy")
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

        stopBleScan()

        Log.d(TAG, "Starting BLE scan in service...")
        bluetoothAdapter?.bluetoothLeScanner?.startScan(null, scanSettings, bleScanCallback)
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
            if (deviceName.contains("Gardia") || deviceName.contains("R300L")) {
                Log.d(TAG, "Target device '$deviceName' found! Signaling connect.")
                stopBleScan()
                CoroutineScope(Dispatchers.IO).launch {
                    RadarScanServiceManager.emitDeviceFound(result.device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "Scan failed with error code: $errorCode")
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
