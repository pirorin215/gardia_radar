package com.pirorin215.gardiaradar.notification

import android.service.notification.NotificationListenerService

/**
 * 通知アクセス権のホルダー。
 * ユーザーがこれを有効化すると、OSがこのサービスを常時バインドしようとするため、
 * アプリプロセスがキルされても自動再起動する強力な永続性が得られる。
 */
class GardiaNotificationListener : NotificationListenerService()
