package com.pirorin215.gardiaradar.data

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class SessionType {
    CONNECTED,      // レーダー接続中
    DISCONNECTED    // レーダー切断中（次の接続まで）
}

data class BatterySession(
    val id: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime? = null,
    val startPhoneBattery: Int,
    val endPhoneBattery: Int? = null,
    val startWatchBattery: Int,
    val endWatchBattery: Int? = null,
    val startRadarBattery: Int,
    val endRadarBattery: Int? = null,
    val wasPowerSavingMode: Boolean = false,
    val wasNormalMode: Boolean = false,
    // セッション種別
    val type: SessionType = SessionType.CONNECTED,
    // タイミング情報
    val sessionStartTime: LocalDateTime? = null,
    val watchBatteryReceivedTime: LocalDateTime? = null,
    val radarBatteryReceivedTime: LocalDateTime? = null,
    // 通信内訳
    val connectionStateCount: Int = 0,  // putConnectionStateData
    val batteryCount: Int = 0,          // putRadarBatteryLevel
    val alertCount: Int = 0,            // putAlertData
    val targetsCount: Int = 0,          // putTargetsData
    val powerSavingCount: Int = 0,      // putPowerSavingModeData
    // レーダー
    val radarPacketCount: Int = 0,
    // 旧フィールド（旧JSON互換用、計算プロパティで代替）
    val watchNotificationCount: Int = 0,
    val watchCommunicationCount: Int = 0
) {
    val totalCommunicationCount: Int
        get() = connectionStateCount + batteryCount + alertCount + targetsCount + powerSavingCount

    val powerSavingState: String
        get() = when {
            wasPowerSavingMode && wasNormalMode -> "混合"
            wasPowerSavingMode -> "省電力ON"
            else -> "省電力OFF"
        }

    val durationMinutes: Long
        get() = if (endTime != null) {
            java.time.Duration.between(startTime, endTime).toMinutes()
        } else 0

    val durationSeconds: Long
        get() = if (endTime != null) {
            java.time.Duration.between(startTime, endTime).seconds
        } else 0

    val phoneLoss: Int? get() = if (endPhoneBattery != null) startPhoneBattery - endPhoneBattery else null
    val watchLoss: Int? get() = if (endWatchBattery != null) startWatchBattery - endWatchBattery else null
    val radarLoss: Int? get() = if (endRadarBattery != null) startRadarBattery - endRadarBattery else null

    val phoneRate: Double? get() = if (durationMinutes > 0 && phoneLoss != null) (phoneLoss!!.toDouble() / durationMinutes * 60) else null
    val watchRate: Double? get() = if (durationMinutes > 0 && watchLoss != null) (watchLoss!!.toDouble() / durationMinutes * 60) else null
    val radarRate: Double? get() = if (durationMinutes > 0 && radarLoss != null) (radarLoss!!.toDouble() / durationMinutes * 60) else null

    fun formatStartTime(): String {
        return try {
            startTime.format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))
        } catch (e: Exception) {
            "不明な日時"
        }
    }

    fun formatSessionRange(): String {
        return try {
            val start = startTime.format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))
            val end = endTime?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "継続中"
            val duration = if (endTime != null) "${durationMinutes}分" else ""
            if (duration.isNotEmpty()) "$start 〜 $end ($duration)" else "$start 〜 $end"
        } catch (e: Exception) {
            "不明なセッション"
        }
    }

    fun formatTime(time: LocalDateTime?): String {
        return try {
            time?.format(DateTimeFormatter.ofPattern("HH:mm:ss")) ?: "--:--:--"
        } catch (e: Exception) {
            "不明"
        }
    }

    fun calcDelayFromSessionStart(targetTime: LocalDateTime?): Long {
        if (sessionStartTime == null || targetTime == null) return -1
        return java.time.Duration.between(sessionStartTime, targetTime).seconds
    }
}
