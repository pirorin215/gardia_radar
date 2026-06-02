package com.pirorin215.gardiaradar.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationDismissReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DISMISS = "com.pirorin215.gardiaradar.ACTION_DISMISS_ALERT"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DISMISS) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 1001)
            notificationManager.cancel(notificationId)
        }
    }
}
