package com.pirorin215.gardiaradar.wear

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class WearableDataListener : WearableListenerService() {

    companion object {
        private const val TAG = "WearableDataListener"
        const val ACTION_TARGETS_UPDATED = "com.pirorin215.gardiaradar.wear.ACTION_TARGETS_UPDATED"
        const val ACTION_CONNECTION_STATE_CHANGED = "com.pirorin215.gardiaradar.wear.ACTION_CONNECTION_STATE_CHANGED"
        const val ACTION_RADAR_BATTERY = "com.pirorin215.gardiaradar.wear.ACTION_RADAR_BATTERY"
        const val PREFS_NAME = "radar_prefs"
        const val PREF_KEY_CONNECTED = "isConnected"
    }

    private var lastTargetsUpdateTime = 0L

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem != null) {
                val path = event.dataItem.uri.path
                if (path == "/radar-targets") {
                    // 1秒以内の重複更新をスキップ（スロットリング）
                    val now = System.currentTimeMillis()
                    if (now - lastTargetsUpdateTime < 1000L) return@forEach
                    lastTargetsUpdateTime = now
                    val dataMap: DataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val targetCount = dataMap.getInt("targetCount", 0)
                    val distancesArrayList = dataMap.getIntegerArrayList("distances")
                    val distances = distancesArrayList?.map { it.toInt() } ?: emptyList()

                    Log.d(TAG, "Targets received: count=$targetCount, distances=$distances")

                    val intent = Intent(ACTION_TARGETS_UPDATED).apply {
                        putExtra("targetCount", targetCount)
                        putIntegerArrayListExtra("distances", java.util.ArrayList(distances))
                    }
                    sendBroadcast(intent)
                } else if (path == "/radar-connection-state") {
                    val dataMap: DataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val isConnected = dataMap.getBoolean("isConnected", false)

                    Log.d(TAG, "Connection state received: connected=$isConnected")

                    // SharedPreferencesに接続状態をキャッシュ
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(PREF_KEY_CONNECTED, isConnected)
                        .apply()

                    val intent = Intent(ACTION_CONNECTION_STATE_CHANGED).apply {
                        putExtra("isConnected", isConnected)
                    }
                    sendBroadcast(intent)

                    // コンプリケーションを更新
                    updateComplications()

                    // 接続/切断を音と振動で通知し、アプリを前面に
                    playConnectionFeedback(isConnected)
                    bringMainActivityToFront()
                } else if (path == "/radar-battery") {
                    val dataMap: DataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val level = dataMap.getInt("level", -1)

                    Log.d(TAG, "Radar battery level received: $level%")

                    val intent = Intent(ACTION_RADAR_BATTERY).apply {
                        putExtra("level", level)
                    }
                    sendBroadcast(intent)
                }
            }
        }
    }

    private fun playConnectionFeedback(isConnected: Boolean) {
        // 振動
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        if (isConnected) {
            // 接続：短く2回振動
            val effect = VibrationEffect.createWaveform(
                longArrayOf(0, 100, 100, 100),
                intArrayOf(0, 200, 0, 200),
                -1
            )
            vibrator?.vibrate(effect)
        } else {
            // 切断：長めに1回振動
            val effect = VibrationEffect.createWaveform(
                longArrayOf(0, 300),
                intArrayOf(0, 255),
                -1
            )
            vibrator?.vibrate(effect)
        }

        // 音
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            if (isConnected) {
                // 接続：短く1回ビープ
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            } else {
                // 切断：2回ビープ
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            }
            toneGen.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play connection sound", e)
        }
    }

    private fun bringMainActivityToFront() {
        try {
            // 画面を起こす（WakeLock）
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GardiaRadar:ConnectionWake"
            )
            wakeLock.acquire(3000L) // 3秒で自動解放

            // Activityを前面に
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            Log.d(TAG, "MainActivity brought to front with WakeLock")
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
            Log.d(TAG, "Complication update requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request complication update", e)
        }
    }
}
