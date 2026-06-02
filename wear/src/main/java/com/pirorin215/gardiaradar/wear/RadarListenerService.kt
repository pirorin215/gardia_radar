package com.pirorin215.gardiaradar.wear

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
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
    }

    private var vibrator: Vibrator? = null
    private var mediaPlayer: MediaPlayer? = null

    // 最大振幅255で振動（繰り返しなし）
    private val vibrationTimings = longArrayOf(0, 500, 200, 500, 200, 500, 200, 500)
    private val vibrationAmplitudes = intArrayOf(0, 255, 0, 255, 0, 255, 0, 255)
    private val vibrationEffect = VibrationEffect.createWaveform(
        vibrationTimings, vibrationAmplitudes, -1 // 繰り返しなし
    )

    override fun onCreate() {
        super.onCreate()
        vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "Message received: path=${messageEvent.path}")

        when (messageEvent.path) {
            PATH_RADAR_ALERT -> {
                val jsonData = String(messageEvent.data, Charsets.UTF_8)
                startAlert(jsonData)
            }
            PATH_RADAR_CLEAR -> {
                stopAlert()
            }
        }
    }

    private fun startAlert(jsonData: String) {
        // 1. 強力な振動開始
        vibrator?.vibrate(vibrationEffect)

        // 2. アラーム音を最大音量でループ再生
        startAlarmSound()

        // 3. フルスクリーン警告画面を起動
        val intent = Intent(this, RadarAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ALERT_JSON, jsonData)
        }
        startActivity(intent)
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
                isLooping = true
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
        vibrator?.cancel()
        stopAlarmSound()

        // 警告画面を閉じる
        val dismissIntent = Intent(ACTION_DISMISS)
        sendBroadcast(dismissIntent)
    }

    override fun onDestroy() {
        vibrator?.cancel()
        stopAlarmSound()
        super.onDestroy()
    }
}
