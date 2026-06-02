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

    val USE_FULLSCREEN_NOTIFICATION = SettingKey.Direct(
        preferencesKey = booleanPreferencesKey("use_fullscreen_notification"),
        defaultValue = false
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
