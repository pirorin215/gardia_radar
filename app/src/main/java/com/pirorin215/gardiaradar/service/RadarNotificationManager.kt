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
import com.pirorin215.gardiaradar.data.AppSettingsRepository
import com.pirorin215.gardiaradar.data.NotificationMode
import com.pirorin215.gardiaradar.data.RadarTarget
import com.pirorin215.gardiaradar.data.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class RadarNotificationManager(
    private val context: Context,
    private val wearMessageSender: WearMessageSender,
    private val appSettingsRepository: AppSettingsRepository
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val CHANNEL_ID = "radar_alerts_v7"
    val NOTIFICATION_ID = 1001
    private var lastTargetCount = 0

    // Strong vibration pattern
    private val VIBRATION_PATTERN = longArrayOf(0, 1000, 200, 1000, 200, 1000)

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Radar Incoming Alert"
            val descriptionText = "Vehicle detection alerts"
            val importance = NotificationManager.IMPORTANCE_HIGH

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
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

    fun handleRadarUpdate(targets: List<RadarTarget>, phoneMode: NotificationMode, wearMode: NotificationMode) {
        val currentCount = targets.size

        // Handle phone notifications
        if (phoneMode == NotificationMode.OFF) {
            notificationManager.cancel(NOTIFICATION_ID)
        } else if (currentCount > 0) {
            when (phoneMode) {
                NotificationMode.FIRST_ONLY -> {
                    if (lastTargetCount == 0) {
                        sendNotification(
                            "Vehicle Detected!",
                            "Distance: ${targets[0].distance}m"
                        )
                    }
                }
                NotificationMode.EVERY_TIME -> {
                    if (currentCount > lastTargetCount) {
                        notificationManager.cancel(NOTIFICATION_ID)
                        sendNotification(
                            "New Vehicle!",
                            "${targets.size} vehicles approaching"
                        )
                    }
                }
                else -> {}
            }
        } else {
            if (lastTargetCount > 0) {
                notificationManager.cancel(NOTIFICATION_ID)
            }
        }

        // Handle Wear notifications
        if (wearMode == NotificationMode.OFF) {
            if (lastTargetCount > 0 && currentCount == 0) {
                wearMessageSender.sendRadarClear()
            }
        } else if (currentCount > 0) {
            when (wearMode) {
                NotificationMode.FIRST_ONLY -> {
                    if (lastTargetCount == 0) {
                        wearMessageSender.sendRadarAlert(targets)
                    }
                }
                NotificationMode.EVERY_TIME -> {
                    if (currentCount > lastTargetCount) {
                        wearMessageSender.sendRadarAlert(targets)
                    }
                }
                else -> {}
            }
        } else {
            if (lastTargetCount > 0) {
                wearMessageSender.sendRadarClear()
            }
        }

        lastTargetCount = currentCount
    }

    private fun sendNotification(title: String, text: String) {
        // Get fullscreen notification setting
        val useFullScreenNotification = runBlocking {
            appSettingsRepository.getFlow(Settings.USE_FULLSCREEN_NOTIFICATION)
                .first()
        }

        // Content intent → opens app
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, 0, contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Dismiss intent → cancels notification
        val dismissIntent = Intent(context, NotificationDismissReceiver::class.java).apply {
            action = NotificationDismissReceiver.ACTION_DISMISS
            putExtra(NotificationDismissReceiver.EXTRA_NOTIFICATION_ID, NOTIFICATION_ID)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context, 1, dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .extend(NotificationCompat.WearableExtender())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Dismiss",
                dismissPendingIntent
            )

        // Fullscreen notification if enabled
        if (useFullScreenNotification) {
            builder.setFullScreenIntent(contentPendingIntent, true)
        }

        val notification = builder.build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
