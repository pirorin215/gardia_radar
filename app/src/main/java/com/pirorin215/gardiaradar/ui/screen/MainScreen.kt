package com.pirorin215.gardiaradar.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pirorin215.gardiaradar.data.ConnectionState
import com.pirorin215.gardiaradar.data.RadarTarget
import com.pirorin215.gardiaradar.viewModel.RadarViewModel

private val Orange = Color(0xFFFF9100)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: RadarViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToHistory: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedDeviceName by viewModel.connectedDeviceName.collectAsState()
    val targets by viewModel.targets.collectAsState()
    val rawPacket by viewModel.rawPacket.collectAsState()
    val radarBatteryLevel by viewModel.radarBatteryLevel.collectAsState()
    val wearBatteryLevel by viewModel.wearBatteryLevel.collectAsState()
    val suppressionRemaining by viewModel.suppressionRemainingSeconds.collectAsState()
    val connectionElapsedSeconds by viewModel.connectionElapsedSeconds.collectAsState()
    val rssi by viewModel.rssi.collectAsState()

    // Convert seconds to HH:MM:SS format
    val elapsedTimeString = remember(connectionElapsedSeconds) {
        val hours = connectionElapsedSeconds / 3600
        val minutes = (connectionElapsedSeconds % 3600) / 60
        val seconds = connectionElapsedSeconds % 60
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    // Determine overall threat level for background color
    val maxThreat = targets.maxOfOrNull { it.threat } ?: 0
    val radarColor = when {
        maxThreat >= 2 -> Color(0xFFFF1744) // Red: Fast approaching
        maxThreat == 1 -> Orange // Orange: Approaching
        connectionState is ConnectionState.Connected -> Color(0xFF00C853) // Green: Clear
        else -> Color.Gray
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Gardia R300L Radar",
                            fontWeight = FontWeight.Normal,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        if (connectionState is ConnectionState.Connected || connectionState is ConnectionState.Connecting) {
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = connectedDeviceName ?: "",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = radarColor,
                    titleContentColor = Color.White,
                ),
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.History, contentDescription = "History", tint = Color.White)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(Color(0xFF121212)) // Dark background
        ) {
            // Main Content Area
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 接続状態、接続時間、RSSI
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    // 接続状態
                    val statusColor = when (connectionState) {
                        is ConnectionState.Connected -> Color(0xFF00C853)
                        is ConnectionState.Disconnected -> Color(0xFFFF1744)
                        else -> Orange
                    }
                    Box(modifier = Modifier.size(12.dp).background(statusColor, shape = androidx.compose.foundation.shape.CircleShape))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (connectionState) {
                            is ConnectionState.Connected -> "接続済"
                            is ConnectionState.Disconnected -> "切断"
                            is ConnectionState.Scanning -> "スキャン中"
                            is ConnectionState.Connecting -> "接続中"
                            else -> "不明"
                        },
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    // 接続経過時間
                    if (connectionState is ConnectionState.Connected) {
                        Text(
                            text = "⏱ $elapsedTimeString",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                    }

                    // RSSI表示
                    val rssiText = rssi?.let { "${it}dBm" } ?: "--dBm"
                    val rssiValue = rssi
                    val rssiColor = when {
                        rssiValue == null -> Color.Gray
                        rssiValue >= -50 -> Color(0xFF00C853) // 緑：強い
                        rssiValue >= -60 -> Color(0xFF64DD17) // 緑
                        rssiValue >= -70 -> Color(0xFFFFD600) // 黄：普通
                        rssiValue >= -80 -> Orange // オレンジ：弱い
                        else -> Color(0xFFFF1744) // 赤：非常に弱い
                    }
                    Text(
                        text = "📶 $rssiText",
                        color = rssiColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // ウォッチ電池残量、レーダー電池残量、再接続ボタン
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    // ウォッチ電池残量
                    Text(
                        text = "⌚ ${if (wearBatteryLevel >= 0) "$wearBatteryLevel%" else "--%"}",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    // レーダー電池残量
                    Text(
                        text = "📡 ${if (radarBatteryLevel >= 0) "$radarBatteryLevel%" else "--%"}",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Button(
                        onClick = { viewModel.forceReconnect() },
                        enabled = connectionState !is ConnectionState.Scanning && connectionState !is ConnectionState.Connecting,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.2f),
                            disabledContainerColor = Color.White.copy(alpha = 0.1f)
                        )
                    ) {
                        Text("再接続", fontSize = 16.sp, color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Vehicle List (Left side)
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxWidth(0.6f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "車数: ${targets.size}",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black
                            )
                            if (suppressionRemaining > 0) {
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = "CDTimer: ${suppressionRemaining}s",
                                    color = Orange,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(targets.sortedBy { it.distance }) { target ->
                                TargetMiniCard(target)
                            }
                        }
                    }

                    // --- Garmin Style Radar Lane (Right side) ---
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.3f)
                            .align(Alignment.CenterEnd)
                            .padding(vertical = 32.dp)
                    ) {
                        // The Lane
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .fillMaxHeight()
                                .background(Color.White.copy(alpha = 0.3f))
                                .align(Alignment.Center)
                        )

                        // Your Bike (Top)
                        Icon(
                            imageVector = Icons.Default.DirectionsBike,
                            contentDescription = "My Bike",
                            tint = Color.White,
                            modifier = Modifier
                                .size(32.dp)
                                .align(Alignment.TopCenter)
                        )

                        // 50m間隔の目盛り（200mまで）
                        val scaleMax = 500f // スケーリングの最大値（画面全体を有効活用）
                        listOf(50, 100, 150, 200).forEach { distance ->
                            val relativePos = (distance.toFloat() / 200f).coerceIn(0f, 1f)
                            val markDp = (relativePos * scaleMax).dp

                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = markDp)
                            ) {
                                // 目盛り線（縦線に揃えて左右に伸びる）
                                Row(
                                    modifier = Modifier.align(Alignment.Center),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 左側の目盛り
                                    Box(
                                        modifier = Modifier
                                            .width(10.dp)
                                            .height(2.dp)
                                            .background(Color.White.copy(alpha = 0.4f))
                                    )
                                    // 縦線の幅分のスペース
                                    Spacer(modifier = Modifier.width(4.dp))
                                    // 右側の目盛り
                                    Box(
                                        modifier = Modifier
                                            .width(10.dp)
                                            .height(2.dp)
                                            .background(Color.White.copy(alpha = 0.4f))
                                    )
                                }
                                // 距離テキスト（目盛りの右横）
                                Text(
                                    text = "${distance}m",
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 16.sp,
                                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 70.dp)
                                )
                            }
                        }

                        // Vehicles (距離順にソートして表示)
                        targets.sortedBy { it.distance }.forEachIndexed { index, target ->
                            // Scale: 0m (Top) to 200m (Bottom)
                            val relativePos = (target.distance.toFloat() / 200f).coerceIn(0f, 1f)

                            // Car Icon
                            val iconColor = if (target.threat >= 2) Color.Red else Color.White

                            key(index, target.id) {
                                // Row for distance text and car icon
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = (relativePos * 500).dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    // Distance text (left of icon)
                                    Text(
                                        text = "${target.distance}",
                                        color = Color.White.copy(alpha = 0.8f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Spacer(modifier = Modifier.width(2.dp))

                                    Icon(
                                        imageVector = Icons.Default.DirectionsCar,
                                        contentDescription = "Car",
                                        tint = iconColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Raw Data Debug View (Bottom)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(8.dp)
            ) {
                Text(
                    text = "RAW: $rawPacket",
                    color = Color.Green,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
fun TargetMiniCard(target: RadarTarget) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val color = if (target.threat >= 2) Color.Red else Orange
            Box(modifier = Modifier.size(10.dp).background(color, shape = androidx.compose.foundation.shape.CircleShape))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "${target.distance}m", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "${target.speed}km/h", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        }
    }
}
