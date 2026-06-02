package com.pirorin215.gardiaradar

import android.Manifest
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
import com.pirorin215.gardiaradar.ui.screen.MainScreen
import com.pirorin215.gardiaradar.ui.theme.BleTemplateTheme
import com.pirorin215.gardiaradar.viewModel.RadarViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {
    private val radarViewModel: RadarViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            BleTemplateTheme {
                var permissionsGranted by remember { mutableStateOf(false) }

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
                    permissionLauncher.launch(required.toTypedArray())
                }

                if (permissionsGranted) {
                    LaunchedEffect(Unit) {
                        radarViewModel.startScan()
                    }
                    MainScreen(viewModel = radarViewModel)
                }
            }
        }
    }
}
