package com.pirorin215.gardiaradar.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.compose.runtime.remember
import androidx.compose.foundation.gestures.detectTapGestures
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var targetCount by mutableStateOf(0)
    private var distances by mutableStateOf(emptyList<Int>())
    private var currentTime by mutableStateOf("")
    private var currentDayOfWeek by mutableStateOf("")
    private var batteryLevel by mutableIntStateOf(-1)
    private var isConnected by mutableStateOf<Boolean?>(null)
    private var radarBatteryLevel by mutableIntStateOf(-1)
    private var connectionStartTime by mutableStateOf<Long?>(null)
    private var connectionEndTime by mutableStateOf<Long?>(null)
    private var elapsedTime by mutableStateOf("")

    private val handler = Handler(Looper.getMainLooper())
    private val finishOnDisconnectRunnable = Runnable {
        Log.d("MainActivity", "Auto-finishing after disconnect timeout")
        finish()
    }

    private val targetsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WearableDataListener.ACTION_TARGETS_UPDATED -> {
                    targetCount = intent.getIntExtra("targetCount", 0)
                    @Suppress("DEPRECATION")
                    distances = intent.getIntegerArrayListExtra("distances")?.map { it.toInt() } ?: emptyList()
                }
                WearableDataListener.ACTION_CONNECTION_STATE_CHANGED -> {
                    isConnected = intent.getBooleanExtra("isConnected", false)
                    val startTime = intent.getLongExtra("startTime", -1L)
                    val endTime = intent.getLongExtra("endTime", -1L)
                    handleConnectionStateChange(
                        isConnected,
                        if (startTime > 0) startTime else null,
                        if (endTime > 0) endTime else null
                    )
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    batteryLevel = if (level > 0 && scale > 0) (level * 100) / scale else -1
                }
                WearableDataListener.ACTION_RADAR_BATTERY -> {
                    radarBatteryLevel = intent.getIntExtra("level", -1)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter(WearableDataListener.ACTION_TARGETS_UPDATED)
        filter.addAction(WearableDataListener.ACTION_CONNECTION_STATE_CHANGED)
        filter.addAction(WearableDataListener.ACTION_RADAR_BATTERY)
        filter.addAction(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(targetsReceiver, filter)

        // 電池残量を取得
        updateBatteryLevel()

        // 接続状態とレーダー電池の初期値をDataClientから読み取る
        readInitialState()

        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }

    private fun updateBatteryLevel() {
        val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryStatus?.let {
            val level: Int = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale: Int = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            batteryLevel = if (level > 0 && scale > 0) {
                (level * 100) / scale
            } else {
                -1
            }
        }
    }

    private fun readInitialState() {
        val prefs = getSharedPreferences(WearableDataListener.PREFS_NAME, MODE_PRIVATE)
        isConnected = prefs.getBoolean(WearableDataListener.PREF_KEY_CONNECTED, false)
        val startTime = prefs.getLong(WearableDataListener.PREF_KEY_START_TIME, -1L)
        val endTime = prefs.getLong(WearableDataListener.PREF_KEY_END_TIME, -1L)
        handleConnectionStateChange(
            isConnected,
            if (startTime > 0) startTime else null,
            if (endTime > 0) endTime else null
        )

        val dataClient = Wearable.getDataClient(this)
        dataClient.dataItems.addOnSuccessListener { dataItems ->
            for (item in dataItems) {
                when (item.uri.path) {
                    "/radar-battery" -> {
                        val dataMap = DataMapItem.fromDataItem(item).dataMap
                        radarBatteryLevel = dataMap.getInt("level", -1)
                    }
                }
            }
            dataItems.release()
        }
    }

    private fun handleConnectionStateChange(connected: Boolean?, startTime: Long? = null, endTime: Long? = null) {
        handler.removeCallbacks(finishOnDisconnectRunnable)
        if (connected == true) {
            if (startTime != null) {
                connectionStartTime = startTime
            } else if (connectionStartTime == null) {
                connectionStartTime = System.currentTimeMillis()
            }
            connectionEndTime = null
        } else if (connected == false) {
            // 切断時も開始記録を維持し、終了時刻を更新する
            if (startTime != null) {
                connectionStartTime = startTime
            }
            if (endTime != null) {
                connectionEndTime = endTime
            } else if (connectionEndTime == null) {
                connectionEndTime = System.currentTimeMillis()
            }
            // 切断時は60秒後にアプリを終了してウォッチフェイスに戻る
            handler.postDelayed(finishOnDisconnectRunnable, 60000L)
        } else {
            // connected == null (不明)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(finishOnDisconnectRunnable)
        unregisterReceiver(targetsReceiver)
    }

    private fun triggerDebugAlert() {
        Log.d("MainActivity", "Triggering debug alert via broadcast")

        // まずServiceを確実に起動
        val serviceIntent = Intent(this, RadarListenerService::class.java)
        startService(serviceIntent)

        // 少し遅延させてからBroadcastを送る
        handler.postDelayed({
            val intent = Intent(RadarListenerService.ACTION_DEBUG_ALERT).apply {
                putExtra("alert_json", "{\"targetCount\":1,\"targets\":[{\"id\":1,\"distance\":100,\"speed\":30,\"threat\":1}]}")
            }
            sendBroadcast(intent)
            Log.d("MainActivity", "Broadcast sent")
        }, 100)
    }

    @Composable
    fun MainScreen() {
        // 時刻と曜日を1分間隔で更新（分の切り替わりに同期）
        val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
        val dayFormat = remember { SimpleDateFormat("d E", Locale.getDefault()) }
        LaunchedEffect(connectionStartTime, connectionEndTime, isConnected) {
            while (true) {
                val now = Date()
                currentTime = timeFormat.format(now)
                currentDayOfWeek = dayFormat.format(now)

                val start = connectionStartTime
                val end = connectionEndTime

                if (start != null) {
                    val durationMillis = if (isConnected == true || end == null) {
                        now.time - start
                    } else {
                        end - start
                    }
                    val hours = (durationMillis / (1000 * 60 * 60)).toInt()
                    val minutes = ((durationMillis / (1000 * 60)) % 60).toInt()
                    elapsedTime = String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)
                } else {
                    elapsedTime = ""
                }

                // 次の分の00秒まで待つ
                val seconds = Calendar.getInstance().get(Calendar.SECOND)
                kotlinx.coroutines.delay((60 - seconds) * 1000L)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // 時刻と曜日（左端、縦中央）
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = currentTime,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = currentDayOfWeek,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // 経過時間表示（接続中または切断後の記録がある場合に表示）
                    Box(
                        modifier = Modifier.height(30.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (elapsedTime.isNotEmpty()) {
                            val icon = if (isConnected == true) "⏱️" else "🏁"
                            Text(
                                text = "$icon $elapsedTime",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // バッテリー（右中央）
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = if (batteryLevel >= 0) "⌚ $batteryLevel%" else "⚡",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 27.sp
                )
                if (radarBatteryLevel >= 0) {
                    Text(
                        text = "📡 $radarBatteryLevel%",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 27.sp
                    )
                }
            }

            // 接続状態バー（画面上部）- 長押しでデバッグアラート
            val connectionColor = when (isConnected) {
                true -> Color(0xFF00C853)   // 緑：接続済み
                false -> Color(0xFFFF1744)  // 赤：切断
                null -> Color.Gray           // グレー：不明
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 0.dp)
                    .width(200.dp)
                    .height(30.dp)
                    .background(connectionColor)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = {
                                triggerDebugAlert()
                            }
                        )
                    }
            )

            // 自転車アイコン（画面上端）
            Text(
                "🚴",
                fontSize = 24.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            )

            // 中央：レーン表示（常に表示）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 40.dp, bottom = 16.dp)
            ) {
                // 縦線
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.3f))
                        .align(Alignment.Center)
                )

                // メモリ線（50, 100, 150, 200m）
                listOf(50, 100, 150, 200).forEach { distance ->
                    val relativePos = (distance.toFloat() / 200f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = (relativePos * 150f).dp)
                            .width(12.dp)
                            .height(2.dp)
                            .background(Color.White.copy(alpha = 0.3f))
                    )
                }

                // 車列表示（車両がある場合のみ）
                if (targetCount > 0) {
                    val sortedDistances = distances.sorted()

                    sortedDistances.forEach { distance ->
                        val relativePos = (distance.toFloat() / 200f).coerceIn(0f, 1f)
                        val positionDp = (relativePos * 150f).dp

                        // 距離に応じた絶対位置に配置
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = positionDp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(Color.Red)
                            )

                            Spacer(modifier = Modifier.width(4.dp))

                            Text(
                                text = "${distance}m",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
