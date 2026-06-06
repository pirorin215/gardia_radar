package com.pirorin215.gardiaradar.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class RadarListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "RadarListenerService"
        const val PATH_RADAR_ALERT = "/radar-alert"
        const val PATH_RADAR_CLEAR = "/radar-clear"
        const val EXTRA_ALERT_JSON = "alert_json"
        const val ACTION_DISMISS = "com.pirorin215.gardiaradar.wear.ACTION_DISMISS"
        const val ACTION_DEBUG_ALERT = "com.pirorin215.gardiaradar.wear.ACTION_DEBUG_ALERT"
        const val ACTION_DEBUG_CLEAR = "com.pirorin215.gardiaradar.wear.ACTION_DEBUG_CLEAR"
    }

    private var vibrator: Vibrator? = null
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val alarmTimeoutRunnable = Runnable { stopAlert() }

    private val debugAlertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Debug alert receiver triggered: action=${intent?.action}")
            when (intent?.action) {
                ACTION_DEBUG_ALERT -> {
                    val jsonData = intent.getStringExtra(EXTRA_ALERT_JSON) ?: "{\"targetCount\":1,\"targets\":[{\"id\":1,\"distance\":100,\"speed\":30,\"threat\":1}]}"
                    Log.d(TAG, "Debug alert triggered, calling startAlert()")
                    startAlert(jsonData)
                }
                ACTION_DEBUG_CLEAR -> {
                    Log.d(TAG, "Debug clear triggered")
                    stopAlert()
                }
            }
        }
    }

    // 音の「チャンチャンチャン（休み）チャンチャンチャン」に合わせた振動パターン
    // 音の実際の長さ（約3.4秒）に合わせてパターンを調整
    private val vibrationTimings = longArrayOf(
        0,      // 開始
        350, 200, 600,
        1200,                        // 休み
        350, 200, 600
    )
    private val vibrationAmplitudes = intArrayOf(
        0,
        255, 0, 255,
        0,
        255, 0, 255
    )
    private val vibrationEffect = VibrationEffect.createWaveform(
        vibrationTimings, vibrationAmplitudes, -1 // 繰り返しなし
    )

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "RadarListenerService onCreate")
        vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        // デバッグ用BroadcastReceiverを登録
        val filter = IntentFilter()
        filter.addAction(ACTION_DEBUG_ALERT)
        filter.addAction(ACTION_DEBUG_CLEAR)
        registerReceiver(debugAlertReceiver, filter)
        Log.d(TAG, "Debug alert receiver registered")
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "=== Message Received ===")
        Log.d(TAG, "Path: ${messageEvent.path}")
        Log.d(TAG, "Data size: ${messageEvent.data.size} bytes")
        Log.d(TAG, "====================")

        when (messageEvent.path) {
            PATH_RADAR_ALERT -> {
                val jsonData = String(messageEvent.data, Charsets.UTF_8)
                Log.d(TAG, "Alert data: $jsonData")
                startAlert(jsonData)
            }
            PATH_RADAR_CLEAR -> {
                Log.d(TAG, "Clear signal received")
                stopAlert()
            }
        }
    }

    private fun startAlert(jsonData: String) {
        Log.d(TAG, "Starting alert...")

        // 1. アラーム音を1回だけ再生（先に開始してMediaPlayerの準備時間を確保）
        startAlarmSound()
        Log.d(TAG, "Alarm sound started")

        // 2. 音より少し遅れて振動開始（同期調整）
        handler.postDelayed({
            vibrator?.vibrate(vibrationEffect)
            Log.d(TAG, "Vibration started (delayed for sync)")
        }, 50)

        // 3. 振動・音の終了（約4.0秒）後に自動停止
        handler.postDelayed(alarmTimeoutRunnable, 4000L)

        // 4. 省電力モードでなければMainActivityを前面に持ってくる
        val powerSaving = getSharedPreferences(WearableDataListener.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(WearableDataListener.PREF_KEY_POWER_SAVING, false)

        if (!powerSaving) {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            Log.d(TAG, "MainActivity brought to front")
        } else {
            Log.d(TAG, "省電力モードが有効 - MainActivityの起動をスキップ")
        }
    }

    private fun startAlarmSound() {
        stopAlarmSound()
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                setDataSource(this@RadarListenerService, alarmUri)
                isLooping = false  // 1回のみ再生
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play alarm sound", e)
        }
    }

    private fun stopAlarmSound() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
    }

    fun stopAlert() {
        Log.d(TAG, "Stopping alert...")
        handler.removeCallbacks(alarmTimeoutRunnable)
        vibrator?.cancel()
        Log.d(TAG, "Vibration cancelled")
        stopAlarmSound()
        Log.d(TAG, "Alarm sound stopped")

        // 警告画面を閉じる
        val dismissIntent = Intent(ACTION_DISMISS)
        sendBroadcast(dismissIntent)
        Log.d(TAG, "Dismiss broadcast sent")
    }

    override fun onDestroy() {
        handler.removeCallbacks(alarmTimeoutRunnable)
        vibrator?.cancel()
        stopAlarmSound()
        try {
            unregisterReceiver(debugAlertReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "DebugAlertReceiver was not registered")
        }
        super.onDestroy()
    }
}
