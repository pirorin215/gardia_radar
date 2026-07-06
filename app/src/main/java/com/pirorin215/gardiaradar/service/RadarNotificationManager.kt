package com.pirorin215.gardiaradar.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pirorin215.gardiaradar.MainActivity
import com.pirorin215.gardiaradar.data.AppSettingsRepository
import com.pirorin215.gardiaradar.data.NotificationMode
import com.pirorin215.gardiaradar.data.RadarTarget
import com.pirorin215.gardiaradar.data.Settings
import com.pirorin215.gardiaradar.util.systemVibrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class RadarNotificationManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val wearMessageSender: WearMessageSender,
    private val appSettingsRepository: AppSettingsRepository,
    private val wearableDataHost: WearableDataHost
) {
    private val TAG = "RadarNotificationManager"
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val LOW_BATTERY_CHANNEL_ID = "radar_low_battery"
    private val LOW_BATTERY_NOTIFICATION_ID = 1002
    private var lastTargetCount = 0
    private var lowBatteryNotified = false
    private var lastClearTimestamp: Long = 0

    // クールダウンタイマー用
    private val _suppressionRemainingSeconds = MutableStateFlow(0)
    val suppressionRemainingSeconds = _suppressionRemainingSeconds.asStateFlow()
    private var suppressionCountdownJob: Job? = null
    private var cooldownActive = false

    // 定期通知用
    private val notificationScope = CoroutineScope(Job())
    private var periodicNotificationJob: Job? = null
    private var currentTargets: List<RadarTarget> = emptyList()

    // 音・振動
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private val alertTimeoutRunnable = Runnable { stopAlertSound() }
    private val vibrator: Vibrator? = context.systemVibrator()

    private val vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
    private val vibrationAmplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
    private val vibrationEffect = VibrationEffect.createWaveform(vibrationPattern, vibrationAmplitudes, -1)

    // 設定値キャッシュ（パフォーマンス改善）
    private var cachedPhoneAlertSoundEnabled = true
    private var cachedPhoneAlertVibrationEnabled = true
    private var cachedPhoneAlertSoundUri = ""

    init {
        createLowBatteryChannel()
        // 設定値をキャッシュに読み込み
        scope.launch {
            kotlinx.coroutines.flow.combine(
                appSettingsRepository.getFlow(Settings.PHONE_ALERT_SOUND_ENABLED),
                appSettingsRepository.getFlow(Settings.PHONE_ALERT_VIBRATION_ENABLED),
                appSettingsRepository.getFlow(Settings.PHONE_ALERT_SOUND_URI)
            ) { soundEnabled, vibrationEnabled, soundUri ->
                Triple(soundEnabled, vibrationEnabled, soundUri)
            }.collect { (soundEnabled, vibrationEnabled, soundUri) ->
                cachedPhoneAlertSoundEnabled = soundEnabled
                cachedPhoneAlertVibrationEnabled = vibrationEnabled
                cachedPhoneAlertSoundUri = soundUri
            }
        }
    }

    private fun createLowBatteryChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Radar Low Battery"
            val descriptionText = "Low battery alerts for radar device"
            val importance = NotificationManager.IMPORTANCE_DEFAULT

            val channel = NotificationChannel(LOW_BATTERY_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun handleRadarUpdate(targets: List<RadarTarget>, phoneMode: NotificationMode, wearMode: NotificationMode) {
        val currentCount = targets.size
        currentTargets = targets // 現在のターゲットを保持

        // 車両がクリアされたときにタイムスタンプを記録＆カウントダウン開始
        if (currentCount == 0 && lastTargetCount > 0) {
            lastClearTimestamp = System.currentTimeMillis()
            if (lastClearTimestamp > 0) {
                Log.d(TAG, "Vehicles cleared. Suppression period started.")
                startSuppressionCountdown()
            }
        }

        // 新しい車列検知時：抑制時間をチェック
        if (currentCount > 0 && lastTargetCount == 0 && lastClearTimestamp > 0) {
            val suppressionSeconds = runBlocking {
                appSettingsRepository.getFlow(Settings.CLEAR_SUPPRESSION_SECONDS).first()
            }
            val timeSinceClear = (System.currentTimeMillis() - lastClearTimestamp) / 1000

            if (timeSinceClear <= suppressionSeconds) {
                Log.d(TAG, "=== Radar Update ===")
                Log.d(TAG, "Suppressed: Vehicles reappeared ${timeSinceClear}s after clearing (within ${suppressionSeconds}s threshold)")
                Log.d(TAG, "Targets: $currentCount (last: $lastTargetCount)")
                Log.d(TAG, "Phone mode: $phoneMode, Wear mode: $wearMode")
                Log.d(TAG, "==================")
                lastTargetCount = currentCount
                return // 通知しない
            } else {
                // 抑制期間を過ぎたのでカウントダウンをリセット
                stopSuppressionCountdown()
            }
        }

        // 車両数が変化したときのみ詳細ログを出力
        val countChanged = currentCount != lastTargetCount
        if (!countChanged) {
            // 車両数が変わっていない場合は通知処理のみ実行（ログなし）
            processNotifications(targets, phoneMode, wearMode, currentCount, false)
            lastTargetCount = currentCount
            return
        }

        Log.d(TAG, "=== Radar Update ===")
        Log.d(TAG, "Targets: $currentCount (last: $lastTargetCount)")
        Log.d(TAG, "Phone mode: $phoneMode, Wear mode: $wearMode")

        // EVERY_TIMEモードの定期通知制御
        val isEveryTimeMode = phoneMode == NotificationMode.EVERY_TIME || wearMode == NotificationMode.EVERY_TIME
        if (currentCount > 0 && isEveryTimeMode) {
            startPeriodicAlert(phoneMode, wearMode)
        } else {
            stopPeriodicAlert()
        }

        processNotifications(targets, phoneMode, wearMode, currentCount, true)

        lastTargetCount = currentCount
        // 通常通知時はカウントダウン停止（抑制期間外の車両検知）
        if (currentCount > 0) {
            stopSuppressionCountdown()
        }
        Log.d(TAG, "==================")
    }

    private fun processNotifications(targets: List<RadarTarget>, phoneMode: NotificationMode, wearMode: NotificationMode, currentCount: Int, withLogging: Boolean) {
        // Handle phone alerts
        if (phoneMode == NotificationMode.OFF) {
            if (withLogging) Log.d(TAG, "Phone: OFF")
        } else if (currentCount > 0) {
            when (phoneMode) {
                NotificationMode.FIRST_ONLY -> {
                    if (lastTargetCount == 0) {
                        if (withLogging) Log.d(TAG, "Phone: FIRST_ONLY - playing alert")
                        playPhoneAlert()
                    } else if (withLogging) {
                        Log.d(TAG, "Phone: FIRST_ONLY - skipping (already alerted)")
                    }
                }
                NotificationMode.EVERY_TIME -> {
                    // 初期検知時のみ通知（定期通知は別途開始）
                    if (lastTargetCount == 0) {
                        if (withLogging) Log.d(TAG, "Phone: EVERY_TIME - initial alert")
                        playPhoneAlert()
                    } else if (withLogging) {
                        Log.d(TAG, "Phone: EVERY_TIME - periodic alert active")
                    }
                }
                else -> {}
            }
        }

        // Handle Wear notifications
        if (wearMode == NotificationMode.OFF) {
            // Wear通知OFF時は何もしない
        } else if (currentCount > 0) {
            when (wearMode) {
                NotificationMode.FIRST_ONLY -> {
                    if (lastTargetCount == 0) {
                        if (withLogging) Log.d(TAG, "Wear: FIRST_ONLY - sending alert")
                        wearMessageSender.sendRadarAlert(targets)
                    } else if (withLogging) {
                        Log.d(TAG, "Wear: FIRST_ONLY - skipping (already notified)")
                    }
                }
                NotificationMode.EVERY_TIME -> {
                    // 初期検知時のみ通知（定期通知は別途開始）
                    if (lastTargetCount == 0) {
                        if (withLogging) Log.d(TAG, "Wear: EVERY_TIME - initial alert")
                        wearMessageSender.sendRadarAlert(targets)
                    } else if (withLogging) {
                        Log.d(TAG, "Wear: EVERY_TIME - periodic notification active")
                    }
                }
                else -> {}
            }
        } else {
            // ウォッチ側の車列表示はData Layer (/radar-targets) で自動クリアされるため
            // clear送信は不要
        }
    }

    /**
     * スマホでアラートを直接再生（通知は表示しない）
     * @param sound trueなら音も再生（初回アラート）、falseなら振動のみ（定期アラート）
     */
    private fun playPhoneAlert(sound: Boolean = true) {
        if (sound && cachedPhoneAlertSoundEnabled) {
            playAlertSound()
        }

        if (cachedPhoneAlertVibrationEnabled) {
            vibrator?.vibrate(vibrationEffect)
        }
    }

    private fun playAlertSound() {
        stopAlertSound()
        try {
            val alarmUri: Uri = if (cachedPhoneAlertSoundUri.isNotEmpty()) {
                Uri.parse(cachedPhoneAlertSoundUri)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    ?: return
            }

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .build()
                )
                setDataSource(context, alarmUri)
                isLooping = false
                prepare()
                start()
            }

            // 4秒後に自動停止（ウォッチと同じ）
            handler.postDelayed(alertTimeoutRunnable, 4000L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play alert sound", e)
        }
    }

    private fun stopAlertSound() {
        handler.removeCallbacks(alertTimeoutRunnable)
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
    }

    private fun startPeriodicAlert(phoneMode: NotificationMode, wearMode: NotificationMode) {
        stopPeriodicAlert()

        periodicNotificationJob = notificationScope.launch {
            while (true) {
                delay(1000)

                if (currentTargets.isNotEmpty()) {
                    // Phone: 定期アラートは振動のみ（音は初回のみ）
                    if (phoneMode == NotificationMode.EVERY_TIME) {
                        playPhoneAlert(sound = false)
                        Log.d(TAG, "Periodic: Phone vibration")
                    }

                    // Wear通知
                    if (wearMode == NotificationMode.EVERY_TIME) {
                        wearMessageSender.sendRadarAlert(currentTargets)
                        Log.d(TAG, "Periodic: Wear alert sent")
                    }
                }
            }
        }
        Log.d(TAG, "Periodic alert started")
    }

    private fun stopPeriodicAlert() {
        periodicNotificationJob?.cancel()
        periodicNotificationJob = null
    }

    private fun startSuppressionCountdown() {
        // 既存ジョブの直接クリーンアップ（stopSuppressionCountdown を呼ぶと
        // ウォッチへの誤ったクールダウン解除通知を送ってしまうため、ここでは送信しない）
        suppressionCountdownJob?.cancel()
        suppressionCountdownJob = null
        _suppressionRemainingSeconds.value = 0

        suppressionCountdownJob = notificationScope.launch {
            val suppressionSeconds = appSettingsRepository.getFlow(Settings.CLEAR_SUPPRESSION_SECONDS).first()
            if (suppressionSeconds <= 0) {
                // クールダウン無効（設定0秒）: 何もしない
                return@launch
            }
            cooldownActive = true
            for (i in suppressionSeconds downTo 1) {
                _suppressionRemainingSeconds.value = i
                delay(1000)
            }
            _suppressionRemainingSeconds.value = 0
            // 自然終了: ウォッチへクールダウン解除を通知
            if (cooldownActive) {
                cooldownActive = false
                wearableDataHost.putCooldownCleared()
            }
        }
    }

    private fun stopSuppressionCountdown() {
        val wasActive = cooldownActive
        cooldownActive = false
        suppressionCountdownJob?.cancel()
        suppressionCountdownJob = null
        _suppressionRemainingSeconds.value = 0
        // 抑制期間外で車両再検知などによる停止時、実際にクールダウン中だった場合のみ通知
        if (wasActive) {
            wearableDataHost.putCooldownCleared()
        }
    }

    fun handleBatteryUpdate(level: Int) {
        if (level < 0) {
            if (lowBatteryNotified) {
                notificationManager.cancel(LOW_BATTERY_NOTIFICATION_ID)
                lowBatteryNotified = false
            }
            return
        }

        val threshold = runBlocking {
            appSettingsRepository.getFlow(Settings.RADAR_LOW_BATTERY_THRESHOLD).first()
        }

        if (level < threshold) {
            if (!lowBatteryNotified) {
                Log.d(TAG, "Low battery notification: level=$level%, threshold=$threshold%")
                sendLowBatteryNotification(level, threshold)
                lowBatteryNotified = true
            }
        } else {
            if (lowBatteryNotified) {
                Log.d(TAG, "Battery recovered: level=$level%, threshold=$threshold%")
                notificationManager.cancel(LOW_BATTERY_NOTIFICATION_ID)
                lowBatteryNotified = false
            }
        }
    }

    private fun sendLowBatteryNotification(level: Int, threshold: Int) {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, 0, contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, LOW_BATTERY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("レーダー電池残量低下")
            .setContentText("Gardia R300L: ${level}% (しきい値: ${threshold}%)")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        notificationManager.notify(LOW_BATTERY_NOTIFICATION_ID, notification)
    }
}
