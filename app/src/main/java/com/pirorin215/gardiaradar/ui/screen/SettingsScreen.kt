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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pirorin215.gardiaradar.data.NotificationMode
import com.pirorin215.gardiaradar.data.ThemeMode
import com.pirorin215.gardiaradar.viewModel.AppSettingsViewModel

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            // --- Phone Notifications ---
            Text("Phone Notifications", style = MaterialTheme.typography.titleMedium)
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
                        NotificationMode.FIRST_ONLY -> "Notify on first detection only (車列の最初だけ)"
                        NotificationMode.EVERY_TIME -> "Notify for every new car (車が検出されるたび)"
                        NotificationMode.OFF -> "Notifications OFF"
                    }
                    Text(label, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Wear OS Notifications ---
            Text("Wear OS Notifications", style = MaterialTheme.typography.titleMedium)
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
                        NotificationMode.FIRST_ONLY -> "Notify on first detection only (車列の最初だけ)"
                        NotificationMode.EVERY_TIME -> "Notify for every new car (車が検出されるたび)"
                        NotificationMode.OFF -> "Notifications OFF"
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
                    Text("Fullscreen notification", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Show notification on lock screen (ロック画面で全画面表示)",
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

            // Clear suppression time slider
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Clear suppression time (車列クリア後の通知抑制)", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "If vehicles reappear within ${clearSuppressionSeconds}s after clearing, treat as same convoy (車列がクリアされてから${clearSuppressionSeconds}秒以内に再検知された場合は通知しない)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${clearSuppressionSeconds}s", style = MaterialTheme.typography.bodyMedium, minLines = 1)
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

            // --- Theme Setting ---
            Text("Theme Mode", style = MaterialTheme.typography.titleMedium)
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
                        Text(mode.name, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
