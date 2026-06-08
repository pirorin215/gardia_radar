package com.pirorin215.gardiaradar.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

sealed class SettingKey<T> {
    abstract val defaultValue: T

    class Direct<T> internal constructor(
        internal val preferencesKey: Preferences.Key<T>,
        override val defaultValue: T
    ) : SettingKey<T>()

    class Mapped<T, R> internal constructor(
        internal val preferencesKey: Preferences.Key<R>,
        override val defaultValue: T,
        internal val toStored: (T) -> R,
        internal val fromStored: (R) -> T
    ) : SettingKey<T>()
}

object Settings {
    val THEME_MODE = SettingKey.Mapped(
        preferencesKey = stringPreferencesKey("theme_mode"),
        defaultValue = ThemeMode.SYSTEM,
        toStored = { it.name },
        fromStored = { ThemeMode.valueOf(it) }
    )

    val PHONE_NOTIFICATION_MODE = SettingKey.Mapped(
        preferencesKey = stringPreferencesKey("phone_notification_mode"),
        defaultValue = NotificationMode.FIRST_ONLY,
        toStored = { it.name },
        fromStored = { NotificationMode.valueOf(it) }
    )

    val WEAR_NOTIFICATION_MODE = SettingKey.Mapped(
        preferencesKey = stringPreferencesKey("wear_notification_mode"),
        defaultValue = NotificationMode.FIRST_ONLY,
        toStored = { it.name },
        fromStored = { NotificationMode.valueOf(it) }
    )

    val CLEAR_SUPPRESSION_SECONDS = SettingKey.Direct(
        preferencesKey = intPreferencesKey("clear_suppression_seconds"),
        defaultValue = 10
    )

    val RADAR_LOW_BATTERY_THRESHOLD = SettingKey.Direct(
        preferencesKey = intPreferencesKey("radar_low_battery_threshold"),
        defaultValue = 20
    )

    val TARGET_DEVICE_ADDRESS = SettingKey.Direct(
        preferencesKey = stringPreferencesKey("target_device_address"),
        defaultValue = ""
    )

    val TARGET_DEVICE_NAME = SettingKey.Direct(
        preferencesKey = stringPreferencesKey("target_device_name"),
        defaultValue = ""
    )

    val WEAR_POWER_SAVING_MODE = SettingKey.Direct(
        preferencesKey = booleanPreferencesKey("wear_power_saving_mode"),
        defaultValue = false
    )

    val RSSI_DISCONNECT_ENABLED = SettingKey.Direct(
        preferencesKey = booleanPreferencesKey("rssi_disconnect_enabled"),
        defaultValue = false
    )

    val RSSI_DISCONNECT_THRESHOLD = SettingKey.Direct(
        preferencesKey = intPreferencesKey("rssi_disconnect_threshold"),
        defaultValue = -90 // -90dBm
    )

    val RSSI_DISCONNECT_COUNT = SettingKey.Direct(
        preferencesKey = intPreferencesKey("rssi_disconnect_count"),
        defaultValue = 3 // 3回連続で切断（15秒）
    )

    val RSSI_CONNECT_THRESHOLD = SettingKey.Direct(
        preferencesKey = intPreferencesKey("rssi_connect_threshold"),
        defaultValue = -80 // -80dBm（接続時のしきい値）
    )

    val PHONE_ALERT_SOUND_ENABLED = SettingKey.Direct(
        preferencesKey = booleanPreferencesKey("phone_alert_sound_enabled"),
        defaultValue = true
    )

    val PHONE_ALERT_VIBRATION_ENABLED = SettingKey.Direct(
        preferencesKey = booleanPreferencesKey("phone_alert_vibration_enabled"),
        defaultValue = true
    )

    val WEAR_ALERT_SOUND_ENABLED = SettingKey.Direct(
        preferencesKey = booleanPreferencesKey("wear_alert_sound_enabled"),
        defaultValue = true
    )

    val WEAR_ALERT_VIBRATION_ENABLED = SettingKey.Direct(
        preferencesKey = booleanPreferencesKey("wear_alert_vibration_enabled"),
        defaultValue = true
    )

    val PHONE_ALERT_SOUND_URI = SettingKey.Direct(
        preferencesKey = stringPreferencesKey("phone_alert_sound_uri"),
        defaultValue = "" // 空文字 = システムデフォルト(TYPE_ALARM)
    )

    val PHONE_ALERT_SOUND_NAME = SettingKey.Direct(
        preferencesKey = stringPreferencesKey("phone_alert_sound_name"),
        defaultValue = "デフォルト"
    )
}

class AppSettingsRepository(private val context: Context) {
    fun <T> getFlow(key: SettingKey<T>): Flow<T> {
        return when (key) {
            is SettingKey.Direct -> {
                context.dataStore.data.map { preferences ->
                    @Suppress("UNCHECKED_CAST")
                    (preferences[key.preferencesKey] as? T) ?: key.defaultValue
                }
            }
            is SettingKey.Mapped<T, *> -> {
                context.dataStore.data.map { preferences ->
                    @Suppress("UNCHECKED_CAST")
                    val stored = preferences[key.preferencesKey as Preferences.Key<Any>]
                    if (stored != null) {
                        try {
                            (key.fromStored as (Any) -> T)(stored)
                        } catch (e: Exception) {
                            key.defaultValue
                        }
                    } else {
                        key.defaultValue
                    }
                }
            }
        }
    }

    suspend fun <T> setValue(key: SettingKey<T>, value: T) {
        when (key) {
            is SettingKey.Direct -> {
                context.dataStore.edit { preferences ->
                    @Suppress("UNCHECKED_CAST")
                    preferences[key.preferencesKey as Preferences.Key<Any>] = value as Any
                }
            }
            is SettingKey.Mapped<T, *> -> {
                context.dataStore.edit { preferences ->
                    @Suppress("UNCHECKED_CAST")
                    val stored = (key.toStored as (T) -> Any)(value)
                    preferences[key.preferencesKey as Preferences.Key<Any>] = stored
                }
            }
        }
    }
}
