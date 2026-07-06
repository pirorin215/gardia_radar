package com.pirorin215.gardiaradar

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import com.pirorin215.permissioncore.PermissionChecker
import com.pirorin215.permissioncore.PermissionGuard
import com.pirorin215.permissioncore.PermissionRequirements
import com.pirorin215.permissioncore.compose.PermissionGateScreen
import com.pirorin215.permissioncore.compose.PermissionItem
import org.koin.androidx.viewmodel.ext.android.viewModel

private fun isNotificationListenerEnabled(context: Context): Boolean =
    PermissionChecker.isNotificationListenerEnabled(
        context, com.pirorin215.gardiaradar.notification.GardiaNotificationListener::class.java
    )

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
                val context = LocalContext.current
                // 共有モジュールで SDK 差異を吸収した権限リスト。
                val requiredPermissions = remember { PermissionRequirements.bleRuntimePermissions() }
                // ランタイム権限（Bluetooth 系）の付与状態。拒否された場合もゲート画面を表示し続ける。
                var permissionsGranted by remember {
                    mutableStateOf(PermissionChecker.areAllGranted(context, requiredPermissions))
                }
                var currentScreen by remember { mutableStateOf("main") }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    // ダイアログの結果後、実際の付与状態で再判定。
                    permissionsGranted = PermissionChecker.areAllGranted(context, requiredPermissions)
                }

                LaunchedEffect(Unit) {
                    val missing = requiredPermissions.filterNot { PermissionChecker.isGranted(context, it) }
                    if (missing.isNotEmpty()) {
                        permissionLauncher.launch(missing.toTypedArray())
                    } else {
                        permissionsGranted = true
                    }
                }

                if (permissionsGranted) {
                    LaunchedEffect(Unit) {
                        // 共有モジュール経由で安全起動（権限不足・bg-start 制限時は起動せず false）。
                        val serviceIntent = Intent(context, com.pirorin215.gardiaradar.service.RadarScanService::class.java)
                        PermissionGuard.safeStartBleScanService(context, serviceIntent)
                        radarViewModel.startScan()
                    }

                    // バッテリー最適化ダイアログ
                    var showBatteryDialog by remember { mutableStateOf(false) }
                    var showNotificationAccessDialog by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                        val isOptimized = !powerManager.isIgnoringBatteryOptimizations(context.packageName)
                        if (isOptimized) {
                            showBatteryDialog = true
                        }

                        // 通知アクセス（NotificationListenerService）チェック
                        val isAccessGranted = isNotificationListenerEnabled(context)
                        if (!isAccessGranted) {
                            showNotificationAccessDialog = true
                        }
                    }

                    if (showBatteryDialog) {
                        BatteryOptimizationDialog(
                            onDismiss = { showBatteryDialog = false },
                            onOpenSettings = {
                                try {
                                    context.startActivity(PermissionGuard.appDetailsSettingsIntent(context))
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Failed to open battery settings", e)
                                }
                                showBatteryDialog = false
                            }
                        )
                    }

                    if (showNotificationAccessDialog) {
                        NotificationAccessPermissionDialog(
                            onDismiss = { showNotificationAccessDialog = false },
                            onOpenSettings = {
                                try {
                                    context.startActivity(PermissionGuard.notificationListenerSettingsIntent())
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Failed to open notification settings", e)
                                }
                                showNotificationAccessDialog = false
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
                } else {
                    // 権限が揃っていない場合はゲート画面を表示（真っ暗を回避）。
                    val listenerClass = remember {
                        com.pirorin215.gardiaradar.notification.GardiaNotificationListener::class.java
                    }
                    val items = remember(permissionsGranted) {
                        listOf(
                            PermissionItem(
                                label = "Bluetooth（スキャン・接続）",
                                granted = PermissionChecker.hasBleScanPermissions(context),
                            ),
                            PermissionItem(
                                label = "通知の表示",
                                granted = PermissionChecker.hasPostNotifications(context),
                                settingsIntent = PermissionGuard.appDetailsSettingsIntent(context),
                            ),
                            PermissionItem(
                                label = "通知アクセス（常駐のため）",
                                granted = PermissionChecker.isNotificationListenerEnabled(context, listenerClass),
                                settingsIntent = PermissionGuard.notificationListenerSettingsIntent(),
                            ),
                        )
                    }
                    PermissionGateScreen(
                        items = items,
                        onRequestRuntimePermissions = {
                            val missing = requiredPermissions.filterNot { PermissionChecker.isGranted(context, it) }
                            if (missing.isNotEmpty()) {
                                permissionLauncher.launch(missing.toTypedArray())
                            } else {
                                permissionsGranted = true
                            }
                        },
                    )
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

@Composable
fun NotificationAccessPermissionDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(imageVector = Icons.Filled.Warning, contentDescription = "警告")
        },
        title = {
            Text("通知アクセス権限の設定が必要です")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "このアプリがバックグラウンドで強制終了されずに動作し続けるためには、" +
                            "「通知アクセス権」の許可が必要です。\n\n" +
                            "※通知の読み取りなどは行いません。常時起動を保証するためのシステム機能として利用します。",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "下のボタンから設定画面を開き、「GardiaRadar」の通知アクセスを許可してください。",
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
