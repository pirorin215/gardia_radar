package com.pirorin215.gardiaradar.wear

import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
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

                    val intent = Intent(ACTION_CONNECTION_STATE_CHANGED).apply {
                        putExtra("isConnected", isConnected)
                    }
                    sendBroadcast(intent)

                    // 接続/切断を音と振動で通知
                    playConnectionFeedback(isConnected)
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
}
