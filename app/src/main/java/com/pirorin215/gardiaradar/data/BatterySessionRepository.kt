package com.pirorin215.gardiaradar.data

import android.content.Context
import android.util.Log
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.lang.reflect.Type
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class BatterySessionRepository(private val context: Context) {
    private val TAG = "BatterySessionRepo"
    private val fileName = "battery_history.json"
    
    // LocalDateTime用のTypeAdapterを定義
    private val gson = GsonBuilder()
        .registerTypeAdapter(LocalDateTime::class.java, object : JsonSerializer<LocalDateTime>, JsonDeserializer<LocalDateTime> {
            private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
            
            override fun serialize(src: LocalDateTime, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
                return JsonPrimitive(src.format(formatter))
            }
            
            override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): LocalDateTime {
                return LocalDateTime.parse(json.asString, formatter)
            }
        })
        .create()

    private val _sessions = MutableStateFlow<List<BatterySession>>(emptyList())
    val sessions: StateFlow<List<BatterySession>> = _sessions

    init {
        loadSessions()
    }

    private fun loadSessions() {
        try {
            val file = File(context.filesDir, fileName)
            if (file.exists()) {
                val json = file.readText()
                val type = object : TypeToken<List<BatterySession>>() {}.type
                val list: List<BatterySession> = gson.fromJson(json, type)
                
                // LocalDateTimeが壊れている（nullが入っている）データを除外
                val validList = list.filter { 
                    try {
                        it.startTime != null && it.id != null
                    } catch (e: Exception) {
                        false
                    }
                }
                
                _sessions.value = validList.sortedByDescending { it.startTime }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions", e)
            // 読み込みに失敗した場合は、最悪ファイルを削除してリセットすることも検討
            // File(context.filesDir, fileName).delete()
        }
    }

    private fun saveSessions(list: List<BatterySession>) {
        try {
            val file = File(context.filesDir, fileName)
            val json = gson.toJson(list)
            file.writeText(json)
            _sessions.value = list.sortedByDescending { it.startTime }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sessions", e)
        }
    }

    fun addSession(session: BatterySession) {
        val newList = _sessions.value.toMutableList()
        newList.add(session)
        // 最大200件に制限（古いものから削除）
        val trimmed = if (newList.size > 200) newList.takeLast(200) else newList
        saveSessions(trimmed)
    }

    fun updateSession(updatedSession: BatterySession) {
        val newList = _sessions.value.map {
            if (it.id == updatedSession.id) updatedSession else it
        }
        saveSessions(newList)
    }

    fun deleteSession(id: String) {
        val newList = _sessions.value.filter { it.id != id }
        saveSessions(newList)
    }

    fun deleteSessions(ids: List<String>) {
        val newList = _sessions.value.filter { it.id !in ids }
        saveSessions(newList)
    }
}
