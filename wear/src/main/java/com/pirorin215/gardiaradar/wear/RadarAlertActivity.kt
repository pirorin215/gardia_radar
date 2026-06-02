package com.pirorin215.gardiaradar.wear

import androidx.activity.ComponentActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import org.json.JSONObject

class RadarAlertActivity : ComponentActivity() {

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RadarListenerService.ACTION_DISMISS) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        registerReceiver(dismissReceiver, IntentFilter(RadarListenerService.ACTION_DISMISS))

        setupAlertScreen(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(dismissReceiver)
        super.onDestroy()
    }

    private fun setupAlertScreen(intent: Intent?) {
        val jsonData = intent?.getStringExtra(RadarListenerService.EXTRA_ALERT_JSON) ?: "{}"
        val targetCount = parseTargetCount(jsonData)
        val distanceInfo = parseDistanceInfo(jsonData)

        setContent {
            MaterialTheme {
                AlertScreen(
                    targetCount = targetCount,
                    distanceInfo = distanceInfo,
                    onDismiss = { dismissAndStop() }
                )
            }
        }
    }

    private fun restartVibration() {
        // 振動を再開
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        val vibrationTimings = longArrayOf(0, 500, 200, 500, 200, 500, 200, 500)
        val vibrationAmplitudes = intArrayOf(0, 255, 0, 255, 0, 255, 0, 255)
        val vibrationEffect = VibrationEffect.createWaveform(
            vibrationTimings, vibrationAmplitudes, -1 // 繰り返しなし
        )
        vibrator.vibrate(vibrationEffect)
    }

    private fun dismissAndStop() {
        // ローカルで振動停止
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.cancel()
        finish()
    }

    private fun parseTargetCount(json: String): Int {
        return try {
            JSONObject(json).optInt("targetCount", 1)
        } catch (e: Exception) {
            1
        }
    }

    private fun parseDistanceInfo(json: String): String {
        return try {
            val obj = JSONObject(json)
            val targets = obj.optJSONArray("targets")
            if (targets != null && targets.length() > 0) {
                val dist = targets.getJSONObject(0).optInt("distance", 0)
                "${dist}m"
            } else {
                "Approaching"
            }
        } catch (e: Exception) {
            "Approaching"
        }
    }
}

@Composable
fun AlertScreen(
    targetCount: Int,
    distanceInfo: String,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFB71C1C)), // 暗赤背景
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "⚠ VEHICLE!",
                color = Color.Yellow,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$targetCount target(s) - $distanceInfo",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onDismiss) {
                Text("DISMISS", fontWeight = FontWeight.Bold)
            }
        }
    }
}
