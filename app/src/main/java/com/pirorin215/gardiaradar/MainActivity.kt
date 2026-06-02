package com.pirorin215.gardiaradar

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
                    
                    when(currentScreen) {
                        "main" -> MainScreen(
                            viewModel = radarViewModel,
                            onNavigateToSettings = { currentScreen = "settings" }
                        )
                        "settings" -> SettingsScreen(
                            viewModel = appSettingsViewModel,
                            onBack = { currentScreen = "main" }
                        )
                    }
                }
            }
        }
    }
}
