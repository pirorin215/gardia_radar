package com.pirorin215.gardiaradar.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

class MainActivity : ComponentActivity() {

    private var targetCount by mutableStateOf(0)
    private var distances by mutableStateOf(emptyList<Int>())

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

        setContent {
            MaterialTheme {
                MainScreen()
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFB71C1C)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "⚠ VEHICLE!",
                    color = Color.Yellow,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 車列表示（縦並び、距離に応じた間隔）
                // Phone側と同じスケール: 0m〜150m → 上〜下
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    // 自転車アイコン（最上部）
                    Text("🚴", fontSize = 16.sp)

                    repeat(targetCount) { index ->
                        val distance = distances.getOrElse(index) { 0 }
                        // Phone側と同じ計算: relativePos = distance / 150
                        val relativePos = (distance.toFloat() / 150f).coerceIn(0f, 1f)
                        // Wear OSの画面に合わせて高さをスケーリング（120dp相当）
                        val spacingDp = (relativePos * 30f).dp

                        Spacer(modifier = Modifier.height(spacingDp))

                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color.Yellow)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "$targetCount target(s)",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    @Composable
    fun WaitingScreen() {
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
        }
    }
}
