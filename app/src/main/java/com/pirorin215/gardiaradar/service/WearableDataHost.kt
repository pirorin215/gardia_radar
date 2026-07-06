package com.pirorin215.gardiaradar.service

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.pirorin215.gardiaradar.data.RadarTarget

class WearableDataHost(
    private val context: Context
) {
    private val TAG = "WearableDataHost"
    private val dataClient = Wearable.getDataClient(context)

    fun putTargetsData(targets: List<RadarTarget>) {
        val putDataMapRequest = PutDataMapRequest.create("/radar-targets")

        // 車両数
        putDataMapRequest.dataMap.putInt("targetCount", targets.size)

        // 距離配列（最初の4台まで）
        val distances = IntArray(minOf(4, targets.size))
        for (i in distances.indices) {
            distances[i] = targets[i].distance
        }
        putDataMapRequest.dataMap.putIntegerArrayList("distances", distances.toCollection(java.util.ArrayList()))

        // 更新を強制するためにタイムスタンプを含める
        putDataMapRequest.dataMap.putLong("timestamp", System.currentTimeMillis())

        val putDataRequest = putDataMapRequest.asPutDataRequest().setUrgent()

        dataClient.putDataItem(putDataRequest).apply {
            addOnSuccessListener {
                // 0台の場合はログを抑止
                if (targets.size > 0) {
                    Log.d(TAG, "Targets data hosted: ${targets.size} vehicles")
                }
            }
            addOnFailureListener { e ->
                Log.e(TAG, "Failed to host targets data", e)
            }
        }
    }

    fun putConnectionStateData(isConnected: Boolean) {
        Log.d(TAG, "putConnectionStateData called: isConnected=$isConnected")
        val putDataMapRequest = PutDataMapRequest.create("/radar-connection-state")
        putDataMapRequest.dataMap.putBoolean("isConnected", isConnected)
        putDataMapRequest.dataMap.putLong("timestamp", System.currentTimeMillis())

        val putDataRequest = putDataMapRequest.asPutDataRequest().setUrgent()

        dataClient.putDataItem(putDataRequest).apply {
            addOnSuccessListener {
                Log.d(TAG, "Successfully hosted connection state: connected=$isConnected")
            }
            addOnFailureListener { e ->
                Log.e(TAG, "Failed to host connection state: connected=$isConnected", e)
            }
        }
    }

    fun putRadarBatteryLevel(level: Int) {
        val putDataMapRequest = PutDataMapRequest.create("/radar-battery")
        putDataMapRequest.dataMap.putInt("level", level)
        putDataMapRequest.dataMap.putLong("timestamp", System.currentTimeMillis())

        val putDataRequest = putDataMapRequest.asPutDataRequest().setUrgent()

        dataClient.putDataItem(putDataRequest).apply {
            addOnSuccessListener {
                Log.d(TAG, "Radar battery level hosted: $level%")
            }
            addOnFailureListener { e ->
                Log.e(TAG, "Failed to host radar battery level", e)
            }
        }
    }

    fun putPowerSavingModeData(enabled: Boolean) {
        val putDataMapRequest = PutDataMapRequest.create("/power-saving-mode")
        putDataMapRequest.dataMap.putBoolean("enabled", enabled)
        putDataMapRequest.dataMap.putLong("timestamp", System.currentTimeMillis())

        val putDataRequest = putDataMapRequest.asPutDataRequest().setUrgent()

        dataClient.putDataItem(putDataRequest).apply {
            addOnSuccessListener {
                Log.d(TAG, "Power saving mode hosted: $enabled")
            }
            addOnFailureListener { e ->
                Log.e(TAG, "Failed to host power saving mode", e)
            }
        }
    }

    fun putAlertData(hasTargets: Boolean) {
        val putDataMapRequest = PutDataMapRequest.create("/radar-alert")
        putDataMapRequest.dataMap.putBoolean("hasTargets", hasTargets)
        putDataMapRequest.dataMap.putLong("timestamp", System.currentTimeMillis())

        val putDataRequest = putDataMapRequest.asPutDataRequest().setUrgent()

        dataClient.putDataItem(putDataRequest).apply {
            addOnSuccessListener {
                Log.d(TAG, "Alert data hosted: hasTargets=$hasTargets")
            }
            addOnFailureListener { e ->
                Log.e(TAG, "Failed to host alert data", e)
            }
        }
    }

    fun putCooldownCleared() {
        val putDataMapRequest = PutDataMapRequest.create("/cooldown-cleared")
        putDataMapRequest.dataMap.putLong("timestamp", System.currentTimeMillis())

        val putDataRequest = putDataMapRequest.asPutDataRequest().setUrgent()

        dataClient.putDataItem(putDataRequest).apply {
            addOnSuccessListener {
                Log.d(TAG, "Cooldown cleared notification hosted")
            }
            addOnFailureListener { e ->
                Log.e(TAG, "Failed to host cooldown cleared notification", e)
            }
        }
    }

    fun putAlertSettingsData(soundEnabled: Boolean, vibrationEnabled: Boolean) {
        val putDataMapRequest = PutDataMapRequest.create("/alert-settings")
        putDataMapRequest.dataMap.putBoolean("soundEnabled", soundEnabled)
        putDataMapRequest.dataMap.putBoolean("vibrationEnabled", vibrationEnabled)
        putDataMapRequest.dataMap.putLong("timestamp", System.currentTimeMillis())

        val putDataRequest = putDataMapRequest.asPutDataRequest().setUrgent()

        dataClient.putDataItem(putDataRequest).apply {
            addOnSuccessListener {
                Log.d(TAG, "Alert settings hosted: sound=$soundEnabled, vibration=$vibrationEnabled")
            }
            addOnFailureListener { e ->
                Log.e(TAG, "Failed to host alert settings", e)
            }
        }
    }
}
