package com.ynk.virtualdisplay.ui.main

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.koin.androidx.compose.koinViewModel
import com.ynk.virtualdisplay.MyApplication
import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.manager.ShizukuManager
import com.ynk.virtualdisplay.ui.screens.SettingsScreen
import com.ynk.virtualdisplay.ui.screens.ShizukuPermissionScreen
import com.ynk.virtualdisplay.ui.screens.VirtualDisplayScreen
import com.ynk.virtualdisplay.ui.theme.MyApplicationTheme
import org.koin.android.ext.android.inject
import rikka.shizuku.Shizuku
import rikka.sui.Sui

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val shizukuManager: ShizukuManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            if (Sui.init(packageName)) {
                Log.i(TAG, "Sui initialized successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sui init failed", e)
        }

        // 首次检查 Shizuku 状态
        shizukuManager.refreshState()

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen(
                    shizukuManager = shizukuManager,
                    onRequestPermission = {
                        try {
                            Shizuku.requestPermission(ShizukuManager.REQUEST_CODE)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to request permission", e)
                        }
                    },
                    onRetryCheck = {
                        shizukuManager.refreshState()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

@Composable
private fun MainScreen(
    shizukuManager: ShizukuManager,
    onRequestPermission: () -> Unit,
    onRetryCheck: () -> Unit
) {
    val viewModel: MainViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsState()

    // 首次进入时尝试连接
    LaunchedEffect(Unit) {
        viewModel.handleIntent(MainIntent.InitializeConnection)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = uiState.currentTab == ScreenTab.CONSOLE,
                    onClick = { viewModel.handleIntent(MainIntent.SwitchTab(ScreenTab.CONSOLE)) },
                    icon = { Icon(Icons.Default.Home, contentDescription = "控制台") },
                    label = { Text("控制台") }
                )
                NavigationBarItem(
                    selected = uiState.currentTab == ScreenTab.SETTINGS,
                    onClick = { viewModel.handleIntent(MainIntent.SwitchTab(ScreenTab.SETTINGS)) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                    label = { Text("设置") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (uiState.currentTab) {
                ScreenTab.CONSOLE -> VirtualDisplayScreen(viewModel = viewModel)
                ScreenTab.SETTINGS -> SettingsScreen(viewModel = viewModel)
            }

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
