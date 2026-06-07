package com.pirorin215.gardiaradar.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pirorin215.gardiaradar.data.BatterySession
import com.pirorin215.gardiaradar.data.SessionType
import com.pirorin215.gardiaradar.viewModel.RadarViewModel

private val OnColor = Color(0xFFFF1744)
private val OffColor = Color(0xFF9E9E9E)
private val GreenColor = Color(0xFF00C853)
private val OrangeColor = Color(0xFFFF9100)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryHistoryScreen(
    viewModel: RadarViewModel,
    onBack: () -> Unit
) {
    val sessions by viewModel.batterySessions.collectAsState(initial = emptyList())
    var selectedSession by remember { mutableStateOf<BatterySession?>(null) }

    // 選択モードの状態管理
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (selectionMode) {
                // 選択モード時のAppBar
                TopAppBar(
                    title = {
                        Text("${selectedIds.size}件選択")
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            selectionMode = false
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "選択モード終了")
                        }
                    },
                    actions = {
                        if (selectedIds.isNotEmpty()) {
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(Icons.Default.Delete, contentDescription = "選択削除", tint = Color.Red)
                            }
                        }
                    }
                )
            } else {
                // 通常時のAppBar
                TopAppBar(
                    title = { Text("通信履歴") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    },
                    actions = {
                        if (sessions.isNotEmpty()) {
                            IconButton(onClick = { selectionMode = true }) {
                                Icon(Icons.Default.Edit, contentDescription = "選択モード")
                            }
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        if (sessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text("履歴はまだありません", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(paddingValues).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(sessions) { session ->
                    SessionCard(
                        session = session,
                        isSelected = session.id in selectedIds,
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) {
                                // 選択モード時は選択/解除をトグル
                                selectedIds = if (session.id in selectedIds) {
                                    selectedIds - session.id
                                } else {
                                    selectedIds + session.id
                                }
                            } else {
                                // 通常時は詳細ダイアログを表示
                                selectedSession = session
                            }
                        }
                    )
                }
            }
        }
    }

    // 詳細ダイアログ（選択モード時は表示しない）
    if (!selectionMode) {
        selectedSession?.let { session ->
            dumpSessionInfo(session)
            SessionDetailDialog(
                session = session,
                onDismiss = { selectedSession = null },
                onDelete = {
                    viewModel.deleteSession(session.id)
                    selectedSession = null
                }
            )
        }
    }

    // 選択削除確認ダイアログ
    if (showDeleteDialog) {
        DeleteConfirmDialog(
            count = selectedIds.size,
            onConfirm = {
                viewModel.deleteSessions(selectedIds.toList())
                selectedIds = emptySet()
                selectionMode = false
                showDeleteDialog = false
            },
            onDismiss = {
                showDeleteDialog = false
            }
        )
    }
}

@Composable
fun SessionCard(
    session: BatterySession,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit
) {
    val isOn = session.type == SessionType.CONNECTED
    val indicatorColor = if (isOn) OnColor else OffColor
    val typeLabel = if (isOn) "ON" else "OFF"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ヘッダー行: タイプ + 時刻 + 通信数 + チェックボックス
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = indicatorColor.copy(alpha = 0.2f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = typeLabel,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = indicatorColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = session.formatSessionRange(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "通信 ${session.totalCommunicationCount}回",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    // 選択モード時はチェックボックスを表示
                    if (selectionMode) {
                        Icon(
                            imageVector = if (isSelected) {
                                Icons.Filled.CheckCircle
                            } else {
                                Icons.Outlined.CheckCircle
                            },
                            contentDescription = if (isSelected) "選択済み" else "未選択",
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            // ONセッション: 省電力 + バッテリー
            if (isOn) {
                Spacer(modifier = Modifier.height(8.dp))

                // 省電力バッジ
                val stateColor = when(session.powerSavingState) {
                    "省電力ON" -> GreenColor
                    "省電力OFF" -> OnColor
                    else -> OrangeColor
                }
                Surface(
                    color = stateColor.copy(alpha = 0.2f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = session.powerSavingState,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = stateColor,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // バッテリー
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BatteryStatColumn("📱", session.startPhoneBattery, session.endPhoneBattery)
                    BatteryStatColumn("⌚", session.startWatchBattery, session.endWatchBattery)
                    BatteryStatColumn("📡", session.startRadarBattery, session.endRadarBattery)
                }
            }
        }
    }
}

@Composable
fun BatteryStatColumn(label: String, start: Int, end: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        Text(
            text = if (start >= 0) {
                if (end != null && end >= 0) "$start%→$end%" else "$start%"
            } else "--",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SessionDetailDialog(
    session: BatterySession,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val isOn = session.type == SessionType.CONNECTED

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val label = if (isOn) "ONセッション詳細" else "OFFセッション詳細"
            Text(label)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 450.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ONセッション: レーダーパケット
                if (isOn) {
                    SectionHeader("レーダー")
                    DetailRow("パケット受信", "${session.radarPacketCount}回")
                }

                // 通信内訳
                SectionHeader("通信内訳")
                DetailRow("接続状態", "${session.connectionStateCount}回")
                DetailRow("バッテリー", "${session.batteryCount}回")
                DetailRow("アラート", "${session.alertCount}回")
                DetailRow("ターゲット", "${session.targetsCount}回")
                DetailRow("省電力設定", "${session.powerSavingCount}回")
                HorizontalDivider()
                DetailRow("合計", "${session.totalCommunicationCount}回", fontWeight = FontWeight.Bold)

                // ONセッション固有情報
                if (isOn) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SectionHeader("タイムスタンプ")
                    DetailRow("セッション開始", session.formatTime(session.sessionStartTime))
                    DetailRow("ウォッチ受信", session.formatTime(session.watchBatteryReceivedTime))
                    DetailRow("レーダー受信", session.formatTime(session.radarBatteryReceivedTime))

                    Spacer(modifier = Modifier.height(8.dp))
                    SectionHeader("バッテリー")
                    if (session.startPhoneBattery >= 0) {
                        DetailRow("スマホ", formatBattery(session.startPhoneBattery, session.endPhoneBattery))
                    }
                    if (session.startWatchBattery >= 0) {
                        DetailRow("ウォッチ", formatBattery(session.startWatchBattery, session.endWatchBattery))
                    }
                    if (session.startRadarBattery >= 0) {
                        DetailRow("レーダー", formatBattery(session.startRadarBattery, session.endRadarBattery))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)) {
                Text("削除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        }
    )
}

@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
    )
}

@Composable
fun DetailRow(label: String, value: String, color: Color = Color.Unspecified, fontWeight: FontWeight = FontWeight.Normal) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = fontWeight,
            color = color
        )
    }
}

private fun formatBattery(start: Int, end: Int?): String {
    return if (end != null && end >= 0) "$start% → $end%" else "$start%"
}

fun dumpSessionInfo(session: BatterySession) {
    val typeLabel = if (session.type == SessionType.CONNECTED) "ON" else "OFF"
    android.util.Log.d("CommSession", "========== SESSION DUMP ($typeLabel) ==========")
    android.util.Log.d("CommSession", "Session Start Time: ${session.sessionStartTime}")
    android.util.Log.d("CommSession", "End Time: ${session.endTime}")
    android.util.Log.d("CommSession", "Duration: ${session.durationSeconds}s")
    android.util.Log.d("CommSession", "--- Communication ---")
    android.util.Log.d("CommSession", "Connection State: ${session.connectionStateCount}")
    android.util.Log.d("CommSession", "Battery: ${session.batteryCount}")
    android.util.Log.d("CommSession", "Alert: ${session.alertCount}")
    android.util.Log.d("CommSession", "Targets: ${session.targetsCount}")
    android.util.Log.d("CommSession", "Power Saving: ${session.powerSavingCount}")
    android.util.Log.d("CommSession", "Total: ${session.totalCommunicationCount}")
    if (session.type == SessionType.CONNECTED) {
        android.util.Log.d("CommSession", "--- Battery ---")
        android.util.Log.d("CommSession", "Phone: ${session.startPhoneBattery}% -> ${session.endPhoneBattery}%")
        android.util.Log.d("CommSession", "Watch: ${session.startWatchBattery}% -> ${session.endWatchBattery}%")
        android.util.Log.d("CommSession", "Radar: ${session.startRadarBattery}% -> ${session.endRadarBattery}%")
        android.util.Log.d("CommSession", "Radar Packets: ${session.radarPacketCount}")
    }
    android.util.Log.d("CommSession", "=======================================")
}

@Composable
fun DeleteConfirmDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("選択した履歴を削除")
        },
        text = {
            Text("${count}件の履歴を削除します。よろしいですか？")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
            ) {
                Text("削除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        }
    )
}
