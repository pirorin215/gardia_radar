package com.pirorin215.gardiaradar.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pirorin215.gardiaradar.R
import com.pirorin215.gardiaradar.data.NotificationMode
import com.pirorin215.gardiaradar.data.RadarTarget

class RadarNotificationManager(private val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val CHANNEL_ID = "radar_alerts"
    private var lastTargetCount = 0

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Radar Alerts"
            val descriptionText = "Notifications for vehicle detection"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun handleRadarUpdate(targets: List<RadarTarget>, mode: NotificationMode) {
        if (mode == NotificationMode.OFF) {
            lastTargetCount = targets.size
            return
        }

        val currentCount = targets.size

        if (currentCount > 0) {
            when (mode) {
                NotificationMode.FIRST_ONLY -> {
                    if (lastTargetCount == 0) {
                        sendNotification("Vehicle Detected!", "Distance: ${targets[0].distance}m")
                    }
                }
                NotificationMode.EVERY_TIME -> {
                    if (currentCount > lastTargetCount) {
                        sendNotification("New Vehicle!", "${targets.size} vehicles approaching")
                    }
                }
                else -> {}
            }
        }

        lastTargetCount = currentCount
    }

    private fun sendNotification(title: String, text: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 100, 500))

        notificationManager.notify(1, builder.build())
    }
}
