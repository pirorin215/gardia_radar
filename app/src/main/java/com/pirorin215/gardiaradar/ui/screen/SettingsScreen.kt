package com.pirorin215.gardiaradar.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pirorin215.gardiaradar.data.NotificationMode
import com.pirorin215.gardiaradar.data.ThemeMode
import com.pirorin215.gardiaradar.viewModel.AppSettingsViewModel

private val Orange = Color(0xFFFF9100)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AppSettingsViewModel,
    onBack: () -> Unit
) {
    val currentThemeMode by viewModel.themeMode.collectAsState()
    val phoneNotificationMode by viewModel.phoneNotificationMode.collectAsState()
    val wearNotificationMode by viewModel.wearNotificationMode.collectAsState()
    val useFullScreenNotification by viewModel.useFullScreenNotification.collectAsState()
    val clearSuppressionSeconds by viewModel.clearSuppressionSeconds.collectAsState()
    val radarLowBatteryThreshold by viewModel.radarLowBatteryThreshold.collectAsState()
    val targetDeviceAddress by viewModel.targetDeviceAddress.collectAsState()
    val targetDeviceName by viewModel.targetDeviceName.collectAsState()
    val wearPowerSavingMode by viewModel.wearPowerSavingMode.collectAsState()
    val rssiDisconnectEnabled by viewModel.rssiDisconnectEnabled.collectAsState()
    val rssiConnectThreshold by viewModel.rssiConnectThreshold.collectAsState()
    val rssiDisconnectThreshold by viewModel.rssiDisconnectThreshold.collectAsState()
    val rssiDisconnectCount by viewModel.rssiDisconnectCount.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // --- Device Selection ---
            DeviceSelectionSection(
                currentDeviceName = targetDeviceName,
                currentDeviceAddress = targetDeviceAddress,
                onSelectDevice = { address, name ->
                    viewModel.saveTargetDevice(address, name)
                },
                onClearDevice = {
                    viewModel.clearTargetDevice()
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // --- Phone Notifications ---
            Text("スマホの通知設定", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            NotificationMode.values().forEach { mode ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (mode == phoneNotificationMode),
                        onClick = { viewModel.savePhoneNotificationMode(mode) }
                    )
                    val label = when(mode) {
                        NotificationMode.FIRST_ONLY -> "最初の1台目のみ通知"
                        NotificationMode.EVERY_TIME -> "車両を検知するたびに通知"
                        NotificationMode.OFF -> "通知オフ"
                    }
                    Text(label, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Wear OS Notifications ---
            Text("ウォッチ(Wear OS)の通知設定", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            NotificationMode.values().forEach { mode ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (mode == wearNotificationMode),
                        onClick = { viewModel.saveWearNotificationMode(mode) }
                    )
                    val label = when(mode) {
                        NotificationMode.FIRST_ONLY -> "最初の1台目のみ通知"
                        NotificationMode.EVERY_TIME -> "車両を検知するたびに通知"
                        NotificationMode.OFF -> "通知オフ"
                    }
                    Text(label, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Fullscreen notification toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("全画面通知 (スマホ)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "ロック画面などで全画面の警告を表示します",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = useFullScreenNotification,
                    onCheckedChange = { viewModel.saveFullScreenNotification(it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Wear OS Power Saving Mode toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ウォッチの省電力モード", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "画面表示を無効化し、振動と音のみで警告します。ウォッチの電池消費を大幅に抑えられます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = wearPowerSavingMode,
                    onCheckedChange = { viewModel.saveWearPowerSavingMode(it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Clear suppression time slider
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("通知の抑制時間", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "車両がいなくなってから、${clearSuppressionSeconds}秒以内の再検知は同じ車列として扱い通知しません",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${clearSuppressionSeconds}秒", style = MaterialTheme.typography.bodyMedium, minLines = 1)
                    Slider(
                        value = clearSuppressionSeconds.toFloat(),
                        onValueChange = { viewModel.saveClearSuppressionSeconds(it.toInt()) },
                        valueRange = 0f..60f,
                        steps = 60,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- Radar Low Battery Threshold ---
            Text("レーダー電池残量の警告しきい値", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "レーダーの電池残量が設定値を下回った際に通知します",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${radarLowBatteryThreshold}%", style = MaterialTheme.typography.bodyMedium, minLines = 1)
                    Slider(
                        value = radarLowBatteryThreshold.toFloat(),
                        onValueChange = { viewModel.saveRadarLowBatteryThreshold(it.toInt()) },
                        valueRange = 0f..100f,
                        steps = 100,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Spacer(modifier = Modifier.height(24.dp))

            // --- RSSI Settings ---
            Text("RSSI設定", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "通信品質に基づいて接続と切断を制御します",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // RSSI Auto Disconnect toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("RSSI自動切断を有効にする", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = rssiDisconnectEnabled,
                    onCheckedChange = { viewModel.saveRssiDisconnectEnabled(it) }
                )
            }

            if (rssiDisconnectEnabled) {
                Spacer(modifier = Modifier.height(16.dp))

                // RSSI Connect Threshold slider
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "接続時のしきい値 (dBm)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "この値を下回る場合は接続しません（スキャン時の判定）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${rssiConnectThreshold}dBm", style = MaterialTheme.typography.bodyMedium, minLines = 1)
                        Slider(
                            value = rssiConnectThreshold.toFloat(),
                            onValueChange = { viewModel.saveRssiConnectThreshold(it.toInt()) },
                            valueRange = -100f..-40f,
                            steps = 60,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // RSSI Disconnect Threshold slider
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "切断時のしきい値 (dBm)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "この値を下回った場合、切断カウントを増やします（接続後の監視）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${rssiDisconnectThreshold}dBm", style = MaterialTheme.typography.bodyMedium, minLines = 1)
                        Slider(
                            value = rssiDisconnectThreshold.toFloat(),
                            onValueChange = { viewModel.saveRssiDisconnectThreshold(it.toInt()) },
                            valueRange = -100f..-40f,
                            steps = 60,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // RSSI Disconnect Count slider
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "切断判定回数",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "切断時のしきい値を下回った回数がこの値に達した場合、切断します（5秒ごとにチェック）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${rssiDisconnectCount}回", style = MaterialTheme.typography.bodyMedium, minLines = 1)
                        Slider(
                            value = rssiDisconnectCount.toFloat(),
                            onValueChange = {
                                val count = it.toInt()
                                if (count >= 1) {
                                    viewModel.saveRssiDisconnectCount(count)
                                }
                            },
                            valueRange = 1f..10f,
                            steps = 9,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- Theme Setting ---
            Text("テーマ設定", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                ThemeMode.values().forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = (mode == currentThemeMode),
                            onClick = { viewModel.saveThemeMode(mode) }
                        )
                        val themeLabel = when(mode) {
                            ThemeMode.SYSTEM -> "システム"
                            ThemeMode.LIGHT -> "ライト"
                            ThemeMode.DARK -> "ダーク"
                        }
                        Text(themeLabel, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
