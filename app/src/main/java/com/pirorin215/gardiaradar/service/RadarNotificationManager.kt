package com.pirorin215.gardiaradar.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pirorin215.gardiaradar.MainActivity
import com.pirorin215.gardiaradar.R
import com.pirorin215.gardiaradar.data.NotificationMode
import com.pirorin215.gardiaradar.data.RadarTarget

class RadarNotificationManager(private val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val CHANNEL_ID = "radar_alerts_v5" // v5 for FullScreen Alarm intent
    private val NOTIFICATION_ID = 1001
    private var lastTargetCount = 0

    // Ultra-strong vibration pattern for standard display
    private val VIBRATION_PATTERN = longArrayOf(0, 1000, 200, 1000, 200, 1000)

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Radar Critical Alerts"
            val descriptionText = "Urgent vehicle detection (Alarm haptics)"
            val importance = NotificationManager.IMPORTANCE_HIGH
            
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
                setSound(null, audioAttributes)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun handleRadarUpdate(targets: List<RadarTarget>, mode: NotificationMode) {
        if (mode == NotificationMode.OFF) {
            notificationManager.cancelAll()
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
                        notificationManager.cancel(NOTIFICATION_ID)
                        sendNotification("New Vehicle!", "${targets.size} vehicles approaching")
                    }
                }
                else -> {}
            }
        } else {
            if (lastTargetCount > 0) {
                notificationManager.cancel(NOTIFICATION_ID)
            }
        }

        lastTargetCount = currentCount
    }

    private fun sendNotification(title: String, text: String) {
        // Create a PendingIntent for FullScreen behavior (Alarm mode trigger)
        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 0, fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true) // CRITICAL: This triggers Alarm UI/Haptics on Watch
            .setAutoCancel(true)
            .setOngoing(false)
            .setVibrate(VIBRATION_PATTERN)
            .setOnlyAlertOnce(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .extend(NotificationCompat.WearableExtender())

        val notification = builder.build()
        
        // HACK: FLAG_INSISTENT makes the vibration/sound repeat like a real alarm
        notification.flags = notification.flags or Notification.FLAG_INSISTENT

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
