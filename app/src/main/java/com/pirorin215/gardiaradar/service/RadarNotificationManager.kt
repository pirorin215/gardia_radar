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
import androidx.core.app.Person
import com.pirorin215.gardiaradar.MainActivity
import com.pirorin215.gardiaradar.R
import com.pirorin215.gardiaradar.data.NotificationMode
import com.pirorin215.gardiaradar.data.RadarTarget

class RadarNotificationManager(
    private val context: Context,
    private val wearMessageSender: WearMessageSender
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val CHANNEL_ID = "radar_alerts_v6" // v6 for CallStyle
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
            val descriptionText = "Vehicle detection (Incoming call style)"
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
                        sendCallNotification(
                            "Vehicle Detected!",
                            "Distance: ${targets[0].distance}m"
                        )
                        wearMessageSender.sendRadarAlert(targets)
                    }
                }
                NotificationMode.EVERY_TIME -> {
                    if (currentCount > lastTargetCount) {
                        notificationManager.cancel(NOTIFICATION_ID)
                        sendCallNotification(
                            "New Vehicle!",
                            "${targets.size} vehicles approaching"
                        )
                        wearMessageSender.sendRadarAlert(targets)
                    }
                }
                else -> {}
            }
        } else {
            if (lastTargetCount > 0) {
                notificationManager.cancel(NOTIFICATION_ID)
                wearMessageSender.sendRadarClear()
            }
        }

        lastTargetCount = currentCount
    }

    private fun sendCallNotification(title: String, text: String) {
        // FullScreen intent → opens app (acts as "answer")
        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 0, fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Dismiss intent → cancels notification (acts as "decline")
        val dismissIntent = Intent(context, NotificationDismissReceiver::class.java).apply {
            action = NotificationDismissReceiver.ACTION_DISMISS
            putExtra(NotificationDismissReceiver.EXTRA_NOTIFICATION_ID, NOTIFICATION_ID)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context, 1, dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // CallStyle caller
        val caller = Person.Builder()
            .setName(title)
            .setImportant(true)
            .build()

        val callStyle = NotificationCompat.CallStyle.forIncomingCall(
            caller,
            fullScreenPendingIntent,  // "answer" → open app
            dismissPendingIntent      // "decline" → dismiss notification
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setStyle(callStyle)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .extend(NotificationCompat.WearableExtender())

        val notification = builder.build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
