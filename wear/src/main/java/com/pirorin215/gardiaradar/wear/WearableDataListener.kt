package com.pirorin215.gardiaradar.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
        private const val CHANNEL_ID = "radar_connection"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem != null) {
                val path = event.dataItem.uri.path
                if (path == "/radar-targets") {
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

                    // 接続/切断通知を表示
                    showConnectionNotification(isConnected)
                }
            }
        }
    }

    private fun showConnectionNotification(isConnected: Boolean) {
        // 通知チャンネルの作成（Android 8.0以降）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Radar Connection",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for radar connection status"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        val title = if (isConnected) "Gardia Radar" else "Gardia Radar"
        val message = if (isConnected) "🔗 レーダーに接続しました" else "🔌 レーダーが切断されました"

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show notification: ${e.message}")
        }
    }
}
