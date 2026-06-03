package com.pirorin215.gardiaradar.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private var targetCount by mutableStateOf(0)
    private var distances by mutableStateOf(emptyList<Int>())
    private var currentTime by mutableStateOf("")
    private var batteryLevel by mutableIntStateOf(-1)

    private val targetsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WearableDataListener.ACTION_TARGETS_UPDATED) {
                targetCount = intent.getIntExtra("targetCount", 0)
                @Suppress("DEPRECATION")
                distances = intent.getIntegerArrayListExtra("distances")?.map { it.toInt() } ?: emptyList()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerReceiver(targetsReceiver, IntentFilter(WearableDataListener.ACTION_TARGETS_UPDATED))

        // 電池残量を取得
        updateBatteryLevel()

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

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(targetsReceiver)
    }

    @Composable
    fun MainScreen() {
        if (targetCount > 0) {
            AlertScreen()
        } else {
            WaitingScreen()
        }
    }

    @Composable
    fun AlertScreen() {
        // 時刻を毎秒更新
        LaunchedEffect(Unit) {
            while (true) {
                currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                kotlinx.coroutines.delay(1000)
            }
        }

        // 電池残量を定期的に更新
        LaunchedEffect(Unit) {
            while (true) {
                updateBatteryLevel()
                kotlinx.coroutines.delay(60000) // 1分ごとに更新
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.TopCenter
        ) {
            // 車列表示（縦並び、距離に応じた間隔）
            // Phone側と同じスケール: 0m〜150m → 上〜下
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
                modifier = Modifier.fillMaxSize()
            ) {
                // 自転車アイコン（最上部）
                Text("🚴", fontSize = 18.sp)

                repeat(targetCount) { index ->
                    val distance = distances.getOrElse(index) { 0 }
                    // Phone側と同じ計算: relativePos = distance / 150
                    val relativePos = (distance.toFloat() / 150f).coerceIn(0f, 1f)
                    // 縦領域を最大化するためスケーリングを調整（80dp相当）
                    val spacingDp = (relativePos * 80f).dp

                    Spacer(modifier = Modifier.height(spacingDp))

                    // 丸と距離テキストを横並びに配置
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
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
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // 時刻と電池残量を画面下部に表示
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Text(
                    text = currentTime,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (batteryLevel >= 0) "🔋 $batteryLevel%" else "⚡",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    @Composable
    fun WaitingScreen() {
        // 時刻を毎秒更新
        LaunchedEffect(Unit) {
            while (true) {
                currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                kotlinx.coroutines.delay(1000)
            }
        }

        // 電池残量を定期的に更新
        LaunchedEffect(Unit) {
            while (true) {
                updateBatteryLevel()
                kotlinx.coroutines.delay(60000) // 1分ごとに更新
            }
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Gardia Radar",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    fontSize = 16.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Waiting for alerts...",
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp
                )
            }

            // 時刻と電池残量を画面下部に表示
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Text(
                    text = currentTime,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (batteryLevel >= 0) "🔋 $batteryLevel%" else "⚡",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
