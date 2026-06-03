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
        val putDataMapRequest = PutDataMapRequest.create("/radar-connection-state")
        putDataMapRequest.dataMap.putBoolean("isConnected", isConnected)
        putDataMapRequest.dataMap.putLong("timestamp", System.currentTimeMillis())

        val putDataRequest = putDataMapRequest.asPutDataRequest().setUrgent()

        dataClient.putDataItem(putDataRequest).apply {
            addOnSuccessListener {
                Log.d(TAG, "Connection state hosted: connected=$isConnected")
            }
            addOnFailureListener { e ->
                Log.e(TAG, "Failed to host connection state", e)
            }
        }
    }
}
