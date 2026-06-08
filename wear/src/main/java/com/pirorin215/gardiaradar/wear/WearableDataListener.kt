package com.pirorin215.gardiaradar.wear

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

class WearableDataListener : WearableListenerService() {

    companion object {
        private const val TAG = "WearableDataListener"
        const val ACTION_TARGETS_UPDATED = "com.pirorin215.gardiaradar.wear.ACTION_TARGETS_UPDATED"
        const val ACTION_CONNECTION_STATE_CHANGED = "com.pirorin215.gardiaradar.wear.ACTION_CONNECTION_STATE_CHANGED"
        const val ACTION_RADAR_BATTERY = "com.pirorin215.gardiaradar.wear.ACTION_RADAR_BATTERY"
        const val ACTION_POWER_SAVING_MODE_CHANGED = "com.pirorin215.gardiaradar.wear.ACTION_POWER_SAVING_MODE_CHANGED"
        const val PREFS_NAME = "radar_prefs"
        const val PREF_KEY_CONNECTED = "isConnected"
        const val PREF_KEY_START_TIME = "connectionStartTime"
        const val PREF_KEY_END_TIME = "connectionEndTime"
        const val PREF_KEY_POWER_SAVING = "powerSavingMode"
        const val PREF_KEY_LAST_BATTERY_LEVEL = "lastSentBatteryLevel"
        const val PREF_KEY_LAST_BATTERY_TIME = "lastSentBatteryTime"
        const val PREF_KEY_ALERT_SOUND_ENABLED = "alertSoundEnabled"
        const val PREF_KEY_ALERT_VIBRATION_ENABLED = "alertVibrationEnabled"
    }

    private var lastTargetsUpdateTime = 0L

    // Vibratorキャッシュ（効率性改善）
    private val vibrator: Vibrator? by lazy { systemVibrator() }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            dataEvents.forEach { event ->
                if (event.type == DataEvent.TYPE_CHANGED && event.dataItem != null) {
                    val path = event.dataItem.uri.path
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    when (path) {
                        "/radar-targets" -> {
                            handleTargetsUpdate(dataMap)
                        }
                        "/radar-connection-state" -> {
                            handleConnectionStateUpdate(dataMap)
                        }
                        "/radar-battery" -> {
                            handleBatteryUpdate(dataMap)
                        }
                        "/power-saving-mode" -> {
                            handlePowerSavingUpdate(dataMap)
                        }
                        "/alert-settings" -> {
                            handleAlertSettingsUpdate(dataMap)
                        }
                    }
                }
            }
        } finally {
            dataEvents.release()
        }
    }

    private fun handleTargetsUpdate(dataMap: DataMap) {
        sendWatchBatteryToPhone(force = false)

        val powerSaving = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(PREF_KEY_POWER_SAVING, false)
        if (powerSaving) return

        val now = System.currentTimeMillis()
        if (now - lastTargetsUpdateTime < 1000L) return
        lastTargetsUpdateTime = now
        
        val targetCount = dataMap.getInt("targetCount", 0)
        val distancesArrayList = dataMap.getIntegerArrayList("distances")
        val distances = distancesArrayList?.map { it.toInt() } ?: emptyList()

        Log.d(TAG, "Targets received: count=$targetCount, distances=$distances")

        val intent = Intent(ACTION_TARGETS_UPDATED).apply {
            putExtra("targetCount", targetCount)
            putIntegerArrayListExtra("distances", java.util.ArrayList(distances))
        }
        sendBroadcast(intent)
    }

    private fun handleConnectionStateUpdate(dataMap: DataMap) {
        val isConnected = dataMap.getBoolean("isConnected", false)
        Log.d(TAG, "Connection state received: connected=$isConnected")

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val wasConnected = prefs.getBoolean(PREF_KEY_CONNECTED, false)
        var startTime = prefs.getLong(PREF_KEY_START_TIME, -1L)
        var endTime = prefs.getLong(PREF_KEY_END_TIME, -1L)
        val now = System.currentTimeMillis()

        if (isConnected) {
            if (!wasConnected) {
                // 切断 -> 接続 への遷移
                // 5分以内の再接続（瞬断やチャタリング）なら、以前の開始時刻を維持する
                if (startTime == -1L || (endTime != -1L && now - endTime > 300_000L)) {
                    Log.d(TAG, "New ride session started")
                    startTime = now
                } else {
                    Log.d(TAG, "Reconnected within grace period, maintaining startTime")
                }
                endTime = -1L
            }
        } else {
            if (wasConnected) {
                // 接続 -> 切断 への遷移
                Log.d(TAG, "Ride session paused/ended (disconnected)")
                endTime = now
            }
        }

        prefs.edit()
            .putBoolean(PREF_KEY_CONNECTED, isConnected)
            .putLong(PREF_KEY_START_TIME, startTime)
            .putLong(PREF_KEY_END_TIME, endTime)
            .apply()

        val intent = Intent(ACTION_CONNECTION_STATE_CHANGED).apply {
            putExtra("isConnected", isConnected)
            putExtra("startTime", startTime)
            putExtra("endTime", endTime)
        }
        sendBroadcast(intent)

        updateComplications()
        playConnectionFeedback(isConnected)
        bringMainActivityToFront()
        sendWatchBatteryToPhone(force = true)
    }

    private fun handleBatteryUpdate(dataMap: DataMap) {
        val level = dataMap.getInt("level", -1)
        Log.d(TAG, "Radar battery level received: $level%")
        val intent = Intent(ACTION_RADAR_BATTERY).apply {
            putExtra("level", level)
        }
        sendBroadcast(intent)
    }

    private fun handlePowerSavingUpdate(dataMap: DataMap) {
        val enabled = dataMap.getBoolean("enabled", false)
        Log.d(TAG, "Power saving mode received: $enabled")
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_KEY_POWER_SAVING, enabled)
            .apply()
        val intent = Intent(ACTION_POWER_SAVING_MODE_CHANGED).apply {
            putExtra("enabled", enabled)
        }
        sendBroadcast(intent)
        sendWatchBatteryToPhone(force = true)
    }

    private fun handleAlertSettingsUpdate(dataMap: DataMap) {
        val soundEnabled = dataMap.getBoolean("soundEnabled", true)
        val vibrationEnabled = dataMap.getBoolean("vibrationEnabled", true)
        Log.d(TAG, "Alert settings received: sound=$soundEnabled, vibration=$vibrationEnabled")
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_KEY_ALERT_SOUND_ENABLED, soundEnabled)
            .putBoolean(PREF_KEY_ALERT_VIBRATION_ENABLED, vibrationEnabled)
            .apply()
    }

    private fun sendWatchBatteryToPhone(force: Boolean) {
        try {
            val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryStatus?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

                if (batteryPct >= 0) {
                    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    val lastLevel = prefs.getInt(PREF_KEY_LAST_BATTERY_LEVEL, -1)
                    val lastTime = prefs.getLong(PREF_KEY_LAST_BATTERY_TIME, 0L)
                    val now = System.currentTimeMillis()

                    if (force || batteryPct != lastLevel || (now - lastTime) > 300_000L) {
                        val dataClient = Wearable.getDataClient(this)
                        val putDataMapReq = com.google.android.gms.wearable.PutDataMapRequest.create("/wear-battery")
                        putDataMapReq.dataMap.putInt("level", batteryPct)
                        putDataMapReq.dataMap.putLong("timestamp", now)
                        val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
                        dataClient.putDataItem(putDataReq)
                        
                        prefs.edit()
                            .putInt(PREF_KEY_LAST_BATTERY_LEVEL, batteryPct)
                            .putLong(PREF_KEY_LAST_BATTERY_TIME, now)
                            .apply()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send watch battery to phone", e)
        }
    }

    private fun playConnectionFeedback(isConnected: Boolean) {
        // 音・振動設定を確認
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val soundEnabled = prefs.getBoolean(PREF_KEY_ALERT_SOUND_ENABLED, true)
        val vibrationEnabled = prefs.getBoolean(PREF_KEY_ALERT_VIBRATION_ENABLED, true)

        if (vibrationEnabled) {
            val effect = if (isConnected) {
                VibrationEffect.createWaveform(longArrayOf(0, 100, 100, 100), intArrayOf(0, 200, 0, 200), -1)
            } else {
                VibrationEffect.createWaveform(longArrayOf(0, 300), intArrayOf(0, 255), -1)
            }
            vibrator?.vibrate(effect)
        }

        if (soundEnabled) {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                if (isConnected) {
                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                } else {
                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                }
                toneGen.release()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play connection sound", e)
            }
        }
    }

    private fun bringMainActivityToFront() {
        try {
            val powerSaving = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_KEY_POWER_SAVING, false)
            if (powerSaving) return

            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GardiaRadar:ConnectionWake"
            )
            wakeLock.acquire(3000L)

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bring MainActivity to front", e)
        }
    }

    private fun updateComplications() {
        try {
            val requester = ComplicationDataSourceUpdateRequester.create(
                this,
                ComponentName(this, RadarComplicationService::class.java)
            )
            requester.requestUpdateAll()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request complication update", e)
        }
    }
}
