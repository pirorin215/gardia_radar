package com.pirorin215.gardiaradar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.pirorin215.gardiaradar.ui.screen.BatteryHistoryScreen
import com.pirorin215.gardiaradar.ui.screen.MainScreen
import com.pirorin215.gardiaradar.ui.screen.SettingsScreen
import com.pirorin215.gardiaradar.ui.theme.BleTemplateTheme
import com.pirorin215.gardiaradar.viewModel.AppSettingsViewModel
import com.pirorin215.gardiaradar.viewModel.RadarViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {
    private val radarViewModel: RadarViewModel by viewModel()
    private val appSettingsViewModel: AppSettingsViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeMode by appSettingsViewModel.themeMode.collectAsState()
            val isDarkTheme = when(themeMode) {
                com.pirorin215.gardiaradar.data.ThemeMode.DARK -> true
                com.pirorin215.gardiaradar.data.ThemeMode.LIGHT -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            BleTemplateTheme(darkTheme = isDarkTheme) {
                var permissionsGranted by remember { mutableStateOf(false) }
                var currentScreen by remember { mutableStateOf("main") }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    permissionsGranted = permissions.values.all { it }
                }

                LaunchedEffect(Unit) {
                    val required = mutableListOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        required.add(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        required.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissionLauncher.launch(required.toTypedArray())
                }

                if (permissionsGranted) {
                    val context = LocalContext.current
                    LaunchedEffect(Unit) {
                        val serviceIntent = Intent(context, com.pirorin215.gardiaradar.service.RadarScanService::class.java)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                        radarViewModel.startScan()
                    }

                    // バッテリー最適化ダイアログ
                    var showBatteryDialog by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                        val isOptimized = !powerManager.isIgnoringBatteryOptimizations(context.packageName)
                        if (isOptimized) {
                            showBatteryDialog = true
                        }
                    }

                    if (showBatteryDialog) {
                        BatteryOptimizationDialog(
                            onDismiss = { showBatteryDialog = false },
                            onOpenSettings = {
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Failed to open battery settings", e)
                                }
                                showBatteryDialog = false
                            }
                        )
                    }

                    when(currentScreen) {
                        "main" -> MainScreen(
                            viewModel = radarViewModel,
                            onNavigateToSettings = { currentScreen = "settings" },
                            onNavigateToHistory = { currentScreen = "history" }
                        )
                        "settings" -> SettingsScreen(
                            viewModel = appSettingsViewModel,
                            onBack = { currentScreen = "main" }
                        )
                        "history" -> BatteryHistoryScreen(
                            viewModel = radarViewModel,
                            onBack = { currentScreen = "main" }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryOptimizationDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = "警告") },
        title = { Text("バックグラウンド処理の許可が必要です") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "バッテリ最適化が有効になっています。\n\n" +
                    "このアプリはバックグラウンドで動作するため、" +
                    "バッテリ使用量を制限しないように設定してください。\n\n" +
                    "「最適化しない」を選択してください。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "設定画面で許可を与えると、\n再インストール後も安定して動作します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("設定を開く")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("後で")
            }
        }
    )
}
