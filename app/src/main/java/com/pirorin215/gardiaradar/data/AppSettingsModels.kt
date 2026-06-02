package com.pirorin215.gardiaradar.data

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

enum class NotificationMode {
    FIRST_ONLY, // 車列が発生した最初だけ通知
    EVERY_TIME,  // 車が検出されるたびに通知
    OFF         // 通知なし
}
